"""Bearer-token authentication (spec decision #24)."""

import secrets

from fastapi import HTTPException, Request
from fastapi.responses import JSONResponse

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


def is_authenticated(request: Request) -> bool:
    """True if the request carries the configured token. Never logs or echoes the token.

    The token is read from `app.state.settings` at request time. Every failure cause is the same.
    """
    expected = request.app.state.settings.token
    presented = _presented_token(request.headers.get("Authorization"))
    if not expected or presented is None:
        return False
    # compare_digest runs even when the lengths differ; bytes avoid TypeError on non-ASCII.
    return secrets.compare_digest(presented.encode("utf-8"), expected.encode("utf-8"))


def unauthorized_response() -> JSONResponse:
    """The 401 response `require_token` produces, built directly (body and header identical)."""
    return JSONResponse(
        {"detail": UNAUTHORIZED_DETAIL},
        status_code=401,
        headers={"WWW-Authenticate": "Bearer"},
    )


def require_token(request: Request) -> None:
    """FastAPI dependency: reject the request with 401 unless it carries the configured token."""
    if not is_authenticated(request):
        raise _unauthorized()
