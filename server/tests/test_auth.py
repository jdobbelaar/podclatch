import logging
import secrets

import pytest
from fastapi import APIRouter, Depends
from fastapi.testclient import TestClient
from pydantic import BaseModel

from podclatch_server import auth
from podclatch_server.app import create_app
from podclatch_server.auth import require_token
from podclatch_server.config import Settings

TOKEN = "test-token-1"


class Body(BaseModel):
    count: int


def build_app(token: str | None = TOKEN):
    app = create_app(Settings(token=token, test_mode=True))
    router = APIRouter(dependencies=[Depends(require_token)])

    @router.get("/protected")
    def protected() -> dict[str, str]:
        return {"ok": "yes"}

    @router.post("/protected-body")
    def protected_body(body: Body, limit: int = 1) -> dict[str, int]:
        return {"count": body.count}

    app.include_router(router)
    return app


@pytest.fixture
def app():
    return build_app()


@pytest.fixture
def client(app) -> TestClient:
    return TestClient(app)


def assert_401(response):
    assert response.status_code == 401
    assert response.headers["WWW-Authenticate"] == "Bearer"
    assert response.json() == {"detail": "Not authenticated."}


BAD_HEADERS = [
    pytest.param(None, id="missing"),
    pytest.param("Basic abc", id="basic"),
    pytest.param(TOKEN, id="bare-token"),
    pytest.param("Bearer", id="bearer-alone"),
    pytest.param("Bearer ", id="bearer-empty"),
    pytest.param("Bearer    ", id="bearer-spaces"),
    pytest.param("Bearer wrong", id="wrong"),
    pytest.param(f"Bearer {TOKEN[:-1]}", id="prefix-of-correct"),
    pytest.param(f"Bearer {TOKEN}x", id="correct-is-prefix"),
    pytest.param("Bearer tökén-ünïcode-✓", id="non-ascii"),
    pytest.param(f"Bearer {TOKEN[:5]} {TOKEN[5:]}", id="inner-whitespace"),
]


@pytest.mark.parametrize("header", BAD_HEADERS)
def test_rejected_with_401(client, header):
    # Send bytes so non-ASCII header values reach the server as raw UTF-8.
    headers = {} if header is None else {"Authorization": header.encode("utf-8")}
    assert_401(client.get("/protected", headers=headers))


def test_correct_token_reaches_route(client):
    response = client.get("/protected", headers={"Authorization": f"Bearer {TOKEN}"})
    assert response.status_code == 200
    assert response.json() == {"ok": "yes"}


@pytest.mark.parametrize("scheme", ["Bearer", "bearer", "BEARER"])
def test_scheme_is_case_insensitive(client, scheme):
    response = client.get("/protected", headers={"Authorization": f"{scheme} {TOKEN}"})
    assert response.status_code == 200


@pytest.mark.parametrize(
    "value",
    [f" Bearer {TOKEN}", f"Bearer {TOKEN} ", f"Bearer    {TOKEN}", f"  Bearer   {TOKEN}  "],
)
def test_surrounding_whitespace_tolerated(client, value):
    assert client.get("/protected", headers={"Authorization": value}).status_code == 200


def test_uses_compare_digest_even_when_lengths_differ(client, monkeypatch):
    calls = []
    real = secrets.compare_digest

    def spy(a, b):
        calls.append((a, b))
        return real(a, b)

    monkeypatch.setattr(auth.secrets, "compare_digest", spy)
    assert_401(client.get("/protected", headers={"Authorization": "Bearer short"}))
    assert calls == [(b"short", TOKEN.encode())]
    assert len(calls[0][0]) != len(calls[0][1])
    calls.clear()
    assert client.get("/protected", headers={"Authorization": f"Bearer {TOKEN}"}).status_code == 200
    assert len(calls) == 1


@pytest.mark.parametrize(
    "header", [None, "Bearer", "Bearer ", "Bearer anything", f"Bearer {TOKEN}"]
)
def test_test_mode_without_token_rejects_everything(monkeypatch, header):
    def fail(a, b):
        raise AssertionError("compare_digest must not be called without a configured token")

    monkeypatch.setattr(auth.secrets, "compare_digest", fail)
    client = TestClient(build_app(token=None))
    headers = {} if header is None else {"Authorization": header}
    assert_401(client.get("/protected", headers=headers))


def test_unauthenticated_invalid_request_gets_401_not_422(client):
    assert_401(client.post("/protected-body", json={"count": "not-a-number"}))
    assert_401(client.post("/protected-body?limit=abc", json={}))
    assert_401(client.post("/protected-body"))


