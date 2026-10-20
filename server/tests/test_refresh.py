import logging
import threading
from concurrent.futures import ThreadPoolExecutor

import pytest
from fastapi.testclient import TestClient
from us25_helpers import AUTH, SECRET_PARTS, SECRET_URL, TOKEN, StubFetcher, ep, feed_of

from podclatch_server import refresh, sql_store
from podclatch_server.app import create_app
from podclatch_server.config import DEFAULT_DATABASE_URL, Settings
from podclatch_server.feeds import FeedTimeoutError, FeedUnreachableError, NotAFeedError
from podclatch_server.refresh import GENERIC_ERROR, TIMEOUT_ERROR, UNSUBSCRIBED_ERROR
from podclatch_server.routes.feeds import get_fetcher
from podclatch_server.scheduler import RefreshScheduler
from podclatch_server.store import store_for_app

URL_A = "https://example.com/a.xml"
URL_B = "https://example.com/b.xml"
URL_C = "https://example.com/c.xml"


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


def subscribe(client, fetcher, url, *episodes, **kwargs) -> dict:
    fetcher.results[url] = feed_of(*episodes, **kwargs)
    response = client.post("/v1/feeds", json={"feedUrl": url})
    assert response.status_code == 201, response.text
    return response.json()


def episodes_of(client, feed_id) -> list[dict]:
    return client.get(f"/v1/feeds/{feed_id}/episodes").json()


def feed_row(client, feed_id) -> dict:
    return next(f for f in client.get("/v1/feeds").json() if f["id"] == feed_id)


def set_last_refreshed(store, feed_id, value=1):
    """Reset lastRefreshedAt through the store without changing anything else."""
    feed = store.get_feed(feed_id)
    parsed = feed_of(
        title=feed.title, author=feed.author
    )  # same metadata (description and art match the stub)
    store.apply_refresh(feed_id, parsed, value)


# --- manual refresh: results and ordering --------------------------------------------------------


def test_refresh_all_with_no_feeds_is_empty(client, fetcher):
    response = client.post("/v1/refresh")
    assert response.status_code == 200 and response.json() == []
    assert fetcher.calls == []


def test_refresh_all_fetches_in_priority_order_and_reports_in_order(client, fetcher):
    a = subscribe(client, fetcher, URL_A, ep(1))
    b = subscribe(client, fetcher, URL_B, ep(2))
    c = subscribe(client, fetcher, URL_C, ep(3))  # priority 0 now: c, b, a
    client.put("/v1/feeds/order", json={"feedIds": [b["id"], a["id"], c["id"]]})
    fetcher.calls.clear()
    results = client.post("/v1/refresh").json()
    assert fetcher.calls == [URL_B, URL_A, URL_C]
    assert results == [{"feedId": i, "ok": True} for i in (b["id"], a["id"], c["id"])]


def test_refresh_one_feed_returns_exactly_one_result(client, fetcher):
    a = subscribe(client, fetcher, URL_A, ep(1))
    subscribe(client, fetcher, URL_B, ep(2))
    fetcher.calls.clear()
    response = client.post(f"/v1/feeds/{a['id']}/refresh")
    assert response.status_code == 200
    assert response.json() == [{"feedId": a["id"], "ok": True}]
    assert fetcher.calls == [URL_A]


@pytest.mark.parametrize("feed_id,status", [("999", 404), ("-1", 404), ("abc", 422)])
def test_refresh_one_unknown_or_bad_id_never_fetches(client, fetcher, feed_id, status):
    response = client.post(f"/v1/feeds/{feed_id}/refresh")
    assert response.status_code == status
    assert isinstance(response.json()["detail"], str)
    if status == 404:
        assert response.json() == {"detail": "Feed not found."}
    assert fetcher.calls == []


# --- apply rules ---------------------------------------------------------------------------------


