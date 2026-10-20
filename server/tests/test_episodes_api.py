import logging

import pytest
from fastapi.testclient import TestClient
from us25_helpers import (
    AUTH,
    EPISODE_KEYS,
    SECRET_PARTS,
    SECRET_URL,
    TOKEN,
    StubFetcher,
    ep,
    feed_of,
)

from podclatch_server.app import create_app
from podclatch_server.config import Settings
from podclatch_server.routes.feeds import get_fetcher

NEWER = 1_800_000_000_000
STATE_KEYS = {"episodeId", "outcome", "status", "playedAt", "positionSec", "stateUpdatedAt"}


@pytest.fixture
def fetcher() -> StubFetcher:
    return StubFetcher()


@pytest.fixture
def app(fetcher):
    app = create_app(Settings(token=TOKEN, test_mode=True))
    app.dependency_overrides[get_fetcher] = lambda: fetcher
    return app


@pytest.fixture
def client(app) -> TestClient:
    return TestClient(app, headers=AUTH)


@pytest.fixture
def store(app, client):
    client.get("/v1/feeds")
    return app.state.store


def subscribe(client, fetcher, url, *episodes) -> dict:
    fetcher.results[url] = feed_of(*episodes)
    response = client.post("/v1/feeds", json={"feedUrl": url})
    assert response.status_code == 201, response.text
    return response.json()


def item(episode_id, status="PLAYED", played_at=NEWER, position=10, updated=NEWER) -> dict:
    return {
        "episodeId": episode_id,
        "status": status,
        "playedAt": played_at if status == "PLAYED" else None,
        "positionSec": position,
        "stateUpdatedAt": updated,
    }


def episodes_of(client, feed_id) -> list[dict]:
    response = client.get(f"/v1/feeds/{feed_id}/episodes")
    assert response.status_code == 200
    return response.json()


# --- list ----------------------------------------------------------------------------------------


def test_list_episodes_shape_order_and_scope(client, fetcher):
    a = subscribe(client, fetcher, "https://example.com/a.xml", ep(1), ep(2), ep(3, artwork="x"))
    b = subscribe(client, fetcher, "https://example.com/b.xml", ep(4))
    result = episodes_of(client, a["id"])
    assert [e["guid"] for e in result] == ["guid-3", "guid-2", "guid-1"]
    assert all(set(e) == EPISODE_KEYS and e["feedId"] == a["id"] for e in result)
    assert result[0]["artworkUrl"] == "x"
    assert result[1]["artworkUrl"] is None  # not replaced by the Feed's artwork
    first = result[2]
    assert first["status"] == "NEW" and first["playedAt"] is None and first["positionSec"] == 0
    assert isinstance(first["durationSec"], int) and isinstance(first["rev"], int)
    assert [e["guid"] for e in episodes_of(client, b["id"])] == ["guid-4"]


def test_list_ties_on_published_at_are_ordered_by_id_descending(client, fetcher):
    feed = subscribe(
        client, fetcher, "https://example.com/a.xml", ep(1, 0), ep(2, 0), ep(3, 5), ep(4, 0)
    )
    result = episodes_of(client, feed["id"])
    assert [e["guid"] for e in result] == ["guid-3", "guid-4", "guid-2", "guid-1"]


def test_list_empty_feed_is_empty_array(client, fetcher):
    feed = subscribe(client, fetcher, "https://example.com/a.xml")
    assert episodes_of(client, feed["id"]) == []


@pytest.mark.parametrize("feed_id", ["999", "-1", "0"])
def test_list_unknown_feed_is_404(client, feed_id):
    response = client.get(f"/v1/feeds/{feed_id}/episodes")
    assert response.status_code == 404
    assert response.json() == {"detail": "Feed not found."}


def test_list_after_unsubscribe_is_404(client, fetcher):
    feed = subscribe(client, fetcher, "https://example.com/a.xml", ep(1))
    assert client.delete(f"/v1/feeds/{feed['id']}").status_code == 204
    assert client.get(f"/v1/feeds/{feed['id']}/episodes").status_code == 404


def test_list_non_integer_id_is_422(client):
    response = client.get("/v1/feeds/abc/episodes")
    assert response.status_code == 422
    assert isinstance(response.json()["detail"], str)


# --- state ---------------------------------------------------------------------------------------


@pytest.fixture
def seeded(client, fetcher):
    feed = subscribe(client, fetcher, "https://example.com/a.xml", ep(1), ep(2))
    ids = sorted(e["id"] for e in episodes_of(client, feed["id"]))
    return feed, ids


