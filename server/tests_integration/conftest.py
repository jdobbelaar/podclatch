"""Session setup: one Compose stack (project `podclatch-it`) for the whole run.

Start: check the settings, remove any leftover `podclatch-it` project, check the host port, then
`up -d --build --wait`. End (also after a failure or a failed start): print the stack logs when
something went wrong, then `down -v --remove-orphans`.
"""

from __future__ import annotations

import contextlib
import sys
import uuid
from collections.abc import Callable, Iterator

import harness
import httpx
import pytest
import support
from harness import Api

_state = {"started": False}


def _say(config: pytest.Config, text: str) -> None:
    reporter = config.pluginmanager.get_plugin("terminalreporter")
    if reporter is not None:
        reporter.write_line(text)
    else:
        print(text, file=sys.stderr)


def _teardown(config: pytest.Config, dump_logs: bool) -> None:
    if not _state["started"]:
        return
    if dump_logs:
        _say(config, "\n===== docker compose logs (project podclatch-it) =====")
        _say(config, harness.stack_logs())
        _say(config, "===== end of logs =====")
    harness.compose("down", "-v", "--remove-orphans", check=False)
    harness.invalidate_feedhost_port()
    _state["started"] = False


def pytest_sessionstart(session: pytest.Session) -> None:
    config = session.config
    settings = harness.load_settings()
    problem = harness.missing_env_message(settings)
    if problem:
        raise pytest.UsageError(problem)

    # A leftover project from an aborted run would hold the port; remove it first.
    harness.compose("down", "-v", "--remove-orphans", check=False)
    if harness.port_in_use(settings.port):
        raise pytest.UsageError(harness.port_in_use_message(settings.port))

    _state["started"] = True
    try:
        harness.compose("up", "-d", "--build", "--wait")
        harness.Api(settings).wait_healthy()
    except BaseException as exc:
        _say(config, harness.redact(f"The stack failed to start: {exc}"))
        _teardown(config, dump_logs=True)
        raise pytest.UsageError(
            "The integration stack failed to start; see the output above."
        ) from None


def pytest_sessionfinish(session: pytest.Session, exitstatus: int) -> None:
    _teardown(session.config, dump_logs=exitstatus != 0)


@pytest.fixture(scope="session")
def api() -> Iterator[Api]:
    client = Api(harness.load_settings())
    yield client
    client.client.close()


@pytest.fixture(scope="session")
def feedhost() -> Callable[[str, str], None]:
    """Store `body` as feed `name` on the fake host; looks up its Docker-assigned port first."""

    def put(name: str, body: str) -> None:
        url = f"http://{harness.HOST}:{harness.feedhost_port()}/_control/feeds/{name}.xml"
        response = httpx.put(url, content=body.encode("utf-8"), timeout=10.0, trust_env=False)
        assert response.status_code == 204

    return put


@pytest.fixture
def unique_name() -> str:
    return uuid.uuid4().hex


@pytest.fixture
def new_feed(api: Api, feedhost: Callable[[str, str], None], cleanup: list[int]):
    """Serve a new feed on the fake host and subscribe to it; returns (Feed, host name, title)."""

    def make(items: list[support.Item], *, title: str | None = None):
        name = uuid.uuid4().hex
        title = title or f"Feed {name}"
        feedhost(name, support.rss(title, items))
        feed = support.subscribe(api, cleanup, support.feed_url(name))
        return feed, name, title

    return make


@pytest.fixture
def cleanup(api: Api) -> Iterator[list[int]]:
    """Feed ids to delete at the end of the test; a 404 (already deleted) is ignored."""
    ids: list[int] = []
    yield ids
    for feed_id in ids:
        with contextlib.suppress(httpx.HTTPError):
            api.delete(f"/v1/feeds/{feed_id}")
