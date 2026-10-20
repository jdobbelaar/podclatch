"""Application factory."""

from fastapi import FastAPI

from podclatch_server.config import Settings, load_settings
from podclatch_server.routes import health


def create_app(settings: Settings | None = None) -> FastAPI:
    """Build the app. With no settings, read them from the environment (and fail fast)."""
    if settings is None:
        settings = load_settings()
    app = FastAPI(title="PodClatch Server")
    app.state.settings = settings
    app.include_router(health.router)
    return app
