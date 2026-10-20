"""Bearer-token authentication (spec decision #24)."""

import secrets

from fastapi import HTTPException, Request

UNAUTHORIZED_DETAIL = "Not authenticated."


def _unauthorized() -> HTTPException:
    return HTTPException(
        status_code=401,
        detail=UNAUTHORIZED_DETAIL,
        headers={"WWW-Authenticate": "Bearer"},
    )


def _presented_token(header: str | None) -> str | None:
    """Return the token from an `Authorization: Bearer <token>` value, else None."""
    if header is None:
        return None
    parts = header.split(None, 1)  # splits on any whitespace run; ignores surrounding whitespace
    if len(parts) != 2 or parts[0].lower() != "bearer":
        return None
    return parts[1].strip() or None


def require_token(request: Request) -> None:
    """FastAPI dependency: reject the request with 401 unless it carries the configured token.

    Every failure cause gets the same response. The token is read from
    `app.state.settings` at request time and is never logged or echoed.
    """
    expected = request.app.state.settings.token
    presented = _presented_token(request.headers.get("Authorization"))
    if not expected or presented is None:
        raise _unauthorized()
    # compare_digest runs even when the lengths differ; bytes avoid TypeError on non-ASCII.
    if not secrets.compare_digest(presented.encode("utf-8"), expected.encode("utf-8")):
        raise _unauthorized()
