"""Fetch a podcast feed over HTTP and parse it into plain domain objects.

A pure library module: no routes, no storage, no FastAPI. Subscribe and Refresh call it.

Feed URLs can contain secrets (spec decision #27), so nothing here ever puts a full URL, query
string, fragment, or userinfo into a log line or an error message. Only host and path are used.
"""

from __future__ import annotations

import logging
import re
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from datetime import UTC
from email.utils import parsedate_to_datetime
from urllib.parse import urlsplit

import feedparser
import httpx

logger = logging.getLogger(__name__)

# Outbound limits (spec decision #26). Shared with other outbound HTTP code (the search proxy).
FETCH_TIMEOUT_SECONDS = 15.0
MAX_REDIRECTS = 10
MAX_RESPONSE_BYTES = 10 * 1024 * 1024

_ITUNES = "{http://www.itunes.com/dtds/podcast-1.0.dtd}"
_CONTENT = "{http://purl.org/rss/1.0/modules/content/}"
_AUDIO_EXTENSIONS = (".mp3", ".m4a", ".aac", ".ogg", ".opus", ".wav", ".flac")
_DURATION_RE = re.compile(r"[0-9]{1,9}(?::[0-9]{1,9}){0,2}")


class FeedError(Exception):
    """Base class for every failure to fetch or parse a feed. Messages never contain the URL."""


class FeedUnreachableError(FeedError):
    """DNS or connection failure, non-2xx status, redirect loop, or a non-http(s) URL."""


class FeedTimeoutError(FeedError):
    """The feed server did not answer within the timeout."""


class FeedTooLargeError(FeedError):
    """The response is bigger than MAX_RESPONSE_BYTES."""


class NotAFeedError(FeedError):
    """The response is not an RSS feed."""


@dataclass(frozen=True)
class ParsedEpisode:
    """One Episode as published by the feed. Maps onto the openapi.yaml Episode schema."""

    guid: str
    title: str
    description: str
    audio_url: str
    published_at: int  # UTC epoch milliseconds; 0 when the feed gives no usable date
    duration_sec: int | None
    artwork_url: str | None  # None means "use the Feed's artwork"; the consumer resolves it


@dataclass(frozen=True)
class ParsedFeed:
    """A Feed and its Episodes in document order. Maps onto the openapi.yaml Feed schema."""

    title: str
    author: str
    description: str
    artwork_url: str
    episodes: list[ParsedEpisode]


def safe_url_label(url: str) -> str:
    """Return host and path only, safe to log (no scheme, userinfo, query, or fragment)."""
    try:
        parts = urlsplit(url)
        host = parts.hostname or ""
        return f"{host}{parts.path}"
    except ValueError:
        return "<invalid url>"


def fetch_feed(url: str, client: httpx.Client | None = None) -> ParsedFeed:
    """Fetch `url` and parse it. Raises a FeedError subclass on any failure.

    Pass `client` to inject an httpx.Client (tests use one with httpx.MockTransport).
    """
    label = safe_url_label(url)
    try:
        data = _download(url, client)
        feed = parse_feed(data)
    except FeedError as exc:
        logger.warning("feed fetch failed for %s: %s", label, exc)
        raise
    logger.info("fetched feed %s with %d episodes", label, len(feed.episodes))
    return feed


def make_client() -> httpx.Client:
    """An httpx client configured with the outbound limits from decision #26."""
    return httpx.Client(
        timeout=FETCH_TIMEOUT_SECONDS,
        follow_redirects=True,
        max_redirects=MAX_REDIRECTS,
    )


def _download(url: str, client: httpx.Client | None) -> bytes:
    try:
        scheme = urlsplit(url).scheme.lower()
    except ValueError:
        raise FeedUnreachableError("The feed address is not a valid URL.") from None
    if scheme not in ("http", "https"):
        raise FeedUnreachableError("The feed address must start with http:// or https://.")

    owns_client = client is None
    active = make_client() if client is None else client
    try:
        with active.stream(
            "GET", url, timeout=FETCH_TIMEOUT_SECONDS, follow_redirects=True
        ) as response:
            if len(response.history) > MAX_REDIRECTS:
                raise FeedUnreachableError("The feed redirected too many times.")
            if not response.is_success:
                raise FeedUnreachableError(
                    f"The feed server answered with status {response.status_code}."
                )
            return _read_capped(response)
    except httpx.TimeoutException:
        raise FeedTimeoutError("The feed server took too long to answer.") from None
    except httpx.TooManyRedirects:
        raise FeedUnreachableError("The feed redirected too many times.") from None
    except (httpx.HTTPError, httpx.InvalidURL):
        raise FeedUnreachableError("The feed server could not be reached.") from None
    finally:
        if owns_client:
            active.close()


