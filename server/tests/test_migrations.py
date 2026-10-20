"""Migrations, startup failures, DATABASE_URL handling, and secrecy of the URL."""

import logging

import pytest
from alembic.autogenerate import compare_metadata
from alembic.runtime.migration import MigrationContext
from fastapi.testclient import TestClient
from sqlalchemy import insert, text
from us25_helpers import AUTH, TOKEN, StubFetcher, ep, feed_of

from podclatch_server import sql_store
from podclatch_server.app import create_app
from podclatch_server.config import ConfigError, Settings, load_settings
from podclatch_server.db import (
    DatabaseStartupError,
    create_db_engine,
    migrate,
    to_sqlalchemy_url,
)
from podclatch_server.models import Base, FeedRow
from podclatch_server.routes.feeds import get_fetcher
from podclatch_server.store import store_for_app

PASSWORD = "Zq9-recognisable-pw"
SECRET_URL = f"postgresql://dbuser:{PASSWORD}@db-host-7.internal:5432/podclatch"


def sqlite_url(path) -> str:
    return f"sqlite:///{path.as_posix()}"


# --- migrations ---------------------------------------------------------------------------------


def test_migrations_match_the_models_with_no_drift(store_kind, databases):
    url, connect_args = databases.new_location(store_kind)
    engine = create_db_engine(url, connect_args)
    try:
        migrate(engine)
        with engine.connect() as conn:
            context = MigrationContext.configure(conn, opts={"compare_type": True})
            assert compare_metadata(context, Base.metadata) == []
            counter = conn.execute(
                text("SELECT id, rev, last_feed_id, last_episode_id FROM counter")
            )
            assert [tuple(r) for r in counter] == [(1, 0, 0, 0)]
    finally:
        engine.dispose()


def test_migrating_twice_changes_nothing_and_keeps_data(tmp_path):
    url = sqlite_url(tmp_path / "again.db")
    first = sql_store.open_store(url)
    feed = first.add_feed("https://example.com/a", feed_of(ep(1), ep(2)), 5)
    rev = first.current_rev()
    first.close()
    engine = create_db_engine(url)
    try:
        migrate(engine)
        migrate(engine)
    finally:
        engine.dispose()
    second = sql_store.open_store(url)
    assert second.current_rev() == rev == 3
    assert second.list_feeds() == [feed]
    assert [e.id for e in second.list_episodes(feed.id)] == [1, 2]
    assert second.add_feed("https://example.com/b", feed_of(ep(1)), 5).id == 2
    second.close()


def test_a_second_app_on_the_same_file_sees_the_data_and_the_counters(tmp_path):
    settings = Settings(database_url=sqlite_url(tmp_path / "app.db"), token=TOKEN, test_mode=True)
    first = create_app(settings)
    stub = StubFetcher()
    first.dependency_overrides[get_fetcher] = lambda: stub
    client = TestClient(first, headers=AUTH)
    created = client.post("/v1/feeds", json={"feedUrl": "https://example.com/a.xml"}).json()
    rev = store_for_app(first).current_rev()
    with client:  # start and stop: the lifespan disposes the engine
        pass
    second = create_app(settings)
    assert store_for_app(second).current_rev() == rev
    again = TestClient(second, headers=AUTH)
    assert [f["id"] for f in again.get("/v1/feeds").json()] == [created["id"]]
    assert again.get("/v1/sync", params={"since": 0}).json()["nextRev"] == rev


def test_a_file_store_is_still_usable_after_the_engine_is_disposed(tmp_path):
    store = sql_store.open_store(sqlite_url(tmp_path / "disposed.db"))
    store.add_feed("https://example.com/a", feed_of(ep(1)), 1)
    store.close()
    assert len(store.list_feeds()) == 1


def test_running_the_migrations_does_not_touch_the_apps_logging(tmp_path):
    logger = logging.getLogger("podclatch_server.refresh")
    before = (logging.getLogger().level, logger.disabled, logger.level)
    sql_store.open_store(sqlite_url(tmp_path / "log.db")).close()
    assert (logging.getLogger().level, logger.disabled, logger.level) == before


# --- startup failures never reveal DATABASE_URL ---------------------------------------------


def assert_clean(exc: BaseException, caplog, *secrets: str) -> None:
    assert "DATABASE_URL" in str(exc.value)
    shown = " ".join([str(exc.value), repr(exc.value), caplog.text])
    for secret in secrets:
        assert secret not in shown
    assert exc.value.__cause__ is None and exc.value.__suppress_context__


