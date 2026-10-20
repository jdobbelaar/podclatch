"""Episodes API: list a Feed's Episodes, push Episode state, pull Sync (spec 5.2, openapi.yaml)."""

import logging
from typing import Annotated, Any, Self

from fastapi import APIRouter, Body, Depends, Query
from pydantic import BaseModel, Field, StrictInt, model_validator

from podclatch_server.auth import require_token
from podclatch_server.routes.feeds import feed_json
from podclatch_server.store import (
    Episode,
    EpisodeStateChange,
    EpisodeStateResult,
    EpisodeStatus,
    Store,
    Tombstone,
    get_store,
)

logger = logging.getLogger(__name__)

MAX_STATE_BATCH = 1000
MAX_POSITION_SEC = 2_147_483_647
DEFAULT_SYNC_LIMIT = 500

router = APIRouter(prefix="/v1", dependencies=[Depends(require_token)])


class EpisodeStateItem(BaseModel):
    episode_id: StrictInt = Field(alias="episodeId")
    status: EpisodeStatus
    played_at: StrictInt | None = Field(alias="playedAt")
    position_sec: StrictInt = Field(alias="positionSec")
    state_updated_at: StrictInt = Field(alias="stateUpdatedAt")

    @model_validator(mode="after")
    def _consistent(self) -> Self:
        if not 0 <= self.position_sec <= MAX_POSITION_SEC:
            raise ValueError(f"positionSec must be between 0 and {MAX_POSITION_SEC}")
        if self.status == EpisodeStatus.PLAYED and self.played_at is None:
            raise ValueError("playedAt is required when status is PLAYED")
        if self.status == EpisodeStatus.NEW and self.played_at is not None:
            raise ValueError("playedAt must be null when status is NEW")
        return self


def episode_json(episode: Episode) -> dict[str, Any]:
    return {
        "id": episode.id,
        "feedId": episode.feed_id,
        "guid": episode.guid,
        "title": episode.title,
        "description": episode.description,
        "audioUrl": episode.audio_url,
        "publishedAt": episode.published_at,
        "durationSec": episode.duration_sec,
        "artworkUrl": episode.artwork_url,  # as stored: null means the app uses the Feed's art
        "status": episode.status.value,
        "playedAt": episode.played_at,
        "positionSec": episode.position_sec,
        "stateUpdatedAt": episode.state_updated_at,
        "rev": episode.rev,
    }


def _state_result_json(result: EpisodeStateResult) -> dict[str, Any]:
    body: dict[str, Any] = {"episodeId": result.episode_id, "outcome": result.outcome.value}
    if result.status is not None:
        body.update(
            status=result.status.value,
            playedAt=result.played_at,
            positionSec=result.position_sec,
            stateUpdatedAt=result.state_updated_at,
        )
    return body


def _tombstone_json(tombstone: Tombstone) -> dict[str, Any]:
    return {
        "entityType": tombstone.entity_type.value,
        "entityId": tombstone.entity_id,
        "rev": tombstone.rev,
    }


@router.get("/feeds/{id}/episodes")
def list_episodes(id: int, store: Annotated[Store, Depends(get_store)]) -> list[dict[str, Any]]:
    return [episode_json(e) for e in store.list_feed_episodes(id)]


@router.post("/episodes/state")
def push_episode_state(
    items: Annotated[list[EpisodeStateItem], Body(min_length=1, max_length=MAX_STATE_BATCH)],
    store: Annotated[Store, Depends(get_store)],
) -> list[dict[str, Any]]:
    results = store.apply_episode_states(
        [
            EpisodeStateChange(
                i.episode_id, i.status, i.played_at, i.position_sec, i.state_updated_at
            )
            for i in items
        ]
    )
    counts: dict[str, int] = {}
    for r in results:
        counts[r.outcome.value] = counts.get(r.outcome.value, 0) + 1
    logger.info("episode state batch of %d items: %s", len(items), counts)
    return [_state_result_json(r) for r in results]


@router.get("/sync")
def pull_sync(
    store: Annotated[Store, Depends(get_store)],
    since: Annotated[int, Query(ge=0)] = 0,
    limit: Annotated[int, Query(ge=1, le=500)] = DEFAULT_SYNC_LIMIT,
) -> dict[str, Any]:
    page = store.sync_page(since, limit)
    return {
        "feeds": [feed_json(f) for f in page.feeds],
        "episodes": [episode_json(e) for e in page.episodes],
        "tombstones": [_tombstone_json(t) for t in page.tombstones],
        "nextRev": page.next_rev,
        "hasMore": page.has_more,
    }