def test_state_applies_newer_and_reports_server_state(client, store, seeded):
    feed, (first, _) = seeded
    rev_before = store.current_rev()
    response = client.post("/v1/episodes/state", json=[item(first)])
    assert response.status_code == 200
    assert response.json() == [
        {
            "episodeId": first,
            "outcome": "APPLIED",
            "status": "PLAYED",
            "playedAt": NEWER,
            "positionSec": 10,
            "stateUpdatedAt": NEWER,
        }
    ]
    assert store.current_rev() == rev_before + 1
    stored = next(e for e in episodes_of(client, feed["id"]) if e["id"] == first)
    assert (stored["status"], stored["positionSec"], stored["rev"]) == (
        "PLAYED",
        10,
        rev_before + 1,
    )
    synced = client.get("/v1/sync", params={"since": rev_before}).json()
    assert [e["id"] for e in synced["episodes"]] == [first]
    assert synced["episodes"][0]["status"] == "PLAYED"


def test_state_equal_and_older_are_stale_and_change_nothing(client, store, seeded):
    _, (first, _) = seeded
    client.post("/v1/episodes/state", json=[item(first, position=10)])
    rev = store.current_rev()
    results = client.post(
        "/v1/episodes/state",
        json=[item(first, position=99), item(first, position=77, updated=NEWER - 1)],
    ).json()
    assert [r["outcome"] for r in results] == ["STALE", "STALE"]
    for r in results:
        assert set(r) == STATE_KEYS
        assert (r["status"], r["positionSec"], r["stateUpdatedAt"]) == ("PLAYED", 10, NEWER)
    assert store.current_rev() == rev


def test_state_unknown_episode_has_only_two_keys_and_batch_continues(client, store, seeded):
    _, (first, _) = seeded
    rev = store.current_rev()
    results = client.post(
        "/v1/episodes/state", json=[item(0), item(-5), item(10**9), item(first)]
    ).json()
    assert results[0] == {"episodeId": 0, "outcome": "UNKNOWN_EPISODE"}
    assert results[1] == {"episodeId": -5, "outcome": "UNKNOWN_EPISODE"}
    assert results[2]["outcome"] == "UNKNOWN_EPISODE"
    assert results[3]["outcome"] == "APPLIED"
    assert store.current_rev() == rev + 1


def test_state_for_deleted_episode_is_unknown(client, fetcher):
    feed = subscribe(client, fetcher, "https://example.com/a.xml", ep(1))
    episode_id = episodes_of(client, feed["id"])[0]["id"]
    client.delete(f"/v1/feeds/{feed['id']}")
    result = client.post("/v1/episodes/state", json=[item(episode_id)]).json()
    assert result == [{"episodeId": episode_id, "outcome": "UNKNOWN_EPISODE"}]


def test_state_duplicates_are_processed_in_order_with_own_results(client, store, seeded):
    _, (first, _) = seeded
    rev = store.current_rev()
    batch = [
        item(first, position=1, updated=NEWER + 1),
        item(first, position=2, updated=NEWER + 5),
        item(first, position=3, updated=NEWER + 5),
        item(first, position=4, updated=NEWER + 2),
    ]
    results = client.post("/v1/episodes/state", json=batch).json()
    assert [r["outcome"] for r in results] == ["APPLIED", "APPLIED", "STALE", "STALE"]
    assert [r["positionSec"] for r in results] == [1, 2, 2, 2]
    assert store.current_rev() == rev + 2


def test_state_every_applied_item_gets_its_own_rev(client, store, seeded):
    feed, (first, second) = seeded
    rev = store.current_rev()
    client.post("/v1/episodes/state", json=[item(first), item(second)])
    revs = sorted(e["rev"] for e in episodes_of(client, feed["id"]))
    assert revs == [rev + 1, rev + 2]


def test_state_played_then_new_leaves_played_at_null(client, seeded):
    feed, (first, _) = seeded
    played = client.post("/v1/episodes/state", json=[item(first, updated=NEWER)]).json()
    assert played[0]["outcome"] == "APPLIED"
    back = client.post(
        "/v1/episodes/state", json=[item(first, status="NEW", position=0, updated=NEWER + 1)]
    ).json()
    assert back[0]["outcome"] == "APPLIED"
    assert back[0]["status"] == "NEW" and back[0]["playedAt"] is None
    stored = next(e for e in episodes_of(client, feed["id"]) if e["id"] == first)
    assert stored["playedAt"] is None


