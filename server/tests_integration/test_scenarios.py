"""End-to-end scenarios over HTTP against the real stack (spec 5.2, decisions #23 to #29).

Each test builds its own Feeds from a unique feed path, asserts only about its own ids, takes its
own Sync baseline, and deletes its Feeds at the end.
"""

from __future__ import annotations

import support
from support import T0, T1, T2, feed_url, item


def test_subscribe(api, feedhost, unique_name, cleanup):
    items = [item(1), item(2), item(3), item(4, audio=False)]
    title = f"Subscribe {unique_name}"
    feedhost(unique_name, support.rss(title, items))

    response = api.post("/v1/feeds", json={"feedUrl": feed_url(unique_name)})
    assert response.status_code == 201
    feed = response.json()
    cleanup.append(feed["id"])

    assert set(feed) == {
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
    assert feed["feedUrl"] == feed_url(unique_name)
    assert feed["title"] == title
    assert feed["author"] == support.FEED_AUTHOR
    assert feed["description"] == support.FEED_DESCRIPTION
    assert feed["priority"] == 0
    assert feed["listenOrder"] == "NEWEST_FIRST"
    for field in ("id", "subscribedAt", "rev"):
        assert type(feed[field]) is int

    response = api.get("/v1/feeds")
    assert response.status_code == 200
    feeds = response.json()
    assert feeds[0] == feed
    assert [f["priority"] for f in feeds] == list(range(len(feeds)))


def test_episodes_appear(new_feed, api):
    feed, _, _ = new_feed([item(1), item(2), item(3), item(4, audio=False)])

    response = api.get(f"/v1/feeds/{feed['id']}/episodes")
    assert response.status_code == 200
    episodes = response.json()

    expected = [item(3), item(2), item(1)]  # newest first; the item without audio is skipped
    assert [e["guid"] for e in episodes] == [i.guid for i in expected]
    published = [e["publishedAt"] for e in episodes]
    assert published == sorted(published, reverse=True)
    for episode, source in zip(episodes, expected, strict=True):
        assert episode["feedId"] == feed["id"]
        assert episode["title"] == source.title
        assert episode["audioUrl"] == source.audio_url
        assert episode["status"] == "NEW"
        assert episode["playedAt"] is None
        assert episode["positionSec"] == 0
        assert type(episode["rev"]) is int


def test_mark_state(new_feed, api):
    feed, _, _ = new_feed([item(1), item(2), item(3)])
    before = support.episodes_of(api, feed["id"])
    target = before[0]

    results = support.push(api, [support.state_item(target["id"], "PLAYED", T1, 1800)])
    assert results == [
        {
            "episodeId": target["id"],
            "outcome": "APPLIED",
            "status": "PLAYED",
            "playedAt": T1,
            "positionSec": 1800,
            "stateUpdatedAt": T1,
        }
    ]

    after = {e["id"]: e for e in support.episodes_of(api, feed["id"])}
    played = after[target["id"]]
    assert played["status"] == "PLAYED"
    assert played["playedAt"] == T1
    assert played["positionSec"] == 1800
    assert played["stateUpdatedAt"] == T1
    assert played["rev"] > target["rev"]
    for other in before[1:]:
        assert after[other["id"]]["status"] == "NEW"


def test_sync_pull(api, new_feed):
    start = support.baseline(api)
    feed, _, _ = new_feed([item(1), item(2), item(3)])
    episodes = support.episodes_of(api, feed["id"])
    played = episodes[0]
    support.push(api, [support.state_item(played["id"], "PLAYED", T1, 1800)])
    ids = {e["id"] for e in episodes}

    page = support.sync(api, start)
    assert page["hasMore"] is False
    assert [f["id"] for f in page["feeds"]] == [feed["id"]]
    assert {e["id"] for e in page["episodes"]} == ids
    assert {e["id"]: e["status"] for e in page["episodes"]}[played["id"]] == "PLAYED"
    assert not [t for t in page["tombstones"] if t["entityId"] in ids | {feed["id"]}]
    revs = []
    for key in ("feeds", "episodes", "tombstones"):
        kind_revs = [row["rev"] for row in page[key]]
        assert kind_revs == sorted(kind_revs)
        revs += kind_revs
    assert min(revs) > start
    assert page["nextRev"] == max(revs)

    # Paging with limit=2 takes more than one call, and the union equals the unpaged result.
    cursor = start
    pages = []
    while True:
        part = support.sync(api, cursor, limit=2)
        pages.append(part)
        assert len(part["feeds"]) + len(part["episodes"]) + len(part["tombstones"]) <= 2
        if not part["hasMore"]:
            break
        cursor = part["nextRev"]
    assert len(pages) > 1
    for key in ("feeds", "episodes", "tombstones"):
        merged = [row for part in pages for row in part[key]]
        assert merged == page[key]  # same rows in the same order, so no duplicate and none lost
    assert pages[-1]["nextRev"] == page["nextRev"]

    done = support.sync(api, page["nextRev"])
    assert done["feeds"] == done["episodes"] == done["tombstones"] == []
    assert done["hasMore"] is False
    assert done["nextRev"] == page["nextRev"]


def test_unsubscribe(api, new_feed):
    keep, _, _ = new_feed([item(1)])
    gone, _, _ = new_feed([item(1), item(2), item(3)])
    episode_ids = {e["id"] for e in support.episodes_of(api, gone["id"])}
    start = support.baseline(api)

    response = api.delete(f"/v1/feeds/{gone['id']}")
    assert response.status_code == 204
    assert response.content == b""

    feeds = support.list_feeds(api)
    assert gone["id"] not in {f["id"] for f in feeds}
    assert keep["id"] in {f["id"] for f in feeds}
    assert [f["priority"] for f in feeds] == list(range(len(feeds)))  # the gap is closed

    response = api.get(f"/v1/feeds/{gone['id']}/episodes")
    assert response.status_code == 404
    assert response.json() == {"detail": "Feed not found."}
    assert api.delete(f"/v1/feeds/{gone['id']}").status_code == 404

    page = support.sync_all(api, start)
    feed_tombstones = [t for t in page["tombstones"] if t["entityType"] == "FEED"]
    episode_tombstones = [t for t in page["tombstones"] if t["entityType"] == "EPISODE"]
    assert [t["entityId"] for t in feed_tombstones] == [gone["id"]]
    assert {t["entityId"] for t in episode_tombstones} == episode_ids
    assert len(episode_tombstones) == 3
    assert all(t["rev"] > start for t in page["tombstones"])
    assert gone["id"] not in {f["id"] for f in page["feeds"]}
    assert not episode_ids & {e["id"] for e in page["episodes"]}


def test_conflicting_writes(api, new_feed):
    feed, _, _ = new_feed([item(1), item(2)])
    first, second = support.episodes_of(api, feed["id"])
    eid = first["id"]
    start = support.baseline(api)

    # Client A pushes PLAYED at T1.
    [result] = support.push(api, [support.state_item(eid, "PLAYED", T1, 1800)])
    assert result["outcome"] == "APPLIED"
    a_state = {"status": "PLAYED", "playedAt": T1, "positionSec": 1800, "stateUpdatedAt": T1}

    # Client B pushes NEW with an older time, then with exactly T1: both are stale.
    for at in (T0, T1):
        [result] = support.push(api, [support.state_item(eid, "NEW", at, 10)])
        assert result == {"episodeId": eid, "outcome": "STALE", **a_state}

    # Client B pushes with a newer time and wins.
    [result] = support.push(api, [support.state_item(eid, "NEW", T2, 10)])
    assert result["outcome"] == "APPLIED"

    b_state = {"status": "NEW", "playedAt": None, "positionSec": 10, "stateUpdatedAt": T2}
    listed = next(e for e in support.episodes_of(api, feed["id"]) if e["id"] == eid)
    synced = next(e for e in support.sync_all(api, start)["episodes"] if e["id"] == eid)
    for read in (listed, synced):
        assert {key: read[key] for key in b_state} == b_state
    assert listed == synced

    # A mixed request returns one result per item, in request order.
    unknown = 9_000_000_000_000_000_000
    results = support.push(
        api,
        [support.state_item(second["id"], "PLAYED", T1, 5), support.state_item(unknown, "NEW", T1)],
    )
    assert [r["outcome"] for r in results] == ["APPLIED", "UNKNOWN_EPISODE"]
    assert [r["episodeId"] for r in results] == [second["id"], unknown]
    assert set(results[1]) == {"episodeId", "outcome"}


def test_refresh_adds_an_episode(api, new_feed, feedhost):
    feed, name, _ = new_feed([item(1), item(2)])
    two, one = support.episodes_of(api, feed["id"])  # newest first
    support.push(api, [support.state_item(one["id"], "PLAYED", T1, 600)])
    before = {e["id"]: e for e in support.episodes_of(api, feed["id"])}
    start = support.baseline(api)

    new_title = f"Renamed {name}"
    feedhost(name, support.rss(new_title, [item(1), item(2), item(3)]))
    response = api.refresh()
    assert response.status_code == 200
    mine = [r for r in response.json() if r["feedId"] == feed["id"]]
    assert mine == [{"feedId": feed["id"], "ok": True}]

    assert next(f for f in support.list_feeds(api) if f["id"] == feed["id"])["title"] == new_title
    episodes = support.episodes_of(api, feed["id"])
    assert len(episodes) == 3
    fresh = next(e for e in episodes if e["id"] not in before)
    assert fresh["guid"] == item(3).guid
    assert fresh["status"] == "NEW"
    assert fresh["positionSec"] == 0
    for old in (one, two):
        assert next(e for e in episodes if e["id"] == old["id"]) == before[old["id"]]

    page = support.sync_all(api, start)
    assert [f["title"] for f in page["feeds"] if f["id"] == feed["id"]] == [new_title]
    assert {e["id"] for e in page["episodes"] if e["feedId"] == feed["id"]} == {fresh["id"]}

    # A second change is picked up by refreshing just this Feed.
    feedhost(name, support.rss(new_title, [item(1), item(2), item(3), item(4)]))
    response = api.refresh(f"/v1/feeds/{feed['id']}/refresh")
    assert response.status_code == 200
    assert response.json() == [{"feedId": feed["id"], "ok": True}]
    assert len(support.episodes_of(api, feed["id"])) == 4
