"""Logging setup: apply the validated `LOG_LEVEL` to the app's own loggers (spec decision #25).

`create_app` calls `configure_logging` once per app, so the result is the same under plain
`uvicorn` and in the Docker image. Only the `podclatch_server` logger (the parent of every module
logger) is touched: not the root logger, and not uvicorn's or any other third-party logger.
"""

import logging
import sys

from uvicorn.logging import DefaultFormatter

APP_LOGGER = "podclatch_server"
_HANDLER_MARK = "_podclatch_handler"


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
    logger = logging.getLogger(APP_LOGGER)
    logger.setLevel(getattr(logging, level))
    if any(getattr(h, _HANDLER_MARK, False) for h in logger.handlers) or logger.hasHandlers():
        return
    handler = _StderrHandler()
    handler.setFormatter(DefaultFormatter("%(levelprefix)s %(message)s", use_colors=None))
    setattr(handler, _HANDLER_MARK, True)
    logger.addHandler(handler)
