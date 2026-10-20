"""Shared test wiring: a fresh, empty database for every test, on SQLite or Postgres.

* `PODCLATCH_TEST_DATABASE_URL` unset: every app/store built with the default `DATABASE_URL`
  gets its own temporary SQLite file under pytest's `tmp_path`.
* Set: the same applies, but each one gets its own freshly created Postgres schema (so ids and
  `rev` start from 1 and 0), dropped at teardown. A database that is set but unreachable makes
  tests fail; it never skips them.
* A test that passes its own `database_url` is honoured.
* The `new_store` fixture (store-contract tests) runs against `sqlite` always and `postgres`
  when the variable is set.
"""

from __future__ import annotations

import os
import uuid
from collections.abc import Callable, Iterator
from pathlib import Path
from typing import Any

import pytest
from sqlalchemy import create_engine, text

from podclatch_server import sql_store
from podclatch_server.config import DEFAULT_DATABASE_URL
from podclatch_server.db import to_sqlalchemy_url
from podclatch_server.sql_store import SqlStore
from podclatch_server.store import Store

POSTGRES_URL_VARIABLE = "PODCLATCH_TEST_DATABASE_URL"
REAL_OPEN_STORE = sql_store.open_store  # captured before the autouse fixture replaces it


def postgres_test_url() -> str | None:
    return os.environ.get(POSTGRES_URL_VARIABLE) or None


class DatabaseFactory:
    """Creates empty databases for one test and removes them (and closes stores) afterwards."""

    def __init__(self, directory: Path) -> None:
        self._directory = directory
        self._stores: list[SqlStore] = []
        self._schemas: list[str] = []
        self._count = 0

    def new_location(self, kind: str) -> tuple[str, dict[str, Any] | None]:
        """A URL (and connect args) for a new empty database of this kind."""
        self._count += 1
        if kind == "sqlite":
            return f"sqlite:///{(self._directory / f'test-{self._count}.db').as_posix()}", None
        url = postgres_test_url()
        assert url, f"{POSTGRES_URL_VARIABLE} is not set"
        schema = f"t_{uuid.uuid4().hex[:16]}"
        admin = create_engine(to_sqlalchemy_url(url))
        try:
            with admin.begin() as conn:
                conn.execute(text(f'CREATE SCHEMA "{schema}"'))
        finally:
            admin.dispose()
        self._schemas.append(schema)
        return url, {"options": f"-csearch_path={schema}"}

    def open(self, kind: str, real_open: Callable[..., SqlStore]) -> SqlStore:
        url, connect_args = self.new_location(kind)
        store = real_open(url, connect_args)
        self._stores.append(store)
        return store

    def track(self, store: SqlStore) -> SqlStore:
        self._stores.append(store)
        return store

    def cleanup(self) -> None:
        for store in self._stores:
            store.close()
        url = postgres_test_url()
        if self._schemas and url:
            admin = create_engine(to_sqlalchemy_url(url))
            try:
                with admin.begin() as conn:
                    for schema in self._schemas:
                        conn.execute(text(f'DROP SCHEMA IF EXISTS "{schema}" CASCADE'))
            finally:
                admin.dispose()


@pytest.fixture(autouse=True)
def databases(tmp_path, monkeypatch) -> Iterator[DatabaseFactory]:
    factory = DatabaseFactory(tmp_path)
    default_kind = "postgres" if postgres_test_url() else "sqlite"

    def open_for_tests(database_url: str, connect_args: dict[str, Any] | None = None) -> SqlStore:
        if database_url == DEFAULT_DATABASE_URL and connect_args is None:
            return factory.open(default_kind, REAL_OPEN_STORE)
        return factory.track(REAL_OPEN_STORE(database_url, connect_args))

    monkeypatch.setattr(sql_store, "open_store", open_for_tests)
    monkeypatch.delenv("DATABASE_URL", raising=False)
    yield factory
    factory.cleanup()


class CheckedStore:
    """Wraps a store and checks, after every mutation, that priorities are exactly 0..n-1."""

    _MUTATIONS = (
        "add_feed",
        "reorder_feeds",
        "set_listen_order",
        "delete_feed",
        "apply_refresh",
        "apply_episode_states",
    )

    def __init__(self, store: SqlStore) -> None:
        self._store = store

    def __getattr__(self, name: str) -> Any:
        attribute = getattr(self._store, name)
        if name not in self._MUTATIONS:
            return attribute

        def checked(*args: Any, **kwargs: Any) -> Any:
            try:
                return attribute(*args, **kwargs)
            finally:
                assert_priorities_contiguous(self._store)

        return checked


def assert_priorities_contiguous(store: Store) -> None:
    priorities = [f.priority for f in store.list_feeds()]
    assert priorities == list(range(len(priorities))), priorities


@pytest.fixture(params=["sqlite", "postgres"])
def store_kind(request) -> str:
    if request.param == "postgres" and not postgres_test_url():
        pytest.skip(f"{POSTGRES_URL_VARIABLE} is not set, so the Postgres store tests are skipped")
    return request.param


@pytest.fixture
def new_store(store_kind, databases) -> Callable[[], Store]:
    """Factory for fresh, empty stores of the parametrized backend (closed at teardown)."""

    def make() -> Store:
        url, connect_args = databases.new_location(store_kind)
        return CheckedStore(databases.track(REAL_OPEN_STORE(url, connect_args)))  # type: ignore[return-value]

    return make
