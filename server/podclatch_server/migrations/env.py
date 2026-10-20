"""Alembic environment, driven programmatically (see `podclatch_server.db.migrate`).

The connection is handed in through `config.attributes["connection"]`; no URL is read from a
config file, and `logging.config.fileConfig` is deliberately not called (it would override the
app's `LOG_LEVEL` and silence its loggers).
"""

from alembic import context

from podclatch_server.models import Base

config = context.config
connection = config.attributes["connection"]

context.configure(
    connection=connection,
    target_metadata=Base.metadata,
    compare_type=True,
)

with context.begin_transaction():
    context.run_migrations()
