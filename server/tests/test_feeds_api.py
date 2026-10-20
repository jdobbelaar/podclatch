import logging
import threading
from concurrent.futures import ThreadPoolExecutor

import pytest
from fastapi.testclient import TestClient

from podclatch_server.app import create_app
from podclatch_server.config import Settings
from podclatch_server.feeds import (
    FeedTimeoutError,
    FeedTooLargeError,
    FeedUnreachableError,
    NotAFeedError,
    ParsedEpisode,
    ParsedFeed,
)
from podclatch_server.routes.feeds import get_fetcher
from podclatch_server.store import EntityType, EpisodeStatus

TOKEN = "test-token-1"
AUTH = {"Authorization": f"Bearer {TOKEN}"}
FEED_KEYS = {
    "id",
    "feedUrl",
    "title",
    "author",
    "description",
    "artworkUrl",
    "priority",
    "listenOrder",
    "lastRefreshedAt",
    "subscribedAt",
    "rev",
}
SECRET_URL = "https://user:pw@example.com/feed.xml?token=SECRET123"


def make_episode(n: int = 1, artwork: str | None = None) -> ParsedEpisode:
    return ParsedEpisode(
        guid=f"guid-{n}",
        title=f"Episode {n}",
        description=f"About {n}",
        audio_url=f"https://example.com/audio/{n}.mp3",
        published_at=1_700_000_000_000 + n,
        duration_sec=60 * n,
        artwork_url=artwork,
    )


def make_feed(title: str = "Show", episodes: int = 2) -> ParsedFeed:
    return ParsedFeed(
        title=title,
        author="Author",
        description="Description",
        artwork_url="https://example.com/art.jpg",
        episodes=[make_episode(n) for n in range(1, episodes + 1)],
    )


class StubFetcher:
    """Returns a canned feed (or raises) and records the URLs it was asked for."""

    def __init__(self) -> None:
        self.calls: list[str] = []
        self.result: ParsedFeed | Exception = make_feed()

    def __call__(self, url: str) -> ParsedFeed:
        self.calls.append(url)
        if isinstance(self.result, Exception):
            raise self.result
        return self.result


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
    client.get("/v1/feeds")  # creates the app's store
    return app.state.store


def assert_priorities_contiguous(client) -> list[dict]:
    feeds = client.get("/v1/feeds").json()
    assert [f["priority"] for f in feeds] == list(range(len(feeds)))
    return feeds


def subscribe(client, fetcher, url: str, title: str = "Show", episodes: int = 2) -> dict:
    fetcher.result = make_feed(title, episodes)
    response = client.post("/v1/feeds", json={"feedUrl": url})
    assert response.status_code == 201, response.text
    return response.json()


def snapshot(client, store):
    return client.get("/v1/feeds").json(), store.current_rev(), store.list_tombstones()


# --- subscribe -----------------------------------------------------------------------------------


def test_subscribe_returns_feed_with_contract_shape(client, fetcher):
    response = client.post("/v1/feeds", json={"feedUrl": "https://example.com/a.xml"})
    assert response.status_code == 201
    feed = response.json()
    assert set(feed) == FEED_KEYS
    assert isinstance(feed["id"], int)
    assert feed["feedUrl"] == "https://example.com/a.xml"
    assert (feed["title"], feed["author"], feed["description"]) == ("Show", "Author", "Description")
    assert feed["artworkUrl"] == "https://example.com/art.jpg"
    assert feed["priority"] == 0
    assert feed["listenOrder"] == "NEWEST_FIRST"
    assert isinstance(feed["lastRefreshedAt"], int)
    assert feed["lastRefreshedAt"] == feed["subscribedAt"]
    assert feed["subscribedAt"] > 1_700_000_000_000
    assert isinstance(feed["rev"], int)
    assert fetcher.calls == ["https://example.com/a.xml"]
    assert_priorities_contiguous(client)


def test_missing_publisher_text_is_empty_string_not_null(client, fetcher):
    fetcher.result = ParsedFeed(title="", author="", description="", artwork_url="", episodes=[])
    feed = client.post("/v1/feeds", json={"feedUrl": "https://example.com/a.xml"}).json()
    assert [feed[k] for k in ("title", "author", "description", "artworkUrl")] == [""] * 4


def test_stored_feed_url_is_trimmed(client, fetcher):
    feed = subscribe(client, fetcher, "  https://example.com/a.xml \n")
    assert feed["feedUrl"] == "https://example.com/a.xml"
    assert fetcher.calls == ["https://example.com/a.xml"]


