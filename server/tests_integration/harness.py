"""Test harness for the integration suite: the Compose helper, settings, and the HTTP client.

Every call to Docker goes through `compose()`, which always names the project `podclatch-it` and
both Compose files and runs from the repository root. Nothing else in this suite runs Docker.
"""

from __future__ import annotations

import os
import socket
import subprocess
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import httpx

ROOT = Path(__file__).resolve().parents[2]
PROJECT = "podclatch-it"
COMPOSE_FILES = (ROOT / "docker-compose.yaml", ROOT / "docker-compose.integration.yaml")
ENV_FILE = ROOT / ".env"
DEFAULT_PORT = "8180"
HOST = "127.0.0.1"
FEEDHOST_PORT = 8080  # inside the stack; the published port is Docker-assigned


# --- settings -----------------------------------------------------------------------------------


def read_env_file(path: Path = ENV_FILE) -> dict[str, str]:
    """Parse plain KEY=VALUE lines the way Compose does (comments, optional quotes)."""
    values: dict[str, str] = {}
    if not path.is_file():
        return values
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        values[key.strip()] = value
    return values


def setting(name: str, default: str = "") -> str:
    """The process environment first, then the root .env, as Compose resolves it."""
    value = os.environ.get(name)
    if value:
        return value
    return read_env_file().get(name) or default


@dataclass(frozen=True)
class Settings:
    token: str
    password: str
    port: str

    @property
    def base_url(self) -> str:
        return f"http://{HOST}:{self.port}"


def load_settings() -> Settings:
    return Settings(
        token=setting("PODCLATCH_TOKEN"),
        password=setting("POSTGRES_PASSWORD"),
        port=setting("PODCLATCH_PORT", DEFAULT_PORT),
    )


def missing_env_message(settings: Settings) -> str | None:
    missing = [
        name
        for name, value in (
            ("PODCLATCH_TOKEN", settings.token),
            ("POSTGRES_PASSWORD", settings.password),
        )
        if not value
    ]
    if not missing:
        return None
    return (
        f"{' and '.join(missing)} not set. The integration tests need both PODCLATCH_TOKEN and "
        "POSTGRES_PASSWORD, from the process environment or from the root .env file. "
        "Copy .env.example to .env and fill in both values. No container was started."
    )


def port_in_use(port: str) -> bool:
    """True when something already listens on or holds 127.0.0.1:<port>."""
    number = int(port)
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.settimeout(1.0)
        if probe.connect_ex((HOST, number)) == 0:
            return True
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        try:
            probe.bind((HOST, number))
        except OSError:
            return True
    return False


def port_in_use_message(port: str) -> str:
    return (
        f"Host port {port} is already in use, so the integration stack cannot publish its "
        "server there (the dev stack from `make up` holds it by default). Run `make down` to "
        f"stop the dev stack, or set PODCLATCH_PORT to a free port (for example "
        "PODCLATCH_PORT=8181) and run again. Nothing was started or stopped."
    )


# --- the one Docker helper ----------------------------------------------------------------------


def redact(text: str, settings: Settings | None = None) -> str:
    """Hide the token and the Postgres password in anything that gets printed."""
    settings = settings or load_settings()
    for secret in (settings.token, settings.password):
        if secret:
            text = text.replace(secret, "***")
    return text


def compose(*args: str, check: bool = True, timeout: float = 600) -> subprocess.CompletedProcess:
    """Run `docker compose -p podclatch-it -f ... -f ... <args>` from the repository root."""
    command = ["docker", "compose", "-p", PROJECT]
    for compose_file in COMPOSE_FILES:
        command += ["-f", compose_file.name]
    command += list(args)
    result = subprocess.run(
        command,
        cwd=ROOT,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=timeout,
    )
    result.stdout = redact(result.stdout)
    result.stderr = redact(result.stderr)
    if check and result.returncode != 0:
        raise ComposeError(args, result)
    return result


class ComposeError(RuntimeError):
    def __init__(self, args: tuple[str, ...], result: subprocess.CompletedProcess) -> None:
        super().__init__(
            f"docker compose {' '.join(args)} failed with exit code {result.returncode}\n"
            f"{result.stdout}\n{result.stderr}"
        )


_feedhost_port: str | None = None


def invalidate_feedhost_port() -> None:
    """Forget the cached fake host port; `down` then `up` assigns a new one."""
    global _feedhost_port
    _feedhost_port = None


def feedhost_port() -> str:
    global _feedhost_port
    if _feedhost_port is None:
        out = compose("port", "feedhost", str(FEEDHOST_PORT)).stdout.strip()
        _feedhost_port = out.rsplit(":", 1)[1]
    return _feedhost_port


def stack_logs() -> str:
    return compose("logs", "--no-color", check=False).stdout


# --- HTTP ---------------------------------------------------------------------------------------

_DEFAULT = object()
SHORT_TIMEOUT = 15.0
REFRESH_TIMEOUT = 90.0  # the server caps a refresh at 60 seconds


class Api:
    """A small client for the PodClatch Server under test. Sends the token unless told not to."""

    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self.client = httpx.Client(
            base_url=settings.base_url, timeout=SHORT_TIMEOUT, trust_env=False
        )

    def request(
        self,
        method: str,
        path: str,
        *,
        token: Any = _DEFAULT,
        headers: dict[str, str] | None = None,
        timeout: float | None = None,
        **kwargs: Any,
    ) -> httpx.Response:
        merged = dict(headers or {})
        if token is _DEFAULT:
            merged["Authorization"] = f"Bearer {self.settings.token}"
        elif token is not None:
            merged["Authorization"] = f"Bearer {token}"
        return self.client.request(
            method, path, headers=merged, timeout=timeout or SHORT_TIMEOUT, **kwargs
        )

    def get(self, path: str, **kwargs: Any) -> httpx.Response:
        return self.request("GET", path, **kwargs)

    def post(self, path: str, **kwargs: Any) -> httpx.Response:
        return self.request("POST", path, **kwargs)

    def delete(self, path: str, **kwargs: Any) -> httpx.Response:
        return self.request("DELETE", path, **kwargs)

    def refresh(self, path: str = "/v1/refresh") -> httpx.Response:
        return self.post(path, timeout=REFRESH_TIMEOUT)

    def wait_healthy(self, seconds: float = 60.0) -> None:
        deadline = time.monotonic() + seconds
        while True:
            try:
                if self.client.get("/healthz", timeout=3.0).status_code == 200:
                    return
            except httpx.HTTPError:
                pass
            if time.monotonic() > deadline:
                raise TimeoutError(f"GET /healthz was not 200 within {seconds:.0f} seconds")
            time.sleep(1.0)
