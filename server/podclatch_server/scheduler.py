"""Background refresh scheduler and the app lifespan that starts and stops it.

A single stdlib thread waits one interval, runs a full refresh of every Feed (no 60 s cap), and
waits again, so runs never overlap and the first run is one interval after startup. The wait is
injectable so tests never sleep. The scheduler only runs outside test mode.
"""

import logging
import threading
from collections.abc import AsyncIterator, Callable
from contextlib import asynccontextmanager

from fastapi import FastAPI

from podclatch_server import refresh
from podclatch_server.feeds import ParsedFeed
from podclatch_server.routes.feeds import Fetcher, get_fetcher
from podclatch_server.store import Store, store_for_app

logger = logging.getLogger(__name__)

STOP_JOIN_SECONDS = 30.0


class RefreshScheduler:
    def __init__(
        self,
        store: Store,
        fetch: Fetcher,
        interval_seconds: float,
        wait: Callable[[float], bool] | None = None,
    ) -> None:
        """`wait(seconds)` blocks and returns True if the scheduler was asked to stop."""
        self._store = store
        self._fetch = fetch
        self._interval = interval_seconds
        self._stop = threading.Event()
        self._wait = wait if wait is not None else self._stop.wait
        self._thread: threading.Thread | None = None

    def start(self) -> None:
        if self._thread is None:
            self._thread = threading.Thread(
                target=self._loop, name="refresh-scheduler", daemon=True
            )
            self._thread.start()

    def stop(self) -> None:
        """Interrupt the wait and join the thread; no run starts afterwards."""
        self._stop.set()
        if self._thread is not None:
            self._thread.join(STOP_JOIN_SECONDS)
            self._thread = None

    def _loop(self) -> None:
        while not self._stop.is_set():
            if self._wait(self._interval) or self._stop.is_set():
                return
            self.run_once()

    def run_once(self) -> None:
        try:
            results = refresh.refresh_feeds(
                self._store,
                self._fetch,
                self._store.list_feeds(),
                should_stop=self._stop.is_set,
            )
            logger.info("scheduled refresh: %s", refresh.summarize(results))
        except Exception as exc:
            logger.error("scheduled refresh failed: %s", type(exc).__name__)


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    """Start the scheduler on startup (unless test mode) and stop it on shutdown."""
    settings = app.state.settings
    scheduler: RefreshScheduler | None = None
    if not settings.test_mode:

        def fetch(url: str) -> ParsedFeed:
            # Resolved per call so a dependency override (used by tests) is honoured.
            return app.dependency_overrides.get(get_fetcher, get_fetcher)()(url)

        scheduler = RefreshScheduler(
            store_for_app(app), fetch, settings.refresh_interval_hours * 3600
        )
        scheduler.start()
        logger.info("refresh scheduler started")
    try:
        yield
    finally:
        if scheduler is not None:
            scheduler.stop()
            logger.info("refresh scheduler stopped")
        store_for_app(app).close()  # after the scheduler has stopped
