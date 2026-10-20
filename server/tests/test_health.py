import pytest
from fastapi.testclient import TestClient

from podclatch_server.app import create_app
from podclatch_server.config import Settings


@pytest.fixture
def client() -> TestClient:
    return TestClient(create_app(Settings(test_mode=True)))


def test_healthz_returns_ok_without_auth(client):
    response = client.get("/healthz")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}
    assert response.text == '{"status":"ok"}'


def test_healthz_ignores_wrong_authorization(client):
    response = client.get("/healthz", headers={"Authorization": "Bearer wrong"})
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_healthz_works_with_token_unset(monkeypatch):
    monkeypatch.delenv("PODCLATCH_TOKEN", raising=False)
    monkeypatch.setenv("PODCLATCH_TEST_MODE", "true")
    response = TestClient(create_app()).get("/healthz")
    assert response.status_code == 200
