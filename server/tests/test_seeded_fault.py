"""Proves the deliberate fault on the demo/seeded-fault branch is real (issue #118).

This file exists only on that branch. A fractional-seconds <itunes:duration> makes parse_feed
raise an unhandled ValueError, so POST /v1/feeds answers 500.
"""

from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from podclatch_server.app import create_app
from podclatch_server.config import Settings
from podclatch_server.feeds import _parse_duration, parse_feed
from podclatch_server.routes.feeds import get_fetcher

FIXTURE = Path(__file__).parent / "fixtures" / "seeded_fault_fractional_duration.xml"
AUTH = {"Authorization": "Bearer test-token-1"}
BODY = {"feedUrl": "http://example.invalid/feed.xml"}


def _app():
    app = create_app(Settings(token="test-token-1", test_mode=True))
    app.dependency_overrides[get_fetcher] = lambda: lambda url: parse_feed(FIXTURE.read_bytes())
    return app


def test_parse_feed_raises_naming_field_and_shape():
    with pytest.raises(ValueError, match=r"itunes:duration.*fractional seconds.*01:02:03\.500"):
        parse_feed(FIXTURE.read_bytes())


def test_subscribe_answers_500():
    client = TestClient(_app(), headers=AUTH, raise_server_exceptions=False)
    assert client.post("/v1/feeds", json=BODY).status_code == 500


def test_exception_escapes_the_app_so_the_server_logs_it():
    # uvicorn logs whatever escapes the app. With raise_server_exceptions the same exception
    # surfaces here, so this is the exception the log line and the trace will carry.
    client = TestClient(_app(), headers=AUTH)
    with pytest.raises(ValueError, match="itunes:duration"):
        client.post("/v1/feeds", json=BODY)


def test_ordinary_durations_are_unaffected():
    assert _parse_duration("12:34") == 754
    assert _parse_duration("abc") is None
