"""Behavior of the database-backed store that the M2 contract tests do not pin down.

Runs on SQLite always and on Postgres when PODCLATCH_TEST_DATABASE_URL is set (see conftest).
"""

import random
import threading
import time

import pytest
from sqlalchemy import text

from podclatch_server import sql_store
from podclatch_server.errors import ConflictError, NotFoundError, UnprocessableError
from podclatch_server.feeds import ParsedEpisode, ParsedFeed
from podclatch_server.store import (
    EntityType,
    Episode,
    EpisodeStateChange,
    EpisodeStatus,
    Feed,
    ListenOrder,
    Tombstone,
)


def parsed(*guids: str, title: str = "T") -> ParsedFeed:
    episodes = [
        ParsedEpisode(g, f"t-{g}", "d", f"https://example.com/{g}.mp3", 1000, None, None)
        for g in guids
    ]
    return ParsedFeed(title, "A", "D", "art", episodes)


def snapshot(store):
    feeds = store.list_feeds()
    return (
        store.current_rev(),
        feeds,
        [store.list_episodes(f.id) for f in feeds],
        store.list_tombstones(),
    )


def count_rows(store, table: str) -> int:
    with store.raw._engine.connect() as conn:
        return conn.execute(text(f"SELECT COUNT(*) FROM {table}")).scalar_one()


# --- return types ---------------------------------------------------------------------------


def test_values_are_dataclasses_with_enums_not_strings(new_store):
    store = new_store()
    feed = store.add_feed("https://example.com/a", parsed("e1"), 5)
    assert type(feed) is Feed and type(feed.listen_order) is ListenOrder
    episode = store.list_episodes(feed.id)[0]
    assert type(episode) is Episode and type(episode.status) is EpisodeStatus
    store.delete_feed(feed.id)
    assert {type(t) for t in store.list_tombstones()} == {Tombstone}
    assert {type(t.entity_type) for t in store.list_tombstones()} == {EntityType}


# --- ordering: revs become visible in order ---------------------------------------------------


def test_a_second_writer_waits_for_the_first_and_gets_greater_revs(new_store):
    store = new_store()
    held, release, first = threading.Event(), threading.Event(), []

    def hold_first_writer_before_commit():
        if not first:  # only writer A
            first.append(True)
            held.set()
            assert release.wait(30)

    store.raw.before_commit = hold_first_writer_before_commit
    results: dict[str, Feed] = {}
    errors: list[BaseException] = []

    def add(name: str) -> None:
        try:
            results[name] = store.add_feed(f"https://example.com/{name}", parsed("x", "y"), 1)
        except BaseException as exc:  # noqa: BLE001
            errors.append(exc)

    a = threading.Thread(target=add, args=("a",))
    a.start()
    assert held.wait(30)  # A has taken its revs and is held before commit
    b = threading.Thread(target=add, args=("b",))
    b.start()
    time.sleep(1.0)
    assert "b" not in results and b.is_alive()  # B waits; it did not return
    assert store.current_rev() == 0  # nothing is committed, so B has taken no rev either
    release.set()
    a.join(30)
    b.join(30)
    assert errors == []
    a_revs = {results["a"].rev} | {e.rev for e in store.list_episodes(results["a"].id)}
    b_feed = next(f for f in store.list_feeds() if f.id == results["b"].id)
    b_revs = {b_feed.rev} | {e.rev for e in store.list_episodes(b_feed.id)}
    assert max(a_revs) < min(b_revs)
    assert results["a"].id < results["b"].id


# --- concurrency: a paging reader ends exactly equal to the final state ------------------------


