"""Checks of the read-only telemetry gateway (US-38a): static checks (no Docker), a unit check of the
gateway's decisions, and a live test against a throwaway Compose project (skipped without Docker).

Run from the repository root:
    uv run --no-project --with pytest --with pyyaml pytest ops/tests
"""

import importlib.util
import os
import re
import shutil
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path

import pytest
import yaml

OPS = Path(__file__).resolve().parent.parent
ROOT = OPS.parent
GATEWAY_PY = OPS / "telemetry-gateway" / "gateway.py"
FAKE_TOKEN = "fake-test-token-117"


def load_gateway():
    spec = importlib.util.spec_from_file_location("gateway", GATEWAY_PY)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@pytest.fixture(scope="module")
def gw():
    return load_gateway()


def compose_service(name="telemetry-gateway", file="docker-compose.yaml"):
    text = (ROOT / file).read_text(encoding="utf-8").replace("!reset", "")  # Compose-only YAML tag
    return yaml.safe_load(text)["services"][name]


# ---------- static ----------


def test_compose_service_is_loopback_only_with_env_token():
    svc = compose_service()
    assert svc["image"].startswith("python:3.12")  # pinned, already used by alert-receiver
    assert len(svc["ports"]) == 1
    assert svc["ports"][0].startswith("127.0.0.1:${PODCLATCH_TELEMETRY_GATEWAY_PORT:-8380}:")
    assert "0.0.0.0" not in svc["ports"][0]
    assert svc["environment"]["TELEMETRY_GATEWAY_TOKEN"] == "${TELEMETRY_GATEWAY_TOKEN:-}"
    assert "depends_on" not in svc


def test_nothing_depends_on_the_gateway_and_it_has_no_other_exposure():
    compose = yaml.safe_load((ROOT / "docker-compose.yaml").read_text(encoding="utf-8"))
    for name, svc in compose["services"].items():
        assert "telemetry-gateway" not in (svc.get("depends_on") or {}), name
    lgtm_ports = compose["services"]["lgtm"]["ports"]
    assert lgtm_ports == ["127.0.0.1:${PODCLATCH_GRAFANA_PORT:-3180}:3000"]  # no raw backend ports


def test_test_stacks_never_start_the_gateway():
    overlay = compose_service(file="docker-compose.integration.yaml")
    assert overlay["profiles"] == ["dev-only"]


def test_env_example_has_a_commented_empty_token_only():
    lines = (ROOT / ".env.example").read_text(encoding="utf-8").splitlines()
    assert "# TELEMETRY_GATEWAY_TOKEN=" in lines
    assert not any(re.match(r"\s*TELEMETRY_GATEWAY_TOKEN=", line) for line in lines)


def test_allowlist_is_exactly_the_query_paths(gw):
    allowed = [
        "/loki/loki/api/v1/query",
        "/loki/loki/api/v1/query_range",
        "/loki/loki/api/v1/labels",
        "/loki/loki/api/v1/label/service_name/values",
        "/tempo/api/traces/0123456789abcdef0123456789abcdef",
        "/tempo/api/search",
        "/prom/api/v1/query",
        "/prom/api/v1/query_range",
    ]
    assert all(gw.is_allowed(p) for p in allowed)
    refused = [
        "/",
        "/api/datasources",
        "/api/admin/users",
        "/metrics",
        "/loki/loki/api/v1/push",
        "/loki/loki/api/v1/delete",
        "/loki/loki/api/v1/tail",
        "/loki/ready",
        "/loki/loki/api/v1/label//values",
        "/loki/loki/api/v1/query/",
        "/loki/loki/api/v1/../../../api/datasources",
        "/loki/loki/api/v1/label/..%2f..%2fx/values",
        "/loki/loki/api/v1/label/a/b/values",
        "/prom/api/v1/query%2f..%2fadmin",
        "/prom/api/v1/admin/tsdb/delete_series",
        "/prom/api/v1/write",
        "/prom/metrics",
        "/prom/api/v1/../../metrics",
        "//prom/api/v1/query",
        "/prom//api/v1/query",
        "/tempo/api/traces/",
        "/tempo/api/traces/xyz",
        "/tempo/api/traces/abc/../../x",
        "/tempo/api/echo",
        "/grafana/api/health",
        "/lgtm/api/v1/query",
    ]
    assert not any(gw.is_allowed(p) for p in refused), [p for p in refused if gw.is_allowed(p)]


def test_only_get_and_head_are_served(gw):
    source = GATEWAY_PY.read_text(encoding="utf-8")
    assert 'not in ("GET", "HEAD")' in source
    assert 'conn.request("GET"' in source  # upstream is always a GET, whatever the client sent


def test_token_comes_from_the_environment_and_is_compared_in_constant_time(gw):
    source = GATEWAY_PY.read_text(encoding="utf-8")
    assert 'os.environ.get("TELEMETRY_GATEWAY_TOKEN"' in source
    assert "hmac.compare_digest" in source
    assert gw.token_ok("Bearer abc", "abc")
    assert not gw.token_ok("Bearer abd", "abc")
    assert not gw.token_ok("abc", "abc")
    assert not gw.token_ok(None, "abc")
    assert not gw.token_ok("Bearer ", "")  # fail closed on an empty token
    assert not gw.token_ok("Bearer", "")


