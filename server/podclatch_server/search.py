"""Podcast search through the iTunes directory, with a small in-memory cache.

A library module: no FastAPI. The router in `routes/search.py` calls `SearchService.search`.

Search terms are personal listening interest, so neither the term, the iTunes URL, nor any
upstream body is ever logged or put in an error message. A failure logs one WARNING that carries
only a category. The directory URL defaults to the real iTunes and can be set with the
ITUNES_SEARCH_URL setting (the integration suite points it at a fake host); it is never logged.
"""

from __future__ import annotations

import json
import logging
import threading
import time
from collections import OrderedDict
from collections.abc import Callable

import httpx

from podclatch_server.config import DEFAULT_ITUNES_SEARCH_URL
from podclatch_server.feeds import (
    FETCH_TIMEOUT_SECONDS,
    MAX_REDIRECTS,
    MAX_RESPONSE_BYTES,
    make_client,
)

logger = logging.getLogger(__name__)
# httpx logs every request URL (which holds the term) at INFO; keep that out of any log setup.
logging.getLogger("httpx").setLevel(logging.WARNING)

RESULT_LIMIT = 25
CACHE_TTL_SECONDS = 600.0  # 10 minutes (spec 5.2)
CACHE_MAX_ENTRIES = 100
MAX_TERM_LENGTH = 200
UPSTREAM_FAILURE_MESSAGE = "The podcast directory is unavailable. Try again later."

Result = dict[str, str]


class SearchUnavailableError(Exception):
    """iTunes failed. The message is safe to show: it never contains the URL or the term."""

    def __init__(self) -> None:
        super().__init__(UPSTREAM_FAILURE_MESSAGE)


class _UpstreamFailure(Exception):
    """Internal: carries only a log-safe category."""


def normalize_term(q: str) -> str:
    """Trim, collapse runs of whitespace to one space, and lowercase."""
    return " ".join(q.split()).lower()


class SearchService:
    """Search iTunes for podcasts. One instance owns one cache."""

    def __init__(
        self,
        client: httpx.Client | None = None,
        clock: Callable[[], float] = time.monotonic,
        url: str = DEFAULT_ITUNES_SEARCH_URL,
    ) -> None:
        # `url` comes from the ITUNES_SEARCH_URL setting; like the term, it is never logged.
        self._url = url
        self._client = client
        self._clock = clock
        self._lock = threading.Lock()
        self._cache: OrderedDict[str, tuple[float, list[Result]]] = OrderedDict()

    def cache_size(self) -> int:
        with self._lock:
            return len(self._cache)

    def search(self, term: str) -> list[Result]:
        """Return up to 25 results for an already-normalized, non-empty `term`.

        Raises SearchUnavailableError when iTunes fails. Failures are never cached.
        """
        cached = self._get_cached(term)
        if cached is not None:
            return cached
        try:
            results = self._fetch(term)
        except _UpstreamFailure as failure:
            logger.warning("podcast search failed: %s", failure)
            raise SearchUnavailableError from None
        self._store(term, results)
        return [dict(r) for r in results]

    def _get_cached(self, term: str) -> list[Result] | None:
        with self._lock:
            entry = self._cache.get(term)
            if entry is None:
                return None
            stored_at, results = entry
            if self._clock() - stored_at >= CACHE_TTL_SECONDS:
                del self._cache[term]
                return None
            return [dict(r) for r in results]

    def _store(self, term: str, results: list[Result]) -> None:
        with self._lock:
            now = self._clock()
            self._cache.pop(term, None)
            if len(self._cache) >= CACHE_MAX_ENTRIES:
                expired = [k for k, (at, _) in self._cache.items() if now - at >= CACHE_TTL_SECONDS]
                for key in expired:
                    del self._cache[key]
            while len(self._cache) >= CACHE_MAX_ENTRIES:
                self._cache.popitem(last=False)  # oldest first
            self._cache[term] = (now, [dict(r) for r in results])

    def _fetch(self, term: str) -> list[Result]:
        owns_client = self._client is None
        client = make_client() if self._client is None else self._client
        try:
            with client.stream(
                "GET",
                self._url,
                params={"media": "podcast", "term": term, "limit": str(RESULT_LIMIT)},
                timeout=FETCH_TIMEOUT_SECONDS,
                follow_redirects=True,
            ) as response:
                if len(response.history) > MAX_REDIRECTS:
                    raise _UpstreamFailure("too many redirects")
                if not response.is_success:
                    raise _UpstreamFailure(f"bad status {response.status_code}")
                body = _read_capped(response)
        except httpx.TimeoutException:
            raise _UpstreamFailure("timeout") from None
        except httpx.TooManyRedirects:
            raise _UpstreamFailure("too many redirects") from None
        except (httpx.HTTPError, httpx.InvalidURL):
            raise _UpstreamFailure("connection error") from None
        finally:
            if owns_client:
                client.close()
        return _map_results(body)


def _read_capped(response: httpx.Response) -> bytes:
    declared = response.headers.get("content-length", "")
    if declared.isdigit() and int(declared) > MAX_RESPONSE_BYTES:
        raise _UpstreamFailure("response too large")
    body = bytearray()
    for chunk in response.iter_bytes():
        body.extend(chunk)
        if len(body) > MAX_RESPONSE_BYTES:
            raise _UpstreamFailure("response too large")
    return bytes(body)


def _text(value: object) -> str:
    return value.strip() if isinstance(value, str) else ""


def _map_results(body: bytes) -> list[Result]:
    """Parse the iTunes JSON (whatever its Content-Type) into SearchResult dicts."""
    try:
        data = json.loads(body)
    except ValueError:  # includes UnicodeDecodeError
        raise _UpstreamFailure("invalid response") from None
    if not isinstance(data, dict) or not isinstance(data.get("results"), list):
        raise _UpstreamFailure("invalid response")
    results: list[Result] = []
    for entry in data["results"]:
        if not isinstance(entry, dict):
            continue
        feed_url = _text(entry.get("feedUrl"))
        if not feed_url:
            continue
        results.append(
            {
                "feedUrl": feed_url,
                "title": _text(entry.get("collectionName")),
                "author": _text(entry.get("artistName")),
                "artworkUrl": _text(entry.get("artworkUrl600")),
            }
        )
        if len(results) == RESULT_LIMIT:
            break
    return results