def test_three_subscribes_give_priorities_zero_one_two_newest_first(client, fetcher):
    for name in ("one", "two", "three"):
        subscribe(client, fetcher, f"https://example.com/{name}.xml", title=name)
        assert_priorities_contiguous(client)
    feeds = client.get("/v1/feeds").json()
    assert [f["title"] for f in feeds] == ["three", "two", "one"]


def test_subscribe_stores_episodes_as_new(client, fetcher, store):
    fetcher.result = ParsedFeed("T", "A", "D", "art", [make_episode(1), make_episode(2, "x.jpg")])
    feed = client.post("/v1/feeds", json={"feedUrl": "https://example.com/a.xml"}).json()
    first, second = store.list_episodes(feed["id"])
    assert first.feed_id == feed["id"]
    assert (first.guid, first.title, first.description) == ("guid-1", "Episode 1", "About 1")
    assert first.audio_url == "https://example.com/audio/1.mp3"
    assert (first.published_at, first.duration_sec) == (1_700_000_000_001, 60)
    assert first.artwork_url is None  # not copied from the Feed
    assert second.artwork_url == "x.jpg"
    for episode in (first, second):
        assert episode.status is EpisodeStatus.NEW
        assert episode.played_at is None
        assert episode.position_sec == 0
        assert episode.state_updated_at == episode.published_at
    assert first.id != second.id


def test_feed_with_zero_episodes_is_valid(client, fetcher, store):
    feed = subscribe(client, fetcher, "https://example.com/a.xml", episodes=0)
    assert store.list_episodes(feed["id"]) == []
    assert len(client.get("/v1/feeds").json()) == 1


def test_duplicate_returns_409_without_fetching(client, fetcher, store):
    subscribe(client, fetcher, "https://example.com/feed.xml")
    before = snapshot(client, store)
    fetcher.calls.clear()
    response = client.post("/v1/feeds", json={"feedUrl": "https://example.com/feed.xml"})
    assert response.status_code == 409
    assert response.json() == {"detail": "Already subscribed to this feed."}
    assert fetcher.calls == []
    assert snapshot(client, store) == before


@pytest.mark.parametrize(
    "variant",
    [
        "HTTPS://Example.com:443/feed.xml",
        " https://example.com/feed.xml ",
        "https://EXAMPLE.com/feed.xml#frag",
        "https://example.com:443/feed.xml",
    ],
)
def test_equivalent_urls_are_duplicates(client, fetcher, variant):
    subscribe(client, fetcher, "https://example.com/feed.xml")
    assert client.post("/v1/feeds", json={"feedUrl": variant}).status_code == 409
    assert_priorities_contiguous(client)


def test_equivalent_urls_the_other_way_round(client, fetcher):
    subscribe(client, fetcher, "HTTPS://Example.com:443/feed.xml")
    response = client.post("/v1/feeds", json={"feedUrl": " https://example.com/feed.xml "})
    assert response.status_code == 409


def test_empty_path_equals_slash(client, fetcher):
    subscribe(client, fetcher, "https://example.com")
    assert client.post("/v1/feeds", json={"feedUrl": "https://example.com/"}).status_code == 409


@pytest.mark.parametrize(
    "other",
    [
        "https://example.com/Feed.xml",
        "https://example.com/feed.xml?a=1",
        "http://example.com/feed.xml",
        "https://other@example.com/feed.xml",
        "https://example.com:8443/feed.xml",
    ],
)
def test_different_urls_are_not_duplicates(client, fetcher, other):
    subscribe(client, fetcher, "https://example.com/feed.xml")
    subscribe(client, fetcher, other)
    assert len(assert_priorities_contiguous(client)) == 2


@pytest.mark.parametrize(
    "error",
    [
        FeedUnreachableError("The feed server could not be reached."),
        FeedTimeoutError("The feed server took too long to answer."),
        FeedTooLargeError("The feed is larger than the 10 MB limit."),
        NotAFeedError("The response is not an RSS feed."),
    ],
    ids=lambda e: type(e).__name__,
)
def test_feed_errors_return_422_and_change_nothing(client, fetcher, store, error):
    subscribe(client, fetcher, "https://example.com/first.xml")
    before = snapshot(client, store)
    fetcher.result = error
    response = client.post("/v1/feeds", json={"feedUrl": "https://example.com/bad.xml"})
    assert response.status_code == 422
    assert response.json() == {"detail": str(error)}
    assert snapshot(client, store) == before