def test_caps_and_logging(gw):
    assert gw.MAX_RESPONSE_BYTES == 4 * 1024 * 1024
    assert 0 < gw.UPSTREAM_TIMEOUT_SECONDS <= 30
    source = GATEWAY_PY.read_text(encoding="utf-8")
    assert "def log_message(self, fmt, *args):" in source  # default access log (query string) is silenced
    log_lines = [line for line in source.splitlines() if "print(" in line]
    assert len(log_lines) == 1
    assert "Authorization" not in log_lines[0] and "headers" not in log_lines[0]
    assert "self.path.split('?', 1)[0]" in log_lines[0]  # path only, never the query string


def test_no_token_like_literal_in_gateway_files():
    pattern = re.compile(r"(?i)bearer\s+[A-Za-z0-9_\-]{16,}|\b[0-9a-f]{32,}\b|ghp_|github_pat_")
    files = [GATEWAY_PY, ROOT / ".env.example", ROOT / "docker-compose.yaml"]
    for f in files:
        assert not pattern.search(f.read_text(encoding="utf-8")), f


def test_unset_token_fails_closed_without_calling_upstream(gw, monkeypatch):
    import threading

    monkeypatch.delenv("TELEMETRY_GATEWAY_TOKEN", raising=False)
    monkeypatch.setattr(gw, "UPSTREAM_HOST", "127.0.0.1")
    server = gw.ThreadingHTTPServer(("127.0.0.1", 0), gw.Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        url = f"http://127.0.0.1:{server.server_address[1]}/prom/api/v1/query?query=up"
        for headers in ({}, {"Authorization": "Bearer "}, {"Authorization": "Bearer x"}):
            with pytest.raises(urllib.error.HTTPError) as err:
                urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=10)
            assert err.value.code == 503
    finally:
        server.shutdown()


# ---------- live (throwaway Compose project) ----------

DOCKER_OK = shutil.which("docker") is not None and (
    subprocess.run(["docker", "info"], capture_output=True).returncode == 0 if shutil.which("docker") else False
)
PROJECT = "podclatch-t117"
GW_PORT = os.environ.get("PODCLATCH_T117_GATEWAY_PORT", "18380")


def request(path, method="GET", token=FAKE_TOKEN, **kw):
    req = urllib.request.Request(f"http://127.0.0.1:{GW_PORT}{path}", method=method, **kw)
    if token is not None:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


@pytest.fixture(scope="module")
def live_stack(tmp_path_factory):
    env = tmp_path_factory.mktemp("t117") / "t117.env"
    env.write_text(
        "POSTGRES_PASSWORD=fakepw\nPODCLATCH_TOKEN=faketok\n"
        f"TELEMETRY_GATEWAY_TOKEN={FAKE_TOKEN}\nPODCLATCH_GRAFANA_PORT=13180\n"
        f"PODCLATCH_TELEMETRY_GATEWAY_PORT={GW_PORT}\nPODCLATCH_ALERT_RECEIVER_PORT=18280\n",
        encoding="utf-8",
    )
    base = ["docker", "compose", "-p", PROJECT, "--env-file", str(env)]
    up = subprocess.run(base + ["up", "-d", "--wait", "lgtm", "telemetry-gateway"], cwd=ROOT, capture_output=True, text=True)
    try:
        assert up.returncode == 0, up.stderr[-500:]
        # Wait until Loki answers through the gateway (the backends start after the container is healthy).
        for _ in range(60):
            if request("/loki/loki/api/v1/labels")[0] == 200 and request("/prom/api/v1/query?query=up")[0] == 200:
                break
            time.sleep(2)
        yield
    finally:
        subprocess.run(base + ["down", "-v", "--remove-orphans"], cwd=ROOT, capture_output=True)


@pytest.mark.skipif(not DOCKER_OK, reason="live test: Docker is not available")
def test_live_gateway(live_stack):
    assert request("/loki/loki/api/v1/labels", token=None)[0] == 401
    assert request("/loki/loki/api/v1/labels", token="wrong")[0] == 401
    status, body = request(
        '/loki/loki/api/v1/query_range?query=%7Bservice_name%3D%22podclatch-server%22%7D&limit=5'
    )
    assert status == 200 and b'"status":"success"' in body
    assert request("/prom/api/v1/query?query=up")[0] == 200
    assert request("/tempo/api/search")[0] == 200
    assert request("/loki/loki/api/v1/label/service_name/values")[0] in (200, 404)
    assert request("/loki/loki/api/v1/labels", method="POST", data=b"x")[0] in (403, 405)
    assert request("/loki/loki/api/v1/push", method="POST", data=b"{}")[0] in (403, 405)
    assert request("/loki/loki/api/v1/push")[0] == 403
    assert request("/api/datasources")[0] == 403
    assert request("/prom/api/v1/admin/tsdb/delete_series")[0] == 403
    assert request("/prom/api/v1/query%2f..%2fadmin")[0] == 403
    assert request("/metrics")[0] == 403
