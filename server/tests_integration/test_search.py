"""Search through the fake iTunes host (issue #51; spec 5.2, decisions #24 to #27).

The server container reads ITUNES_SEARCH_URL from docker-compose.integration.yaml and calls the
fake host's `/search`. Each test starts from `POST /_control/reset`. The server keeps its own
10-minute cache that the reset does not touch, so every test uses a term that is unique to it.
"""

from __future__ import annotations

import json
import uuid

import harness
import httpx
import pytest

BAD_GATEWAY = {"detail": "The podcast directory is unavailable. Try again later."}


class FakeSearch:
    """The fake host's search control endpoints, reached from the test process."""

    def _url(self, path: str) -> str:
        return f"http://{harness.HOST}:{harness.feedhost_port()}{path}"

    def reset(self) -> None:
        assert (
            httpx.post(self._url("/_control/reset"), timeout=10.0, trust_env=False).status_code
            == 204
        )

    def serve(self, results: list[dict[str, str]]) -> None:
        body = json.dumps({"resultCount": len(results), "results": results})
        response = httpx.put(
            self._url("/_control/search.json"),
            content=body.encode("utf-8"),
            timeout=10.0,
            trust_env=False,
        )
        assert response.status_code == 204

    def fail(self, on: bool) -> None:
        response = httpx.post(
            self._url("/_control/search-fail"),
            content=b"true" if on else b"false",
            timeout=10.0,
            trust_env=False,
        )
        assert response.status_code == 204

    def count(self) -> int:
        response = httpx.get(self._url("/_control/search-count"), timeout=10.0, trust_env=False)
        assert response.status_code == 200
        return int(response.text)


@pytest.fixture
def fake_search() -> FakeSearch:
    fake = FakeSearch()
    fake.reset()
    return fake


def new_term() -> str:
    return f"term{uuid.uuid4().hex}"  # made up by the test; unique so the server cache is cold


def itunes_entries(n: int) -> list[dict[str, str]]:
    return [
        {
            "collectionName": f"Show {i}",
            "artistName": f"Author {i}",
            "feedUrl": f"http://feedhost:8080/feeds/show-{i}.xml",
            "artworkUrl600": f"http://feedhost:8080/art/{i}.jpg",
        }
        for i in range(n)
    ]


def server_logs() -> str:
    return harness.compose("logs", "--no-color", "server").stdout


def assert_term_not_in_logs(term: str) -> None:
    """The term appears nowhere in the server logs, the access log's request line included."""
    logs = server_logs()
    assert logs.strip()  # only meaningful if the server logged something
    assert term not in logs


def test_search_maps_results_and_caps_at_25(api, fake_search):
    term = new_term()
    fake_search.serve(itunes_entries(30))

    response = api.get("/v1/search", params={"q": term})

    assert response.status_code == 200
    results = response.json()
    assert len(results) == 25
    assert results[0] == {
        "feedUrl": "http://feedhost:8080/feeds/show-0.xml",
        "title": "Show 0",
        "author": "Author 0",
        "artworkUrl": "http://feedhost:8080/art/0.jpg",
    }
    assert fake_search.count() == 1
    assert_term_not_in_logs(term)


def test_second_identical_search_comes_from_the_cache(api, fake_search):
    term = new_term()
    fake_search.serve(itunes_entries(3))

    first = api.get("/v1/search", params={"q": term})
    second = api.get("/v1/search", params={"q": term})

    assert first.status_code == second.status_code == 200
    assert first.json() == second.json()
    assert len(first.json()) == 3
    assert fake_search.count() == 1
    assert_term_not_in_logs(term)


def test_directory_failure_is_502(api, fake_search):
    term = new_term()
    fake_search.fail(True)

    response = api.get("/v1/search", params={"q": term})

    assert response.status_code == 502
    assert response.json() == BAD_GATEWAY
    assert fake_search.count() == 1
    assert term not in response.text
    assert_term_not_in_logs(term)


def test_search_without_the_token_is_401_and_never_reaches_the_host(api, fake_search):
    response = api.get("/v1/search", params={"q": new_term()}, token=None)

    assert response.status_code == 401
    assert fake_search.count() == 0