def writer_script(store, n: int, errors: list[BaseException]) -> None:
    rng = random.Random(n)
    try:
        for round_no in range(2):
            url = f"https://example.com/w{n}/r{round_no}"
            feed = store.add_feed(url, parsed(f"w{n}-a", f"w{n}-b", f"w{n}-c"), 1)
            episodes = store.list_episodes(feed.id)
            store.apply_episode_states(
                [
                    EpisodeStateChange(e.id, EpisodeStatus.PLAYED, 50 + i, 10 * i, 5000 + i)
                    for i, e in enumerate(episodes)
                ]
            )
            store.set_listen_order(feed.id, ListenOrder.OLDEST_FIRST)
            ids = [f.id for f in store.list_feeds()]
            rng.shuffle(ids)
            try:
                store.reorder_feeds(ids)
            except UnprocessableError:
                pass  # another writer subscribed or unsubscribed in between: nothing consumed
            store.apply_refresh(feed.id, parsed(f"w{n}-a", f"w{n}-d", title=f"Renamed {n}"), 9)
            if round_no == 0:
                store.delete_feed(feed.id)
    except BaseException as exc:  # noqa: BLE001
        errors.append(exc)


def test_concurrent_writers_and_a_paging_reader_end_equal_to_the_final_state(new_store):
    store = new_store()
    errors: list[BaseException] = []
    writers_done = threading.Event()
    feeds: dict[int, Feed] = {}
    episodes: dict[int, Episode] = {}
    tombstoned: set[tuple[EntityType, int]] = set()
    seen: list[tuple[str, int, int]] = []

    def absorb(since: int) -> tuple[int, bool]:
        page = store.sync_page(since, 7)
        for item in [*page.feeds, *page.episodes, *page.tombstones]:
            assert item.rev > since  # never an item at or below the cursor
            kind = type(item).__name__
            ident = (
                item.entity_id if isinstance(item, Tombstone) else item.id  # type: ignore[union-attr]
            )
            seen.append((kind, ident, item.rev))
        feeds.update({f.id: f for f in page.feeds})
        episodes.update({e.id: e for e in page.episodes})
        tombstoned.update((t.entity_type, t.entity_id) for t in page.tombstones)
        return page.next_rev, page.has_more

    def reader() -> None:
        cursor = 0
        try:
            while not writers_done.is_set():
                cursor, _ = absorb(cursor)
                time.sleep(0.001)
            more = True
            while more:
                cursor, more = absorb(cursor)
        except BaseException as exc:  # noqa: BLE001
            errors.append(exc)

    reader_thread = threading.Thread(target=reader)
    reader_thread.start()
    writers = [threading.Thread(target=writer_script, args=(store, n, errors)) for n in range(8)]
    for w in writers:
        w.start()
    for w in writers:
        w.join(300)
    writers_done.set()
    reader_thread.join(300)
    assert errors == []
    assert not any(t.is_alive() for t in [reader_thread, *writers])

    final_feeds = {f.id: f for f in store.list_feeds()}
    final_episodes = {e.id: e for f in final_feeds.values() for e in store.list_episodes(f.id)}
    built_feeds = {i: f for i, f in feeds.items() if (EntityType.FEED, i) not in tombstoned}
    built_episodes = {
        i: e for i, e in episodes.items() if (EntityType.EPISODE, i) not in tombstoned
    }
    assert built_feeds == final_feeds
    assert built_episodes == final_episodes

    # Every rev appears once (an entity seen again has a new rev), and the counter is the top one.
    assert len({rev for _, _, rev in seen}) == len(seen)
    tombstones = store.list_tombstones()
    live_revs = [f.rev for f in final_feeds.values()] + [e.rev for e in final_episodes.values()]
    live_revs += [t.rev for t in tombstones]
    assert len(set(live_revs)) == len(live_revs)
    assert store.current_rev() == max(live_revs)
    assert len(tombstones) == 8 * (4 + 1)  # per writer: one deleted Feed with 4 Episodes


# --- priority stays contiguous (conftest checks it after every mutation too) -------------------


def priorities(store) -> list[tuple[str, int]]:
    return [(f.feed_url[-1], f.priority) for f in store.list_feeds()]


