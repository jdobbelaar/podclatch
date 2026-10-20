"""Authentication over HTTP: every protected endpoint answers 401 before it looks at the body."""

from __future__ import annotations

import base64
import uuid

import pytest
import support
from support import T1, feed_url

SUBSCRIBE_BODY = {"feedUrl": feed_url("never-fetched")}
STATE_BODY = [support.state_item(1, "PLAYED", T1, 5)]

ENDPOINTS = [
    ("GET", "/v1/feeds", None),
    ("POST", "/v1/feeds", SUBSCRIBE_BODY),
    ("DELETE", "/v1/feeds/1", None),
    ("GET", "/v1/feeds/1/episodes", None),
    ("POST", "/v1/episodes/state", STATE_BODY),
    ("GET", "/v1/sync", None),
    ("POST", "/v1/refresh", None),
    ("POST", "/v1/feeds/1/refresh", None),
    ("GET", "/v1/search?q=history", None),
]


def _authorization(variant: str, token: str) -> dict[str, str]:
    if variant == "missing":
        return {}
    if variant == "wrong-token":
        return {"Authorization": f"Bearer wrong-{uuid.uuid4().hex}"}
    basic = base64.b64encode(f"user:{token}".encode()).decode()
    return {"Authorization": f"Basic {basic}"}


@pytest.mark.parametrize("variant", ["missing", "wrong-token", "non-bearer-scheme"])
@pytest.mark.parametrize(
    ("method", "path", "body"), ENDPOINTS, ids=[f"{m} {p.split('?')[0]}" for m, p, _ in ENDPOINTS]
)
def test_unauthenticated_requests_get_401(api, method, path, body, variant):
    headers = _authorization(variant, api.settings.token)
    response = api.request(method, path, token=None, headers=headers, json=body)
    assert response.status_code == 401
    assert response.headers["www-authenticate"] == "Bearer"
    assert response.json() == {"detail": "Not authenticated."}


@pytest.mark.parametrize("path", ["/v1/feeds", "/v1/episodes/state"])
def test_malformed_json_is_401_without_a_token_and_422_with_one(api, path):
    content = b"{not json"
    json_header = {"Content-Type": "application/json"}
    anonymous = api.post(path, token=None, headers=json_header, content=content)
    assert anonymous.status_code == 401
    assert anonymous.json() == {"detail": "Not authenticated."}
    authenticated = api.post(path, headers=json_header, content=content)
    assert authenticated.status_code == 422


def test_a_rejected_subscribe_creates_nothing(api):
    feeds_before = support.list_feeds(api)
    rev_before = support.baseline(api)
    response = api.post("/v1/feeds", token=None, json=SUBSCRIBE_BODY)
    assert response.status_code == 401
    assert support.list_feeds(api) == feeds_before
    assert support.baseline(api) == rev_before


def test_healthz_needs_no_token(api):
    response = api.get("/healthz", token=None)
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_lower_case_bearer_scheme_is_accepted(api):
    headers = {"Authorization": f"bearer {api.settings.token}"}
    response = api.get("/v1/feeds", token=None, headers=headers)
    assert response.status_code == 200