def test_refresh_inserts_unseen_guids_as_new_in_feed_order(client, store, fetcher):
    feed = subscribe(client, fetcher, URL_A, ep(1))
    rev = store.current_rev()
    fetcher.results[URL_A] = feed_of(ep(3), ep(1), ep(2, artwork="art"), ep(4))
    client.post(f"/v1/feeds/{feed['id']}/refresh")
    new = [e for e in episodes_of(client, feed["id"]) if e["guid"] != "guid-1"]
    by_guid = {e["guid"]: e for e in new}
    assert set(by_guid) == {"guid-2", "guid-3", "guid-4"}
    for e in new:
        assert (e["status"], e["playedAt"], e["positionSec"]) == ("NEW", None, 0)
        assert e["stateUpdatedAt"] == e["publishedAt"]
    assert by_guid["guid-2"]["artworkUrl"] == "art" and by_guid["guid-3"]["artworkUrl"] is None
    # Ids and revs follow the feed's listing order (3, 2, 4), one rev each.
    ordered = sorted(new, key=lambda e: e["id"])
    assert [e["guid"] for e in ordered] == ["guid-3", "guid-2", "guid-4"]
    assert [e["rev"] for e in ordered] == [rev + 1, rev + 2, rev + 3]
    assert store.current_rev() == rev + 3


def test_refresh_keeps_known_episodes_untouched_and_never_deletes(client, store, fetcher):
    feed = subscribe(client, fetcher, URL_A, ep(1), ep(2))
    first = next(e for e in episodes_of(client, feed["id"]) if e["guid"] == "guid-1")
    client.post(
        "/v1/episodes/state",
        json=[
            {
                "episodeId": first["id"],
                "status": "PLAYED",
                "playedAt": 1_800_000_000_000,
                "positionSec": 33,
                "stateUpdatedAt": 1_800_000_000_000,
            }
        ],
    )
    before = {e["id"]: e for e in episodes_of(client, feed["id"])}
    tombstones = store.list_tombstones()
    changed = ep(1)
    fetcher.results[URL_A] = feed_of(
        type(changed)(
            guid="guid-1",
            title="Renamed",
            description="New text",
            audio_url="https://example.com/audio/other.mp3",
            published_at=5,
            duration_sec=7,
            artwork_url="x",
        )
    )  # guid-2 disappeared from the publisher's feed
    client.post(f"/v1/feeds/{feed['id']}/refresh")
    assert {e["id"]: e for e in episodes_of(client, feed["id"])} == before
    assert store.list_tombstones() == tombstones


def test_refresh_metadata_change_bumps_feed_rev_and_keeps_other_fields(client, store, fetcher):
    feed = subscribe(client, fetcher, URL_A, ep(1), title="Old")
    other = subscribe(client, fetcher, URL_B, ep(2))  # becomes priority 0
    client.patch(f"/v1/feeds/{feed['id']}", json={"listenOrder": "OLDEST_FIRST"})
    before = feed_row(client, feed["id"])
    set_last_refreshed(store, feed["id"], 1)
    rev = store.current_rev()
    fetcher.results[URL_A] = feed_of(ep(1), title="New", author="Someone")
    client.post(f"/v1/feeds/{feed['id']}/refresh")
    after = feed_row(client, feed["id"])
    assert (after["title"], after["author"]) == ("New", "Someone")
    assert after["rev"] == rev + 1 == store.current_rev()
    assert after["lastRefreshedAt"] > 1
    for key in ("feedUrl", "priority", "listenOrder", "subscribedAt"):
        assert after[key] == before[key]
    assert feed_row(client, other["id"])["priority"] == 0


def test_noop_refresh_bumps_nothing_but_updates_last_refreshed_at(client, store, fetcher):
    feed = subscribe(client, fetcher, URL_A, ep(1), ep(2))
    set_last_refreshed(store, feed["id"], 1)
    rev = store.current_rev()
    row_before = feed_row(client, feed["id"])
    assert row_before["lastRefreshedAt"] == 1
    client.post("/v1/refresh")
    assert store.current_rev() == rev
    row = feed_row(client, feed["id"])
    assert row["lastRefreshedAt"] > 1 and row["rev"] == row_before["rev"]


