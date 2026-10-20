"""SQLAlchemy 2 models for the server schema (spec section 4).

Column names are the spec's names in snake_case. Enums are stored as strings guarded by a CHECK
constraint (no native Postgres enum type, so SQLite and Postgres have the same schema). The
`*_digest` columns are the one addition to the spec schema: a SHA-256 hex digest of the
normalised feed URL (and of the guid) carries the unique constraint, so an index key stays small
however long the URL or guid is. `Feed.priority` has no UNIQUE constraint; the store keeps
`0..n-1` (shifting priorities inside one transaction would trip a unique index).

`CounterRow` is the single row that numbers everything (`rev` and the issued ids) and is locked
first in every write transaction.
"""

from __future__ import annotations

from sqlalchemy import (
    BigInteger,
    CheckConstraint,
    ForeignKey,
    Index,
    Integer,
    MetaData,
    String,
    Text,
    UniqueConstraint,
    text,
)
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column

NAMING_CONVENTION = {
    "ix": "ix_%(table_name)s_%(column_0_name)s",
    "uq": "uq_%(table_name)s_%(column_0_name)s",
    "pk": "pk_%(table_name)s",
    "fk": "fk_%(table_name)s_%(column_0_name)s_%(referred_table_name)s",
}

DIGEST_LENGTH = 64  # SHA-256 as hex


class Base(DeclarativeBase):
    metadata = MetaData(naming_convention=NAMING_CONVENTION)


class FeedRow(Base):
    __tablename__ = "feed"
    __table_args__ = (
        UniqueConstraint("feed_url_digest", name="uq_feed_feed_url_digest"),
        CheckConstraint(
            "listen_order IN ('NEWEST_FIRST', 'OLDEST_FIRST')", name="ck_feed_listen_order"
        ),
        Index("ix_feed_rev", "rev"),
        Index("ix_feed_priority", "priority"),
    )

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=False)
    feed_url: Mapped[str] = mapped_column(Text)
    feed_url_digest: Mapped[str] = mapped_column(String(DIGEST_LENGTH))
    title: Mapped[str] = mapped_column(Text)
    author: Mapped[str] = mapped_column(Text)
    description: Mapped[str] = mapped_column(Text)
    artwork_url: Mapped[str] = mapped_column(Text)
    priority: Mapped[int] = mapped_column(Integer)
    listen_order: Mapped[str] = mapped_column(
        String(16), default="NEWEST_FIRST", server_default=text("'NEWEST_FIRST'")
    )
    last_refreshed_at: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    subscribed_at: Mapped[int] = mapped_column(BigInteger)
    rev: Mapped[int] = mapped_column(BigInteger)


class EpisodeRow(Base):
    __tablename__ = "episode"
    __table_args__ = (
        UniqueConstraint("feed_id", "guid_digest", name="uq_episode_feed_id_guid_digest"),
        CheckConstraint("status IN ('NEW', 'PLAYED')", name="ck_episode_status"),
        Index("ix_episode_rev", "rev"),
        Index("ix_episode_feed_id", "feed_id"),
    )

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=False)
    feed_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("feed.id", ondelete="CASCADE", name="fk_episode_feed_id_feed")
    )
    guid: Mapped[str] = mapped_column(Text)
    guid_digest: Mapped[str] = mapped_column(String(DIGEST_LENGTH))
    title: Mapped[str] = mapped_column(Text)
    description: Mapped[str] = mapped_column(Text)
    audio_url: Mapped[str] = mapped_column(Text)
    published_at: Mapped[int] = mapped_column(BigInteger)
    duration_sec: Mapped[int | None] = mapped_column(Integer, nullable=True)
    artwork_url: Mapped[str | None] = mapped_column(Text, nullable=True)
    status: Mapped[str] = mapped_column(String(8), default="NEW", server_default=text("'NEW'"))
    played_at: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    position_sec: Mapped[int] = mapped_column(Integer, default=0, server_default=text("0"))
    state_updated_at: Mapped[int] = mapped_column(BigInteger)
    rev: Mapped[int] = mapped_column(BigInteger)


class TombstoneRow(Base):
    __tablename__ = "tombstone"
    __table_args__ = (
        CheckConstraint("entity_type IN ('FEED', 'EPISODE')", name="ck_tombstone_entity_type"),
        Index("ix_tombstone_rev", "rev"),
    )

    # The primary key makes a Tombstone unique per (entity_type, entity_id).
    entity_type: Mapped[str] = mapped_column(String(8), primary_key=True)
    entity_id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=False)
    rev: Mapped[int] = mapped_column(BigInteger)


class CounterRow(Base):
    """The one row (id = 1) holding the global `rev` and the last-issued Feed and Episode ids."""

    __tablename__ = "counter"
    __table_args__ = (CheckConstraint("id = 1", name="ck_counter_single_row"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=False)
    rev: Mapped[int] = mapped_column(BigInteger)
    last_feed_id: Mapped[int] = mapped_column(BigInteger)
    last_episode_id: Mapped[int] = mapped_column(BigInteger)
