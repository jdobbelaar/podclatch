# PodClatch Server image. Build context: server/
#   docker build -t podclatch-server server/                    (production image)
#   docker build --target test -t podclatch-server-test server/  (adds dev deps and tests)

# Stage 1: install the locked, non-dev dependencies into /app/.venv. uv lives only in this stage.
FROM python:3.12-slim AS deps
COPY --from=ghcr.io/astral-sh/uv:0.9 /uv /usr/local/bin/uv
ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PYTHON_DOWNLOADS=never \
    UV_PYTHON=/usr/local/bin/python3.12
WORKDIR /app
COPY pyproject.toml uv.lock ./
RUN uv sync --locked --no-dev --no-install-project

# Stage 2 (test target): the same virtualenv plus the dev group, the app, and the tests.
FROM deps AS test
RUN uv sync --locked --no-install-project
COPY podclatch_server ./podclatch_server
COPY tests ./tests
ENV PATH="/app/.venv/bin:$PATH" \
    PYTHONUNBUFFERED=1
CMD ["pytest"]

# Stage 3 (default target): runtime image. No uv, no compiler, no tests, no dev dependencies.
FROM python:3.12-slim AS final
RUN groupadd --system --gid 10001 podclatch \
    && useradd --system --uid 10001 --gid podclatch --no-create-home --shell /usr/sbin/nologin podclatch
WORKDIR /app
COPY --from=deps /app/.venv /app/.venv
COPY podclatch_server ./podclatch_server
ENV PATH="/app/.venv/bin:$PATH" \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1
USER podclatch
EXPOSE 8000
HEALTHCHECK --interval=10s --timeout=5s --start-period=20s --retries=5 \
    CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/healthz', timeout=3)"]
CMD ["uvicorn", "--factory", "podclatch_server.app:create_app", "--host", "0.0.0.0", "--port", "8000"]
