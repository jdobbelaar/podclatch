from __future__ import annotations

import logging
from pathlib import Path

import httpx
import pytest

from podclatch_server import feeds
from podclatch_server.feeds import (
    FeedError,
    FeedTimeoutError,
    FeedTooLargeError,
    FeedUnreachableError,
    NotAFeedError,
    fetch_feed,
    parse_feed,
)

FIXTURES = Path(__file__).parent / "fixtures"
URL = "https://example.com/feed.xml"


def fixture(name: str) -> bytes:
    return (FIXTURES / name).read_bytes()


def rss(items: str = "", channel: str = "") -> bytes:
    return (
        '<?xml version="1.0"?><rss version="2.0" '
        'xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd" '
        'xmlns:content="http://purl.org/rss/1.0/modules/content/">'
        f"<channel><title>T</title>{channel}{items}</channel></rss>"
    ).encode()


def item(extra: str = "", guid: str | None = "g1", enclosure: str | None = None) -> str:
    if enclosure is None:
        enclosure = '<enclosure url="https://example.com/a.mp3" type="audio/mpeg"/>'
    guid_xml = "" if guid is None else f"<guid>{guid}</guid>"
    return f"<item><title>I</title>{guid_xml}{enclosure}{extra}</item>"


def client_for(handler) -> httpx.Client:
    return httpx.Client(transport=httpx.MockTransport(handler))


def serving(body: bytes, status: int = 200, headers: dict | None = None) -> httpx.Client:
    return client_for(lambda request: httpx.Response(status, content=body, headers=headers))


# --- parsing -----------------------------------------------------------------------------------


def test_parses_feed_metadata_and_episodes():
    feed = parse_feed(fixture("full.xml"))
    assert feed.title == "Example Show"
    assert feed.author == "Example Author"
    assert feed.description == "Channel description"
    assert feed.artwork_url == "https://example.com/itunes-image.jpg"
    assert [e.guid for e in feed.episodes] == ["ep-1", "ep-2"]
    first = feed.episodes[0]
    assert first.title == "Episode one"
    assert first.audio_url == "https://example.com/ep1.mp3"
    assert first.published_at == 1672653600000
    assert first.duration_sec == 754
    assert first.artwork_url == "https://example.com/ep1.jpg"
    assert first.description == "Plain description"


def test_parsed_objects_are_immutable():
    feed = parse_feed(fixture("full.xml"))
    with pytest.raises(AttributeError):
        feed.title = "x"  # type: ignore[misc]
    with pytest.raises(AttributeError):
        feed.episodes[0].guid = "x"  # type: ignore[misc]


def test_feed_metadata_fallbacks():
    channel = (
        "<managingEditor>ed@example.com</managingEditor>"
        "<itunes:subtitle>Sub</itunes:subtitle>"
        "<image><url> https://example.com/img.jpg </url></image>"
    )
    feed = parse_feed(rss(channel=channel))
    assert feed.author == "ed@example.com"
    assert feed.description == "Sub"
    assert feed.artwork_url == "https://example.com/img.jpg"


def test_itunes_summary_is_description_fallback_regardless_of_element_order():
    channel = "<itunes:summary>Sum</itunes:summary>"
    assert parse_feed(rss(channel=channel)).description == "Sum"
    both = "<itunes:summary>Sum</itunes:summary><description>Desc</description>"
    assert parse_feed(rss(channel=both)).description == "Desc"


def test_itunes_author_beats_managing_editor_in_either_order():
    a = "<managingEditor>m@example.com</managingEditor><itunes:author>IA</itunes:author>"
    b = "<itunes:author>IA</itunes:author><managingEditor>m@example.com</managingEditor>"
    assert parse_feed(rss(channel=a)).author == "IA"
    assert parse_feed(rss(channel=b)).author == "IA"


def test_missing_feed_fields_are_empty_strings():
    feed = parse_feed(fixture("empty_channel.xml"))
    assert (feed.title, feed.author, feed.description, feed.artwork_url) == (
        "Empty Show",
        "",
        "",
        "",
    )
    assert feed.episodes == []


def test_slightly_malformed_rss_is_accepted():
    feed = parse_feed(fixture("malformed.xml"))
    assert [e.guid for e in feed.episodes] == ["m-1"]
    assert feed.title


@pytest.mark.parametrize(
    "body",
    [
        fixture("page.html"),
        b'{"title": "json"}',
        b"",
        b"   \n\t ",
        fixture("atom.xml"),
    ],
    ids=["html", "json", "empty", "whitespace", "atom"],
)
def test_not_a_feed(body):
    with pytest.raises(NotAFeedError):
        parse_feed(body)


