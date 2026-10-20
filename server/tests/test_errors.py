import pytest
from fastapi import APIRouter, Depends
from fastapi.testclient import TestClient
from pydantic import BaseModel

from podclatch_server.app import create_app
from podclatch_server.auth import require_token
from podclatch_server.config import Settings
from podclatch_server.errors import ApiError, ConflictError, NotFoundError
from tests.test_auth import _unprotected_routes  # noqa: F401  (the #8 guard, reused below)

TOKEN = "test-token-1"
AUTH = {"Authorization": f"Bearer {TOKEN}"}


class Body(BaseModel):
    count: int


def build_app():
    app = create_app(Settings(token=TOKEN, test_mode=True))
    router = APIRouter(dependencies=[Depends(require_token)])

    @router.post("/echo")
    def echo(body: Body) -> dict[str, int]:
        return {"count": body.count}

    @router.get("/missing")
    def missing() -> None:
        raise NotFoundError("Thing not found.")

    @router.get("/clash")
    def clash() -> None:
        raise ConflictError("Clash.")

    @router.get("/generic")
    def generic() -> None:
        raise ApiError("Custom.")

    app.include_router(router)
    return app


@pytest.fixture
def client() -> TestClient:
    return TestClient(build_app(), headers=AUTH)


def assert_error(response, status: int):
    assert response.status_code == status
    assert response.headers["content-type"].startswith("application/json")
    detail = response.json()["detail"]
    assert set(response.json()) == {"detail"}
    assert isinstance(detail, str) and detail
    return detail


def test_project_errors_use_their_status_and_message(client):
    assert assert_error(client.get("/missing"), 404) == "Thing not found."
    assert assert_error(client.get("/clash"), 409) == "Clash."
    assert assert_error(client.get("/generic"), 500) == "Custom."


def test_validation_errors_become_one_string(client):
    detail = assert_error(client.post("/echo", json={"count": "abc"}), 422)
    assert "count" in detail
    assert "abc" not in detail
    assert_error(client.post("/echo", json={}), 422)
    assert_error(client.post("/echo"), 422)
    assert_error(
        client.post("/echo", content=b"{", headers={"Content-Type": "application/json"}), 422
    )


def test_validation_errors_never_echo_the_input(client):
    secret = "https://example.com/feed.xml?token=SECRET123"
    for payload in ({"count": secret}, {"count": [secret]}, {"count": {"k": secret}}):
        response = client.post("/echo", json=payload)
        assert_error(response, 422)
        assert "SECRET123" not in response.text


def test_http_exceptions_unknown_routes_and_wrong_methods(client):
    assert_error(client.get("/nowhere"), 404)
    response = client.delete("/echo")
    assert_error(response, 405)
    assert "POST" in response.headers["Allow"]


def test_401_keeps_its_header_and_body():
    response = TestClient(build_app()).get("/missing")
    assert assert_error(response, 401) == "Not authenticated."
    assert response.headers["WWW-Authenticate"] == "Bearer"


def test_feed_id_that_is_not_an_integer_is_422(client):
    assert_error(client.get("/v1/feeds/abc"), 405)  # no GET on an id; still a string body
    assert_error(client.delete("/v1/feeds/abc"), 422)
    assert_error(client.patch("/v1/feeds/abc", json={"listenOrder": "OLDEST_FIRST"}), 422)


def test_real_app_guards_every_route_except_healthz():
    assert _unprotected_routes(create_app(Settings(token=TOKEN, test_mode=True)).routes) == []
    app = create_app(Settings(token=TOKEN, test_mode=True))
    assert TestClient(app, headers=AUTH).get("/v1/feeds").status_code == 200
