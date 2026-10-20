"""The server's data store: an interface plus an in-memory implementation.

Every multi-step change is one operation of the interface, so a database implementation (issue
#9) can make each one a single transaction. Issue #6 reuses the Episodes, Tombstones, and the
global `rev` counter held here and adds methods; it does not change what exists.

`rev` rules: one global counter starting at 0 (the first change gets 1). Every insert, update,
and delete of a Feed or Episode takes the next value, and so does each Tombstone. A change that
alters nothing takes no value. Ids are never reused. A rejected request consumes nothing.
"""

from __future__ import annotations

import threading
from dataclasses import dataclass, replace
from enum import StrEnum
from typing import Protocol
from urllib.parse import urlsplit

from fastapi import FastAPI, Request

from podclatch_server.errors import ConflictError, NotFoundError, UnprocessableError
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


class InMemoryStore:
    """A `Store` kept in dictionaries behind one lock, held only for the in-memory change."""

    def __init__(self) -> None:
        self._lock = threading.RLock()
        self._rev = 0
        self._next_feed_id = 1
        self._next_episode_id = 1
        self._feeds: dict[int, Feed] = {}
        self._episodes: dict[int, Episode] = {}
        self._episode_keys: set[tuple[int, str]] = set()  # (feedId, guid) uniqueness
        self._tombstones: list[Tombstone] = []

    def _bump(self) -> int:
        self._rev += 1
        return self._rev

    def _ordered(self) -> list[Feed]:
        return sorted(self._feeds.values(), key=lambda f: f.priority)

    def current_rev(self) -> int:
        with self._lock:
            return self._rev

    def list_feeds(self) -> list[Feed]:
        with self._lock:
            return self._ordered()

    def get_feed_by_url(self, feed_url: str) -> Feed | None:
        key = normalize_feed_url(feed_url)
        with self._lock:
            return next(
                (f for f in self._feeds.values() if normalize_feed_url(f.feed_url) == key), None
            )

    def add_feed(self, feed_url: str, parsed: ParsedFeed, now_ms: int) -> Feed:
        feed_url = feed_url.strip()
        with self._lock:
            if self.get_feed_by_url(feed_url) is not None:  # repeated inside the atomic insert
                raise ConflictError(DUPLICATE_FEED_DETAIL)
            for existing in self._ordered():
                self._feeds[existing.id] = replace(
                    existing, priority=existing.priority + 1, rev=self._bump()
                )
            feed = Feed(
                id=self._next_feed_id,
                feed_url=feed_url,
                title=parsed.title,
                author=parsed.author,
                description=parsed.description,
                artwork_url=parsed.artwork_url,
                priority=0,
                listen_order=ListenOrder.NEWEST_FIRST,
                last_refreshed_at=now_ms,
                subscribed_at=now_ms,
                rev=self._bump(),
            )
            self._next_feed_id += 1
            self._feeds[feed.id] = feed
            for item in parsed.episodes:
                if (feed.id, item.guid) in self._episode_keys:
                    continue
                episode = Episode(
                    id=self._next_episode_id,
                    feed_id=feed.id,
                    guid=item.guid,
                    title=item.title,
                    description=item.description,
                    audio_url=item.audio_url,
                    published_at=item.published_at,
                    duration_sec=item.duration_sec,
                    artwork_url=item.artwork_url,
                    status=EpisodeStatus.NEW,
                    played_at=None,
                    position_sec=0,
                    state_updated_at=item.published_at,
                    rev=self._bump(),
                )
                self._next_episode_id += 1
                self._episodes[episode.id] = episode
                self._episode_keys.add((feed.id, episode.guid))
            return feed

    def reorder_feeds(self, feed_ids: list[int]) -> list[Feed]:
        with self._lock:
            if len(set(feed_ids)) != len(feed_ids) or set(feed_ids) != set(self._feeds):
                raise UnprocessableError(BAD_ORDER_DETAIL)
            for priority, feed_id in enumerate(feed_ids):
                feed = self._feeds[feed_id]
                if feed.priority != priority:
                    self._feeds[feed_id] = replace(feed, priority=priority, rev=self._bump())
            return self._ordered()

    def set_listen_order(self, feed_id: int, listen_order: ListenOrder) -> Feed:
        with self._lock:
            feed = self._feeds.get(feed_id)
            if feed is None:
                raise NotFoundError(FEED_NOT_FOUND_DETAIL)
            if feed.listen_order != listen_order:
                feed = replace(feed, listen_order=listen_order, rev=self._bump())
                self._feeds[feed_id] = feed
            return feed

    def delete_feed(self, feed_id: int) -> None:
        with self._lock:
            feed = self._feeds.get(feed_id)
            if feed is None:
                raise NotFoundError(FEED_NOT_FOUND_DETAIL)
            for episode in [e for e in self._episodes.values() if e.feed_id == feed_id]:
                del self._episodes[episode.id]
                self._episode_keys.discard((feed_id, episode.guid))
                self._tombstones.append(Tombstone(EntityType.EPISODE, episode.id, self._bump()))
            del self._feeds[feed_id]
            self._tombstones.append(Tombstone(EntityType.FEED, feed_id, self._bump()))
            for other in self._ordered():
                if other.priority > feed.priority:
                    self._feeds[other.id] = replace(
                        other, priority=other.priority - 1, rev=self._bump()
                    )

    def list_episodes(self, feed_id: int) -> list[Episode]:
        with self._lock:
            return [e for e in self._episodes.values() if e.feed_id == feed_id]

    def list_tombstones(self) -> list[Tombstone]:
        with self._lock:
            return list(self._tombstones)

    def get_feed(self, feed_id: int) -> Feed | None:
        with self._lock:
            return self._feeds.get(feed_id)

    def list_feed_episodes(self, feed_id: int) -> list[Episode]:
        with self._lock:
            if feed_id not in self._feeds:
                raise NotFoundError(FEED_NOT_FOUND_DETAIL)
            episodes = [e for e in self._episodes.values() if e.feed_id == feed_id]
        return sorted(episodes, key=lambda e: (e.published_at, e.id), reverse=True)

    def apply_episode_states(self, changes: list[EpisodeStateChange]) -> list[EpisodeStateResult]:
        results: list[EpisodeStateResult] = []
        with self._lock:
            for change in changes:
                episode = self._episodes.get(change.episode_id)
                if episode is None:
                    results.append(
                        EpisodeStateResult(change.episode_id, StateOutcome.UNKNOWN_EPISODE)
                    )
                    continue
                outcome = StateOutcome.STALE
                if change.state_updated_at > episode.state_updated_at:
                    outcome = StateOutcome.APPLIED
                    episode = replace(
                        episode,
                        status=change.status,
                        played_at=change.played_at,
                        position_sec=change.position_sec,
                        state_updated_at=change.state_updated_at,
                        rev=self._bump(),
                    )
                    self._episodes[episode.id] = episode
                results.append(
                    EpisodeStateResult(
                        episode.id,
                        outcome,
                        episode.status,
                        episode.played_at,
                        episode.position_sec,
                        episode.state_updated_at,
                    )
                )
        return results

    def sync_page(self, since: int, limit: int) -> SyncPage:
        with self._lock:
            items: list[Feed | Episode | Tombstone] = [
                *(f for f in self._feeds.values() if f.rev > since),
                *(e for e in self._episodes.values() if e.rev > since),
                *(t for t in self._tombstones if t.rev > since),
            ]
        items.sort(key=lambda item: item.rev)
        page = items[:limit]
        return SyncPage(
            feeds=[i for i in page if isinstance(i, Feed)],
            episodes=[i for i in page if isinstance(i, Episode)],
            tombstones=[i for i in page if isinstance(i, Tombstone)],
            next_rev=page[-1].rev if page else since,
            has_more=len(items) > len(page),
        )

    def apply_refresh(self, feed_id: int, parsed: ParsedFeed, now_ms: int) -> RefreshApplied | None:
        with self._lock:
            feed = self._feeds.get(feed_id)
            if feed is None:
                return None
            changed = (feed.title, feed.author, feed.description, feed.artwork_url) != (
                parsed.title,
                parsed.author,
                parsed.description,
                parsed.artwork_url,
            )
            self._feeds[feed_id] = replace(
                feed,
                title=parsed.title,
                author=parsed.author,
                description=parsed.description,
                artwork_url=parsed.artwork_url,
                last_refreshed_at=now_ms,
                rev=self._bump() if changed else feed.rev,
            )
            inserted = 0
            for item in parsed.episodes:
                if (feed_id, item.guid) in self._episode_keys:  # re-checked under the lock
                    continue
                episode = Episode(
                    id=self._next_episode_id,
                    feed_id=feed_id,
                    guid=item.guid,
                    title=item.title,
                    description=item.description,
                    audio_url=item.audio_url,
                    published_at=item.published_at,
                    duration_sec=item.duration_sec,
                    artwork_url=item.artwork_url,
                    status=EpisodeStatus.NEW,
                    played_at=None,
                    position_sec=0,
                    state_updated_at=item.published_at,
                    rev=self._bump(),
                )
                self._next_episode_id += 1
                self._episodes[episode.id] = episode
                self._episode_keys.add((feed_id, episode.guid))
                inserted += 1
            return RefreshApplied(inserted=inserted, feed_changed=changed)


_creation_lock = threading.Lock()


def store_for_app(app: FastAPI) -> Store:
    """The app's one store, created on first use (so each app starts empty)."""
    state = app.state
    with _creation_lock:
        if getattr(state, "store", None) is None:
            state.store = InMemoryStore()
        return state.store


def get_store(request: Request) -> Store:
    """FastAPI dependency: the app's one store."""
    return store_for_app(request.app)