def test_failed_fetch_changes_nothing_and_does_not_stop_others(client, store, fetcher):
    a = subscribe(client, fetcher, URL_A, ep(1))
    b = subscribe(client, fetcher, URL_B, ep(2))  # priority 0, fetched first
    set_last_refreshed(store, a["id"], 1)
    set_last_refreshed(store, b["id"], 1)
    rev = store.current_rev()
    fetcher.results[URL_B] = FeedUnreachableError("Could not fetch the feed (HTTP 503).")
    fetcher.results[URL_A] = feed_of(ep(1), ep(5))
    results = client.post("/v1/refresh").json()
    assert results[0] == {
        "feedId": b["id"],
        "ok": False,
        "error": "Could not fetch the feed (HTTP 503).",
    }
    assert results[1] == {"feedId": a["id"], "ok": True}
    assert feed_row(client, b["id"])["lastRefreshedAt"] == 1
    assert len(episodes_of(client, b["id"])) == 1
    assert store.current_rev() == rev + 1  # only the new episode of A


@pytest.mark.parametrize("error", [FeedTimeoutError(), NotAFeedError(""), FeedUnreachableError()])
def test_feed_errors_always_give_a_nonempty_string(client, fetcher, error):
    feed = subscribe(client, fetcher, URL_A, ep(1))
    fetcher.results[URL_A] = error
    (result,) = client.post(f"/v1/feeds/{feed['id']}/refresh").json()
    assert result["ok"] is False and isinstance(result["error"], str) and result["error"]


def test_unexpected_fetcher_exception_is_generic_and_loop_continues(client, fetcher):
    a = subscribe(client, fetcher, URL_A, ep(1))
    b = subscribe(client, fetcher, URL_B, ep(2))
    fetcher.results[URL_B] = RuntimeError(f"boom {SECRET_URL}")
    response = client.post("/v1/refresh")
    assert response.status_code == 200
    assert response.json() == [
        {"feedId": b["id"], "ok": False, "error": GENERIC_ERROR},
        {"feedId": a["id"], "ok": True},
    ]


def test_error_text_never_contains_url_query_or_userinfo(client, fetcher):
    feed = subscribe(client, fetcher, SECRET_URL, ep(1))
    for message in (f"failed for {SECRET_URL}", "bad token=SECRET123", "user:pw rejected"):
        fetcher.results[SECRET_URL] = FeedUnreachableError(message)
        (result,) = client.post(f"/v1/feeds/{feed['id']}/refresh").json()
        assert result["ok"] is False
        assert not any(part in result["error"] for part in SECRET_PARTS)
        assert "example.com/feed.xml" not in result["error"] or "?" not in result["error"]


def test_feed_unsubscribed_during_fetch_gets_nothing_inserted(client, store, fetcher):
    feed = subscribe(client, fetcher, URL_A, ep(1))
    other = subscribe(client, fetcher, URL_B, ep(2))
    rev_holder = {}

    def fetch(url):
        if url == URL_A:
            client.delete(f"/v1/feeds/{feed['id']}")
            rev_holder["rev"] = store.current_rev()
        return feed_of(ep(1), ep(7))

    client.app.dependency_overrides[get_fetcher] = lambda: fetch
    results = client.post("/v1/refresh").json()
    assert results == [
        {"feedId": other["id"], "ok": True},
        {"feedId": feed["id"], "ok": False, "error": UNSUBSCRIBED_ERROR},
    ]
    assert client.get(f"/v1/feeds/{feed['id']}/episodes").status_code == 404
    assert store.get_feed(feed["id"]) is None
    tombstones = store.list_tombstones()
    assert (
        len([t for t in tombstones if t.entity_id == feed["id"] and t.entity_type == "FEED"]) == 1
    )
    assert store.current_rev() == rev_holder["rev"]  # the late result consumed no rev


# --- 60-second cap, locks, concurrency -----------------------------------------------------------


def join_manual_threads():
    for thread in threading.enumerate():
        if thread.name == "manual-refresh":
            thread.join(5)


def test_cap_reports_unfinished_feeds_and_discards_late_results(
    client, store, fetcher, monkeypatch
):
    a = subscribe(client, fetcher, URL_A, ep(1))
    b = subscribe(client, fetcher, URL_B, ep(2))
    c = subscribe(client, fetcher, URL_C, ep(3))  # order: c, b, a
    rev = store.current_rev()
    started, release = threading.Event(), threading.Event()
    calls = []

    def blocking(url):
        calls.append(url)
        started.set()
        release.wait(10)
        return feed_of(ep(1), ep(50))

    client.app.dependency_overrides[get_fetcher] = lambda: blocking
    monkeypatch.setattr(refresh, "REFRESH_CAP_SECONDS", 0.05)
    results = client.post("/v1/refresh").json()
    assert started.is_set()
    assert results == [{"feedId": f["id"], "ok": False, "error": TIMEOUT_ERROR} for f in (c, b, a)]
    release.set()
    join_manual_threads()
    assert calls == [URL_C]  # the others were never started
    assert store.current_rev() == rev
    assert len(episodes_of(client, c["id"])) == 1


