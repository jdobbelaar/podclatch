"""LOG_LEVEL is applied to the app's own loggers by `create_app` (issue #50)."""

import contextlib
import functools
import io
import logging

import pytest
from fastapi.testclient import TestClient
from us25_helpers import AUTH, SECRET_URL, TOKEN, StubFetcher, ep, feed_of

from podclatch_server.app import create_app
from podclatch_server.config import Settings
from podclatch_server.logging_setup import ACCESS_LOGGER, RedactQueryFilter
from podclatch_server.routes.feeds import get_fetcher

APP_LOGGER = "podclatch_server"


@contextlib.contextmanager
def bare_root():
    """No handlers on the root logger (as under plain uvicorn) while the block runs.

    pytest attaches its capture handlers to the root logger when each phase opens, so this has
    to happen inside the test body, not in a fixture.
    """
    root = logging.getLogger()
    saved = root.handlers[:]
    root.handlers[:] = []
    try:
        yield
    finally:
        root.handlers[:] = saved


def bare(test):
    @functools.wraps(test)
    def wrapper(*args, **kwargs):
        with bare_root():
            return test(*args, **kwargs)

    return wrapper


def emit_all(name: str = "podclatch_server.scheduler") -> None:
    log = logging.getLogger(name)
    log.debug("rec-debug")
    log.info("rec-info")
    log.warning("rec-warning")
    log.error("rec-error")


def build(level: str | None = None):
    kwargs = {} if level is None else {"log_level": level}
    return create_app(Settings(token=TOKEN, test_mode=True, **kwargs))


@pytest.mark.parametrize(
    ("level", "emitted"),
    [
        (None, {"rec-info", "rec-warning", "rec-error"}),
        ("INFO", {"rec-info", "rec-warning", "rec-error"}),
        ("WARNING", {"rec-warning", "rec-error"}),
        ("DEBUG", {"rec-debug", "rec-info", "rec-warning", "rec-error"}),
        ("CRITICAL", set()),
        ("ERROR", {"rec-error"}),
    ],
)
@bare
def test_log_level_decides_which_records_are_emitted(capsys, level, emitted):
    build(level)
    emit_all()
    err = capsys.readouterr().err
    assert {w for w in ("rec-debug", "rec-info", "rec-warning", "rec-error") if w in err} == emitted


@bare
def test_output_uses_uvicorn_format(capsys):
    build()
    logging.getLogger("podclatch_server.x").warning("hello")
    assert capsys.readouterr().err.strip() == "WARNING:  hello"


@bare
def test_only_the_app_logger_is_touched():
    root_level = logging.getLogger().level
    uvicorn_level = logging.getLogger("uvicorn").level
    build("DEBUG")
    assert logging.getLogger().level == root_level
    assert logging.getLogger().handlers == []
    assert logging.getLogger("uvicorn").level == uvicorn_level


@bare
def test_applying_twice_leaves_one_handler_and_one_line(capsys):
    build()
    build()
    assert len(logging.getLogger(APP_LOGGER).handlers) == 1
    logging.getLogger("podclatch_server.x").info("once")
    assert capsys.readouterr().err.count("once") == 1


def test_existing_handlers_are_respected(caplog):
    # Under pytest the root logger already has a handler, so none is added and nothing doubles.
    build("WARNING")
    assert logging.getLogger(APP_LOGGER).handlers == []
    assert logging.getLogger(APP_LOGGER).level == logging.WARNING
    logging.getLogger("podclatch_server.x").info("dropped")
    logging.getLogger("podclatch_server.x").warning("kept")
    assert [r.getMessage() for r in caplog.records] == ["kept"]


@bare
def test_scheduler_lines_appear_once_each_at_the_default_level(capsys):
    app = create_app(Settings(token=TOKEN, refresh_interval_hours=1000))
    app.dependency_overrides[get_fetcher] = lambda: StubFetcher()
    with TestClient(app, headers=AUTH):
        pass
    err = capsys.readouterr().err
    assert err.count("refresh scheduler started") == 1
    assert err.count("refresh scheduler stopped") == 1


@bare
def test_debug_logging_leaks_no_secret_through_subscribe_and_refresh(capsys, tmp_path):
    secret_url = SECRET_URL + "&q=zebra-search-term"
    fetcher = StubFetcher()
    fetcher.results[secret_url] = feed_of(ep(1), ep(2))
    database_url = f"sqlite:///{(tmp_path / 'secret-db-marker.db').as_posix()}"
    app = create_app(
        Settings(token=TOKEN, test_mode=True, log_level="DEBUG", database_url=database_url)
    )
    app.dependency_overrides[get_fetcher] = lambda: fetcher
    with TestClient(app, headers=AUTH) as client:
        created = client.post("/v1/feeds", json={"feedUrl": secret_url})
        assert created.status_code == 201
        assert client.post("/v1/refresh").status_code == 200
        client.get("/v1/search", params={"term": "zebra-search-term"})
    err = capsys.readouterr().err
    assert err  # DEBUG output was captured, so the check below means something
    for forbidden in (TOKEN, "secret-db-marker", "SECRET123", "user:pw", "token=", "zebra-search"):
        assert forbidden not in err