def test_state_batch_limit_is_1000(client, store, seeded):
    ok = client.post("/v1/episodes/state", json=[item(0)] * 1000)
    assert ok.status_code == 200 and len(ok.json()) == 1000
    rev = store.current_rev()
    too_many = client.post("/v1/episodes/state", json=[item(0)] * 1001)
    assert too_many.status_code == 422 and isinstance(too_many.json()["detail"], str)
    assert store.current_rev() == rev


def valid(**changes) -> dict:
    return {**item(1), **changes}


def without(key) -> dict:
    body = valid()
    del body[key]
    return body


INVALID_BODIES = {
    "not-an-array": {"episodeId": 1},
    "string-body": "x",
    "empty-array": [],
    "missing-episodeId": [without("episodeId")],
    "missing-status": [without("status")],
    "missing-playedAt": [without("playedAt")],
    "missing-positionSec": [without("positionSec")],
    "missing-stateUpdatedAt": [without("stateUpdatedAt")],
    "episodeId-string": [valid(episodeId="1")],
    "episodeId-float": [valid(episodeId=1.5)],
    "episodeId-bool": [valid(episodeId=True)],
    "positionSec-string": [valid(positionSec="3")],
    "positionSec-float": [valid(positionSec=3.0)],
    "positionSec-bool": [valid(positionSec=False)],
    "playedAt-string": [valid(playedAt="5")],
    "playedAt-float": [valid(playedAt=5.5)],
    "playedAt-bool": [valid(playedAt=True)],
    "stateUpdatedAt-string": [valid(stateUpdatedAt="5")],
    "stateUpdatedAt-float": [valid(stateUpdatedAt=5.0)],
    "stateUpdatedAt-bool": [valid(stateUpdatedAt=True)],
    "stateUpdatedAt-null": [valid(stateUpdatedAt=None)],
    "status-bad": [valid(status="DONE")],
    "status-lowercase": [valid(status="played")],
    "status-null": [valid(status=None)],
    "position-negative": [valid(positionSec=-1)],
    "position-too-big": [valid(positionSec=2_147_483_648)],
    "played-without-playedAt": [valid(status="PLAYED", playedAt=None)],
    "new-with-playedAt": [valid(status="NEW", playedAt=5)],
    "bad-item-after-good-one": [valid(), valid(positionSec=-1)],
    "item-not-object": [5],
}


@pytest.mark.parametrize("body", INVALID_BODIES.values(), ids=INVALID_BODIES.keys())
def test_state_validation_rejects_whole_request(client, store, seeded, body):
    feed, _ = seeded
    rev = store.current_rev()
    before = episodes_of(client, feed["id"])
    response = client.post("/v1/episodes/state", json=body)
    assert response.status_code == 422
    assert isinstance(response.json()["detail"], str)
    assert store.current_rev() == rev
    assert episodes_of(client, feed["id"]) == before


def test_state_body_that_is_not_json_is_422(client):
    response = client.post(
        "/v1/episodes/state", content=b"nope", headers={"Content-Type": "application/json"}
    )
    assert response.status_code == 422
    assert isinstance(response.json()["detail"], str)


def test_state_accepts_boundary_position(client, seeded):
    _, (first, _) = seeded
    result = client.post("/v1/episodes/state", json=[item(first, position=2_147_483_647)])
    assert result.json()[0]["outcome"] == "APPLIED"


# --- sync ----------------------------------------------------------------------------------------

SYNC_KEYS = {"feeds", "episodes", "tombstones", "nextRev", "hasMore"}


def pull_all(client, limit, between=None):
    """A paging client: follows nextRev while hasMore; applies upserts and Tombstones."""
    feeds, episodes = {}, {}
    since, pages = 0, 0
    while True:
        page = client.get("/v1/sync", params={"since": since, "limit": limit}).json()
        pages += 1
        assert set(page) == SYNC_KEYS
        for f in page["feeds"]:
            feeds[f["id"]] = f
        for e in page["episodes"]:
            episodes[e["id"]] = e
        for t in page["tombstones"]:
            (feeds if t["entityType"] == "FEED" else episodes).pop(t["entityId"], None)
        since = page["nextRev"]
        if not page["hasMore"]:
            return feeds, episodes, since, pages
        if between:
            between(pages)


def server_state(client):
    feeds = {f["id"]: f for f in client.get("/v1/feeds").json()}
    episodes = {e["id"]: e for f in feeds for e in episodes_of(client, f)}
    return feeds, episodes


def test_sync_empty_server(client):
    assert client.get("/v1/sync").json() == {
        "feeds": [],
        "episodes": [],
        "tombstones": [],
        "nextRev": 0,
        "hasMore": False,
    }


