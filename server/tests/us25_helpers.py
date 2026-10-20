"""Shared helpers for the US-25 tests (stub fetchers, feed builders). Example.com URLs only."""

from podclatch_server.feeds import ParsedEpisode, ParsedFeed

TOKEN = "test-token-1"
AUTH = {"Authorization": f"Bearer {TOKEN}"}
SECRET_URL = "https://user:pw@example.com/feed.xml?token=SECRET123"
SECRET_PARTS = ("SECRET123", "user:pw", "token=")

EPISODE_KEYS = {
    "id",
    "feedId",
    "guid",
    "title",
    "description",
    "audioUrl",
    "publishedAt",
    "durationSec",
    "artworkUrl",
    "status",
    "playedAt",
    "positionSec",
    "stateUpdatedAt",
    "rev",
}


def ep(n: int, published: int | None = None, artwork: str | None = None) -> ParsedEpisode:
    return ParsedEpisode(
        guid=f"guid-{n}",
        title=f"Episode {n}",
        description=f"About {n}",
        audio_url=f"https://example.com/audio/{n}.mp3",
        published_at=1_700_000_000_000 + n if published is None else published,
        duration_sec=60 * n,
        artwork_url=artwork,
    )


def feed_of(*episodes: ParsedEpisode, title: str = "Show", author: str = "Author") -> ParsedFeed:
    return ParsedFeed(
        title=title,
        author=author,
        description="Description",
        artwork_url="https://example.com/art.jpg",
        episodes=list(episodes),
    )


class StubFetcher:
    """Returns canned feeds per URL (or raises), recording the URLs asked for, in order."""

    def __init__(self) -> None:
        self.calls: list[str] = []
        self.results: dict[str, ParsedFeed | Exception] = {}
        self.default: ParsedFeed | Exception = feed_of(ep(1), ep(2))

    def __call__(self, url: str) -> ParsedFeed:
        self.calls.append(url)
        result = self.results.get(url, self.default)
        if isinstance(result, Exception):
            raise result
        return result
