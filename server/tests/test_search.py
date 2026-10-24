from __future__ import annotations

import json
import logging
from pathlib import Path

import httpx
import pytest
from fastapi.testclient import TestClient

from podclatch_server import search as search_module
from podclatch_server.app import create_app
from podclatch_server.config import Settings
from podclatch_server.search import SearchService

FIXTURES = Path(__file__).parent / "fixtures"
TOKEN = "test-token-1"
AUTH = {"Authorization": f"Bearer {TOKEN}"}
BAD_GATEWAY = {"detail": "The podcast directory is unavailable. Try again later."}
ITUNES_JSON = {"content-type": "text/javascript; charset=utf-8"}
SECRET_TERM = "zq-secret-term"


def fixture_bytes() -> bytes:
    return (FIXTURES / "itunes_search.json").read_bytes()


class Clock:
    def __init__(self) -> None:
        self.now = 1000.0

    def __call__(self) -> float:
        return self.now


class Upstream:
    """A stubbed iTunes: records requests and answers with `handler` (default: the fixture)."""

    def __init__(self, handler=None) -> None:
        self.requests: list[httpx.Request] = []
        self.handler = handler or (
            lambda request: httpx.Response(200, content=fixture_bytes(), headers=ITUNES_JSON)
        )

    def __call__(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        return self.handler(request)

    @property
    def calls(self) -> int:
        return len(self.requests)


def make(handler=None):
    upstream = Upstream(handler)
    clock = Clock()
    app = create_app(Settings(token=TOKEN, test_mode=True))
    client = httpx.Client(
        transport=httpx.MockTransport(upstream), follow_redirects=True, max_redirects=10
    )
    app.state.search_service = SearchService(client=client, clock=clock)
    return TestClient(app), upstream, clock, app


def results_response(count: int) -> httpx.Response:
    items = [
        {"feedUrl": f"https://example.com/f{i}.xml", "collectionName": f"P{i}"}
        for i in range(count)
    ]
    return httpx.Response(200, json={"resultCount": count, "results": items}, headers=ITUNES_JSON)


def get(client, q, headers=AUTH):
    return client.get("/v1/search", params={"q": q}, headers=headers)


# ---- request to iTunes ----


def test_request_shape():
    client, upstream, _, _ = make()
    assert get(client, "History").status_code == 200
    assert upstream.calls == 1
    request = upstream.requests[0]
    assert request.method == "GET"
    assert request.url.host == "itunes.apple.com"
    assert request.url.scheme == "https"
    assert request.url.path == "/search"
    assert dict(request.url.params) == {"media": "podcast", "term": "history", "limit": "25"}


@pytest.mark.parametrize(
    "q",
    [
        "a b",
        "a&b",
        "a+b",
        "a#b",
        "a%b",
        "a=b",
        "café",
        "日本語 ポッドキャスト",
        "😀 pod",
        "a&limit=500",
        "x&media=music",
    ],
)
def test_term_is_encoded_by_the_client(q):
    client, upstream, _, _ = make()
    assert get(client, q).status_code == 200
    request = upstream.requests[0]
    assert request.url.params.get("term") == q.lower()
    assert request.url.params.get_list("term") == [q.lower()]
    assert request.url.params.get_list("media") == ["podcast"]
    assert request.url.params.get_list("limit") == ["25"]
    assert request.url.params.get("media") == "podcast"
    assert request.url.params.get("limit") == "25"


def test_outbound_limits_are_the_documented_ones():
    seen = {}

    def handler(request):
        seen["timeout"] = request.extensions["timeout"]
        return httpx.Response(200, json={"results": []})

    client, _, _, _ = make(handler)
    get(client, "x")
    assert seen["timeout"]["read"] == 15.0
    assert seen["timeout"]["connect"] == 15.0


def test_default_client_uses_limits():
    from podclatch_server.feeds import make_client

    c = make_client()
    assert c.follow_redirects is True
    assert c.max_redirects == 10
    assert c.timeout.read == 15.0


def test_redirects_are_followed():
    def handler(request):
        if request.url.path == "/search":
            return httpx.Response(
                302, headers={"location": "https://itunes.apple.com/search2?term=x"}
            )
        return httpx.Response(200, content=fixture_bytes(), headers=ITUNES_JSON)

    client, upstream, _, _ = make(handler)
    assert get(client, "x").status_code == 200
    assert upstream.calls == 2


# ---- response mapping ----


def test_mapping_of_the_fixture():
    client, _, _, _ = make()
    response = get(client, "history")
    assert response.status_code == 200
    assert response.json() == [
        {
            "feedUrl": "https://example.com/feeds/history.xml",
            "title": "Example History Podcast",
            "author": "Example Media",
            "artworkUrl": "https://example.com/art/history-600.jpg",
        },
        {
            "feedUrl": "https://example.com/feeds/sparse.xml",
            "title": "",
            "author": "",
            "artworkUrl": "",
        },
        {
            "feedUrl": "https://example.com/feeds/extras.xml",
            "title": "Extras Podcast",
            "author": "Extra Author",
            "artworkUrl": "https://example.com/art/extras-600.jpg",
        },
    ]
    for item in response.json():
        assert set(item) == {"feedUrl", "title", "author", "artworkUrl"}


def test_truncates_to_25_in_order():
    client, _, _, _ = make(lambda r: results_response(30))
    body = get(client, "x").json()
    assert [i["title"] for i in body] == [f"P{i}" for i in range(25)]


@pytest.mark.parametrize("feed_url", [None, 5, "", "   ", ["x"], {"a": 1}])
def test_bad_feed_url_is_skipped(feed_url):
    payload = {
        "results": [
            {"feedUrl": feed_url, "collectionName": "Bad"},
            {"feedUrl": "https://example.com/ok.xml", "collectionName": "Ok"},
        ]
    }
    client, _, _, _ = make(lambda r: httpx.Response(200, json=payload))
    assert [i["title"] for i in get(client, "x").json()] == ["Ok"]


def test_missing_feed_url_key_and_non_objects_are_skipped():
    payload = {
        "results": [
            {"collectionName": "NoUrl"},
            1,
            None,
            "s",
            [],
            {"feedUrl": "https://example.com/a.xml"},
        ]
    }
    client, _, _, _ = make(lambda r: httpx.Response(200, json=payload))
    assert get(client, "x").json() == [
        {"feedUrl": "https://example.com/a.xml", "title": "", "author": "", "artworkUrl": ""}
    ]


@pytest.mark.parametrize("value", [None, 7, ["a"], {"a": 1}, True])
def test_non_string_optional_fields_become_empty(value):
    payload = {
        "results": [
            {
                "feedUrl": "https://example.com/a.xml",
                "collectionName": value,
                "artistName": value,
                "artworkUrl600": value,
            }
        ]
    }
    client, _, _, _ = make(lambda r: httpx.Response(200, json=payload))
    assert get(client, "x").json() == [
        {"feedUrl": "https://example.com/a.xml", "title": "", "author": "", "artworkUrl": ""}
    ]


def test_empty_upstream_result_is_empty_list():
    client, _, _, _ = make(lambda r: httpx.Response(200, json={"resultCount": 0, "results": []}))
    response = get(client, "x")
    assert response.status_code == 200
    assert response.json() == []


# ---- query rules ----


@pytest.mark.parametrize("params", [{}, {"q": ""}])
def test_missing_or_empty_q_is_422(params):
    client, upstream, _, _ = make()
    response = client.get("/v1/search", params=params, headers=AUTH)
    assert response.status_code == 422
    assert set(response.json()) == {"detail"}
    assert isinstance(response.json()["detail"], str)
    assert upstream.calls == 0


@pytest.mark.parametrize("q", ["  ", "\t", " \t \n ", " "])
def test_blank_q_is_422_with_string_detail(q):
    client, upstream, _, _ = make()
    response = get(client, q)
    assert response.status_code == 422
    assert isinstance(response.json()["detail"], str)
    assert upstream.calls == 0


def test_q_length_limit():
    client, upstream, _, _ = make()
    too_long = get(client, "a" * 201)
    assert too_long.status_code == 422
    assert isinstance(too_long.json()["detail"], str)
    assert upstream.calls == 0
    assert get(client, "a" * 200).status_code == 200
    assert get(client, "  " + "b" * 200 + "  ").status_code == 200  # trimmed before measuring
    assert upstream.calls == 2


def test_normalization_shares_one_cache_entry():
    client, upstream, _, _ = make()
    for q in ["Foo", "foo", " foo ", "foo", "FOO\t", "Foo"]:
        assert get(client, q).status_code == 200
    assert upstream.calls == 1
    assert upstream.requests[0].url.params["term"] == "foo"
    get(client, "foo  bar")
    get(client, "foo\tbar")
    get(client, " Foo Bar ")
    assert upstream.calls == 2
    assert upstream.requests[1].url.params["term"] == "foo bar"


# ---- cache ----


def test_cache_hit_returns_same_body_without_upstream_call():
    client, upstream, _, _ = make()
    first = get(client, "history")
    second = get(client, "history")
    assert upstream.calls == 1
    assert first.json() == second.json()


def test_ttl_boundary():
    client, upstream, clock, _ = make()
    get(client, "x")
    clock.now += 599.9
    get(client, "x")
    assert upstream.calls == 1
    clock.now = 1000.0 + 600.0
    get(client, "x")
    assert upstream.calls == 2
    clock.now += 700
    get(client, "x")
    assert upstream.calls == 3


def test_different_terms_cached_independently():
    client, upstream, _, _ = make()
    get(client, "a")
    get(client, "b")
    get(client, "a")
    get(client, "b")
    assert upstream.calls == 2


def test_cache_is_capped_at_100_and_evicts_oldest():
    client, upstream, clock, app = make(lambda r: results_response(1))
    for i in range(101):
        clock.now += 1
        get(client, f"term{i}")
    assert upstream.calls == 101
    assert app.state.search_service.cache_size() == 100
    get(client, "term100")  # newest still cached
    assert upstream.calls == 101
    get(client, "term0")  # oldest was evicted
    assert upstream.calls == 102
    assert app.state.search_service.cache_size() == 100


def test_full_cache_drops_expired_before_live_entries():
    client, upstream, clock, app = make(lambda r: results_response(1))
    get(client, "old-but-hot")
    clock.now += 500
    for i in range(99):
        get(client, f"t{i}")
    clock.now += 200  # "old-but-hot" is 700 s old, expired; the rest are 200 s old
    get(client, "newest")
    assert app.state.search_service.cache_size() == 100
    calls = upstream.calls
    get(client, "t0")  # oldest *live* entry must survive
    assert upstream.calls == calls


def test_empty_result_is_cached():
    client, upstream, _, _ = make(lambda r: httpx.Response(200, json={"results": []}))
    assert get(client, "x").json() == []
    assert get(client, "x").json() == []
    assert upstream.calls == 1


def test_cached_list_is_not_shared_with_callers():
    client, _, _, app = make()
    service: SearchService = app.state.search_service
    first = service.search("history")
    first.clear()
    assert len(service.search("history")) == 3
    second = service.search("history")
    second[0]["title"] = "mutated"
    assert service.search("history")[0]["title"] == "Example History Podcast"


def test_cache_is_per_app_instance():
    a, upstream_a, _, _ = make()
    b, upstream_b, _, _ = make()
    get(a, "x")
    get(b, "x")
    assert upstream_a.calls == 1
    assert upstream_b.calls == 1


# ---- failures ----


def _raise(exc):
    def handler(request):
        raise exc

    return handler


def _big_chunks():
    def chunks():
        for _ in range(11):
            yield b" " * (1024 * 1024)

    return chunks()


FAILURES = {
    "connect-error": _raise(httpx.ConnectError("boom itunes.apple.com")),
    "dns-error": _raise(httpx.ConnectError("[Errno -2] Name or service not known")),
    "timeout": _raise(httpx.ReadTimeout("slow")),
    "connect-timeout": _raise(httpx.ConnectTimeout("slow")),
    "redirect-loop": lambda r: httpx.Response(
        302, headers={"location": "https://itunes.apple.com/search"}
    ),
    "status-400": lambda r: httpx.Response(400),
    "status-403": lambda r: httpx.Response(403),
    "status-404": lambda r: httpx.Response(404),
    "status-429": lambda r: httpx.Response(429),
    "status-500": lambda r: httpx.Response(500),
    "status-503": lambda r: httpx.Response(503, json={"results": []}),
    "non-json": lambda r: httpx.Response(200, content=b"<html>oops</html>", headers=ITUNES_JSON),
    "bad-utf8": lambda r: httpx.Response(200, content=b"\xff\xfe\x00"),
    "json-array": lambda r: httpx.Response(200, json=[1, 2]),
    "json-string": lambda r: httpx.Response(200, json="x"),
    "missing-results": lambda r: httpx.Response(200, json={"resultCount": 0}),
    "results-not-list": lambda r: httpx.Response(200, json={"results": {"a": 1}}),
    "too-large-declared": lambda r: httpx.Response(
        200, content=b"{}", headers={"content-length": str(11 * 1024 * 1024)}
    ),
    "too-large-streamed": lambda r: httpx.Response(200, content=_big_chunks()),
}


@pytest.mark.parametrize("name", list(FAILURES))
def test_upstream_failure_is_502_and_not_cached(name, caplog):
    caplog.set_level(logging.DEBUG)
    failing = {"on": True}

    def handler(request):
        if failing["on"]:
            return FAILURES[name](request)
        return httpx.Response(200, content=fixture_bytes(), headers=ITUNES_JSON)

    client, upstream, _, _ = make(handler)
    response = get(client, SECRET_TERM)
    assert response.status_code == 502
    assert response.json() == BAD_GATEWAY
    assert SECRET_TERM not in response.text
    assert "itunes" not in response.text.lower()
    assert upstream.calls >= 1
    calls = upstream.calls

    warnings = [
        r
        for r in caplog.records
        if r.levelno >= logging.WARNING and r.name.startswith("podclatch_server")
    ]
    assert len(warnings) == 1
    for record in caplog.records:
        message = record.getMessage()
        assert SECRET_TERM not in message
        assert "itunes.apple.com" not in message
        assert "boom" not in message

    failing["on"] = False  # not cached: the next request calls iTunes again
    assert get(client, SECRET_TERM).status_code == 200
    assert upstream.calls == calls + 1


def test_success_does_not_log_the_term(caplog):
    caplog.set_level(logging.DEBUG)
    client, _, _, _ = make()
    get(client, SECRET_TERM)
    get(client, SECRET_TERM)
    for record in caplog.records:
        assert SECRET_TERM not in record.getMessage()
        assert "itunes.apple.com" not in record.getMessage()


def test_httpx_logger_does_not_emit_request_urls():
    assert logging.getLogger("httpx").getEffectiveLevel() >= logging.WARNING
    assert search_module.logging.getLogger("httpx") is logging.getLogger("httpx")


def test_exactly_ten_mb_is_accepted():
    body = b'{"results": []}'
    padded = body + b" " * (10 * 1024 * 1024 - len(body))
    client, _, _, _ = make(lambda r: httpx.Response(200, content=padded))
    assert get(client, "x").status_code == 200


# ---- authentication ----


@pytest.mark.parametrize(
    "headers",
    [{}, {"Authorization": "Bearer wrong"}, {"Authorization": "Basic abc"}],
)
@pytest.mark.parametrize("q", ["fine", None, "", " ", "a" * 300])
def test_unauthenticated_gets_401_before_anything_else(headers, q):
    client, upstream, _, _ = make()
    params = {} if q is None else {"q": q}
    response = client.get("/v1/search", params=params, headers=headers)
    assert response.status_code == 401
    assert response.headers["WWW-Authenticate"] == "Bearer"
    assert response.json() == {"detail": "Not authenticated."}
    assert upstream.calls == 0


def test_default_service_is_created_lazily_once():
    app = create_app(Settings(token=TOKEN, test_mode=True))
    assert not hasattr(app.state, "search_service")
    client = TestClient(app)
    assert client.get("/v1/search", params={"q": " "}, headers=AUTH).status_code == 422
    service = app.state.search_service
    client.get("/v1/search", params={"q": " "}, headers=AUTH)
    assert app.state.search_service is service


def test_fixture_is_valid_json_with_example_urls_only():
    data = json.loads(fixture_bytes())
    text = json.dumps(data)
    assert "example.com" in text
    assert "itunes" not in text


# ---- ITUNES_SEARCH_URL (issue #51) ----

CANARY_URL = "http://canary-itunes.invalid/secret-path/search"


def app_with_url(url):
    return create_app(Settings(token=TOKEN, test_mode=True, itunes_search_url=url))


def stub_client(monkeypatch, upstream):
    """The lazily created SearchService builds its client with make_client; hand it a stub."""
    monkeypatch.setattr(
        search_module,
        "make_client",
        lambda: httpx.Client(transport=httpx.MockTransport(upstream), follow_redirects=True),
    )


def test_default_url_is_the_real_itunes_when_unset(monkeypatch):
    upstream = Upstream()
    stub_client(monkeypatch, upstream)
    client = TestClient(create_app(Settings(token=TOKEN, test_mode=True)))
    assert get(client, "history").status_code == 200
    assert str(upstream.requests[0].url).startswith("https://itunes.apple.com/search?")


def test_configured_url_is_the_one_called(monkeypatch):
    upstream = Upstream()
    stub_client(monkeypatch, upstream)
    client = TestClient(app_with_url("http://feedhost:8080/search"))
    assert get(client, "history").status_code == 200
    request = upstream.requests[0]
    assert (request.url.scheme, request.url.host, request.url.port) == ("http", "feedhost", 8080)
    assert request.url.path == "/search"
    assert dict(request.url.params) == {"media": "podcast", "term": "history", "limit": "25"}


def test_search_service_url_argument_is_used():
    upstream = Upstream()
    client = httpx.Client(transport=httpx.MockTransport(upstream))
    SearchService(client=client, url="https://example.invalid/dir").search("history")
    assert upstream.requests[0].url.host == "example.invalid"
    assert upstream.requests[0].url.path == "/dir"


def test_canary_url_and_term_are_never_logged_at_debug(monkeypatch, caplog):
    def failing(request):
        return httpx.Response(500, content=b"canary-body")

    for handler in (None, failing):
        upstream = Upstream(handler)
        stub_client(monkeypatch, upstream)
        client = TestClient(app_with_url(CANARY_URL))
        caplog.clear()
        caplog.set_level(logging.DEBUG)
        get(client, SECRET_TERM)
        assert upstream.calls == 1
        assert upstream.requests[0].url.host == "canary-itunes.invalid"
        for record in caplog.records:
            text = record.getMessage()
            for canary in (SECRET_TERM, "canary-itunes", "secret-path", "canary-body"):
                assert canary not in text


def test_compose_file_for_normal_use_does_not_set_the_variable():
    compose = Path(__file__).parents[2] / "docker-compose.yaml"
    if not compose.is_file():
        pytest.skip("the repository root is not available (tests run in the container)")
    assert "ITUNES_SEARCH_URL" not in compose.read_text(encoding="utf-8")