def access_lines(level: int, *records_args) -> str:
    """Run access records (uvicorn's shape) through the real logger and a plain handler."""
    stream = io.StringIO()
    handler = logging.StreamHandler(stream)
    handler.setFormatter(logging.Formatter("%(message)s"))
    access = logging.getLogger(ACCESS_LOGGER)
    saved_level = access.level
    access.addHandler(handler)
    access.setLevel(level)
    try:
        for args in records_args:
            access.log(level, '%s - "%s %s HTTP/%s" %d', *args)
    finally:
        access.removeHandler(handler)
        access.setLevel(saved_level)
    return stream.getvalue()


@pytest.mark.parametrize("level", [logging.DEBUG, logging.INFO, logging.WARNING, logging.ERROR])
def test_access_log_drops_the_query_string_at_every_level(level):
    build()
    out = access_lines(
        level,
        ("1.2.3.4:5", "GET", "/v1/search?q=canary-term&x=1", "1.1", 200),
        ("1.2.3.4:5", "POST", "/v1/feeds?token=canary-token", "1.1", 401),
        ("1.2.3.4:5", "GET", "/v1/episodes", "1.1", 200),
    )
    assert 'GET /v1/search HTTP/1.1" 200' in out
    assert 'POST /v1/feeds HTTP/1.1" 401' in out
    assert 'GET /v1/episodes HTTP/1.1" 200' in out
    assert "canary" not in out
    assert "?" not in out


def test_access_filter_is_installed_once():
    build()
    build()
    access = logging.getLogger(ACCESS_LOGGER)
    assert len([f for f in access.filters if isinstance(f, RedactQueryFilter)]) == 1


@pytest.mark.parametrize(
    "args",
    [(), ("only",), ("a", "b", 3, "d", 5), ("a", "b", "/p?q=1"), {"k": "v"}, None],
)
def test_access_filter_passes_unexpected_shapes_through(args):
    record = logging.LogRecord("uvicorn.access", logging.INFO, "f", 1, "msg", None, None)
    record.args = args
    assert RedactQueryFilter().filter(record) is True
    assert record.args == args


EDGE_PATHS = [
    ("GET", "/healthz?canary=CANARY", "/healthz"),
    ("GET", "/v1/sync?since=0&limit=1&canary=CANARY", "/v1/sync"),
    ("GET", "/v1/search?q=CANARY", "/v1/search"),
    ("GET", "/v1/feeds?canary=CANARY", "/v1/feeds"),
    ("GET", "/no/such/path?canary=CANARY", "/no/such/path"),
    ("GET", "/x%3Fcanary=KEPT", "/x%3Fcanary=KEPT"),  # an encoded ? is path, not a query
    ("GET", "/v1/feeds?", "/v1/feeds"),
    ("POST", "/v1/feeds?a=CANARY&a=CANARY2&a=3", "/v1/feeds"),
    ("GET", "/v1/search?q=%43ANARY%26x%3D1", "/v1/search"),
    ("GET", "/v1/search?q=" + "CANARY" * 2000, "/v1/search"),  # several KB
]


@pytest.mark.parametrize(("method", "target", "expected"), EDGE_PATHS)
def test_access_log_edge_cases(method, target, expected):
    build()
    out = access_lines(logging.INFO, ("1.2.3.4:5", method, target, "1.1", 404))
    assert out.strip() == f'1.2.3.4:5 - "{method} {expected} HTTP/1.1" 404'
    assert "CANARY" not in out


@pytest.mark.parametrize(
    ("target", "expected"),
    [
        ("/healthz?canary=CANARY", "/healthz"),
        ("/v1/sync?since=0&canary=CANARY", "/v1/sync"),
        ("/v1/search?q=CANARY", "/v1/search"),
        ("/v1/feeds?canary=CANARY", "/v1/feeds"),
        ("/no/such/path?canary=CANARY", "/no/such/path"),
    ],
)
def test_requests_through_the_real_app_are_logged_without_the_query(target, expected):
    app = build()
    with TestClient(app, headers=AUTH) as client:
        status = client.get(target).status_code
    out = access_lines(logging.INFO, ("testclient:1", "GET", target, "1.1", status))
    assert out.strip() == f'testclient:1 - "GET {expected} HTTP/1.1" {status}'
    assert "CANARY" not in out