def _read_capped(response: httpx.Response) -> bytes:
    declared = response.headers.get("content-length", "")
    if declared.isdigit() and int(declared) > MAX_RESPONSE_BYTES:
        raise FeedTooLargeError("The feed is larger than the 10 MB limit.")
    body = bytearray()
    for chunk in response.iter_bytes():
        body.extend(chunk)
        if len(body) > MAX_RESPONSE_BYTES:
            raise FeedTooLargeError("The feed is larger than the 10 MB limit.")
    return bytes(body)


def parse_feed(data: bytes) -> ParsedFeed:
    """Parse already-fetched RSS 2.0 bytes. Raises NotAFeedError if there is no RSS channel."""
    if len(data) > MAX_RESPONSE_BYTES:
        raise FeedTooLargeError("The feed is larger than the 10 MB limit.")
    try:
        result = feedparser.parse(data)
    except Exception:
        raise NotAFeedError("The response is not an RSS feed.") from None
    # feedparser tolerates garbage, so "no exception" proves nothing: require an RSS channel.
    if not str(result.get("version", "")).startswith("rss"):
        raise NotAFeedError("The response is not an RSS feed.")

    channel, items = _extract(data, result)
    descriptions = _sanitize([channel["description"], *(i["description"] for i in items)])
    channel["description"] = descriptions[0]

    episodes: list[ParsedEpisode] = []
    seen: set[str] = set()
    for item, description in zip(items, descriptions[1:], strict=True):
        audio_url = _pick_audio_url(item["enclosures"])
        if audio_url is None:
            continue
        guid = item["guid"] or audio_url
        if guid in seen:
            continue  # duplicate guid: the first occurrence in document order wins
        seen.add(guid)
        episodes.append(
            ParsedEpisode(
                guid=guid,
                title=item["title"],
                description=description,
                audio_url=audio_url,
                published_at=_parse_date_ms(item["pub_date"]),
                duration_sec=_parse_duration(item["duration"]),
                artwork_url=item["artwork"] or None,
            )
        )
    return ParsedFeed(
        title=channel["title"],
        author=channel["author"],
        description=channel["description"],
        artwork_url=channel["artwork"],
        episodes=episodes,
    )


def _text(element: ET.Element | None) -> str:
    if element is None or element.text is None:
        return ""
    return element.text.strip()


def _first(*values: str) -> str:
    return next((v for v in values if v), "")


def _extract(data: bytes, result: feedparser.FeedParserDict) -> tuple[dict, list[dict]]:
    """Collect the raw fields with the precedence the spec asks for.

    feedparser merges itunes:author with managingEditor, and itunes:summary with description,
    depending on element order, so the precedence cannot be recovered from its output. The
    namespace-aware XML pass reads each element separately. If the XML is too broken for that
    (or declares entities), fall back to what feedparser recovered.
    """
    if b"<!ENTITY" not in data.upper():
        try:
            return _extract_xml(ET.fromstring(data))
        except (ET.ParseError, ValueError):
            pass
    return _extract_feedparser(result)


