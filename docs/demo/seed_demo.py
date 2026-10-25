"""Seeds the demo data that the screenshots and the walkthrough in docs/demo.md were taken with.

Standard library only, except for the optional artwork (Pillow, `pip install pillow`; without it the
Feeds simply have no artwork). It builds on android/tools/seed_feed.py, the feed builder of the
end-to-end test. Run it with plain `python3` (not `-I`) when Pillow is installed in your user site.

    python3 docs/demo/seed_demo.py --feedhost http://127.0.0.1:<feed host port> \
        --server http://127.0.0.1:18232 --artwork-dir <a scratch folder>

What it does:
  1. Stores four fictional Feeds on the fake feed host (the `feedhost` service of
     docker-compose.integration.yaml): "The Slow Radio Hour", "Bike Shop Banter", "Tiny Science", and
     "Garden Gossip". The Episodes are tone files served by android/tools/audio_fixture.py (port 8191),
     with readable titles. The Garden Gossip audio goes through the fixture's /slow/ route (40 KB/s) so a
     download stays visible for a while.
  2. Stores a search answer, so the app's search for "garden" finds Garden Gossip and three others.
  3. Writes four plain coloured squares as artwork into --artwork-dir. Serve that folder on
     http://127.0.0.1:8192 (for example `python3 -I -m http.server 8192 --bind 127.0.0.1 --directory <dir>`).
  4. Unless --no-subscribe is given, subscribes three of the Feeds through the server API (Tiny Science,
     then Bike Shop Banter, then The Slow Radio Hour, so the last one ends on top). Garden Gossip is left
     for you to subscribe in the app, as the walkthrough does. The token is read from the environment
     variable PODCLATCH_TOKEN and is never printed.

Nothing here is a real feed, a real address, or a real token.
"""

import argparse
import json
import os
import sys
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "android" / "tools"))
import seed_feed  # noqa: E402

AUDIO = "http://127.0.0.1:8191"
ART = "http://127.0.0.1:8192"
NOW = datetime(2026, 10, 22, 12, 0, tzinfo=timezone.utc)

# name -> (title, author, artwork file, audio base, description, ((Episode title, seconds, hz), ...))
FEEDS = {
    "radio.xml": ("The Slow Radio Hour", "Mina Okafor", "radio.png", AUDIO, "Quiet stories for the end of the day.",
                  (("The Night Ferry", 720, 440), ("A Lighthouse Keeper's Letters", 540, 494), ("Foghorn Folk Songs", 420, 523))),
    "bike.xml": ("Bike Shop Banter", "Ravi and Joan", "bike.png", AUDIO, "Two mechanics argue about bicycles.",
                 (("Why Your Chain Squeaks", 480, 587), ("Tubeless Tyres Explained", 360, 659), ("Winter Commuting Tips", 600, 698))),
    "science.xml": ("Tiny Science", "Dr. Lena Park", "science.png", AUDIO, "One small question, answered in a few minutes.",
                    (("Why the Night Sky Is Dark", 180, 784), ("How Bees Count", 240, 880))),
    "garden.xml": ("Garden Gossip", "Rosa Fern", "garden.png", AUDIO + "/slow/40", "Weekly chat for people with dirty fingernails.",
                   (("Tomatoes Before Frost", 300, 392), ("Composting for Beginners", 240, 349), ("The Case for Weeds", 180, 330))),
}

# What the fake directory answers to any search: the first one is the Feed the walkthrough subscribes to.
SEARCH_RESULTS = (
    ("Garden Gossip", "Rosa Fern", "garden.xml", "garden.png"),
    ("Garden Hour Live", "Pat Moss", "garden-live.xml", "radio.png"),
    ("Backyard Botany", "Dr. Ines Vale", "botany.xml", "science.png"),
    ("The Slow Radio Hour", "Mina Okafor", "radio.xml", "radio.png"),
)

COLOURS = {"radio": (52, 96, 140), "bike": (190, 90, 40), "science": (70, 140, 90), "garden": (150, 70, 120)}


def store_feeds(feedhost):
    for name, (title, author, art, audio, blurb, episodes) in FEEDS.items():
        seed_feed.FEED_TITLE = title
        xml = seed_feed.build_feed(audio, episodes=episodes, now=NOW)
        xml = xml.replace("A fake feed for the end-to-end test.", blurb)
        xml = xml.replace(
            "<itunes:author>E2E Author</itunes:author>",
            '<itunes:author>%s</itunes:author>\n    <itunes:image href="%s/%s"/>' % (author, ART, art),
        )
        xml = xml.replace("e2e-episode-", name.split(".")[0] + "-episode-")
        seed_feed.put_feed(feedhost, name, xml)
        print("stored feed %s (%d Episodes)" % (name, len(episodes)))


def store_search(feedhost):
    results = [
        {"collectionName": title, "artistName": author, "feedUrl": "http://feedhost:8080/feeds/" + feed,
         "artworkUrl600": "%s/%s" % (ART, art)}
        for title, author, feed, art in SEARCH_RESULTS
    ]
    body = json.dumps({"resultCount": len(results), "results": results}).encode()
    request = urllib.request.Request(feedhost.rstrip("/") + "/_control/search.json", data=body, method="PUT")
    urllib.request.urlopen(request, timeout=10).close()
    print("stored the search answer")


def write_artwork(directory):
    try:
        from PIL import Image, ImageDraw, ImageFont
    except ImportError:
        print("Pillow is not installed: skipping the artwork (the Feeds will have none)")
        return
    directory.mkdir(parents=True, exist_ok=True)
    for key, rgb in COLOURS.items():
        image = Image.new("RGB", (600, 600), rgb)
        ImageDraw.Draw(image).text((300, 300), key[0].upper(), fill=(255, 255, 255),
                                   font=ImageFont.load_default(size=320), anchor="mm")
        image.save(directory / (key + ".png"), optimize=True)
    print("wrote the artwork to", directory)


def subscribe(server, names):
    token = os.environ.get("PODCLATCH_TOKEN", "")
    if not token:
        sys.exit("PODCLATCH_TOKEN is not set; export it (it is not printed) or pass --no-subscribe.")
    for name in names:
        body = json.dumps({"feedUrl": "http://feedhost:8080/feeds/" + name}).encode()
        request = urllib.request.Request(
            server.rstrip("/") + "/v1/feeds", data=body, method="POST",
            headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"},
        )
        with urllib.request.urlopen(request, timeout=30) as response:
            print("subscribed to %s (HTTP %d)" % (FEEDS[name][0], response.status))


def main(argv=None):
    parser = argparse.ArgumentParser(description="Seed the PodClatch demo data.")
    parser.add_argument("--feedhost", required=True, help="the fake feed host, as this machine reaches it")
    parser.add_argument("--server", default="http://127.0.0.1:18232", help="your demo PodClatch Server")
    parser.add_argument("--artwork-dir", type=Path, required=True, help="where to write the artwork squares")
    parser.add_argument("--no-subscribe", action="store_true", help="only seed the feed host")
    args = parser.parse_args(argv)
    store_feeds(args.feedhost)
    store_search(args.feedhost)
    write_artwork(args.artwork_dir)
    if not args.no_subscribe:
        subscribe(args.server, ("science.xml", "bike.xml", "radio.xml"))


if __name__ == "__main__":
    main()
