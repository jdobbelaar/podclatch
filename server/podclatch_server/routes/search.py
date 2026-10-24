"""GET /v1/search: authenticated podcast search proxy."""

import threading
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Query, Request

from podclatch_server.auth import require_token
from podclatch_server.search import (
    MAX_TERM_LENGTH,
    UPSTREAM_FAILURE_MESSAGE,
    SearchService,
    SearchUnavailableError,
    normalize_term,
)

router = APIRouter(prefix="/v1", dependencies=[Depends(require_token)])

_service_lock = threading.Lock()


def get_search_service(request: Request) -> SearchService:
    """The app's SearchService (one cache per app). Tests may set `app.state.search_service`."""
    state = request.app.state
    with _service_lock:
        if getattr(state, "search_service", None) is None:
            state.search_service = SearchService(url=state.settings.itunes_search_url)
        return state.search_service


@router.get("/search")
def search(
    q: Annotated[str, Query(min_length=1)],
    service: Annotated[SearchService, Depends(get_search_service)],
) -> list[dict[str, str]]:
    term = normalize_term(q)
    if not term:
        raise HTTPException(status_code=422, detail="The search term must not be blank.")
    if len(q.strip()) > MAX_TERM_LENGTH:
        raise HTTPException(
            status_code=422,
            detail=f"The search term must be at most {MAX_TERM_LENGTH} characters.",
        )
    try:
        return service.search(term)
    except SearchUnavailableError:
        raise HTTPException(status_code=502, detail=UPSTREAM_FAILURE_MESSAGE) from None
