"""Refresh API: POST /v1/refresh and POST /v1/feeds/{id}/refresh (spec 5.2, openapi.yaml)."""

import logging
from typing import Annotated, Any

from fastapi import APIRouter, Depends

from podclatch_server import refresh
from podclatch_server.auth import require_token
from podclatch_server.errors import NotFoundError
from podclatch_server.routes.feeds import Fetcher, get_fetcher
from podclatch_server.store import FEED_NOT_FOUND_DETAIL, Store, get_store

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/v1", dependencies=[Depends(require_token)])


@router.post("/refresh")
def refresh_all(
    store: Annotated[Store, Depends(get_store)],
    fetch: Annotated[Fetcher, Depends(get_fetcher)],
) -> list[dict[str, Any]]:
    results = refresh.refresh_feeds(
        store, fetch, store.list_feeds(), cap_seconds=refresh.REFRESH_CAP_SECONDS
    )
    logger.info("manual refresh: %s", refresh.summarize(results))
    return results


@router.post("/feeds/{id}/refresh")
def refresh_one(
    id: int,
    store: Annotated[Store, Depends(get_store)],
    fetch: Annotated[Fetcher, Depends(get_fetcher)],
) -> list[dict[str, Any]]:
    feed = store.get_feed(id)
    if feed is None:
        raise NotFoundError(FEED_NOT_FOUND_DETAIL)
    results = refresh.refresh_feeds(store, fetch, [feed], cap_seconds=refresh.REFRESH_CAP_SECONDS)
    logger.info("manual refresh of feed %d: %s", id, refresh.summarize(results))
    return results