@pytest.mark.parametrize(
    "enclosure",
    [
        "",
        '<enclosure url="" type="audio/mpeg"/>',
        '<enclosure url="https://example.com/v.mp4" type="video/mp4"/>',
        '<enclosure url="https://example.com/i.jpg" type="image/jpeg"/>',
        '<enclosure url="https://example.com/noext"/>',
        '<enclosure url="https://example.com/doc.pdf"/>',
    ],
    ids=["none", "empty-url", "video", "image", "untyped-no-ext", "untyped-pdf"],
)
def test_items_without_audio_are_skipped(enclosure):
    feed = parse_feed(rss(item(enclosure=enclosure) + item(guid="keep")))
    assert [e.guid for e in feed.episodes] == ["keep"]


@pytest.mark.parametrize("ext", ["mp3", "m4a", "aac", "ogg", "opus", "wav", "flac"])
def test_untyped_enclosure_accepted_by_audio_extension(ext):
    enclosure = f'<enclosure url="https://example.com/a.{ext}?x=1"/>'
    feed = parse_feed(rss(item(enclosure=enclosure)))
    assert len(feed.episodes) == 1


def test_first_qualifying_enclosure_wins():
    enclosure = (
        '<enclosure url="https://example.com/v.mp4" type="video/mp4"/>'
        '<enclosure url="https://example.com/first.mp3" type="audio/mpeg"/>'
        '<enclosure url="https://example.com/second.mp3" type="audio/mpeg"/>'
    )
    feed = parse_feed(rss(item(enclosure=enclosure)))
    assert feed.episodes[0].audio_url == "https://example.com/first.mp3"


def test_guid_falls_back_to_enclosure_url():
    blank = rss(item(guid="  "))
    assert parse_feed(blank).episodes[0].guid == "https://example.com/a.mp3"
    assert parse_feed(rss(item(guid=None))).episodes[0].guid == "https://example.com/a.mp3"
    assert parse_feed(rss(item(guid=" abc "))).episodes[0].guid == "abc"


def test_duplicate_guids_keep_first_in_document_order():
    items = (
        "<item><title>first</title><guid>d</guid>"
        '<enclosure url="https://example.com/1.mp3" type="audio/mpeg"/></item>'
        "<item><title>second</title><guid>d</guid>"
        '<enclosure url="https://example.com/2.mp3" type="audio/mpeg"/></item>'
    )
    feed = parse_feed(rss(items))
    assert [e.title for e in feed.episodes] == ["first"]


def test_missing_title_and_description_are_empty_strings():
    body = rss(
        '<item><guid>x</guid><enclosure url="https://example.com/a.mp3" type="audio/mpeg"/></item>'
    )
    episode = parse_feed(body).episodes[0]
    assert episode.title == ""
    assert episode.description == ""


def test_description_fallback_order_and_html_kept():
    html = '<![CDATA[<p>Hello <a href="https://example.com/x">link</a></p>]]>'
    only_encoded = rss(item(extra=f"<content:encoded>{html}</content:encoded>"))
    desc = parse_feed(only_encoded).episodes[0].description
    assert "<p>" in desc
    assert '<a href="https://example.com/x">' in desc

    both = rss(
        item(
            extra="<itunes:summary>S</itunes:summary>"
            f"<content:encoded>{html}</content:encoded><description>D</description>"
        )
    )
    assert parse_feed(both).episodes[0].description == "D"
    encoded_vs_summary = rss(
        item(extra=f"<itunes:summary>S</itunes:summary><content:encoded>{html}</content:encoded>")
    )
    assert "<p>" in parse_feed(encoded_vs_summary).episodes[0].description
    summary_only = rss(item(extra="<itunes:summary>S</itunes:summary>"))
    assert parse_feed(summary_only).episodes[0].description == "S"


def test_script_tags_are_sanitised():
    xml = rss(
        item(extra="<description><![CDATA[<p>ok</p><script>alert(1)</script>]]></description>")
    )
    desc = parse_feed(xml).episodes[0].description
    assert "<p>ok</p>" in desc
    assert "<script" not in desc


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        ("90", 90),
        ("12:34", 754),
        ("1:02:03", 3723),
        ("75:30", 4530),
        ("  90  ", 90),
        ("abc", None),
        ("1:2:3:4", None),
        ("", None),
        ("-5", None),
        ("90.5", None),
        ("12:xx", None),
        ("12:", None),
    ],
)
def test_duration(raw, expected):
    feed = parse_feed(rss(item(extra=f"<itunes:duration>{raw}</itunes:duration>")))
    assert len(feed.episodes) == 1
    assert feed.episodes[0].duration_sec == expected


def test_huge_duration_digits_keep_the_episode():
    raw = "9" * 5000
    feed = parse_feed(rss(item(extra=f"<itunes:duration>{raw}</itunes:duration>")))
    assert len(feed.episodes) == 1
    assert feed.episodes[0].duration_sec is None


