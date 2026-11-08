"""Regression tests for the fractional-seconds <itunes:duration> 500 (issue #118).

A bad duration must not fail the request: the episode is kept with no duration.
"""

from pathlib import Path

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


def test_fractional_duration_is_none_and_episode_kept():
    feed = parse_feed(FIXTURE.read_bytes())
    assert [e.duration_sec for e in feed.episodes] == [754, None]


def test_subscribe_does_not_answer_500():
    client = TestClient(_app(), headers=AUTH, raise_server_exceptions=False)
    assert client.post("/v1/feeds", json=BODY).status_code < 500


def test_ordinary_durations_are_unaffected():
    assert _parse_duration("12:34") == 754
    assert _parse_duration("01:02:03.500") is None
    assert _parse_duration("abc") is None