def test_failed_subscribe_leaves_ids_and_revs_as_if_it_never_happened(app, fetcher):
    def run(with_failures: bool):
        fresh = create_app(Settings(token=TOKEN, test_mode=True))
        stub = StubFetcher()
        fresh.dependency_overrides[get_fetcher] = lambda: stub
        c = TestClient(fresh, headers=AUTH)
        first = subscribe(c, stub, "https://example.com/1.xml")
        if with_failures:
            stub.result = NotAFeedError("nope")
            assert (
                c.post("/v1/feeds", json={"feedUrl": "https://example.com/x.xml"}).status_code
                == 422
            )
            assert (
                c.post("/v1/feeds", json={"feedUrl": "https://example.com/1.xml"}).status_code
                == 409
            )
            assert c.post("/v1/feeds", json={"feedUrl": 5}).status_code == 422
        second = subscribe(c, stub, "https://example.com/2.xml")
        for f in (first, second):
            f.pop("subscribedAt"), f.pop("lastRefreshedAt")
        return first, second, [e.id for e in fresh.state.store.list_episodes(second["id"])]

    assert run(with_failures=True) == run(with_failures=False)


@pytest.mark.parametrize(
    "body",
    [
        {},
        {"feedUrl": None},
        {"feedUrl": 5},
        {"feedUrl": ["a"]},
        {"feedUrl": ""},
        {"feedUrl": "  \t"},
    ],
)
def test_invalid_subscribe_body_is_422_without_fetching(client, fetcher, body):
    response = client.post("/v1/feeds", json=body)
    assert response.status_code == 422
    assert isinstance(response.json()["detail"], str)
    assert fetcher.calls == []


def test_non_json_body_is_422(client, fetcher):
    response = client.post(
        "/v1/feeds", content=b"not json", headers={"Content-Type": "application/json"}
    )
    assert response.status_code == 422
    assert fetcher.calls == []


def test_non_http_url_reaches_the_real_fetcher_and_is_422(app):
    app.dependency_overrides.clear()  # the real fetch_feed rejects the scheme before any network
    client = TestClient(app, headers=AUTH)
    response = client.post("/v1/feeds", json={"feedUrl": "ftp://example.com/feed.xml"})
    assert response.status_code == 422
    assert isinstance(response.json()["detail"], str)
    assert client.get("/v1/feeds").json() == []


def test_fetch_does_not_hold_the_store_lock(app):
    started = threading.Event()
    release = threading.Event()

    def slow(url: str) -> ParsedFeed:
        started.set()
        assert release.wait(5)
        return make_feed()

    app.dependency_overrides[get_fetcher] = lambda: slow
    with ThreadPoolExecutor(max_workers=1) as pool:
        pending = pool.submit(
            lambda: TestClient(app, headers=AUTH).post(
                "/v1/feeds", json={"feedUrl": "https://example.com/slow.xml"}
            )
        )
        try:
            assert started.wait(5)
            listing = TestClient(app, headers=AUTH).get("/v1/feeds")
            assert listing.status_code == 200
            assert listing.json() == []
        finally:
            release.set()
        assert pending.result(5).status_code == 201


# --- list ----------------------------------------------------------------------------------------


def test_list_is_empty_list_with_no_feeds(client):
    response = client.get("/v1/feeds")
    assert response.status_code == 200
    assert response.json() == []


# --- reorder -------------------------------------------------------------------------------------


@pytest.fixture
def three(client, fetcher):
    return [subscribe(client, fetcher, f"https://example.com/{i}.xml", f"f{i}") for i in range(3)]


def test_reorder_rewrites_priorities(client, three):
    ids = [f["id"] for f in three]  # priorities are now 2, 1, 0 in this order
    new_order = [ids[1], ids[0], ids[2]]
    response = client.put("/v1/feeds/order", json={"feedIds": new_order})
    assert response.status_code == 200
    body = response.json()
    assert [f["id"] for f in body] == new_order
    assert [f["priority"] for f in body] == [0, 1, 2]
    assert client.get("/v1/feeds").json() == body
    assert_priorities_contiguous(client)