def test_no_store_lock_is_held_while_a_fetch_is_in_flight(client, fetcher):
    feed = subscribe(client, fetcher, URL_A, ep(1))
    started, release = threading.Event(), threading.Event()

    def blocking(url):
        started.set()
        release.wait(10)
        return feed_of(ep(1))

    client.app.dependency_overrides[get_fetcher] = lambda: blocking
    with ThreadPoolExecutor(max_workers=1) as pool:
        future = pool.submit(client.post, f"/v1/feeds/{feed['id']}/refresh")
        try:
            assert started.wait(5)
            assert client.get("/v1/feeds").status_code == 200
            assert client.get("/v1/sync").status_code == 200
        finally:
            release.set()
        assert future.result(10).json() == [{"feedId": feed["id"], "ok": True}]


def test_concurrent_refreshes_never_insert_a_guid_twice(client, store, fetcher):
    feed = subscribe(client, fetcher, URL_A, ep(1))
    barrier = threading.Barrier(2, timeout=5)

    def fetch(url):
        barrier.wait()  # both fetches are in flight before either applies
        return feed_of(ep(1), ep(2), ep(3))

    client.app.dependency_overrides[get_fetcher] = lambda: fetch
    rev = store.current_rev()
    with ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(client.post, f"/v1/feeds/{feed['id']}/refresh") for _ in range(2)]
        assert all(f.result(10).json() == [{"feedId": feed["id"], "ok": True}] for f in futures)
    guids = sorted(e["guid"] for e in episodes_of(client, feed["id"]))
    assert guids == ["guid-1", "guid-2", "guid-3"]
    assert store.current_rev() == rev + 2  # two new episodes, nothing for the skipped duplicates


# --- auth, methods, logging ----------------------------------------------------------------------


@pytest.mark.parametrize("path", ["/v1/refresh", "/v1/feeds/1/refresh", "/v1/feeds/abc/refresh"])
@pytest.mark.parametrize("headers", [{}, {"Authorization": "Bearer nope"}])
def test_refresh_requires_token_before_anything_else(app, fetcher, path, headers):
    response = TestClient(app).post(path, headers=headers)
    assert response.status_code == 401
    assert response.headers["WWW-Authenticate"] == "Bearer"
    assert response.json() == {"detail": "Not authenticated."}
    assert fetcher.calls == []


@pytest.mark.parametrize("path", ["/v1/refresh", "/v1/feeds/12/refresh"])
@pytest.mark.parametrize("authed", [True, False])
def test_refresh_get_is_405(app, client, path, authed):
    response = (client if authed else TestClient(app)).get(path)
    assert response.status_code == 405 and isinstance(response.json()["detail"], str)


def test_refresh_never_logs_feed_urls(client, fetcher, caplog):
    caplog.set_level(logging.DEBUG)
    feed = subscribe(client, fetcher, SECRET_URL, ep(1))
    client.post("/v1/refresh")
    fetcher.results[SECRET_URL] = FeedUnreachableError(f"nope {SECRET_URL}")
    client.post(f"/v1/feeds/{feed['id']}/refresh")
    fetcher.results[SECRET_URL] = RuntimeError(SECRET_URL)
    client.post("/v1/refresh")
    text = "\n".join(r.getMessage() for r in caplog.records)
    assert text and not any(part in text for part in SECRET_PARTS)


# --- scheduler -----------------------------------------------------------------------------------


class FakeWait:
    """Records the requested waits; asks the scheduler to stop on the call numbered `stop_at`."""

    def __init__(self, stop_at: int, on_call=None) -> None:
        self.stop_at = stop_at
        self.calls: list[float] = []
        self.on_call = on_call

    def __call__(self, seconds: float) -> bool:
        self.calls.append(seconds)
        if self.on_call:
            self.on_call(len(self.calls))
        return len(self.calls) >= self.stop_at


