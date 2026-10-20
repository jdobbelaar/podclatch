"""Initial schema: Feed, Episode, Tombstone, and the counter row.

Revision ID: 0001
Revises:
"""

import sqlalchemy as sa
from alembic import op

revision = "0001"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "feed",
        sa.Column("id", sa.BigInteger(), autoincrement=False, nullable=False),
        sa.Column("feed_url", sa.Text(), nullable=False),
        sa.Column("feed_url_digest", sa.String(length=64), nullable=False),
        sa.Column("title", sa.Text(), nullable=False),
        sa.Column("author", sa.Text(), nullable=False),
        sa.Column("description", sa.Text(), nullable=False),
        sa.Column("artwork_url", sa.Text(), nullable=False),
        sa.Column("priority", sa.Integer(), nullable=False),
        sa.Column(
            "listen_order",
            sa.String(length=16),
            server_default=sa.text("'NEWEST_FIRST'"),
            nullable=False,
        ),
        sa.Column("last_refreshed_at", sa.BigInteger(), nullable=True),
        sa.Column("subscribed_at", sa.BigInteger(), nullable=False),
        sa.Column("rev", sa.BigInteger(), nullable=False),
        sa.CheckConstraint(
            "listen_order IN ('NEWEST_FIRST', 'OLDEST_FIRST')", name="ck_feed_listen_order"
        ),
        sa.PrimaryKeyConstraint("id", name="pk_feed"),
        sa.UniqueConstraint("feed_url_digest", name="uq_feed_feed_url_digest"),
    )
    op.create_index("ix_feed_rev", "feed", ["rev"])
    op.create_index("ix_feed_priority", "feed", ["priority"])

    op.create_table(
        "episode",
        sa.Column("id", sa.BigInteger(), autoincrement=False, nullable=False),
        sa.Column("feed_id", sa.BigInteger(), nullable=False),
        sa.Column("guid", sa.Text(), nullable=False),
        sa.Column("guid_digest", sa.String(length=64), nullable=False),
        sa.Column("title", sa.Text(), nullable=False),
        sa.Column("description", sa.Text(), nullable=False),
        sa.Column("audio_url", sa.Text(), nullable=False),
        sa.Column("published_at", sa.BigInteger(), nullable=False),
        sa.Column("duration_sec", sa.Integer(), nullable=True),
        sa.Column("artwork_url", sa.Text(), nullable=True),
        sa.Column("status", sa.String(length=8), server_default=sa.text("'NEW'"), nullable=False),
        sa.Column("played_at", sa.BigInteger(), nullable=True),
        sa.Column("position_sec", sa.Integer(), server_default=sa.text("0"), nullable=False),
        sa.Column("state_updated_at", sa.BigInteger(), nullable=False),
        sa.Column("rev", sa.BigInteger(), nullable=False),
        sa.CheckConstraint("status IN ('NEW', 'PLAYED')", name="ck_episode_status"),
        sa.ForeignKeyConstraint(
            ["feed_id"], ["feed.id"], name="fk_episode_feed_id_feed", ondelete="CASCADE"
        ),
        sa.PrimaryKeyConstraint("id", name="pk_episode"),
        sa.UniqueConstraint("feed_id", "guid_digest", name="uq_episode_feed_id_guid_digest"),
    )
    op.create_index("ix_episode_rev", "episode", ["rev"])
    op.create_index("ix_episode_feed_id", "episode", ["feed_id"])

    op.create_table(
        "tombstone",
        sa.Column("entity_type", sa.String(length=8), nullable=False),
        sa.Column("entity_id", sa.BigInteger(), autoincrement=False, nullable=False),
        sa.Column("rev", sa.BigInteger(), nullable=False),
        sa.CheckConstraint("entity_type IN ('FEED', 'EPISODE')", name="ck_tombstone_entity_type"),
        sa.PrimaryKeyConstraint("entity_type", "entity_id", name="pk_tombstone"),
    )
    op.create_index("ix_tombstone_rev", "tombstone", ["rev"])

    counter = op.create_table(
        "counter",
        sa.Column("id", sa.Integer(), autoincrement=False, nullable=False),
        sa.Column("rev", sa.BigInteger(), nullable=False),
        sa.Column("last_feed_id", sa.BigInteger(), nullable=False),
        sa.Column("last_episode_id", sa.BigInteger(), nullable=False),
        sa.CheckConstraint("id = 1", name="ck_counter_single_row"),
        sa.PrimaryKeyConstraint("id", name="pk_counter"),
    )
    op.bulk_insert(counter, [{"id": 1, "rev": 0, "last_feed_id": 0, "last_episode_id": 0}])


def downgrade() -> None:
    op.drop_table("counter")
    op.drop_table("tombstone")
    op.drop_table("episode")
    op.drop_table("feed")
