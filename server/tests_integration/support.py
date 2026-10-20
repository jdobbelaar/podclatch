"""Shared helpers for the scenarios: feed fixtures, URLs, Sync paging, and fixed timestamps."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, datetime
from email.utils import format_datetime
from typing import Any
from xml.sax.saxutils import escape, quoteattr

from harness import Api

# Client timestamps are fixed values in the year 2100 or later (UTC epoch milliseconds), so the
# clock of the server container and the Episodes' initial stateUpdatedAt never affect a result.
Y2100_MS = 4_102_444_800_000
T0 = Y2100_MS + 1_000
T1 = Y2100_MS + 2_000
T2 = Y2100_MS + 3_000

FEED_AUTHOR = "Integration Author"
FEED_DESCRIPTION = "A feed served by the fake host."


def feed_url(name: str) -> str:
    """The only way the tests build a feed URL: the fake host, inside the Compose network."""
    return f"http://feedhost:8080/feeds/{name}.xml"


@dataclass(frozen=True)
class Item:
    guid: str
    title: str
    published: datetime
    audio: bool = True

    @property
    def audio_url(self) -> str:
        return f"http://feedhost:8080/audio/{self.guid}.mp3"


def item(n: int, *, audio: bool = True, prefix: str = "ep") -> Item:
    """Item n is published on 2024-01-n, so a higher n is newer."""
    return Item(f"{prefix}-{n}", f"Episode {n}", datetime(2024, 1, n, 12, 0, tzinfo=UTC), audio)


def rss(title: str, items: list[Item], *, author: str = FEED_AUTHOR) -> str:
    parts = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">',
        "<channel>",
        f"<title>{escape(title)}</title>",
        f"<description>{escape(FEED_DESCRIPTION)}</description>",
        f"<itunes:author>{escape(author)}</itunes:author>",
    ]
    for entry in items:
        parts += [
            "<item>",
            f"<title>{escape(entry.title)}</title>",
            f'<guid isPermaLink="false">{escape(entry.guid)}</guid>',
            f"<pubDate>{format_datetime(entry.published)}</pubDate>",
        ]
        if entry.audio:
            parts.append(
                f'<enclosure url={quoteattr(entry.audio_url)} length="1000" type="audio/mpeg"/>'
            )
        parts.append("</item>")
    parts += ["</channel>", "</rss>"]
    return "\n".join(parts)


def sync(api: Api, since: int = 0, limit: int | None = None) -> dict[str, Any]:
    query = f"/v1/sync?since={since}" + (f"&limit={limit}" if limit else "")
    response = api.get(query)
    assert response.status_code == 200
    return response.json()


def sync_all(api: Api, since: int = 0, limit: int = 500) -> dict[str, Any]:
    """Page through Sync until hasMore is false and return the merged result."""
    merged: dict[str, Any] = {"feeds": [], "episodes": [], "tombstones": [], "pages": 0}
    cursor = since
    while True:
        page = sync(api, cursor, limit)
        for key in ("feeds", "episodes", "tombstones"):
            merged[key] += page[key]
        merged["pages"] += 1
        merged["nextRev"] = page["nextRev"]
        if not page["hasMore"]:
            merged["hasMore"] = False
            return merged
        cursor = page["nextRev"]


def baseline(api: Api) -> int:
    """The current revision: a `since` value after which only later changes are returned."""
    return sync_all(api)["nextRev"]


def subscribe(api: Api, cleanup: list[int], url: str) -> dict[str, Any]:
    response = api.post("/v1/feeds", json={"feedUrl": url})
    assert response.status_code == 201
    feed = response.json()
    cleanup.append(feed["id"])
    return feed


def episodes_of(api: Api, feed_id: int) -> list[dict[str, Any]]:
    response = api.get(f"/v1/feeds/{feed_id}/episodes")
    assert response.status_code == 200
    return response.json()


def list_feeds(api: Api) -> list[dict[str, Any]]:
    response = api.get("/v1/feeds")
    assert response.status_code == 200
    return response.json()


def state_item(
    episode_id: int, status: str, at: int, position: int = 0, played_at: int | None = None
) -> dict[str, Any]:
    if status == "PLAYED" and played_at is None:
        played_at = at
    return {
        "episodeId": episode_id,
        "status": status,
        "playedAt": played_at if status == "PLAYED" else None,
        "positionSec": position,
        "stateUpdatedAt": at,
    }


def push(api: Api, items: list[dict[str, Any]]) -> list[dict[str, Any]]:
    response = api.post("/v1/episodes/state", json=items)
    assert response.status_code == 200
    return response.json()


def snapshot(api: Api) -> dict[str, Any]:
    """Everything a client can read: Feeds, each Feed's Episodes, and the complete Sync."""
    feeds = list_feeds(api)
    return {
        "feeds": feeds,
        "episodes": {feed["id"]: episodes_of(api, feed["id"]) for feed in feeds},
        "sync": sync_all(api, 0, 500),
    }
