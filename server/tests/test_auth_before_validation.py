"""401 comes before body and parameter validation on every protected route (issue #6 round 2)."""

import re

import pytest
from fastapi.routing import APIRoute
from fastapi.testclient import TestClient
from pydantic import BaseModel

from podclatch_server.app import create_app
from podclatch_server.config import Settings

TOKEN = "test-token-1"
AUTH = {"Authorization": f"Bearer {TOKEN}"}
WRONG = {"Authorization": "Bearer wrong-token"}
JSON_HEADERS = {"Content-Type": "application/json"}
SECRET = "SECRET123"


@pytest.fixture
def app():
    return create_app(Settings(token=TOKEN, test_mode=True))


def protected_routes(app) -> list[tuple[str, str, bool]]:
    """(method, path, takes_body) for every real route except /healthz, from the live tree."""
    found: list[tuple[str, str, bool]] = []

    def walk(routes, prefix=""):
        for route in routes:
            original = getattr(route, "original_router", None)
            if original is not None:  # an included router, wrapped by FastAPI
                context = getattr(route, "include_context", None)
                walk(original.routes, prefix + getattr(context, "prefix", ""))
                continue
            if not isinstance(route, APIRoute) or prefix + route.path == "/healthz":
                continue
            for method in sorted(route.methods - {"HEAD", "OPTIONS"}):
                found.append((method, prefix + route.path, route.body_field is not None))

    walk(app.routes)
    return found


def concrete(path: str, value: str = "abc") -> str:
    return re.sub(r"\{[^}]+\}", value, path)


def assert_401(response):
    assert response.status_code == 401
    assert response.headers["WWW-Authenticate"] == "Bearer"
    assert response.json() == {"detail": "Not authenticated."}


def test_the_walk_finds_the_known_body_routes(app):
    routes = {(m, p) for m, p, body in protected_routes(app) if body}
    assert {
        ("POST", "/v1/feeds"),
        ("PUT", "/v1/feeds/order"),
        ("PATCH", "/v1/feeds/{id}"),
        ("POST", "/v1/episodes/state"),
    } <= routes


@pytest.mark.parametrize("headers", [{}, WRONG], ids=["no-token", "wrong-token"])
def test_every_protected_route_is_401_for_any_malformed_request(app, headers):
    client = TestClient(app)
    routes = protected_routes(app)
    assert routes
    for method, path, _takes_body in routes:
        url = concrete(path)
        attempts = [
            {"url": url, "headers": headers},
            {"url": url + "?since=abc&limit=0&q=", "headers": headers},
            {"url": url, "headers": {**headers, **JSON_HEADERS}, "content": b"{bad json"},
            {"url": url, "headers": {**headers, **JSON_HEADERS}, "content": b'{"a": 1}'},
            {"url": url, "headers": {**headers, **JSON_HEADERS}, "content": b'"' + SECRET.encode()},
            {"url": url, "headers": {**headers, **JSON_HEADERS}, "content": b"[1, 2"},
        ]
        for attempt in attempts:
            response = client.request(method, **attempt)
            assert_401(response)
            assert SECRET not in response.text


@pytest.mark.parametrize(
    "method,path",
    [
        ("POST", "/v1/feeds"),
        ("PUT", "/v1/feeds/order"),
        ("PATCH", "/v1/feeds/1"),
        ("POST", "/v1/episodes/state"),
    ],
)
def test_malformed_json_is_401_without_a_valid_token_and_422_with_one(app, method, path):
    client = TestClient(app)
    for headers in ({}, WRONG):
        assert_401(client.request(method, path, headers={**headers, **JSON_HEADERS}, content=b"{"))
    response = client.request(method, path, headers={**AUTH, **JSON_HEADERS}, content=b"{")
    assert response.status_code == 422
    assert set(response.json()) == {"detail"} and isinstance(response.json()["detail"], str)


def test_valid_token_still_gets_422_for_other_bad_requests(app):
    client = TestClient(app, headers=AUTH)
    for response in (
        client.post("/v1/feeds", json={}),
        client.post("/v1/episodes/state"),
        client.get("/v1/sync?limit=0"),
        client.get("/v1/feeds/abc/episodes"),
        client.delete("/v1/feeds/abc"),
        client.get("/v1/search"),
    ):
        assert response.status_code == 422
        assert isinstance(response.json()["detail"], str)


def test_healthz_404_and_405_are_unaffected(app):
    client = TestClient(app)
    assert client.get("/healthz").status_code == 200
    assert client.get("/nowhere").status_code == 404
    response = client.get("/v1/refresh")
    assert response.status_code == 405 and "WWW-Authenticate" not in response.headers
    assert client.delete("/v1/episodes/state", headers=WRONG).status_code == 405


def test_a_route_without_require_token_keeps_its_422(app):
    class Item(BaseModel):
        n: int

    @app.post("/open")
    def open_route(item: Item) -> dict[str, int]:
        return {"n": item.n}

    response = TestClient(app).post("/open", headers=JSON_HEADERS, content=b"{")
    assert response.status_code == 422


def test_the_401_path_never_logs_the_token_or_body(app, caplog):
    caplog.set_level("DEBUG")
    TestClient(app).post(
        "/v1/episodes/state",
        headers={"Authorization": f"Bearer {SECRET}", **JSON_HEADERS},
        content=b"{" + SECRET.encode(),
    )
    assert SECRET not in "\n".join(r.getMessage() for r in caplog.records)
