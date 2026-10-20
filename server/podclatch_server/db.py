"""Database engine setup, URL handling, and startup migrations.

`DATABASE_URL` can hold a password, so nothing here puts the URL, or any driver message that
could echo it, into an exception or a log line. Startup failures raise `DatabaseStartupError`
with a fixed text that names the variable and nothing else, chained `from None`.
"""

from __future__ import annotations

import logging
import sqlite3
from pathlib import Path
from typing import Any
from urllib.parse import parse_qsl, unquote, urlsplit

from alembic import command
from alembic.config import Config
from alembic.runtime.migration import MigrationContext
from alembic.script import ScriptDirectory
from sqlalchemy import Connection, Engine, create_engine, event
from sqlalchemy.engine import URL
from sqlalchemy.exc import DBAPIError
from sqlalchemy.pool import StaticPool

logger = logging.getLogger(__name__)

SUPPORTED_SCHEMES = ("sqlite", "postgresql", "postgres", "postgresql+psycopg")
BUSY_TIMEOUT_SECONDS = 60
CONNECT_TIMEOUT_SECONDS = 10  # Postgres: fail startup instead of hanging on an unreachable host
MIGRATIONS_DIR = Path(__file__).parent / "migrations"

# Execution option set on a connection that will write; `begin` then takes the SQLite write lock.
WRITE_OPTION = "podclatch_write"


class DatabaseStartupError(RuntimeError):
    """The database cannot be used. The message names DATABASE_URL, never its value."""


UNREACHABLE_MESSAGE = (
    "Cannot use the database named by DATABASE_URL: it is unreachable, the credentials are "
    "wrong, or (for SQLite) the file's directory is missing or not writable."
)
NEWER_SCHEMA_MESSAGE = (
    "The database named by DATABASE_URL has a schema newer than this version of the server "
    "understands. Upgrade the server instead of starting it against this database."
)
MIGRATION_FAILED_MESSAGE = "The migrations failed for the database named by DATABASE_URL."
INVALID_URL_MESSAGE = "DATABASE_URL is not a valid database URL."


def scheme_of(database_url: str) -> str:
    return database_url.split("://", 1)[0].lower() if "://" in database_url else ""


def is_supported_url(database_url: str) -> bool:
    return scheme_of(database_url) in SUPPORTED_SCHEMES


def to_sqlalchemy_url(database_url: str) -> URL:
    """Parse `DATABASE_URL`; `postgresql://` and `postgres://` use the psycopg 3 driver.

    A password may contain percent-escapes or a literal `@`: the userinfo ends at the last `@`.
    """
    scheme = scheme_of(database_url)
    if scheme == "sqlite":
        return URL.create("sqlite", database=_sqlite_path(database_url))
    if scheme not in SUPPORTED_SCHEMES:
        raise ValueError(INVALID_URL_MESSAGE)
    parts = urlsplit(database_url)
    userinfo, at, hostport = parts.netloc.rpartition("@")
    username, _, password = userinfo.partition(":") if at else ("", "", "")
    host, port = hostport, None
    if hostport.startswith("["):  # IPv6 literal
        bracket = hostport.find("]")
        host = hostport[1:bracket]
        rest = hostport[bracket + 1 :]
        port = int(rest[1:]) if rest.startswith(":") and rest[1:] else None
    elif ":" in hostport:
        host, _, raw_port = hostport.rpartition(":")
        port = int(raw_port) if raw_port else None
    return URL.create(
        "postgresql+psycopg",
        username=unquote(username) or None,
        password=unquote(password) if password else None,
        host=unquote(host) or None,
        port=port,
        database=unquote(parts.path[1:]) or None,
        query=dict(parse_qsl(parts.query)),
    )


def _sqlite_path(database_url: str) -> str | None:
    """The file path of a `sqlite:///path` URL; None for an in-memory database."""
    rest = database_url.split("://", 1)[1]
    query_at = rest.find("?")
    if query_at >= 0:
        rest = rest[:query_at]
    path = rest[1:] if rest.startswith("/") else rest
    if path in ("", ":memory:"):
        return None
    return unquote(path) if "%" in path else path


