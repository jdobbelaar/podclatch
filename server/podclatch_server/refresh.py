"""Fetch-and-apply logic for Refresh, shared by the manual endpoints and the scheduler.

Feeds are fetched one at a time, in the order given, with no store lock held during a fetch. A
successful fetch is applied with one `Store.apply_refresh` call. Feed URLs can contain secrets
(spec decision #27), so logs and `error` texts use Feed ids and exception type names only.
"""

import logging
import threading
import time
from collections.abc import Callable
from typing import Any
from urllib.parse import urlsplit

from podclatch_server.feeds import FeedError
from podclatch_server.routes.feeds import Fetcher
from podclatch_server.store import Feed, Store

logger = logging.getLogger(__name__)

# Cap on a manual refresh (spec 5.2). Read at call time so tests can shorten it.
REFRESH_CAP_SECONDS = 60.0

GENERIC_ERROR = "The feed could not be refreshed."
UNSUBSCRIBED_ERROR = "The feed was unsubscribed during the refresh."
TIMEOUT_ERROR = "The refresh did not finish within 60 seconds."


def now_ms() -> int:
    return int(time.time() * 1000)


def _failure(feed_id: int, error: str) -> dict[str, Any]:
    return {"feedId": feed_id, "ok": False, "error": error}


def _secret_parts(url: str) -> list[str]:
    """Pieces of a feed URL that must never appear in an error text."""
    parts = [url]
    try:
        split = urlsplit(url)
        userinfo = split.netloc.rpartition("@")[0]
        parts += [split.query, userinfo, split.password or "", split.username or ""]
    except ValueError:
        pass
    return [p for p in parts if p]


def _error_text(exc: FeedError, url: str) -> str:
    message = str(exc).strip()
    if not message or any(part in message for part in _secret_parts(url)):
        return GENERIC_ERROR
    return message


class _Run:
    """Shared between a run's worker and the caller that may cancel it."""

    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.cancelled = False
        self.results: dict[int, dict[str, Any]] = {}


def _refresh_one(
    run: _Run, store: Store, fetch: Fetcher, feed_id: int, clock: Callable[[], int]
) -> None:
    feed = store.get_feed(feed_id)
    if feed is None:
        result = _failure(feed_id, UNSUBSCRIBED_ERROR)
    else:
        try:
            parsed = fetch(feed.feed_url)  # no store lock held
        except FeedError as exc:
            logger.info("refresh of feed %d failed: %s", feed_id, type(exc).__name__)
            result = _failure(feed_id, _error_text(exc, feed.feed_url))
        except Exception as exc:
            logger.warning(
                "refresh of feed %d failed unexpectedly: %s", feed_id, type(exc).__name__
            )
            result = _failure(feed_id, GENERIC_ERROR)
        else:
            with run.lock:
                if run.cancelled:  # the cap passed while fetching: discard
                    return
                applied = store.apply_refresh(feed_id, parsed, clock())
                run.results[feed_id] = (
                    {"feedId": feed_id, "ok": True}
                    if applied is not None
                    else _failure(feed_id, UNSUBSCRIBED_ERROR)
                )
            if applied is not None:
                logger.info(
                    "refreshed feed %d: %d new episodes, metadata changed: %s",
                    feed_id,
                    applied.inserted,
                    applied.feed_changed,
                )
            return
    with run.lock:
        if not run.cancelled:
            run.results[feed_id] = result


def _work(
    run: _Run,
    store: Store,
    fetch: Fetcher,
    feed_ids: list[int],
    clock: Callable[[], int],
    should_stop: Callable[[], bool],
) -> None:
    for feed_id in feed_ids:
        if run.cancelled or should_stop():
            return
        try:
            _refresh_one(run, store, fetch, feed_id, clock)
        except Exception as exc:  # e.g. the store failed; keep going with the other Feeds
            logger.warning(
                "refresh of feed %d failed unexpectedly: %s", feed_id, type(exc).__name__
            )
            with run.lock:
                if not run.cancelled:
                    run.results[feed_id] = _failure(feed_id, GENERIC_ERROR)


def refresh_feeds(
    store: Store,
    fetch: Fetcher,
    feeds: list[Feed],
    *,
    cap_seconds: float | None = None,
    clock: Callable[[], int] = now_ms,
    should_stop: Callable[[], bool] = lambda: False,
) -> list[dict[str, Any]]:
    """Refresh the Feeds one at a time in the given order; one result per Feed, same order.

    With `cap_seconds`, the call returns within that time: Feeds not finished (including ones
    never started) are reported as failed and an in-flight fetch's result is discarded. Without
    it the run happens inline. If `should_stop` turns true, the Feeds not yet started are left
    out of the returned results.
    """
    feed_ids = [f.id for f in feeds]
    run = _Run()
    if cap_seconds is None:
        _work(run, store, fetch, feed_ids, clock, should_stop)
        return [run.results[i] for i in feed_ids if i in run.results]
    worker = threading.Thread(
        target=_work,
        args=(run, store, fetch, feed_ids, clock, should_stop),
        name="manual-refresh",
        daemon=True,
    )
    worker.start()
    worker.join(cap_seconds)
    with run.lock:
        run.cancelled = True
        done = dict(run.results)
    if len(done) < len(feed_ids):
        logger.warning("refresh cap reached with %d of %d feeds done", len(done), len(feed_ids))
    return [done.get(i) or _failure(i, TIMEOUT_ERROR) for i in feed_ids]


def summarize(results: list[dict[str, Any]]) -> str:
    ok = [r["feedId"] for r in results if r["ok"]]
    failed = [r["feedId"] for r in results if not r["ok"]]
    return f"{len(ok)} ok {ok}, {len(failed)} failed {failed}"
