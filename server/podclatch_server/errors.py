"""Error bodies shared by every route: each 4xx response is `{"detail": "<string>"}`.

`register_error_handlers(app)` is the only place that shapes error responses. Other stories call
it once and must not define their own 422 handling.
"""

from http import HTTPStatus

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from podclatch_server.auth import is_authenticated, require_token, unauthorized_response


class ApiError(Exception):
    """An error with an HTTP status and a human-readable message. The message is the `detail`."""

    status_code = 500

    def __init__(self, detail: str) -> None:
        super().__init__(detail)
        self.detail = detail


class NotFoundError(ApiError):
    status_code = 404


class ConflictError(ApiError):
    status_code = 409


class UnprocessableError(ApiError):
    status_code = 422


def _body(detail: str) -> dict[str, str]:
    return {"detail": detail}


def _validation_detail(exc: RequestValidationError) -> str:
    """One sentence from field locations and messages only; never the submitted input.

    A feed URL can carry a secret (spec decision #27), and pydantic puts the input in each error.
    """
    messages = []
    for error in exc.errors():
        location = ".".join(str(part) for part in error.get("loc", ()))
        message = str(error.get("msg", "Invalid value"))
        messages.append(f"{location}: {message}" if location else message)
    return "; ".join(messages) or "The request is not valid."


async def _api_error(request: Request, exc: ApiError) -> JSONResponse:
    return JSONResponse(_body(exc.detail), status_code=exc.status_code)


def _requires_token(route: object) -> bool:
    """Does the matched route (or any of its dependencies) use `require_token`?

    Falls back to "yes" for everything except /healthz when no route was matched.
    """
    dependant = getattr(route, "dependant", None)
    if dependant is None:
        return False
    stack = [dependant]
    while stack:
        current = stack.pop()
        if current.call is require_token:
            return True
        stack.extend(current.dependencies)
    return False


async def _validation_error(request: Request, exc: RequestValidationError) -> JSONResponse:
    # FastAPI validates (and decodes) the body before the router-level auth dependency runs, so
    # an unauthenticated request would get a 422 that reveals validation details. Answer it with
    # the same 401 `require_token` gives. Nothing from the body or the token is read or logged.
    route = request.scope.get("route")
    protected = _requires_token(route) if route is not None else request.url.path != "/healthz"
    if protected and not is_authenticated(request):
        return unauthorized_response()
    return JSONResponse(_body(_validation_detail(exc)), status_code=422)


async def _http_error(request: Request, exc: StarletteHTTPException) -> JSONResponse:
    detail = exc.detail if isinstance(exc.detail, str) and exc.detail else None
    if detail is None:
        try:
            detail = HTTPStatus(exc.status_code).phrase
        except ValueError:
            detail = "Error"
    return JSONResponse(
        _body(detail), status_code=exc.status_code, headers=getattr(exc, "headers", None)
    )


def register_error_handlers(app: FastAPI) -> None:
    app.add_exception_handler(ApiError, _api_error)
    app.add_exception_handler(RequestValidationError, _validation_error)
    app.add_exception_handler(StarletteHTTPException, _http_error)
