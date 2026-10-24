"""Seeding code for the end-to-end test (US-32), reusable for screenshots and demos (US-35).
Standard library only.

    python -I android/tools/seed_feed.py --feedhost http://127.0.0.1:49153 --audio-base http://127.0.0.1:8191

Builds a small RSS feed whose enclosures are tone WAV files served by `audio_fixture.py`, and stores it on
the fake feed host (`PUT /_control/feeds/<name>.xml`, see server/tests_integration/fakehost.py). The server
then fetches it like any publisher's feed. The feed has a title and no artwork; each Episode has a guid,
a descending pubDate, an itunes:duration, and an enclosure. Nothing here prints a feed or an audio address.
"""

import argparse
import sys
import urllib.error
import urllib.request
from email.utils import format_datetime
from datetime import datetime, timedelta, timezone
from xml.sax.saxutils import escape, quoteattr

FEED_TITLE = "E2E Test Podcast"
DEFAULT_NAME = "e2e.xml"
# (title, seconds, hz), newest first. The first Episode is the newest; the app downloads it ahead by default.
EPISODES = (
    ("E2E Episode Three", 20, 440),
    ("E2E Episode Two", 25, 550),
    ("E2E Episode One", 30, 660),
)


def build_feed(audio_base, episodes=EPISODES, now=None):
    """Returns the RSS text. audio_base is a base URL such as http://127.0.0.1:8191 (no trailing slash)."""
    base = audio_base.rstrip("/")
    now = now or datetime(2026, 1, 1, 12, 0, tzinfo=timezone.utc)
    items = []
    for index, (title, seconds, hz) in enumerate(episodes):
        published = format_datetime(now - timedelta(days=index))
        enclosure = "%s/tone-%d-%d.wav" % (base, seconds, hz)
        items.append(
            "    <item>\n"
            "      <title>%s</title>\n"
            "      <guid isPermaLink=\"false\">e2e-episode-%d</guid>\n"
            "      <pubDate>%s</pubDate>\n"
            "      <itunes:duration>%d</itunes:duration>\n"
            "      <description>%s</description>\n"
            "      <enclosure url=%s type=\"audio/wav\" length=\"1\"/>\n"
            "    </item>\n"
            % (escape(title), len(episodes) - index, published, seconds, escape(title), quoteattr(enclosure))
        )
    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">\n'
        "  <channel>\n"
        "    <title>%s</title>\n"
        "    <link>http://feedhost.invalid/</link>\n"
        "    <description>A fake feed for the end-to-end test.</description>\n"
        "    <itunes:author>E2E Author</itunes:author>\n"
        "%s"
        "  </channel>\n"
        "</rss>\n" % (escape(FEED_TITLE), "".join(items))
    )


def put_feed(feedhost, name, xml):
    """Stores the feed on the fake feed host. Raises RuntimeError (never echoing the address) on failure."""
    request = urllib.request.Request(
        "%s/_control/feeds/%s" % (feedhost.rstrip("/"), name), data=xml.encode("utf-8"), method="PUT"
    )
    request.add_header("Content-Type", "application/rss+xml")
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            status = response.status
    except urllib.error.HTTPError as error:
        status = error.code
    except OSError as error:
        raise RuntimeError("could not reach the fake feed host (%s)" % type(error).__name__) from None
    if status != 204:
        raise RuntimeError("the fake feed host answered %d to the feed upload" % status)


def main(argv=None):
    parser = argparse.ArgumentParser(description="Store the seed feed on the fake feed host.")
    parser.add_argument("--feedhost", required=True, help="base URL of the fake feed host, as the host reaches it")
    parser.add_argument("--audio-base", default="http://127.0.0.1:8191", help="base URL the emulator uses for audio")
    parser.add_argument("--name", default=DEFAULT_NAME)
    args = parser.parse_args(argv)
    put_feed(args.feedhost, args.name, build_feed(args.audio_base))
    print("seeded %s (%d Episodes)" % (args.name, len(EPISODES)), file=sys.stderr)


if __name__ == "__main__":
    main()