def seeded_store(fetcher, *urls):
    store = sql_store.open_store(DEFAULT_DATABASE_URL)  # conftest gives it a fresh temp database
    for url in urls:
        store.add_feed(url, feed_of(ep(1)), 1)
    return store


def test_scheduler_waits_one_interval_before_each_run_and_runs_do_their_work():
    fetcher = StubFetcher()
    store = seeded_store(fetcher, URL_A)
    feed = store.list_feeds()[0]

    def new_episode(call_number):  # the publisher adds an episode before each run
        fetcher.results[URL_A] = feed_of(*[ep(n) for n in range(1, call_number + 2)])

    wait = FakeWait(stop_at=4, on_call=new_episode)
    RefreshScheduler(store, fetcher, 3 * 3600, wait)._loop()
    assert wait.calls == [10800, 10800, 10800, 10800]
    assert len(fetcher.calls) == 3  # the fourth wait stopped the loop before a run
    assert len(store.list_feed_episodes(feed.id)) == 4


def test_scheduler_does_not_run_when_stopped_during_the_first_wait():
    fetcher = StubFetcher()
    store = seeded_store(fetcher, URL_A)
    RefreshScheduler(store, fetcher, 5, FakeWait(stop_at=1))._loop()
    assert fetcher.calls == []


def test_scheduler_survives_a_failing_run_and_a_failing_feed(caplog):
    caplog.set_level(logging.DEBUG)
    fetcher = StubFetcher()
    store = seeded_store(fetcher, SECRET_URL, URL_B)  # order: B, then the secret feed
    fetcher.results[URL_B] = FeedUnreachableError(f"x {SECRET_URL}")
    real_list = store.list_feeds
    state = {"n": 0}

    def flaky_list():
        state["n"] += 1
        if state["n"] == 1:
            raise RuntimeError(SECRET_URL)  # an unforeseen failure of the whole first run
        return real_list()

    store.list_feeds = flaky_list
    fetcher.results[SECRET_URL] = feed_of(ep(1), ep(2))
    wait = FakeWait(stop_at=3)
    RefreshScheduler(store, fetcher, 1, wait)._loop()
    assert fetcher.calls == [URL_B, SECRET_URL]  # run 1 failed; run 2 went on past B's failure
    secret_feed = store.get_feed_by_url(SECRET_URL)
    assert len(store.list_feed_episodes(secret_feed.id)) == 2
    text = "\n".join(r.getMessage() for r in caplog.records)
    assert "RuntimeError" in text
    assert not any(part in text for part in SECRET_PARTS)


def test_scheduler_stops_between_feeds_when_asked():
    fetcher = StubFetcher()
    store = seeded_store(fetcher, URL_A, URL_B, URL_C)
    scheduler = RefreshScheduler(store, fetcher, 1, FakeWait(stop_at=99))
    original = fetcher.__call__

    def stopping(url):
        result = original(url)
        scheduler._stop.set()
        return result

    scheduler._fetch = stopping
    scheduler.run_once()
    assert len(fetcher.calls) == 1


def scheduler_threads():
    return [t for t in threading.enumerate() if t.name == "refresh-scheduler"]


def test_lifespan_starts_runs_and_stops_the_scheduler_outside_test_mode():
    fetcher = StubFetcher()
    ran = threading.Event()

    def fetch(url):
        ran.set()
        return feed_of(ep(1), ep(2))

    app = create_app(Settings(token=TOKEN, refresh_interval_hours=1e-7))
    app.dependency_overrides[get_fetcher] = lambda: fetch
    feed = store_for_app(app).add_feed(URL_A, feed_of(ep(1)), 1)
    with TestClient(app, headers=AUTH):
        assert ran.wait(10)
        assert len(scheduler_threads()) == 1
    assert scheduler_threads() == []
    assert len(store_for_app(app).list_feed_episodes(feed.id)) == 2
    assert fetcher.calls == []


def test_scheduler_is_never_created_in_test_mode(app):
    with TestClient(app, headers=AUTH):
        assert scheduler_threads() == []