@pytest.mark.parametrize(
    "query",
    ["since=-1", "since=abc", "since=", "limit=0", "limit=501", "limit=abc", "limit=1.5", "limit="],
)
def test_sync_bad_query_is_422(client, query):
    response = client.get(f"/v1/sync?{query}")
    assert response.status_code == 422
    assert isinstance(response.json()["detail"], str)


def test_sync_since_zero_returns_everything_with_contract_shapes(client, fetcher):
    a = subscribe(client, fetcher, "https://example.com/a.xml", ep(1), ep(2))
    subscribe(client, fetcher, "https://example.com/b.xml", ep(3))
    client.delete(f"/v1/feeds/{a['id']}")
    page = client.get("/v1/sync").json()
    assert page["hasMore"] is False
    assert len(page["feeds"]) == 1 and "feedUrl" in page["feeds"][0]
    assert len(page["episodes"]) == 1 and set(page["episodes"][0]) == EPISODE_KEYS
    assert page["episodes"][0]["artworkUrl"] is None
    assert {t["entityType"] for t in page["tombstones"]} == {"FEED", "EPISODE"}
    assert all(set(t) == {"entityType", "entityId", "rev"} for t in page["tombstones"])
    assert page["nextRev"] == max(
        x["rev"] for x in page["feeds"] + page["episodes"] + page["tombstones"]
    )


def test_sync_limit_one_pages_return_each_item_once(client, store, fetcher):
    subscribe(client, fetcher, "https://example.com/a.xml", ep(1), ep(2))
    total = store.current_rev()
    seen = []
    since = 0
    for _ in range(total):
        page = client.get("/v1/sync", params={"since": since, "limit": 1}).json()
        items = page["feeds"] + page["episodes"] + page["tombstones"]
        assert len(items) == 1
        seen.append(items[0]["rev"])
        assert page["nextRev"] == items[0]["rev"]
        assert page["hasMore"] is (len(seen) < total)
        since = page["nextRev"]
    assert seen == sorted(set(seen)) and len(seen) == total


def test_sync_page_ending_exactly_at_last_item_has_no_more(client, store, fetcher):
    subscribe(client, fetcher, "https://example.com/a.xml", ep(1))
    total = store.current_rev()
    page = client.get("/v1/sync", params={"limit": total}).json()
    assert page["hasMore"] is False
    page = client.get("/v1/sync", params={"limit": total - 1}).json()
    assert page["hasMore"] is True and page["nextRev"] == total - 1


def test_sync_empty_page_next_rev_equals_since(client, store, fetcher):
    subscribe(client, fetcher, "https://example.com/a.xml", ep(1))
    top = store.current_rev()
    for since in (top, top + 100):
        page = client.get("/v1/sync", params={"since": since}).json()
        assert page["nextRev"] == since and page["hasMore"] is False
        assert page["feeds"] == page["episodes"] == page["tombstones"] == []


def test_sync_does_not_change_rev(client, store, fetcher):
    subscribe(client, fetcher, "https://example.com/a.xml", ep(1))
    rev = store.current_rev()
    client.get("/v1/sync")
    assert store.current_rev() == rev


def test_sync_mixes_kinds_in_rev_order_and_arrays_ascend(client, fetcher):
    a = subscribe(client, fetcher, "https://example.com/a.xml", ep(1), ep(2))
    episode_id = episodes_of(client, a["id"])[0]["id"]
    client.post("/v1/episodes/state", json=[item(episode_id)])
    page = client.get("/v1/sync", params={"limit": 3}).json()
    for key in ("feeds", "episodes", "tombstones"):
        revs = [x["rev"] for x in page[key]]
        assert revs == sorted(revs)
    assert len(page["feeds"]) + len(page["episodes"]) + len(page["tombstones"]) == 3


def test_sync_after_unsubscribe_returns_tombstones_not_entities(client, store, fetcher):
    feed = subscribe(client, fetcher, "https://example.com/a.xml", ep(1), ep(2))
    ids = {e["id"] for e in episodes_of(client, feed["id"])}
    since = store.current_rev()
    client.delete(f"/v1/feeds/{feed['id']}")
    page = client.get("/v1/sync", params={"since": 0}).json()
    assert page["feeds"] == [] and page["episodes"] == []
    tombs = page["tombstones"]
    assert {t["entityId"] for t in tombs if t["entityType"] == "EPISODE"} == ids
    assert [t["entityId"] for t in tombs if t["entityType"] == "FEED"] == [feed["id"]]
    assert len({t["rev"] for t in tombs}) == 3 and all(t["rev"] > since for t in tombs)