def test_priorities_are_contiguous_through_subscribe_reorder_and_unsubscribe(new_store):
    store = new_store()
    ids = {name: store.add_feed(f"https://example.com/{name}", parsed(), 1).id for name in "abcd"}
    assert priorities(store) == [("d", 0), ("c", 1), ("b", 2), ("a", 3)]
    store.reorder_feeds([ids[n] for n in "abcd"])  # a full reversal
    assert priorities(store) == [("a", 0), ("b", 1), ("c", 2), ("d", 3)]
    rev = store.current_rev()
    store.reorder_feeds([ids[n] for n in "abcd"])  # moves nothing
    assert store.current_rev() == rev
    store.delete_feed(ids["a"])  # the first
    assert priorities(store) == [("b", 0), ("c", 1), ("d", 2)]
    store.delete_feed(ids["c"])  # a middle one
    assert priorities(store) == [("b", 0), ("d", 1)]
    store.delete_feed(ids["d"])  # the last
    assert priorities(store) == [("b", 0)]


# --- ids: never reused, also across a restart ---------------------------------------------------


def test_ids_are_never_reused_after_delete_and_after_restart(new_store):
    store = new_store()
    feed = store.add_feed("https://example.com/a", parsed("e1", "e2"), 1)
    store.delete_feed(feed.id)  # the highest Feed and Episode ids are gone
    again = store.add_feed("https://example.com/b", parsed("e1"), 1)
    assert again.id == feed.id + 1
    assert [e.id for e in store.list_episodes(again.id)] == [3]
    url, connect_args = new_store.last_location
    store.close()
    restarted = sql_store.open_store(url, connect_args)  # a "restart": a new engine, same data
    assert restarted.current_rev() == 8
    restarted.delete_feed(again.id)
    third = restarted.add_feed("https://example.com/c", parsed("e1"), 1)
    assert third.id == again.id + 1
    assert [e.id for e in restarted.list_episodes(third.id)] == [4]
    restarted.close()


# --- atomicity ----------------------------------------------------------------------------------


def test_a_failure_mid_add_feed_leaves_everything_as_before(new_store):
    store = new_store()
    store.add_feed("https://example.com/a", parsed("e1"), 1)
    before = snapshot(store)

    def explode():
        raise RuntimeError("forced")

    store.raw.before_commit = explode  # runs after the shifts, Feed, and Episodes were written
    with pytest.raises(RuntimeError):
        store.add_feed("https://example.com/b", parsed("x", "y", "z"), 1)
    store.raw.before_commit = None
    assert snapshot(store) == before
    assert count_rows(store, "feed") == 1 and count_rows(store, "episode") == 1
    nxt = store.add_feed("https://example.com/b", parsed("x"), 1)
    assert nxt.id == 2 and [e.id for e in store.list_episodes(nxt.id)] == [2]
    assert nxt.rev == before[0] + 2  # the shift of Feed a took before[0] + 1


def test_deleting_a_feed_cascades_to_its_episodes_in_the_database(new_store):
    store = new_store()
    feed = store.add_feed("https://example.com/a", parsed("e1", "e2", "e3"), 1)
    other = store.add_feed("https://example.com/b", parsed("e1"), 1)
    assert count_rows(store, "episode") == 4
    store.delete_feed(feed.id)
    assert count_rows(store, "episode") == 1  # none left for the deleted Feed
    assert len(store.list_episodes(other.id)) == 1
    assert len(store.list_tombstones()) == 4


def test_racing_subscribers_produce_one_feed_and_consume_nothing_extra(new_store):
    store = new_store()
    barrier = threading.Barrier(6)
    outcomes: list[object] = []

    def attempt() -> None:
        barrier.wait(10)
        try:
            outcomes.append(store.add_feed("https://example.com/a", parsed("e1", "e2"), 1))
        except ConflictError as exc:
            outcomes.append(exc)

    threads = [threading.Thread(target=attempt) for _ in range(6)]
    for t in threads:
        t.start()
    for t in threads:
        t.join(30)
    assert sorted(type(o).__name__ for o in outcomes) == ["ConflictError"] * 5 + ["Feed"]
    assert store.current_rev() == 3  # one Feed and two Episodes; the losers took nothing
    assert store.add_feed("https://example.com/b", parsed(), 1).id == 2