def test_missing_duration_is_none():
    assert parse_feed(rss(item())).episodes[0].duration_sec is None


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        ("Mon, 02 Jan 2023 10:00:00 GMT", 1672653600000),
        ("Mon, 02 Jan 2023 10:00:00 UT", 1672653600000),
        ("Mon, 02 Jan 2023 12:00:00 +0200", 1672653600000),
        ("Mon, 02 Jan 2023 05:00:00 -0500", 1672653600000),
        ("Mon, 02 Jan 2023 05:00:00 EST", 1672653600000),
        ("02 Jan 2023 10:00:00 GMT", 1672653600000),
        ("Mon, 02 Jan 2023 10:00 GMT", 1672653600000),
        ("Mon, 02 Jan 23 10:00:00 GMT", 1672653600000),
        ("Mon, 02 Jan 2023 10:00:00 -0000", 1672653600000),
        ("", 0),
        ("not a date", 0),
        ("Mon, 99 Foo 2023 10:00:00 GMT", 0),
    ],
)
def test_published_at(raw, expected):
    feed = parse_feed(rss(item(extra=f"<pubDate>{raw}</pubDate>")))
    assert len(feed.episodes) == 1
    assert feed.episodes[0].published_at == expected


def test_missing_date_is_epoch_and_episode_kept():
    assert parse_feed(rss(item())).episodes[0].published_at == 0


def test_episode_artwork_is_not_filled_from_feed_artwork():
    channel = '<itunes:image href="https://example.com/feed.jpg"/>'
    feed = parse_feed(rss(item(), channel=channel))
    assert feed.episodes[0].artwork_url is None
    assert feed.artwork_url == "https://example.com/feed.jpg"


def test_episodes_keep_document_order():
    items = "".join(
        item(guid=g, extra="<pubDate>Mon, 02 Jan 2023 10:00:00 GMT</pubDate>") for g in "cab"
    )
    assert [e.guid for e in parse_feed(rss(items)).episodes] == ["c", "a", "b"]


# --- fetching ----------------------------------------------------------------------------------


def test_fetch_returns_parsed_feed():
    feed = fetch_feed(URL, serving(fixture("full.xml")))
    assert feed.title == "Example Show"


def test_default_client_uses_15_second_timeout_and_10_redirects():
    assert feeds.FETCH_TIMEOUT_SECONDS == 15.0
    client = feeds.make_client()
    try:
        assert client.timeout == httpx.Timeout(15.0)
        assert client.max_redirects == 10
        assert client.follow_redirects is True
    finally:
        client.close()


def test_request_carries_the_15_second_timeout():
    seen = {}

    def handler(request):
        seen.update(request.extensions["timeout"])
        return httpx.Response(200, content=fixture("full.xml"))

    fetch_feed(URL, client_for(handler))
    assert set(seen.values()) == {15.0}


def test_timeout_error():
    def handler(request):
        raise httpx.ReadTimeout("slow")

    with pytest.raises(FeedTimeoutError):
        fetch_feed(URL, client_for(handler))


def test_connection_failure_is_unreachable():
    def handler(request):
        raise httpx.ConnectError("refused")

    with pytest.raises(FeedUnreachableError):
        fetch_feed(URL, client_for(handler))


@pytest.mark.parametrize("status", [404, 500])
def test_non_2xx_is_unreachable(status):
    with pytest.raises(FeedUnreachableError):
        fetch_feed(URL, serving(b"nope", status=status))


@pytest.mark.parametrize("url", ["ftp://example.com/feed.xml", "file:///etc/passwd", "example.com"])
def test_non_http_scheme_is_unreachable(url):
    def handler(request):
        raise AssertionError("must not be requested")

    with pytest.raises(FeedUnreachableError):
        fetch_feed(url, client_for(handler))


@pytest.mark.parametrize("status", [301, 302])
def test_redirect_is_followed(status):
    def handler(request):
        if request.url.path == "/a.xml":
            return httpx.Response(status, headers={"location": "https://example.com/b.xml"})
        return httpx.Response(200, content=fixture("full.xml"))

    feed = fetch_feed("https://example.com/a.xml", client_for(handler))
    assert feed.title == "Example Show"


def test_redirect_loop_is_unreachable():
    def handler(request):
        return httpx.Response(302, headers={"location": "https://example.com/loop.xml"})

    with pytest.raises(FeedUnreachableError):
        fetch_feed("https://example.com/loop.xml", client_for(handler))


def test_more_than_ten_redirects_is_unreachable():
    def handler(request):
        n = int(request.url.path.removeprefix("/r"))
        return httpx.Response(302, headers={"location": f"https://example.com/r{n + 1}"})

    with pytest.raises(FeedUnreachableError):
        fetch_feed("https://example.com/r0", client_for(handler))


