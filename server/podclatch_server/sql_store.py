"""The database-backed `Store` (SQLAlchemy Core over the models in `models.py`).

Rules this module keeps (issue #9):

* Every write operation is one transaction whose first statement locks the single `counter` row
  (`UPDATE counter SET rev = rev`; on SQLite `BEGIN IMMEDIATE` already holds the database write
  lock). Writers therefore run one after another and commit in `rev` order, so a reader that
  sees `rev` N+1 also sees every `rev <= N` that will ever exist.
* `rev` and the ids come from that counter row and are written back in the same transaction, so
  a rejected or failed operation consumes nothing (sequences and AUTOINCREMENT would not roll
  back). Ids are never reused.
* `sync_page` reads in one snapshot (an explicit transaction on SQLite WAL, REPEATABLE READ
  read-only on Postgres).
* One short-lived connection per operation; no connection or session outlives a call, and
  nothing here ever fetches a feed.
* Values leave the store as the frozen dataclasses from `store.py`, never as rows.
"""

from __future__ import annotations

import contextlib
import hashlib
import threading
from collections.abc import Callable, Iterator, Sequence
from dataclasses import dataclass, replace
from typing import Any

from sqlalchemy import Connection, Engine, Row, delete, insert, select, update
from sqlalchemy.exc import IntegrityError

from podclatch_server.db import WRITE_OPTION, create_db_engine, is_memory_sqlite, migrate
from podclatch_server.errors import ConflictError, NotFoundError, UnprocessableError
from podclatch_server.feeds import ParsedEpisode, ParsedFeed
from podclatch_server.models import CounterRow, EpisodeRow, FeedRow, TombstoneRow
from podclatch_server.store import (
    BAD_ORDER_DETAIL,
    DUPLICATE_FEED_DETAIL,
    FEED_NOT_FOUND_DETAIL,
    EntityType,
    Episode,
    EpisodeStateChange,
    EpisodeStateResult,
    EpisodeStatus,
    Feed,
    ListenOrder,
    RefreshApplied,
    StateOutcome,
    SyncPage,
    Tombstone,
    normalize_feed_url,
)

_FEED = FeedRow.__table__
_EPISODE = EpisodeRow.__table__
_TOMBSTONE = TombstoneRow.__table__
_COUNTER = CounterRow.__table__


