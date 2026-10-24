"""Logging setup: apply the validated `LOG_LEVEL` to the app's own loggers (spec decision #25).

`create_app` calls `configure_logging` once per app, so the result is the same under plain
`uvicorn` and in the Docker image. Only the `podclatch_server` logger (the parent of every module
logger) gets its level set; the root logger is never touched.

It also installs a filter on `uvicorn.access` that drops the query string from the request path in
each access-log line (decision #27: search terms and feed URLs can be secrets). The filter lives in
the app, so it works under plain `uvicorn` and in the Docker image alike.
"""

import logging
import sys

from uvicorn.logging import DefaultFormatter

APP_LOGGER = "podclatch_server"
ACCESS_LOGGER = "uvicorn.access"
_HANDLER_MARK = "_podclatch_handler"


class RedactQueryFilter(logging.Filter):
    """Drops the query string from the request path of a uvicorn access record.

    uvicorn logs `'%s - "%s %s HTTP/%s" %d'` with args (client, method, full_path, version,
    status). A record of any other shape passes through unchanged, and the filter never drops one.
    """

    def filter(self, record: logging.LogRecord) -> bool:
        args = record.args
        if isinstance(args, tuple) and len(args) == 5 and isinstance(args[2], str):
            record.args = (*args[:2], args[2].partition("?")[0], *args[3:])
        return True


class _StderrHandler(logging.StreamHandler):
    """Writes to whatever `sys.stderr` is at emit time (so redirection and capture work)."""

    def __init__(self) -> None:
        super().__init__(sys.stderr)

    @property
    def stream(self):  # type: ignore[override]
        return sys.stderr

    @stream.setter
    def stream(self, value) -> None:
        pass


def configure_logging(level: str) -> None:
    """Set the app logger to `level` (already validated and upper-case, see `load_settings`).

    If nothing is configured to receive these records (no handler on the app logger, its
    ancestors, or the root), one stderr handler in uvicorn's format is added; calling this again
    never adds a second one.
    """
    access = logging.getLogger(ACCESS_LOGGER)
    if not any(isinstance(f, RedactQueryFilter) for f in access.filters):
        access.addFilter(RedactQueryFilter())
    logger = logging.getLogger(APP_LOGGER)
    logger.setLevel(getattr(logging, level))
    if any(getattr(h, _HANDLER_MARK, False) for h in logger.handlers) or logger.hasHandlers():
        return
    handler = _StderrHandler()
    handler.setFormatter(DefaultFormatter("%(levelprefix)s %(message)s", use_colors=None))
    setattr(handler, _HANDLER_MARK, True)
    logger.addHandler(handler)
