"""Application factory."""

from fastapi import FastAPI

from podclatch_server import sql_store
from podclatch_server.config import Settings, load_settings
from podclatch_server.errors import register_error_handlers
from podclatch_server.routes import episodes, feeds, health, refresh, search
from podclatch_server.scheduler import lifespan


def create_app(settings: Settings | None = None) -> FastAPI:
    """Build the app. With no settings, read them from the environment (and fail fast)."""
    if settings is None:
        settings = load_settings()
    # No interactive docs or schema routes: only /healthz may be reachable without the token.
    app = FastAPI(
        title="PodClatch Server", docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan
    )
    app.state.settings = settings
    # The only place the store is built: connect, migrate, and keep it on the app.
    app.state.store = sql_store.open_store(settings.database_url)
    register_error_handlers(app)
    app.include_router(health.router)
    app.include_router(feeds.router)
    app.include_router(search.router)
    app.include_router(episodes.router)
    app.include_router(refresh.router)
    return app
