"""Feeds API: subscribe, list, reorder, Listen Order, unsubscribe (spec 5.2, openapi.yaml)."""

import logging
import time
from collections.abc import Callable
from typing import Annotated, Any

from fastapi import APIRouter, Depends, Response
from pydantic import BaseModel, ConfigDict, Field, StrictInt, StrictStr, field_validator

from podclatch_server.auth import require_token
from podclatch_server.errors import ConflictError, UnprocessableError
from podclatch_server.feeds import FeedError, ParsedFeed, fetch_feed, safe_url_label
from podclatch_server.store import DUPLICATE_FEED_DETAIL, Feed, ListenOrder, Store, get_store

logger = logging.getLogger(__name__)

Fetcher = Callable[[str], ParsedFeed]
FETCH_FAILED_DETAIL = "The feed could not be fetched or parsed."

router = APIRouter(prefix="/v1/feeds", dependencies=[Depends(require_token)])


def get_fetcher() -> Fetcher:
    """FastAPI dependency: the feed fetcher. Tests override it so nothing touches the network."""
    return fetch_feed


class SubscribeRequest(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    feed_url: StrictStr = Field(alias="feedUrl")

    @field_validator("feed_url")
    @classmethod
    def _not_blank(cls, value: str) -> str:
        value = value.strip()
        if not value:
            raise ValueError("must not be empty")
        return value


class ReorderRequest(BaseModel):
    feed_ids: list[StrictInt] = Field(alias="feedIds")


class UpdateFeedRequest(BaseModel):
    listen_order: ListenOrder = Field(alias="listenOrder")


def feed_json(feed: Feed) -> dict[str, Any]:
    return {
        "id": feed.id,
        "feedUrl": feed.feed_url,
        "title": feed.title,
        "author": feed.author,
        "description": feed.description,
        "artworkUrl": feed.artwork_url,
        "priority": feed.priority,
        "listenOrder": feed.listen_order.value,
        "lastRefreshedAt": feed.last_refreshed_at,
        "subscribedAt": feed.subscribed_at,
        "rev": feed.rev,
    }


@router.get("")
def list_feeds(store: Annotated[Store, Depends(get_store)]) -> list[dict[str, Any]]:
    return [feed_json(f) for f in store.list_feeds()]


@router.post("", status_code=201)
def subscribe_feed(
    body: SubscribeRequest,
    store: Annotated[Store, Depends(get_store)],
    fetch: Annotated[Fetcher, Depends(get_fetcher)],
) -> dict[str, Any]:
    url = body.feed_url
    label = safe_url_label(url)
    # Checked before the fetch (no network call for a duplicate), and again inside add_feed.
    if store.get_feed_by_url(url) is not None:
        logger.info("subscribe rejected, already subscribed: %s", label)
        raise ConflictError(DUPLICATE_FEED_DETAIL)
    try:
        parsed = fetch(url)  # runs without the store lock held
    except FeedError as exc:
        logger.info("subscribe failed for %s: %s", label, type(exc).__name__)
        raise UnprocessableError(str(exc) or FETCH_FAILED_DETAIL) from None
    feed = store.add_feed(url, parsed, int(time.time() * 1000))
    logger.info("subscribed feed %d with %d episodes", feed.id, len(parsed.episodes))
    return feed_json(feed)


# Registered before /{id} so "order" is never read as an id.
@router.put("/order")
def reorder_feeds(
    body: ReorderRequest, store: Annotated[Store, Depends(get_store)]
) -> list[dict[str, Any]]:
    feeds = store.reorder_feeds(body.feed_ids)
    logger.info("reordered %d feeds", len(feeds))
    return [feed_json(f) for f in feeds]


@router.patch("/{id}")
def update_feed(
    id: int, body: UpdateFeedRequest, store: Annotated[Store, Depends(get_store)]
) -> dict[str, Any]:
    feed = store.set_listen_order(id, body.listen_order)
    logger.info("feed %d listen order is %s", feed.id, feed.listen_order.value)
    return feed_json(feed)


@router.delete("/{id}", status_code=204)
def unsubscribe_feed(id: int, store: Annotated[Store, Depends(get_store)]) -> Response:
    store.delete_feed(id)
    logger.info("unsubscribed feed %d", id)
    return Response(status_code=204)