def test_authenticated_invalid_request_still_gets_422(client):
    response = client.post(
        "/protected-body",
        json={"count": "not-a-number"},
        headers={"Authorization": f"Bearer {TOKEN}"},
    )
    assert response.status_code == 422


def test_token_never_leaks_in_responses_or_logs(client, caplog):
    caplog.set_level(logging.DEBUG)
    presented = "presented-secret-9"
    for header in [None, "Basic abc", "Bearer", f"Bearer {presented}", f"Bearer {TOKEN}x"]:
        headers = {} if header is None else {"Authorization": header}
        response = client.get("/protected", headers=headers)
        assert response.status_code == 401
        blob = response.text + repr(dict(response.headers))
        assert TOKEN not in blob
        assert presented not in blob
    for record in caplog.records:
        text = record.getMessage() + repr(record.__dict__)
        assert TOKEN not in text
        assert presented not in text


def test_healthz_stays_open(client):
    for headers in ({}, {"Authorization": "Bearer wrong"}):
        response = client.get("/healthz", headers=headers)
        assert response.status_code == 200
        assert response.json() == {"status": "ok"}
    assert TestClient(build_app(token=None)).get("/healthz").status_code == 200


@pytest.mark.parametrize("path", ["/docs", "/redoc", "/openapi.json"])
def test_docs_routes_are_not_served(client, path):
    assert client.get(path).status_code == 404
    assert client.get(path, headers={"Authorization": f"Bearer {TOKEN}"}).status_code == 404


def _dependant_calls(dependant) -> set:
    calls = {dependant.call}
    for sub in dependant.dependencies:
        calls |= _dependant_calls(sub)
    return calls


def _unprotected_routes(routes, inherited=frozenset(), prefix="") -> list[str]:
    """Walk the whole route tree, including routes outside the OpenAPI schema.

    FastAPI wraps included routers in a container exposing `original_router` and
    `include_context`; it is read with getattr, so nothing private is imported. Dependencies
    from every enclosing include, router, and route (plus the route's dependant tree) count.
    """
    unprotected = []
    for route in routes:
        original = getattr(route, "original_router", None)
        if original is not None:
            context = getattr(route, "include_context", None)
            deps = {d.dependency for d in getattr(context, "dependencies", [])}
            deps |= {d.dependency for d in getattr(original, "dependencies", [])}
            unprotected += _unprotected_routes(
                original.routes,
                inherited | deps,
                prefix + getattr(context, "prefix", ""),
            )
            continue
        path = prefix + getattr(route, "path", repr(route))
        if path == "/healthz":
            continue
        calls = set(inherited)
        calls |= {d.dependency for d in getattr(route, "dependencies", [])}
        dependant = getattr(route, "dependant", None)
        if dependant is not None:
            calls |= _dependant_calls(dependant)
        if require_token not in calls:
            unprotected.append(path)
    return unprotected


def test_every_route_except_healthz_requires_token(app):
    assert _unprotected_routes(app.routes) == []
    assert _unprotected_routes(create_app(Settings(test_mode=True)).routes) == []


def test_guard_catches_a_route_without_the_dependency():
    app = build_app()
    router = APIRouter()

    @router.get("/forgot")
    def forgot() -> dict[str, str]:
        return {}

    app.include_router(router)
    assert _unprotected_routes(app.routes) == ["/forgot"]


def test_guard_catches_unprotected_routes_outside_the_schema():
    app = build_app()

    @app.get("/leak", include_in_schema=False)
    def leak() -> dict[str, str]:
        return {}

    inner = APIRouter(prefix="/inner")

    @inner.get("/deep", include_in_schema=False)
    def deep() -> dict[str, str]:
        return {}

    outer = APIRouter(prefix="/outer")
    outer.include_router(inner)
    app.include_router(outer, prefix="/v1")
    assert sorted(_unprotected_routes(app.routes)) == ["/leak", "/v1/outer/inner/deep"]


def test_guard_accepts_protected_nested_routes_and_exact_healthz_exemption():
    app = build_app()
    inner = APIRouter(prefix="/inner")

    @inner.get("/deep", include_in_schema=False)
    def deep() -> dict[str, str]:
        return {}

    outer = APIRouter(prefix="/outer", dependencies=[Depends(require_token)])
    outer.include_router(inner)
    app.include_router(outer)

    @app.get("/healthz/extra", include_in_schema=False)
    def extra() -> dict[str, str]:
        return {}

    assert _unprotected_routes(app.routes) == ["/healthz/extra"]