def test_unknown_ids_are_rejected_without_a_trace(new_store):
    store = new_store()
    with pytest.raises(NotFoundError):
        store.set_listen_order(5, ListenOrder.OLDEST_FIRST)
    with pytest.raises(NotFoundError):
        store.delete_feed(5)
    with pytest.raises(UnprocessableError):
        store.reorder_feeds([5])
    assert store.current_rev() == 0


# --- long and NUL values ------------------------------------------------------------------------


def test_very_long_urls_and_guids_are_stored_and_deduplicated(new_store):
    store = new_store()
    url = "https://example.com/" + "p" * 4980
    guid = "g" * 5000
    feed = store.add_feed(url, parsed(guid, guid, guid[:-1] + "h"), 1)
    assert feed.feed_url == url and len(url) == 5000
    assert [e.guid for e in store.list_episodes(feed.id)] == [guid, guid[:-1] + "h"]
    assert store.get_feed_by_url(url.upper().replace("HTTPS", "https", 1)) is None  # path differs
    assert store.get_feed_by_url(url) == feed
    with pytest.raises(ConflictError):
        store.add_feed(" " + url + " ", parsed(), 1)
    again = store.apply_refresh(feed.id, parsed(guid, guid[:-1] + "h", guid[:-2] + "ij"), 2)
    assert again.inserted == 1


def test_nul_characters_are_removed_not_rejected(new_store):
    store = new_store()
    feed = store.add_feed(
        "https://example.com/a",
        ParsedFeed(
            "Ti\x00tle",
            "Au\x00thor",
            "De\x00scription",
            "https://example.com/a\x00rt.jpg",
            [
                ParsedEpisode(
                    "g\x00uid", "t\x00", "d\x00", "https://example.com/\x00a.mp3", 1, 2, "x\x00"
                )
            ],
        ),
        1,
    )
    assert (feed.title, feed.author, feed.description) == ("Title", "Author", "Description")
    episode = store.list_episodes(feed.id)[0]
    assert (episode.guid, episode.title, episode.description) == ("guid", "t", "d")
    assert episode.artwork_url == "x" and episode.audio_url == "https://example.com/a.mp3"
    rev = store.current_rev()
    same = ParsedFeed(
        "Title",
        "Author",
        "Description",
        "https://example.com/art.jpg",
        [ParsedEpisode("gu\x00id", "t", "d", "u", 1, None, None)],
    )
    result = store.apply_refresh(feed.id, same, 3)
    assert (result.inserted, result.feed_changed) == (0, False)  # same guid once NUL is removed
    assert store.current_rev() == rev


# --- sqlite specifics ---------------------------------------------------------------------------


def test_sqlite_connections_enforce_foreign_keys_and_use_wal(tmp_path):
    store = sql_store.open_store(f"sqlite:///{(tmp_path / 'pragmas.db').as_posix()}")
    try:
        with store._engine.connect() as conn:
            assert conn.exec_driver_sql("PRAGMA foreign_keys").scalar() == 1
            assert conn.exec_driver_sql("PRAGMA journal_mode").scalar() == "wal"
    finally:
        store.close()


def test_in_memory_sqlite_works_and_is_gone_after_close():
    store = sql_store.open_store("sqlite://")
    store.add_feed("https://example.com/a", parsed("e1"), 1)
    threads = [
        threading.Thread(target=store.add_feed, args=(f"https://example.com/{i}", parsed(), 1))
        for i in range(5)
    ]
    for t in threads:
        t.start()
    for t in threads:
        t.join(30)
    assert len(store.list_feeds()) == 6
    store.close()
    assert sql_store.open_store("sqlite:///:memory:").list_feeds() == []