@pytest.mark.parametrize("limit", [1, 2, 7, 500])
def test_sync_paging_client_converges_on_a_static_server(client, fetcher, limit):
    subscribe(client, fetcher, "https://example.com/a.xml", ep(1), ep(2), ep(3))
    b = subscribe(client, fetcher, "https://example.com/b.xml", ep(4), ep(5))
    client.delete(f"/v1/feeds/{b['id']}")
    subscribe(client, fetcher, "https://example.com/c.xml", ep(6))
    feeds, episodes, _, _ = pull_all(client, limit)
    assert (feeds, episodes) == server_state(client)


@pytest.mark.parametrize("limit", [1, 2, 7, 500])
def test_sync_paging_client_converges_while_server_changes(client, fetcher, limit):
    a = subscribe(client, fetcher, "https://example.com/a.xml", *[ep(n) for n in range(1, 6)])
    b = subscribe(client, fetcher, "https://example.com/b.xml", ep(10), ep(11), ep(12))
    c = subscribe(client, fetcher, "https://example.com/c.xml", ep(20))
    a_ids = sorted(e["id"] for e in episodes_of(client, a["id"]))
    b_ids = sorted(e["id"] for e in episodes_of(client, b["id"]))
    changes = [
        lambda: client.post("/v1/episodes/state", json=[item(a_ids[0])]),  # already delivered
        lambda: client.post("/v1/episodes/state", json=[item(a_ids[-1], updated=NEWER + 1)]),
        lambda: client.delete(f"/v1/feeds/{b['id']}"),
        lambda: client.put("/v1/feeds/order", json={"feedIds": [a["id"], c["id"]]}),
        lambda: (
            fetcher.results.__setitem__("https://example.com/a.xml", feed_of(ep(1), ep(99))),
            client.post(f"/v1/feeds/{a['id']}/refresh"),
        ),
        lambda: client.post("/v1/episodes/state", json=[item(b_ids[0])]),  # now unknown
    ]

    def between(page_number):
        if page_number <= len(changes):
            changes[page_number - 1]()

    feeds, episodes, since, pages = pull_all(client, limit, between)
    # The client stopped when hasMore was false; one more pull picks up any later change.
    for change in changes[pages - 1 :]:
        change()
    while True:
        page = client.get("/v1/sync", params={"since": since, "limit": limit}).json()
        for f in page["feeds"]:
            feeds[f["id"]] = f
        for e in page["episodes"]:
            episodes[e["id"]] = e
        for t in page["tombstones"]:
            (feeds if t["entityType"] == "FEED" else episodes).pop(t["entityId"], None)
        since = page["nextRev"]
        if not page["hasMore"]:
            break
    assert (feeds, episodes) == server_state(client)


# --- auth and methods ----------------------------------------------------------------------------


UNAUTH_CALLS = [
    ("get", "/v1/feeds/1/episodes", None),
    ("post", "/v1/episodes/state", [{"bad": 1}]),
    ("get", "/v1/sync?since=-1", None),
]


@pytest.mark.parametrize("method,path,body", UNAUTH_CALLS)
@pytest.mark.parametrize("headers", [{}, {"Authorization": "Bearer wrong"}])
def test_unauthenticated_requests_are_401_before_validation(app, method, path, body, headers):
    response = TestClient(app).request(method, path, json=body, headers=headers)
    assert response.status_code == 401
    assert response.headers["WWW-Authenticate"] == "Bearer"
    assert response.json() == {"detail": "Not authenticated."}


@pytest.mark.parametrize(
    "method,path",
    [
        ("get", "/v1/episodes/state"),
        ("post", "/v1/sync"),
        ("delete", "/v1/feeds/12/episodes"),
        ("post", "/v1/feeds/12/episodes"),
    ],
)
@pytest.mark.parametrize("authed", [True, False])
def test_undefined_methods_are_405(app, client, method, path, authed):
    caller = client if authed else TestClient(app)
    response = caller.request(method, path)
    assert response.status_code == 405
    assert isinstance(response.json()["detail"], str)


# --- logging -------------------------------------------------------------------------------------


def test_listing_and_state_never_log_feed_urls(client, fetcher, caplog):
    caplog.set_level(logging.DEBUG)
    feed = subscribe(client, fetcher, SECRET_URL, ep(1))
    episode_id = episodes_of(client, feed["id"])[0]["id"]
    client.post("/v1/episodes/state", json=[item(episode_id)])
    client.get("/v1/sync")
    text = "\n".join(r.getMessage() for r in caplog.records)
    assert not any(part in text for part in SECRET_PARTS)