def test_unreachable_database_fails_clearly_without_the_url(caplog):
    url = f"postgresql://dbuser:{PASSWORD}@127.0.0.1:1/podclatch"
    with caplog.at_level(logging.DEBUG), pytest.raises(DatabaseStartupError) as exc:
        create_app(Settings(database_url=url, test_mode=True))
    assert_clean(exc, caplog, PASSWORD, "dbuser", "127.0.0.1")


def test_missing_directory_fails_clearly_without_the_url(tmp_path, caplog):
    url = sqlite_url(tmp_path / "no-such-dir" / "x.db")
    with caplog.at_level(logging.DEBUG), pytest.raises(DatabaseStartupError) as exc:
        create_app(Settings(database_url=url, test_mode=True))
    assert_clean(exc, caplog, "no-such-dir")


def test_a_path_that_cannot_be_opened_as_a_database_fails_clearly(tmp_path, caplog):
    url = sqlite_url(tmp_path)  # a directory, so it cannot be opened for writing
    with caplog.at_level(logging.DEBUG), pytest.raises(DatabaseStartupError) as exc:
        create_app(Settings(database_url=url, test_mode=True))
    assert_clean(exc, caplog, tmp_path.name)


def test_a_schema_newer_than_the_code_fails_clearly(tmp_path, caplog):
    url = sqlite_url(tmp_path / "future.db")
    sql_store.open_store(url).close()
    engine = create_db_engine(url)
    with engine.begin() as conn:
        conn.execute(text("UPDATE alembic_version SET version_num = 'made-up-from-the-future'"))
    engine.dispose()
    with caplog.at_level(logging.DEBUG), pytest.raises(DatabaseStartupError) as exc:
        create_app(Settings(database_url=url, test_mode=True))
    assert "newer" in str(exc.value)
    assert_clean(exc, caplog, "made-up-from-the-future", "future.db")


def test_failing_statements_do_not_print_parameters(tmp_path):
    engine = create_db_engine(sqlite_url(tmp_path / "params.db"))
    try:
        migrate(engine)
        secret = "https://example.com/feed.xml?token=SECRET123"
        with pytest.raises(Exception) as exc, engine.begin() as conn:
            conn.execute(insert(FeedRow).values(id=None, feed_url=secret))
        assert "SECRET123" not in str(exc.value) and "SECRET123" not in repr(exc.value)
    finally:
        engine.dispose()


# --- DATABASE_URL handling ----------------------------------------------------------------------


def test_settings_never_show_the_database_url():
    settings = load_settings({"DATABASE_URL": SECRET_URL, "PODCLATCH_TEST_MODE": "true"})
    assert PASSWORD not in repr(settings) and PASSWORD not in str(settings)


@pytest.mark.parametrize(
    "url",
    [
        "sqlite:///./x.db",
        "sqlite://",
        "sqlite:///:memory:",
        "postgresql://u:p@h/db",
        "postgres://u:p@h/db",
        "postgresql+psycopg://u:p@h/db",
    ],
)
def test_supported_schemes_are_accepted_without_connecting(url):
    assert Settings(database_url=url, test_mode=True).database_url == url


@pytest.mark.parametrize("url", ["mysql://u:hunter2@h/db", "not a url hunter2", "oracle+x://h"])
def test_other_schemes_are_rejected_naming_the_variable_not_the_value(url):
    with pytest.raises(ConfigError) as exc:
        Settings(database_url=url, test_mode=True)
    assert "DATABASE_URL" in str(exc.value)
    assert "hunter2" not in str(exc.value) and url not in str(exc.value)


@pytest.mark.parametrize("scheme", ["postgresql", "postgres", "postgresql+psycopg"])
def test_postgres_urls_use_the_psycopg_3_driver(scheme):
    url = to_sqlalchemy_url(f"{scheme}://u:p@h:5433/db")
    assert url.drivername == "postgresql+psycopg"
    assert (url.username, url.password, url.host, url.port, url.database) == (
        "u",
        "p",
        "h",
        5433,
        "db",
    )


def test_passwords_with_percent_or_at_signs_are_kept_intact():
    assert to_sqlalchemy_url("postgresql://u:p%40ss%25w@h/db").password == "p@ss%w"
    assert to_sqlalchemy_url("postgresql://u:p@ss@h/db").password == "p@ss"
    assert to_sqlalchemy_url("postgresql://u:50%off@h/db").password == "50%off"
    assert to_sqlalchemy_url("postgresql://u:p@[::1]:5432/db").host == "::1"
