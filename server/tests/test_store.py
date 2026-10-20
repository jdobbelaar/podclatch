import threading
from dataclasses import FrozenInstanceError

import pytest

from podclatch_server.errors import ConflictError, NotFoundError, UnprocessableError
from podclatch_server.feeds import ParsedEpisode, ParsedFeed
from podclatch_server.store import (
    EntityType,
    InMemoryStore,
    ListenOrder,
    normalize_feed_url,
)


def parsed(*guids: str) -> ParsedFeed:
    episodes = [
        ParsedEpisode(g, f"t-{g}", "d", f"https://example.com/{g}.mp3", 1000, None, None)
        for g in guids
    ]
    return ParsedFeed("T", "A", "D", "art", episodes)


def all_revs(store: InMemoryStore, feed_ids: list[int]) -> list[int]:
    revs = [f.rev for f in store.list_feeds()]
    for feed_id in feed_ids:
        revs += [e.rev for e in store.list_episodes(feed_id)]
    revs += [t.rev for t in store.list_tombstones()]
    return revs


def test_rev_starts_at_zero_and_first_change_is_one():
    store = InMemoryStore()
    assert store.current_rev() == 0
    feed = store.add_feed("https://example.com/a", parsed(), 5)
    assert feed.rev == 1
    assert store.current_rev() == 1


def test_subscribe_gives_every_changed_entity_its_own_rev():
    store = InMemoryStore()
    a = store.add_feed("https://example.com/a", parsed("e1", "e2"), 5)
    b = store.add_feed("https://example.com/b", parsed("e3"), 6)
    feeds = {f.id: f for f in store.list_feeds()}
    assert feeds[a.id].priority == 1
    assert feeds[a.id].rev > a.rev  # shifted, so bumped
    assert b.priority == 0
    revs = all_revs(store, [a.id, b.id])
    assert len(revs) == len(set(revs)) == 5  # 2 feeds + 3 episodes
    assert store.current_rev() == max(revs) == 1 + 2 + 1 + 1 + 1 + 1 - 1  # a, 2 ep, shift a, b, ep


def test_reorder_bumps_only_feeds_that_moved_and_noop_bumps_nothing():
    store = InMemoryStore()
    ids = [store.add_feed(f"https://example.com/{i}", parsed(), 1).id for i in range(3)]
    # priorities now: ids[2]=0, ids[1]=1, ids[0]=2
    before = {f.id: f for f in store.list_feeds()}
    rev = store.current_rev()
    store.reorder_feeds([ids[2], ids[1], ids[0]])  # nothing moves
    assert store.current_rev() == rev
    store.reorder_feeds([ids[1], ids[2], ids[0]])  # swaps the first two
    after = {f.id: f for f in store.list_feeds()}
    assert after[ids[0]].rev == before[ids[0]].rev
    assert after[ids[1]].rev > rev and after[ids[2]].rev > rev
    assert after[ids[1]].rev != after[ids[2]].rev
    assert store.current_rev() == rev + 2


def test_listen_order_bumps_only_on_change():
    store = InMemoryStore()
    feed = store.add_feed("https://example.com/a", parsed(), 1)
    rev = store.current_rev()
    assert store.set_listen_order(feed.id, ListenOrder.NEWEST_FIRST) == feed
    assert store.current_rev() == rev
    changed = store.set_listen_order(feed.id, ListenOrder.OLDEST_FIRST)
    assert changed.rev == rev + 1 == store.current_rev()


def test_delete_gives_each_tombstone_and_shifted_feed_its_own_rev():
    store = InMemoryStore()
    a = store.add_feed("https://example.com/a", parsed("e1", "e2"), 1)
    store.add_feed("https://example.com/b", parsed(), 1)  # priority 0; a is 1
    c = store.add_feed("https://example.com/c", parsed(), 1)  # c 0, b 1, a 2
    rev = store.current_rev()
    store.delete_feed(c.id)  # first Feed: b and a shift
    assert store.current_rev() == rev + 1 + 2
    store.delete_feed(a.id)
    tombstones = store.list_tombstones()
    assert [t.entity_type for t in tombstones].count(EntityType.EPISODE) == 2
    assert len({t.rev for t in tombstones}) == len(tombstones) == 4
    assert [(t.entity_type, t.entity_id) for t in tombstones][0] == (EntityType.FEED, c.id)