def is_memory_sqlite(url: URL) -> bool:
    return url.get_backend_name() == "sqlite" and not url.database


def create_db_engine(database_url: str, connect_args: dict[str, Any] | None = None) -> Engine:
    """Create the engine. Never connects. Parameters are hidden from statement errors."""
    try:
        url = to_sqlalchemy_url(database_url)
    except (ValueError, TypeError):
        raise DatabaseStartupError(INVALID_URL_MESSAGE) from None
    if url.get_backend_name() == "sqlite":
        engine = _create_sqlite_engine(url, connect_args)
    else:
        engine = create_engine(
            url,
            hide_parameters=True,
            pool_pre_ping=True,
            pool_size=10,
            max_overflow=30,
            isolation_level="READ COMMITTED",
            connect_args={"connect_timeout": CONNECT_TIMEOUT_SECONDS, **(connect_args or {})},
        )
    return engine


def _create_sqlite_engine(url: URL, connect_args: dict[str, Any] | None) -> Engine:
    args: dict[str, Any] = {"check_same_thread": False, "timeout": BUSY_TIMEOUT_SECONDS}
    args.update(connect_args or {})
    memory = is_memory_sqlite(url)
    if memory:
        # One shared connection; the store serializes every operation with a lock.
        engine = create_engine(
            "sqlite://", hide_parameters=True, poolclass=StaticPool, connect_args=args
        )
    else:
        engine = create_engine(
            url, hide_parameters=True, pool_size=10, max_overflow=30, connect_args=args
        )

    @event.listens_for(engine, "connect")
    def _on_connect(dbapi_connection: sqlite3.Connection, _record: object) -> None:
        # Take over transaction control from pysqlite, which does not BEGIN for a SELECT.
        dbapi_connection.isolation_level = None
        cursor = dbapi_connection.cursor()
        try:
            cursor.execute("PRAGMA foreign_keys=ON")
            cursor.execute(f"PRAGMA busy_timeout={BUSY_TIMEOUT_SECONDS * 1000}")
            if not memory:
                cursor.execute("PRAGMA journal_mode=WAL")
                cursor.execute("PRAGMA synchronous=NORMAL")
        finally:
            cursor.close()

    @event.listens_for(engine, "begin")
    def _on_begin(conn: Connection) -> None:
        # A writer takes SQLite's write lock up front so write transactions run one at a time.
        if conn.get_execution_options().get(WRITE_OPTION):
            conn.exec_driver_sql("BEGIN IMMEDIATE")
        else:
            conn.exec_driver_sql("BEGIN")

    return engine


def _alembic_config(connection: Connection) -> Config:
    config = Config()  # no alembic.ini: no URL in a config file, and no logging override
    config.set_main_option("script_location", str(MIGRATIONS_DIR))
    config.attributes["connection"] = connection
    return config


def migrate(engine: Engine) -> None:
    """Bring the schema to the newest revision. Idempotent; safe to run on every start.

    Raises DatabaseStartupError (message without the URL) if the database cannot be reached or
    migrated, or if it was written by a newer version of the server.
    """
    try:
        with engine.begin() as connection:
            config = _alembic_config(connection)
            script = ScriptDirectory.from_config(config)
            current = MigrationContext.configure(connection).get_current_revision()
            known = {revision.revision for revision in script.walk_revisions()}
            if current is not None and current not in known:
                raise _NewerSchemaError
            command.upgrade(config, "head")
    except _NewerSchemaError:
        raise DatabaseStartupError(NEWER_SCHEMA_MESSAGE) from None
    except Exception as exc:
        logger.error("database migration failed: %s", type(exc).__name__)
        if isinstance(exc, DBAPIError) or _is_connection_error(exc):
            raise DatabaseStartupError(UNREACHABLE_MESSAGE) from None
        raise DatabaseStartupError(MIGRATION_FAILED_MESSAGE) from None


class _NewerSchemaError(Exception):
    pass


def _is_connection_error(exc: Exception) -> bool:
    return isinstance(exc, OSError | sqlite3.Error) or type(exc).__module__.startswith("psycopg")