def _digest(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8", "replace")).hexdigest()


def _clean(value: str) -> str:
    """Drop U+0000, which Postgres text cannot hold."""
    return value.replace("\x00", "")


def _clean_optional(value: str | None) -> str | None:
    return None if value is None else _clean(value)


def _clean_parsed(parsed: ParsedFeed) -> ParsedFeed:
    return ParsedFeed(
        title=_clean(parsed.title),
        author=_clean(parsed.author),
        description=_clean(parsed.description),
        artwork_url=_clean(parsed.artwork_url),
        episodes=[
            ParsedEpisode(
                guid=_clean(e.guid),
                title=_clean(e.title),
                description=_clean(e.description),
                audio_url=_clean(e.audio_url),
                published_at=e.published_at,
                duration_sec=e.duration_sec,
                artwork_url=_clean_optional(e.artwork_url),
            )
            for e in parsed.episodes
        ],
    )


def _feed(row: Row[Any]) -> Feed:
    return Feed(
        id=row.id,
        feed_url=row.feed_url,
        title=row.title,
        author=row.author,
        description=row.description,
        artwork_url=row.artwork_url,
        priority=row.priority,
        listen_order=ListenOrder(row.listen_order),
        last_refreshed_at=row.last_refreshed_at,
        subscribed_at=row.subscribed_at,
        rev=row.rev,
    )


def _episode(row: Row[Any]) -> Episode:
    return Episode(
        id=row.id,
        feed_id=row.feed_id,
        guid=row.guid,
        title=row.title,
        description=row.description,
        audio_url=row.audio_url,
        published_at=row.published_at,
        duration_sec=row.duration_sec,
        artwork_url=row.artwork_url,
        status=EpisodeStatus(row.status),
        played_at=row.played_at,
        position_sec=row.position_sec,
        state_updated_at=row.state_updated_at,
        rev=row.rev,
    )


def _tombstone(row: Row[Any]) -> Tombstone:
    return Tombstone(EntityType(row.entity_type), row.entity_id, row.rev)


@dataclass
class _Counters:
    """The counter row as read at the start of a write transaction, advanced in memory."""

    rev: int
    last_feed_id: int
    last_episode_id: int

    def next_rev(self) -> int:
        self.rev += 1
        return self.rev

    def next_feed_id(self) -> int:
        self.last_feed_id += 1
        return self.last_feed_id

    def next_episode_id(self) -> int:
        self.last_episode_id += 1
        return self.last_episode_id


class _WriteTx:
    def __init__(self, conn: Connection, counters: _Counters) -> None:
        self.conn = conn
        self.counters = counters


class SqlStore:
    """A `Store` kept in a SQL database (SQLite or Postgres)."""

    def __init__(self, engine: Engine) -> None:
        self._engine = engine
        self._postgres = engine.dialect.name == "postgresql"
        # A shared in-memory SQLite connection must not run two operations at once.
        self._serial: threading.RLock | None = (
            threading.RLock() if is_memory_sqlite(engine.url) else None
        )
        # Test seam: called in a write transaction after its revs are taken, before commit.
        self.before_commit: Callable[[], None] | None = None

    # --- transactions ------------------------------------------------------------------------

    @contextlib.contextmanager
    def _guard(self) -> Iterator[None]:
        if self._serial is None:
            yield
        else:
            with self._serial:
                yield

    @contextlib.contextmanager
    def _read(self, *, snapshot: bool = False) -> Iterator[Connection]:
        """A read-only transaction on its own connection. `snapshot` pins one view of the data
        across several statements (needed by `sync_page`)."""
        with self._guard(), self._engine.connect() as conn:
            if self._postgres:
                conn.execution_options(
                    isolation_level="REPEATABLE READ" if snapshot else "READ COMMITTED",
                    postgresql_readonly=True,
                )
            with conn.begin():
                yield conn

    @contextlib.contextmanager
    def _write(self) -> Iterator[_WriteTx]:
        """One write transaction: lock the counter row first, commit or roll back as a unit."""
        with self._guard(), self._engine.connect() as conn:
            conn.execution_options(**{WRITE_OPTION: True})
            with conn.begin():
                # Takes the exclusive lock on the counter row (held until commit/rollback).
                conn.execute(update(_COUNTER).where(_COUNTER.c.id == 1).values(rev=_COUNTER.c.rev))
                row = conn.execute(select(_COUNTER).where(_COUNTER.c.id == 1)).one()
                counters = _Counters(row.rev, row.last_feed_id, row.last_episode_id)
                before = replace(counters)
                yield _WriteTx(conn, counters)
                if self.before_commit is not None:
                    self.before_commit()
                if counters != before:
                    conn.execute(
                        update(_COUNTER)
                        .where(_COUNTER.c.id == 1)
                        .values(
                            rev=counters.rev,
                            last_feed_id=counters.last_feed_id,
                            last_episode_id=counters.last_episode_id,
                        )
                    )

    def close(self) -> None:
        """Release pooled connections. A file database stays usable afterwards (connections are
        reopened on demand); an in-memory SQLite database is gone."""
        self._engine.dispose()

    # --- reads ---------------------------------------------------------------------------------

    def current_rev(self) -> int:
        with self._read() as conn:
            return conn.execute(select(_COUNTER.c.rev).where(_COUNTER.c.id == 1)).scalar_one()

    def list_feeds(self) -> list[Feed]:
        with self._read() as conn:
            return self._feeds(conn)

    @staticmethod
    def _feeds(conn: Connection) -> list[Feed]:
        rows = conn.execute(select(_FEED).order_by(_FEED.c.priority, _FEED.c.id))
        return [_feed(r) for r in rows]

    def get_feed(self, feed_id: int) -> Feed | None:
        with self._read() as conn:
            row = conn.execute(select(_FEED).where(_FEED.c.id == feed_id)).one_or_none()
            return None if row is None else _feed(row)

    def get_feed_by_url(self, feed_url: str) -> Feed | None:
        digest = _digest(normalize_feed_url(_clean(feed_url)))
        with self._read() as conn:
            row = conn.execute(select(_FEED).where(_FEED.c.feed_url_digest == digest)).one_or_none()
            return None if row is None else _feed(row)

    def list_episodes(self, feed_id: int) -> list[Episode]:
        with self._read() as conn:
            rows = conn.execute(
                select(_EPISODE).where(_EPISODE.c.feed_id == feed_id).order_by(_EPISODE.c.id)
            )
            return [_episode(r) for r in rows]

    def list_feed_episodes(self, feed_id: int) -> list[Episode]:
        with self._read(snapshot=True) as conn:
            if conn.execute(select(_FEED.c.id).where(_FEED.c.id == feed_id)).first() is None:
                raise NotFoundError(FEED_NOT_FOUND_DETAIL)
            rows = conn.execute(
                select(_EPISODE)
                .where(_EPISODE.c.feed_id == feed_id)
                .order_by(_EPISODE.c.published_at.desc(), _EPISODE.c.id.desc())
            )
            return [_episode(r) for r in rows]

    def list_tombstones(self) -> list[Tombstone]:
        with self._read() as conn:
            rows = conn.execute(select(_TOMBSTONE).order_by(_TOMBSTONE.c.rev))
            return [_tombstone(r) for r in rows]

    def sync_page(self, since: int, limit: int) -> SyncPage:
        with self._read(snapshot=True) as conn:
            items: list[Feed | Episode | Tombstone] = []
            for table, build in ((_FEED, _feed), (_EPISODE, _episode), (_TOMBSTONE, _tombstone)):
                rows = conn.execute(
                    select(table).where(table.c.rev > since).order_by(table.c.rev).limit(limit + 1)
                )
                items.extend(build(r) for r in rows)
        items.sort(key=lambda item: item.rev)
        page = items[:limit]
        return SyncPage(
            feeds=[i for i in page if isinstance(i, Feed)],
            episodes=[i for i in page if isinstance(i, Episode)],
            tombstones=[i for i in page if isinstance(i, Tombstone)],
            next_rev=page[-1].rev if page else since,
            has_more=len(items) > len(page),
        )

    # --- writes --------------------------------------------------------------------------------

    def add_feed(self, feed_url: str, parsed: ParsedFeed, now_ms: int) -> Feed:
        feed_url = _clean(feed_url.strip())
        parsed = _clean_parsed(parsed)
        digest = _digest(normalize_feed_url(feed_url))
        try:
            with self._write() as tx:
                conn, counters = tx.conn, tx.counters
                exists = conn.execute(
                    select(_FEED.c.id).where(_FEED.c.feed_url_digest == digest)
                ).first()
                if exists is not None:  # re-checked inside the transaction, after the lock
                    raise ConflictError(DUPLICATE_FEED_DETAIL)
                self._shift_priorities(tx, lambda priority: True, +1)
                feed = Feed(
                    id=counters.next_feed_id(),
                    feed_url=feed_url,
                    title=parsed.title,
                    author=parsed.author,
                    description=parsed.description,
                    artwork_url=parsed.artwork_url,
                    priority=0,
                    listen_order=ListenOrder.NEWEST_FIRST,
                    last_refreshed_at=now_ms,
                    subscribed_at=now_ms,
                    rev=0,
                )
                feed = replace(feed, rev=counters.next_rev())
                conn.execute(
                    insert(_FEED).values(
                        id=feed.id,
                        feed_url=feed.feed_url,
                        feed_url_digest=digest,
                        title=feed.title,
                        author=feed.author,
                        description=feed.description,
                        artwork_url=feed.artwork_url,
                        priority=0,
                        listen_order=feed.listen_order.value,
                        last_refreshed_at=now_ms,
                        subscribed_at=now_ms,
                        rev=feed.rev,
                    )
                )
                self._insert_episodes(tx, feed.id, parsed.episodes, known=set())
                return feed
        except IntegrityError:
            raise ConflictError(DUPLICATE_FEED_DETAIL) from None

    def reorder_feeds(self, feed_ids: list[int]) -> list[Feed]:
        with self._write() as tx:
            conn = tx.conn
            current = {r.id: r.priority for r in conn.execute(select(_FEED.c.id, _FEED.c.priority))}
            if len(set(feed_ids)) != len(feed_ids) or set(feed_ids) != set(current):
                raise UnprocessableError(BAD_ORDER_DETAIL)
            for priority, feed_id in enumerate(feed_ids):
                if current[feed_id] != priority:
                    conn.execute(
                        update(_FEED)
                        .where(_FEED.c.id == feed_id)
                        .values(priority=priority, rev=tx.counters.next_rev())
                    )
            return self._feeds(conn)

    def set_listen_order(self, feed_id: int, listen_order: ListenOrder) -> Feed:
        with self._write() as tx:
            conn = tx.conn
            row = conn.execute(select(_FEED).where(_FEED.c.id == feed_id)).one_or_none()
            if row is None:
                raise NotFoundError(FEED_NOT_FOUND_DETAIL)
            if row.listen_order == listen_order.value:
                return _feed(row)
            conn.execute(
                update(_FEED)
                .where(_FEED.c.id == feed_id)
                .values(listen_order=listen_order.value, rev=tx.counters.next_rev())
            )
            return _feed(conn.execute(select(_FEED).where(_FEED.c.id == feed_id)).one())

    def delete_feed(self, feed_id: int) -> None:
        with self._write() as tx:
            conn, counters = tx.conn, tx.counters
            row = conn.execute(select(_FEED).where(_FEED.c.id == feed_id)).one_or_none()
            if row is None:
                raise NotFoundError(FEED_NOT_FOUND_DETAIL)
            episode_ids = conn.execute(
                select(_EPISODE.c.id).where(_EPISODE.c.feed_id == feed_id).order_by(_EPISODE.c.id)
            ).scalars()
            tombstones = [
                {"entity_type": EntityType.EPISODE.value, "entity_id": episode_id}
                for episode_id in episode_ids
            ]
            for tombstone in tombstones:
                tombstone["rev"] = counters.next_rev()
            tombstones.append(
                {
                    "entity_type": EntityType.FEED.value,
                    "entity_id": feed_id,
                    "rev": counters.next_rev(),
                }
            )
            conn.execute(insert(_TOMBSTONE), tombstones)
            # The Episodes go with the Feed through ON DELETE CASCADE.
            conn.execute(delete(_FEED).where(_FEED.c.id == feed_id))
            self._shift_priorities(tx, lambda priority: priority > row.priority, -1)

    def apply_episode_states(self, changes: list[EpisodeStateChange]) -> list[EpisodeStateResult]:
        results: list[EpisodeStateResult] = []
        with self._write() as tx:
            conn = tx.conn
            for change in changes:
                row = conn.execute(
                    select(_EPISODE).where(_EPISODE.c.id == change.episode_id)
                ).one_or_none()
                if row is None:
                    results.append(
                        EpisodeStateResult(change.episode_id, StateOutcome.UNKNOWN_EPISODE)
                    )
                    continue
                outcome = StateOutcome.STALE
                state = (row.status, row.played_at, row.position_sec, row.state_updated_at)
                if change.state_updated_at > row.state_updated_at:
                    outcome = StateOutcome.APPLIED
                    state = (
                        change.status.value,
                        change.played_at,
                        change.position_sec,
                        change.state_updated_at,
                    )
                    conn.execute(
                        update(_EPISODE)
                        .where(_EPISODE.c.id == row.id)
                        .values(
                            status=state[0],
                            played_at=state[1],
                            position_sec=state[2],
                            state_updated_at=state[3],
                            rev=tx.counters.next_rev(),
                        )
                    )
                results.append(
                    EpisodeStateResult(
                        row.id, outcome, EpisodeStatus(state[0]), state[1], state[2], state[3]
                    )
                )
        return results

    def apply_refresh(self, feed_id: int, parsed: ParsedFeed, now_ms: int) -> RefreshApplied | None:
        parsed = _clean_parsed(parsed)
        with self._write() as tx:
            conn = tx.conn
            row = conn.execute(select(_FEED).where(_FEED.c.id == feed_id)).one_or_none()
            if row is None:
                return None
            changed = (row.title, row.author, row.description, row.artwork_url) != (
                parsed.title,
                parsed.author,
                parsed.description,
                parsed.artwork_url,
            )
            values: dict[str, Any] = {
                "title": parsed.title,
                "author": parsed.author,
                "description": parsed.description,
                "artwork_url": parsed.artwork_url,
                "last_refreshed_at": now_ms,
            }
            if changed:
                values["rev"] = tx.counters.next_rev()
            conn.execute(update(_FEED).where(_FEED.c.id == feed_id).values(**values))
            known = set(
                conn.execute(
                    select(_EPISODE.c.guid_digest).where(_EPISODE.c.feed_id == feed_id)
                ).scalars()
            )  # read inside the transaction, so a concurrent insert is seen
            inserted = self._insert_episodes(tx, feed_id, parsed.episodes, known)
            return RefreshApplied(inserted=inserted, feed_changed=changed)

    # --- helpers (inside a write transaction) --------------------------------------------------

    @staticmethod
    def _shift_priorities(tx: _WriteTx, selected: Callable[[int], bool], delta: int) -> None:
        """Move the Feeds whose priority is selected by `delta`, ascending, one rev each."""
        rows = tx.conn.execute(select(_FEED.c.id, _FEED.c.priority).order_by(_FEED.c.priority))
        for feed_id, priority in rows.all():
            if selected(priority):
                tx.conn.execute(
                    update(_FEED)
                    .where(_FEED.c.id == feed_id)
                    .values(priority=priority + delta, rev=tx.counters.next_rev())
                )

    @staticmethod
    def _insert_episodes(
        tx: _WriteTx, feed_id: int, items: Sequence[ParsedEpisode], known: set[str]
    ) -> int:
        """Insert the items whose guid is unseen (in feed order) as New. Returns the count."""
        rows = []
        for item in items:
            digest = _digest(item.guid)
            if digest in known:
                continue
            known.add(digest)
            rows.append(
                {
                    "id": tx.counters.next_episode_id(),
                    "feed_id": feed_id,
                    "guid": item.guid,
                    "guid_digest": digest,
                    "title": item.title,
                    "description": item.description,
                    "audio_url": item.audio_url,
                    "published_at": item.published_at,
                    "duration_sec": item.duration_sec,
                    "artwork_url": item.artwork_url,
                    "status": EpisodeStatus.NEW.value,
                    "played_at": None,
                    "position_sec": 0,
                    "state_updated_at": item.published_at,
                    "rev": tx.counters.next_rev(),
                }
            )
        if rows:
            tx.conn.execute(insert(_EPISODE), rows)
        return len(rows)


def open_store(database_url: str, connect_args: dict[str, Any] | None = None) -> SqlStore:
    """Create the engine, migrate the schema, and return the store. This is the one place the
    app builds its store. Raises `DatabaseStartupError` (never containing the URL) on failure."""
    engine = create_db_engine(database_url, connect_args)
    try:
        migrate(engine)
    except BaseException:
        engine.dispose()
        raise
    return SqlStore(engine)
