"""Tests of ops/oncall/trigger-fault.sh (US-38b, issue #118).

No Docker, no Anthropic call, no GitHub call. The "server" is a throwaway HTTP server on 127.0.0.1.
The script needs bash, curl, and python3 (skipped when bash or curl is missing).

Run from the repository root:
    uv run --no-project --with pytest --with pyyaml pytest ops/tests
"""

import http.server
import os
import re
import shutil
import subprocess
import threading
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent.parent
SCRIPT = ROOT / "ops" / "oncall" / "trigger-fault.sh"
SECRET = "tok-SECRET-1234567890abcdef"

def _find_bash():
    """Git for Windows' bash if present (plain `bash` can be the WSL launcher), else PATH bash."""
    git = shutil.which("git")
    if git and os.name == "nt":
        for rel in ("../../bin/bash.exe", "../bin/bash.exe"):
            cand = (Path(git).parent / rel).resolve()
            if cand.exists():
                return str(cand)
    return shutil.which("bash")


BASH = _find_bash()

pytestmark = pytest.mark.skipif(not (BASH and shutil.which("curl")), reason="needs bash and curl")


class FakeServer:
    def __init__(self, status=500):
        self.status = status
        self.requests = []
        outer = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_POST(self):
                length = int(self.headers.get("Content-Length", "0"))
                outer.requests.append(
                    (self.path, self.headers.get("Authorization"), self.rfile.read(length))
                )
                self.send_response(outer.status)
                self.send_header("Content-Length", "0")
                self.end_headers()

            def log_message(self, *args):
                pass

        self.httpd = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.port = self.httpd.server_address[1]
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()

    def close(self):
        self.httpd.shutdown()


@pytest.fixture
def server():
    s = FakeServer()
    yield s
    s.close()


@pytest.fixture
def env_file(tmp_path):
    path = tmp_path / ".env"
    path.write_text(f"PODCLATCH_PORT=8180\nPODCLATCH_TOKEN={SECRET}\n", encoding="utf-8")
    return path


@pytest.fixture
def fixture_file(tmp_path):
    path = tmp_path / "fault.xml"
    path.write_text("<rss version='2.0'><channel><title>x</title></channel></rss>", encoding="utf-8")
    return path


def free_port():
    import socket

    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def run(env_extra, timeout=60):
    env = {k: v for k, v in os.environ.items() if not k.startswith(("ONCALL_", "PODCLATCH_"))}
    env.update(env_extra)
    return subprocess.run(
        [BASH, SCRIPT.as_posix()], capture_output=True, text=True, env=env, timeout=timeout
    )


def base_env(server, env_file, fixture_file):
    return {
        "ONCALL_SERVER_URL": f"http://127.0.0.1:{server.port}",
        "ONCALL_ENV_FILE": str(env_file),
        "ONCALL_FIXTURE": str(fixture_file),
        "ONCALL_FIXTURE_PORT": str(free_port()),
        "ONCALL_INTERVAL": "0",
        "ONCALL_MAX_REQUESTS": "3",
    }


def test_script_is_executable_with_lf_endings():
    data = SCRIPT.read_bytes()
    assert b"\r" not in data
    assert data.startswith(b"#!/usr/bin/env bash")


def test_posts_the_fixture_feed_url_with_the_token_and_never_prints_it(
    server, env_file, fixture_file
):
    result = run(base_env(server, env_file, fixture_file))
    assert result.returncode == 0, result.stderr
    assert len(server.requests) == 3
    for path, auth, body in server.requests:
        assert path == "/v1/feeds"
        assert auth == f"Bearer {SECRET}"
        assert re.fullmatch(
            rb'\{"feedUrl": "http://host\.docker\.internal:[0-9]+/seeded-fault\.xml"\}', body
        )
    assert "HTTP 500" in result.stdout
    assert SECRET not in result.stdout + result.stderr


def test_fixture_is_served_from_loopback_while_running(server, env_file, fixture_file):
    env = base_env(server, env_file, fixture_file)
    env["ONCALL_INTERVAL"] = "2"
    env["ONCALL_MAX_REQUESTS"] = "2"
    env["ONCALL_FIXTURE_HOST"] = "127.0.0.1"
    proc = subprocess.Popen(
        [BASH, SCRIPT.as_posix()],
        env={**{k: v for k, v in os.environ.items() if not k.startswith("ONCALL_")}, **env},
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    try:
        import time
        import urllib.request

        deadline = time.time() + 20
        body = None
        while time.time() < deadline and body is None:
            try:
                with urllib.request.urlopen(
                    f"http://127.0.0.1:{env['ONCALL_FIXTURE_PORT']}/seeded-fault.xml", timeout=2
                ) as r:
                    body = r.read()
            except OSError:
                time.sleep(0.3)
        assert body is not None and b"<rss" in body
    finally:
        proc.wait(timeout=30)
    assert SECRET not in (proc.stdout.read() + proc.stderr.read())


@pytest.mark.parametrize(
    "url",
    [
        "http://example.com",
        "https://api.github.com",
        "http://10.0.0.5:8180",
        "http://localhost.evil.com",
        "http://localhost@evil.com",
        "http://127.0.0.1.evil.com:8180",
        "http://127.0.0.1:8180/../x",
        "http://[::1]:8180",
        "http://0.0.0.0:8180",
        "ftp://localhost",
    ],
)
def test_refuses_non_local_servers(url, server, env_file, fixture_file):
    env = base_env(server, env_file, fixture_file)
    env["ONCALL_SERVER_URL"] = url
    result = run(env)
    assert result.returncode != 0
    assert "refusing to run" in result.stderr
    assert server.requests == []
    assert SECRET not in result.stdout + result.stderr


@pytest.mark.parametrize("url", ["http://localhost:8180", "https://127.0.0.1", "http://localhost"])
def test_accepts_local_server_urls(url, server, env_file, fixture_file):
    env = base_env(server, env_file, fixture_file)
    env["ONCALL_SERVER_URL"] = url
    env["ONCALL_MAX_REQUESTS"] = "1"
    # Nothing listens there, so the request fails, but the guard must not refuse it.
    result = run(env)
    assert "refusing to run" not in result.stderr


def test_missing_fixture_explains_where_it_lives(server, env_file, fixture_file, tmp_path):
    env = base_env(server, env_file, fixture_file)
    env["ONCALL_FIXTURE"] = str(tmp_path / "nope.xml")
    result = run(env)
    assert result.returncode != 0
    assert "demo/seeded-fault" in result.stderr
    assert server.requests == []


def test_missing_or_odd_token_is_refused_without_echoing_it(server, tmp_path, fixture_file):
    env_file = tmp_path / "empty.env"
    env_file.write_text("PODCLATCH_TOKEN=\n", encoding="utf-8")
    result = run(base_env(server, env_file, fixture_file))
    assert result.returncode != 0 and "PODCLATCH_TOKEN" in result.stderr

    odd = tmp_path / "odd.env"
    odd.write_text('PODCLATCH_TOKEN=abc"def\n', encoding="utf-8")
    result = run(base_env(server, odd, fixture_file))
    assert result.returncode != 0
    assert 'abc"def' not in result.stdout + result.stderr
    assert server.requests == []


def test_script_text_never_echoes_or_logs_the_token():
    text = SCRIPT.read_text(encoding="utf-8")
    assert "echo \"$TOKEN\"" not in text and "echo $TOKEN" not in text
    assert "set -x" not in text
    assert "-H \"Authorization" not in text  # header goes through curl -K - (stdin), not argv