def _padded(size: int) -> bytes:
    base = rss(item())
    return base + b" " * (size - len(base))


def test_body_of_exactly_the_cap_parses():
    body = _padded(feeds.MAX_RESPONSE_BYTES)
    assert len(body) == 10 * 1024 * 1024
    assert len(fetch_feed(URL, serving(body)).episodes) == 1


def test_body_one_byte_over_the_cap_is_too_large():
    with pytest.raises(FeedTooLargeError):
        fetch_feed(URL, serving(_padded(feeds.MAX_RESPONSE_BYTES + 1)))


def test_cap_is_enforced_while_streaming_with_lying_content_length():
    read = 0

    def chunks():
        nonlocal read
        for _ in range(100):
            read += 1024 * 1024
            yield b"x" * (1024 * 1024)

    def handler(request):
        return httpx.Response(200, content=chunks(), headers={"content-length": "10"})

    with pytest.raises(FeedTooLargeError):
        fetch_feed(URL, client_for(handler))
    assert read <= 12 * 1024 * 1024


def test_content_length_over_cap_is_rejected_without_reading_body():
    def body():
        raise AssertionError("body must not be read")
        yield b""

    def handler(request):
        headers = {"content-length": str(feeds.MAX_RESPONSE_BYTES + 1)}
        return httpx.Response(200, content=body(), headers=headers)

    with pytest.raises(FeedTooLargeError):
        fetch_feed(URL, client_for(handler))


def test_not_a_feed_from_fetch():
    with pytest.raises(NotAFeedError):
        fetch_feed(URL, serving(fixture("page.html")))


def test_only_the_feed_url_is_requested():
    requested = []

    def handler(request):
        requested.append(str(request.url))
        return httpx.Response(200, content=fixture("full.xml"))

    fetch_feed(URL, client_for(handler))
    assert requested == [URL]


def test_errors_share_the_feed_error_base():
    for cls in (FeedUnreachableError, FeedTimeoutError, FeedTooLargeError, NotAFeedError):
        assert issubclass(cls, FeedError)
    assert len({FeedUnreachableError, FeedTimeoutError, FeedTooLargeError, NotAFeedError}) == 4


def test_httpx_errors_never_escape():
    def handler(request):
        raise httpx.DecodingError("bad")

    with pytest.raises(FeedError):
        fetch_feed(URL, client_for(handler))


# --- secrets in logs -----------------------------------------------------------------------------

SECRET_URL = "https://user:secret@example.com/feed.xml?token=SECRET123"


def _failing_clients():
    def timeout(request):
        raise httpx.ConnectTimeout("t")

    return {
        "404": serving(b"", status=404),
        "timeout": client_for(timeout),
        "too-large": serving(b"x" * (feeds.MAX_RESPONSE_BYTES + 1)),
        "not-a-feed": serving(fixture("page.html")),
    }


def _assert_clean(caplog, error_text: str = ""):
    texts = [error_text]
    for record in caplog.records:
        texts.append(record.getMessage())
        texts.append(str(record.msg))
        texts.append(str(record.args))
        if record.exc_info:
            texts.append(repr(record.exc_info[1]))
    blob = "\n".join(texts)
    for forbidden in ("SECRET123", "secret", "token=", SECRET_URL, "user:"):
        assert forbidden not in blob


def test_success_logs_no_secrets(caplog):
    caplog.set_level(logging.DEBUG, logger="podclatch_server")
    fetch_feed(SECRET_URL, serving(fixture("full.xml")))
    assert caplog.records
    assert "example.com/feed.xml" in caplog.text
    _assert_clean(caplog)
    assert "SECRET123" not in caplog.text


@pytest.mark.parametrize("case", ["404", "timeout", "too-large", "not-a-feed"])
def test_failures_log_no_secrets(caplog, case):
    caplog.set_level(logging.DEBUG, logger="podclatch_server")
    with pytest.raises(FeedError) as excinfo:
        fetch_feed(SECRET_URL, _failing_clients()[case])
    assert any(r.levelno == logging.WARNING for r in caplog.records)
    _assert_clean(caplog, str(excinfo.value))
    assert "SECRET123" not in caplog.text
    assert excinfo.value.__cause__ is None
    assert excinfo.value.__suppress_context__ or excinfo.value.__context__ is None


def test_invalid_scheme_error_has_no_url():
    with pytest.raises(FeedUnreachableError) as excinfo:
        fetch_feed("ftp://user:secret@example.com/f.xml?token=SECRET123")
    assert "SECRET123" not in str(excinfo.value)
    assert "secret" not in str(excinfo.value)
