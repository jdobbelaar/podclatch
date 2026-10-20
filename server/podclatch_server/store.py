"""The server's data store: the interface, its value types, and the duplicate-URL rule.

Every multi-step change is one operation of the interface, which the database implementation
(`sql_store.SqlStore`) runs as a single transaction.

`rev` rules: one global counter starting at 0 (the first change gets 1). Every insert, update,
and delete of a Feed or Episode takes the next value, and so does each Tombstone. A change that
alters nothing takes no value. Ids are never reused. A rejected request consumes nothing.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum
from typing import Protocol
from urllib.parse import urlsplit

from fastapi import FastAPI, Request

from podclatch_server.feeds import ParsedFeed

DUPLICATE_FEED_DETAIL = "Already subscribed to this feed."
FEED_NOT_FOUND_DETAIL = "Feed not found."
BAD_ORDER_DETAIL = "feedIds must list every subscribed Feed id exactly once."
_DEFAULT_PORTS = {"http": "80", "https": "443"}


class ListenOrder(StrEnum):
    NEWEST_FIRST = "NEWEST_FIRST"
    OLDEST_FIRST = "OLDEST_FIRST"


class EpisodeStatus(StrEnum):
    NEW = "NEW"
    PLAYED = "PLAYED"


class EntityType(StrEnum):
    FEED = "FEED"
    EPISODE = "EPISODE"


@dataclass(frozen=True)
class Feed:
    id: int
    feed_url: str
    title: str
    author: str
    description: str
    artwork_url: str
    priority: int
    listen_order: ListenOrder
    last_refreshed_at: int | None
    subscribed_at: int
    rev: int


@dataclass(frozen=True)
class Episode:
    id: int
    feed_id: int
    guid: str
    title: str
    description: str
    audio_url: str
    published_at: int
    duration_sec: int | None
    artwork_url: str | None
    status: EpisodeStatus
    played_at: int | None
    position_sec: int
    state_updated_at: int
    rev: int


@dataclass(frozen=True)
class Tombstone:
    entity_type: EntityType
    entity_id: int
    rev: int


class StateOutcome(StrEnum):
    APPLIED = "APPLIED"
    STALE = "STALE"
    UNKNOWN_EPISODE = "UNKNOWN_EPISODE"


@dataclass(frozen=True)
class EpisodeStateChange:
    """One requested Episode state change (already validated)."""

    episode_id: int
    status: EpisodeStatus
    played_at: int | None
    position_sec: int
    state_updated_at: int


@dataclass(frozen=True)
class EpisodeStateResult:
    """Outcome of one change. The state fields hold the Episode's stored state after it."""

    episode_id: int
    outcome: StateOutcome
    status: EpisodeStatus | None = None
    played_at: int | None = None
    position_sec: int | None = None
    state_updated_at: int | None = None


@dataclass(frozen=True)
class SyncPage:
    feeds: list[Feed]
    episodes: list[Episode]
    tombstones: list[Tombstone]
    next_rev: int
    has_more: bool


@dataclass(frozen=True)
class RefreshApplied:
    inserted: int  # Episodes inserted
    feed_changed: bool  # title, author, description, or artwork changed (the Feed took a new rev)


class Store(Protocol):
    """Everything the routes and tests need. Returned values are frozen copies."""

    def close(self) -> None:
        """Release the store's database connections."""
        ...

    def current_rev(self) -> int: ...

    def list_feeds(self) -> list[Feed]:
        """All Feeds in ascending priority order."""
        ...

    def get_feed_by_url(self, feed_url: str) -> Feed | None:
        """The Feed whose normalised URL matches, else None."""
        ...

    def add_feed(self, feed_url: str, parsed: ParsedFeed, now_ms: int) -> Feed:
        """Insert at priority 0 (shifting the others), with its Episodes as New. Atomic.

        Raises ConflictError if the URL is already subscribed.
        """
        ...

    def reorder_feeds(self, feed_ids: list[int]) -> list[Feed]:
        """Rewrite priorities to 0..n-1 in the given order. Atomic.

        Raises UnprocessableError unless `feed_ids` holds every Feed id exactly once.
        """
        ...

    def set_listen_order(self, feed_id: int, listen_order: ListenOrder) -> Feed:
        """Raises NotFoundError for an unknown id. Setting the same value changes nothing."""
        ...

    def delete_feed(self, feed_id: int) -> None:
        """Delete the Feed and its Episodes, write Tombstones, close the gap. Atomic.

        Raises NotFoundError for an unknown id.
        """
        ...

    def list_episodes(self, feed_id: int) -> list[Episode]:
        """The Feed's Episodes in insertion order (empty for an unknown Feed)."""
        ...

    def list_tombstones(self) -> list[Tombstone]:
        """Every Tombstone in the order it was written."""
        ...

    def get_feed(self, feed_id: int) -> Feed | None:
        """The Feed with this id, else None."""
        ...

    def list_feed_episodes(self, feed_id: int) -> list[Episode]:
        """The Feed's Episodes by publishedAt descending, ties by id descending.

        Raises NotFoundError for an unknown Feed id.
        """
        ...

    def apply_episode_states(self, changes: list[EpisodeStateChange]) -> list[EpisodeStateResult]:
        """Apply the changes in order, last-write-wins, as one atomic operation.

        A change is APPLIED only if its `state_updated_at` is strictly greater than the stored
        one (and takes the next `rev`); otherwise STALE. An unknown Episode is UNKNOWN_EPISODE.
        Returns one result per change, in order.
        """
        ...

    def sync_page(self, since: int, limit: int) -> SyncPage:
        """Feeds, Episodes, and Tombstones with `rev > since`, ascending by rev, at most `limit`
        items in total, read as one consistent snapshot."""
        ...

    def apply_refresh(self, feed_id: int, parsed: ParsedFeed, now_ms: int) -> RefreshApplied | None:
        """Apply a successful fetch to a Feed as one atomic operation.

        Inserts Episodes whose guid is not yet stored (as New), replaces the Feed metadata
        (a new `rev` only if it changed), and sets `last_refreshed_at`. Known Episodes are not
        touched. Returns None, changing nothing, if the Feed no longer exists.
        """
        ...


def normalize_feed_url(feed_url: str) -> str:
    """The duplicate-detection key (PM decision on issue #5).

    Trims whitespace, lower-cases scheme and host, drops the default port and the fragment, and
    treats an empty path as `/`. Path, query, and userinfo are kept exactly as given.
    """
    url = feed_url.strip()
    try:
        parts = urlsplit(url)
        port = parts.port
    except ValueError:
        return url
    scheme = parts.scheme.lower()
    userinfo, at, hostport = parts.netloc.rpartition("@")
    host = hostport.rsplit(":", 1)[0] if port is not None else hostport
    host = host.lower()
    if port is not None and str(port) != _DEFAULT_PORTS.get(scheme):
        host = f"{host}:{port}"
    query = f"?{parts.query}" if parts.query else ""
    return f"{scheme}://{userinfo}{at}{host}{parts.path or '/'}{query}"


def store_for_app(app: FastAPI) -> Store:
    """The app's one store, built by `create_app` and kept on `app.state.store`."""
    return app.state.store


def get_store(request: Request) -> Store:
    """FastAPI dependency: the app's one store."""
    return store_for_app(request.app)