def _extract_xml(root: ET.Element) -> tuple[dict, list[dict]]:
    channel = root.find("channel")
    if root.tag != "rss" or channel is None:
        raise ValueError("not an rss document")
    image = channel.find(f"{_ITUNES}image")
    image_url = channel.find("image/url")
    fields = {
        "title": _text(channel.find("title")),
        "author": _first(
            _text(channel.find(f"{_ITUNES}author")),
            _text(channel.find("managingEditor")),
            _text(channel.find("author")),
        ),
        "description": _first(
            _text(channel.find("description")),
            _text(channel.find(f"{_ITUNES}summary")),
            _text(channel.find(f"{_ITUNES}subtitle")),
        ),
        "artwork": _first(
            (image.get("href") or "").strip() if image is not None else "", _text(image_url)
        ),
    }
    items = []
    for item in channel.findall("item"):
        item_image = item.find(f"{_ITUNES}image")
        items.append(
            {
                "title": _text(item.find("title")),
                "description": _first(
                    _text(item.find("description")),
                    _text(item.find(f"{_CONTENT}encoded")),
                    _text(item.find(f"{_ITUNES}summary")),
                ),
                "guid": _text(item.find("guid")),
                "pub_date": _text(item.find("pubDate")),
                "duration": _text(item.find(f"{_ITUNES}duration")),
                "artwork": (item_image.get("href") or "").strip() if item_image is not None else "",
                "enclosures": [
                    ((e.get("url") or "").strip(), (e.get("type") or "").strip())
                    for e in item.findall("enclosure")
                ],
            }
        )
    return fields, items


def _extract_feedparser(result: feedparser.FeedParserDict) -> tuple[dict, list[dict]]:
    feed = result.feed
    fields = {
        "title": str(feed.get("title", "")).strip(),
        "author": str(feed.get("author", "")).strip(),
        "description": _first(
            str(feed.get("subtitle", "")).strip(), str(feed.get("summary", "")).strip()
        ),
        "artwork": str(feed.get("image", {}).get("href", "")).strip(),
    }
    items = []
    for entry in result.entries:
        content = entry.get("content") or [{}]
        items.append(
            {
                "title": str(entry.get("title", "")).strip(),
                "description": _first(
                    str(entry.get("summary", "")).strip(),
                    str(content[0].get("value", "")).strip(),
                ),
                "guid": str(entry.get("id", "")).strip(),
                "pub_date": str(entry.get("published", "")).strip(),
                "duration": str(entry.get("itunes_duration", "")).strip(),
                "artwork": str(entry.get("image", {}).get("href", "")).strip(),
                "enclosures": [
                    (str(e.get("href", "")).strip(), str(e.get("type", "")).strip())
                    for e in entry.get("enclosures", [])
                ],
            }
        )
    return fields, items


def _sanitize(texts: list[str]) -> list[str]:
    """Run description markup through feedparser's sanitiser (drops script and friends).

    Done by parsing a synthetic feed, so only feedparser's public API is used. The markup is
    kept, not stripped to text.
    """
    root = ET.Element("rss", version="2.0")
    channel = ET.SubElement(root, "channel")
    for index, text in enumerate(texts):
        item = ET.SubElement(channel, "item")
        ET.SubElement(item, "title").text = str(index)
        ET.SubElement(item, "description").text = text
    parsed = feedparser.parse(ET.tostring(root))
    cleaned = {int(e.get("title", -1)): str(e.get("summary", "")).strip() for e in parsed.entries}
    return [cleaned.get(i, "") for i in range(len(texts))]


def _pick_audio_url(enclosures: list[tuple[str, str]]) -> str | None:
    """The first enclosure that looks like audio, or None (the item is then skipped)."""
    for url, mime in enclosures:
        if not url:
            continue
        mime = mime.lower()
        if mime:
            if mime.startswith(("video/", "image/")):
                continue
            return url
        try:
            path = urlsplit(url).path.lower()
        except ValueError:
            continue
        if path.endswith(_AUDIO_EXTENSIONS):
            return url
    return None


def _parse_duration(value: str) -> int | None:
    """Accept `90`, `12:34`, `1:02:03` only; anything else is None (the episode is kept)."""
    value = value.strip()
    if not _DURATION_RE.fullmatch(value):
        return None
    total = 0
    for part in value.split(":"):
        total = total * 60 + int(part)
    return total


def _parse_date_ms(value: str) -> int:
    """RFC 822 date to UTC epoch milliseconds.

    A missing, empty, or unparseable date gives 0 (the epoch): the episode is kept, not
    skipped, and it is not an error.
    """
    if not value.strip():
        return 0
    try:
        moment = parsedate_to_datetime(value.strip())
        if moment.tzinfo is None:  # "-0000" means UTC with no local-time information
            moment = moment.replace(tzinfo=UTC)
        return int(moment.timestamp() * 1000)
    except (ValueError, TypeError, OverflowError, IndexError):
        return 0