def test_rejected_calls_consume_no_rev_or_id():
    store = InMemoryStore()
    a = store.add_feed("https://example.com/a", parsed("e1"), 1)
    rev = store.current_rev()
    with pytest.raises(ConflictError):
        store.add_feed("HTTPS://EXAMPLE.com:443/a", parsed("e9"), 1)
    with pytest.raises(UnprocessableError):
        store.reorder_feeds([a.id, a.id])
    with pytest.raises(NotFoundError):
        store.set_listen_order(999, ListenOrder.OLDEST_FIRST)
    with pytest.raises(NotFoundError):
        store.delete_feed(999)
    assert store.current_rev() == rev
    b = store.add_feed("https://example.com/b", parsed("e2"), 1)
    assert b.id == a.id + 1
    assert [e.id for e in store.list_episodes(b.id)] == [2]


def test_revs_only_increase_across_a_mixed_sequence():
    store = InMemoryStore()
    seen = [store.current_rev()]
    a = store.add_feed("https://example.com/a", parsed("e1"), 1)
    seen.append(store.current_rev())
    b = store.add_feed("https://example.com/b", parsed("e2"), 1)
    seen.append(store.current_rev())
    store.reorder_feeds([a.id, b.id])
    seen.append(store.current_rev())
    store.set_listen_order(a.id, ListenOrder.OLDEST_FIRST)
    seen.append(store.current_rev())
    store.delete_feed(a.id)
    seen.append(store.current_rev())
    assert seen == sorted(seen) and len(set(seen)) == len(seen)


def test_duplicate_check_is_repeated_inside_the_atomic_insert():
    store = InMemoryStore()
    barrier = threading.Barrier(2)
    outcomes: list[object] = []

    def attempt() -> None:
        # Both threads pass the early check (nothing is stored yet), then race to insert.
        assert store.get_feed_by_url("https://example.com/a") is None
        barrier.wait(5)
        try:
            outcomes.append(store.add_feed("https://example.com/a", parsed("e1"), 1))
        except ConflictError as exc:
            outcomes.append(exc)

    threads = [threading.Thread(target=attempt) for _ in range(2)]
    for t in threads:
        t.start()
    for t in threads:
        t.join(5)
    assert sorted(type(o).__name__ for o in outcomes) == ["ConflictError", "Feed"]
    assert len(store.list_feeds()) == 1
    assert len(store.list_episodes(store.list_feeds()[0].id)) == 1


def test_returned_values_cannot_change_the_store():
    store = InMemoryStore()
    feed = store.add_feed("https://example.com/a", parsed("e1"), 1)
    with pytest.raises(FrozenInstanceError):
        feed.priority = 7  # type: ignore[misc]
    episodes = store.list_episodes(feed.id)
    with pytest.raises(FrozenInstanceError):
        episodes[0].status = "PLAYED"  # type: ignore[misc]
    episodes.clear()
    store.list_feeds().clear()
    store.list_tombstones().append("junk")  # type: ignore[arg-type]
    assert len(store.list_episodes(feed.id)) == 1
    assert len(store.list_feeds()) == 1
    assert store.list_tombstones() == []


def test_episodes_are_unique_per_feed_and_guid():
    store = InMemoryStore()
    feed = store.add_feed("https://example.com/a", parsed("e1", "e1", "e2"), 1)
    assert [e.guid for e in store.list_episodes(feed.id)] == ["e1", "e2"]


def test_feeds_do_not_share_state_between_stores():
    one, two = InMemoryStore(), InMemoryStore()
    one.add_feed("https://example.com/a", parsed(), 1)
    assert two.list_feeds() == []
    assert two.current_rev() == 0


@pytest.mark.parametrize(
    "a,b,same",
    [
        ("HTTPS://Example.com:443/feed.xml", " https://example.com/feed.xml ", True),
        ("http://example.com:80/x", "http://example.com/x", True),
        ("https://example.com/x#a", "https://example.com/x", True),
        ("https://example.com", "https://example.com/", True),
        ("https://example.com/Feed.xml", "https://example.com/feed.xml", False),
        ("https://example.com/x?a=1", "https://example.com/x", False),
        ("http://example.com/x", "https://example.com/x", False),
        ("https://u@example.com/x", "https://example.com/x", False),
        ("https://U@example.com/x", "https://u@example.com/x", False),
        ("https://example.com:8443/x", "https://example.com/x", False),
        ("https://example.com:443/x", "http://example.com:443/x", False),
    ],
)
def test_normalize_feed_url(a, b, same):
    assert (normalize_feed_url(a) == normalize_feed_url(b)) is same


def test_normalize_survives_unparseable_urls():
    assert normalize_feed_url(" http://[bad ") == "http://[bad"
    assert normalize_feed_url("https://example.com:notaport/x") == "https://example.com:notaport/x"