@pytest.mark.parametrize(
    "build",
    [
        pytest.param(lambda ids: [ids[0], ids[1]], id="missing"),
        pytest.param(lambda ids: [ids[0], ids[0], ids[1], ids[2]], id="repeated"),
        pytest.param(lambda ids: [ids[0], ids[1], 9999], id="unknown-replaces"),
        pytest.param(lambda ids: [*ids, 9999], id="extra-unknown"),
        pytest.param(lambda ids: [], id="empty-with-feeds"),
        pytest.param(lambda ids: [str(ids[0]), ids[1], ids[2]], id="string-entry"),
        pytest.param(lambda ids: [1.5, ids[1], ids[2]], id="float-entry"),
        pytest.param(lambda ids: [True, ids[1], ids[2]], id="bool-entry"),
        pytest.param(lambda ids: [None, ids[1], ids[2]], id="null-entry"),
    ],
)
def test_invalid_reorder_is_422_and_atomic(client, store, three, build):
    before = snapshot(client, store)
    response = client.put("/v1/feeds/order", json={"feedIds": build([f["id"] for f in three])})
    assert response.status_code == 422
    assert isinstance(response.json()["detail"], str)
    assert snapshot(client, store) == before
    assert_priorities_contiguous(client)


@pytest.mark.parametrize("body", [{}, {"feedIds": None}, {"feedIds": "1,2,3"}, []])
def test_reorder_without_a_list_is_422(client, store, three, body):
    before = snapshot(client, store)
    assert client.put("/v1/feeds/order", json=body).status_code == 422
    assert snapshot(client, store) == before


def test_empty_reorder_is_valid_with_no_feeds(client):
    response = client.put("/v1/feeds/order", json={"feedIds": []})
    assert response.status_code == 200
    assert response.json() == []


# --- listen order --------------------------------------------------------------------------------


def test_patch_listen_order(client, three):
    target = client.get("/v1/feeds").json()[2]
    for value in ("OLDEST_FIRST", "NEWEST_FIRST"):
        response = client.patch(f"/v1/feeds/{target['id']}", json={"listenOrder": value})
        assert response.status_code == 200
        updated = response.json()
        assert updated["listenOrder"] == value
        assert {k: v for k, v in updated.items() if k not in ("listenOrder", "rev")} == {
            k: v for k, v in target.items() if k not in ("listenOrder", "rev")
        }
        listed = {f["id"]: f for f in client.get("/v1/feeds").json()}
        assert listed[target["id"]]["listenOrder"] == value


@pytest.mark.parametrize("feed_id", [9999, -1, 0])
def test_patch_unknown_feed_is_404(client, feed_id):
    response = client.patch(f"/v1/feeds/{feed_id}", json={"listenOrder": "OLDEST_FIRST"})
    assert response.status_code == 404
    assert response.json() == {"detail": "Feed not found."}


def test_patch_unsubscribed_feed_is_404(client, three):
    client.delete(f"/v1/feeds/{three[0]['id']}")
    response = client.patch(f"/v1/feeds/{three[0]['id']}", json={"listenOrder": "OLDEST_FIRST"})
    assert response.status_code == 404


@pytest.mark.parametrize(
    "body",
    [{"listenOrder": "oldest_first"}, {"listenOrder": "SIDEWAYS"}, {}, {"listenOrder": None}],
)
def test_patch_invalid_listen_order_is_422_even_for_unknown_id(client, three, body):
    assert client.patch(f"/v1/feeds/{three[0]['id']}", json=body).status_code == 422
    assert client.patch("/v1/feeds/9999", json=body).status_code == 422


# --- unsubscribe ---------------------------------------------------------------------------------


def test_unsubscribe_removes_feed_episodes_and_writes_tombstones(client, fetcher, store):
    feed = subscribe(client, fetcher, "https://example.com/a.xml", episodes=3)
    episode_ids = [e.id for e in store.list_episodes(feed["id"])]
    response = client.delete(f"/v1/feeds/{feed['id']}")
    assert response.status_code == 204
    assert response.content == b""
    assert client.get("/v1/feeds").json() == []
    assert store.list_episodes(feed["id"]) == []
    tombstones = store.list_tombstones()
    assert {(t.entity_type, t.entity_id) for t in tombstones} == {
        (EntityType.FEED, feed["id"]),
        *((EntityType.EPISODE, i) for i in episode_ids),
    }
    assert len({t.rev for t in tombstones}) == len(tombstones) == 4


@pytest.mark.parametrize("index", [0, 1, 2, 3])
def test_unsubscribe_closes_the_priority_gap(client, fetcher, index):
    for i in range(4):
        subscribe(client, fetcher, f"https://example.com/{i}.xml", f"f{i}")
    before = client.get("/v1/feeds").json()  # priorities 0..3
    assert client.delete(f"/v1/feeds/{before[index]['id']}").status_code == 204
    after = assert_priorities_contiguous(client)
    assert [f["id"] for f in after] == [f["id"] for i, f in enumerate(before) if i != index]


def test_unsubscribe_only_feed(client, fetcher):
    feed = subscribe(client, fetcher, "https://example.com/a.xml")
    assert client.delete(f"/v1/feeds/{feed['id']}").status_code == 204
    assert client.get("/v1/feeds").json() == []


def test_unsubscribe_unknown_or_twice_is_404_and_changes_nothing(client, fetcher, store):
    feed = subscribe(client, fetcher, "https://example.com/a.xml")
    assert client.delete(f"/v1/feeds/{feed['id']}").status_code == 204
    before = snapshot(client, store)
    for feed_id in (feed["id"], 9999, -3):
        response = client.delete(f"/v1/feeds/{feed_id}")
        assert response.status_code == 404
        assert response.json() == {"detail": "Feed not found."}
    assert snapshot(client, store) == before


def test_resubscribe_gets_new_ids_and_old_tombstones_remain(client, fetcher, store):
    url = "https://example.com/a.xml"
    old = subscribe(client, fetcher, url)
    old_episode_ids = {e.id for e in store.list_episodes(old["id"])}
    client.delete(f"/v1/feeds/{old['id']}")
    tombstones = store.list_tombstones()
    new = subscribe(client, fetcher, url)
    assert new["id"] != old["id"]
    assert not old_episode_ids & {e.id for e in store.list_episodes(new["id"])}
    assert store.list_tombstones()[: len(tombstones)] == tombstones


# --- authentication ------------------------------------------------------------------------------

OPERATIONS = [
    ("get", "/v1/feeds", None),
    ("post", "/v1/feeds", {"feedUrl": "https://example.com/a.xml"}),
    ("post", "/v1/feeds", {"nonsense": True}),
    ("put", "/v1/feeds/order", {"feedIds": []}),
    ("put", "/v1/feeds/order", {"feedIds": "bad"}),
    ("patch", "/v1/feeds/1", {"listenOrder": "OLDEST_FIRST"}),
    ("patch", "/v1/feeds/abc", {"listenOrder": "bad"}),
    ("delete", "/v1/feeds/1", None),
    ("delete", "/v1/feeds/abc", None),
]


@pytest.mark.parametrize(
    "headers", [{}, {"Authorization": "Bearer wrong"}], ids=["missing", "wrong"]
)
@pytest.mark.parametrize("method,path,body", OPERATIONS)
def test_every_operation_requires_the_token(app, fetcher, method, path, body, headers):
    anonymous = TestClient(app)
    response = anonymous.request(method, path, json=body, headers=headers)
    assert response.status_code == 401
    assert response.headers["WWW-Authenticate"] == "Bearer"
    assert response.json() == {"detail": "Not authenticated."}
    assert fetcher.calls == []


# --- logging and secrets (decision 27) -----------------------------------------------------------


def test_no_log_or_response_contains_the_url_query_or_userinfo(client, fetcher, caplog):
    caplog.set_level(logging.DEBUG)
    bodies = []

    def record(response):
        if response.status_code >= 400:  # a Feed body carries its own feedUrl by contract
            bodies.append(response.text)
        return response

    created = record(client.post("/v1/feeds", json={"feedUrl": SECRET_URL})).json()
    assert created["feedUrl"] == SECRET_URL  # the owner's own Feed echoes its own URL
    record(client.post("/v1/feeds", json={"feedUrl": SECRET_URL}))  # duplicate
    fetcher.result = NotAFeedError("The response is not an RSS feed.")
    record(client.post("/v1/feeds", json={"feedUrl": SECRET_URL.replace("feed.xml", "other.xml")}))
    record(client.post("/v1/feeds", json={"feedUrl": [SECRET_URL]}))  # validation error
    record(client.post("/v1/feeds", json={"feedUrl": 12345, "note": "token=SECRET123"}))
    record(client.put("/v1/feeds/order", json={"feedIds": [created["id"]]}))
    record(client.put("/v1/feeds/order", json={"feedIds": [SECRET_URL]}))
    record(client.patch(f"/v1/feeds/{created['id']}", json={"listenOrder": "OLDEST_FIRST"}))
    record(client.delete(f"/v1/feeds/{created['id']}"))
    for text in bodies:
        assert "SECRET123" not in text
        assert "user:pw" not in text
    assert caplog.records
    for rec in caplog.records:
        text = rec.getMessage() + repr(rec.__dict__)
        assert "SECRET123" not in text
        assert "token=" not in text
        assert "user:pw" not in text
        assert SECRET_URL not in text
