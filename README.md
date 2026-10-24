# PodClatch

## Introduction

Podclatch is a personal, single-user podcast player for Android built around a queue that fills itself. The user subscribes to feeds, ranks them by priority, sets a listen order for each feed, and PodClatch downloads and queues episodes automatically.  

PodClatch has two parts:

- **PodClatch Android app** (`android/`): native Android (Kotlin, Jetpack Compose, Media3). It plays episodes, builds the queue, downloads audio directly from the podcasts' publishers, and works offline from a local cache. It also supports Android Auto.
- **PodClatch Server** (`server/`): a small single-user web service (Python, FastAPI, Postgres). It searches for podcasts, fetches and refreshes their RSS feeds on a schedule, and stores the user's subscriptions, feed priorities, listen orders, and played/new state, so that state survives and syncs. There are no user accounts, only one access token.

See `_docs/podclatch-spec.md` for the full product specification.

This app was developed partly as a means to pass a class, and its development process was crafted to align with [the class's document on scoring criteria](https://github.com/DataTalksClub/ai-dev-tools-zoomcamp/tree/main/project#criteria).  


### The problem being solved

There are a few problems being solved by the creation of this app:
- The user's current favorite podcast player is no longer maintained nor offered in the Google app store, so the user fears that it will likely become unusable at some point.  
- That prior app disappoints the user in a few ways, enumerated below, that the user hopes to improve upon.

Since one of the goals of this project was to displace the user's favorite podcast player, it should be noted that the user's current favorite podcast player (referred to herein as "the prior app") was not directly used as the basis of the specification for the new app, except that the behavior of the queue in the new app was indeed designed to mimic the behavior that the user liked in how the user's current favorite app managed its queue. Otherwise, the user simply asked the agent for a spec to build his own podcast player app, and then requested some changes to that generic specification. 


#### Disappointments in the other app to be improved upon
- When a new feed is subscribed, the prior app assigns it last priority. The user wants the opposite. When subscribing to a new feed, the user usually wants to hear episodes from that feed before any others, so the user wants it added with top priority.  
- Every time the prior app connects to Android Auto, it switches from the user's preferred and populated queue to another queue that the user never uses.  Consequently, nothing is queued when the episode in progress finishes, and the user has to manually switch back to the desired queue.  The new app has only the one desired queue.  
- Although the user prefers to listen at an accelerated playback speed, the user's wife dislikes the fast talking, and the prior app, in Android Auto mode, does not provide an easy way to change the playback speed to enable the user to accommodate his wife.  The user's new app has a convenient one-touch playback speed toggle to make it easy to satisfy the wife when she is in the car, and the user when she is not. 


## Running the server

Run these from `server/`:

1. Install [uv](https://docs.astral.sh/uv/). It provides Python 3.12 automatically.
2. `uv sync`
3. `cp .env.example .env`, then set `PODCLATCH_TOKEN` in `.env` to a token of your choosing. The server refuses to start without one.
4. Load the variables and start the server: `set -a; . ./.env; set +a; uv run uvicorn --factory podclatch_server.app:create_app`
5. Check it: `curl http://localhost:8000/healthz` returns `{"status":"ok"}`.

   In Windows PowerShell, `curl` is an alias for `Invoke-WebRequest` and rejects options such as `-i` and `-H`. Type `curl.exe` instead of `curl` (it ships with Windows 10 and later), or use `Invoke-RestMethod http://localhost:8000/healthz`.

   The server keeps its data in a database and creates or upgrades the schema by itself at startup (no migration command). By default that is the SQLite file `server/podclatch.db` (git-ignored; run from `server/`, or the file is created in whatever directory you start from). To use Postgres, set `DATABASE_URL=postgresql://user:password@host:5432/dbname`. To also run the tests against Postgres, set `PODCLATCH_TEST_DATABASE_URL` to a running database before `uv run pytest`; each test uses its own temporary schema, and without the variable the Postgres tests are skipped.
6. Run the tests: `uv run pytest`
7. Run the linter: `uv run ruff check .`

### Running the server with Docker Compose

This starts the server and its Postgres together. Run these from the repository root.

**Prerequisites.** Docker Desktop, or Docker Engine with Compose v2. GNU make is optional: every `make` target is one plain `docker compose` command, listed in the table below. Git Bash does not ship make; on Windows install it with `winget install GnuWin32.Make` or `choco install make`.

**First run.**

1. `cp .env.example .env` (on PowerShell: `Copy-Item .env.example .env`).
2. Edit `.env` and set `PODCLATCH_TOKEN` (the access token the app presents) and `POSTGRES_PASSWORD`. Both are empty in the example on purpose, and the stack refuses to start while either is empty. The password is placed inside the database URL, so use only letters, digits, `-`, and `_`; percent-encode any other character. `.env` is git-ignored; never commit it.
3. `make up` builds the image, starts both services, and returns once both are healthy.
4. Check it (use your token; replace 8180 if you set `PODCLATCH_PORT`):
   - `curl -i http://localhost:8180/healthz` returns 200 and `{"status":"ok"}`.
   - `curl -i -H "Authorization: Bearer <your token>" http://localhost:8180/v1/feeds` returns 200 and `[]` on a fresh volume.
   - `curl -i http://localhost:8180/v1/feeds` (no token) returns 401 with `{"detail":"Not authenticated."}`.

   **PowerShell:** `curl` is an alias for `Invoke-WebRequest` there and rejects `-i` and `-H`. Use `curl.exe` in place of `curl` in the three commands above (it ships with Windows 10 and later). If you prefer PowerShell's own commands: `Invoke-RestMethod http://localhost:8180/healthz`, and `Invoke-RestMethod http://localhost:8180/v1/feeds -Headers @{ Authorization = "Bearer <your token>" }`.

| Target | Raw command | What it does |
| --- | --- | --- |
| `make up` | `docker compose up -d --build --wait` | Build, start, and wait until both services are healthy |
| `make down` | `docker compose down` | Remove the containers and network; the Postgres data is kept |
| `make logs` | `docker compose logs -f server postgres` | Follow the logs |
| `make test` | `docker compose -f docker-compose.test.yaml run --rm --build test` | Run the server tests in a container |
| `make test-integration` | `uv run --directory server pytest tests_integration` | Run the end-to-end tests against a throwaway stack (server, Postgres, and a fake feed host) |
| `make clean` | `docker compose down -v --remove-orphans` | Also delete the Postgres volume: **this erases all data** |

**Ports.** The server listens on `127.0.0.1:8180` by default. It is not 8000 because other local projects commonly use 8000 (and 3000, 9090, 5432, and 5433, which this setup also avoids). Set `PODCLATCH_PORT` in `.env` to change it. It is bound to loopback only; to reach it from a phone on your LAN, set `PODCLATCH_BIND_ADDRESS=0.0.0.0` (anyone on the network can then reach the token-protected API, so only do this on a network you trust). Postgres is not published to the host by default. To open it for debugging, add the override file, which publishes it on `127.0.0.1:5442` (change with `PODCLATCH_POSTGRES_PORT`): `docker compose -f docker-compose.yaml -f docker-compose.debug.yaml up -d`, then `psql -h localhost -p 5442 -U podclatch podclatch`.

**Search directory.** `GET /v1/search` calls the iTunes directory at `https://itunes.apple.com/search`. The optional variable `ITUNES_SEARCH_URL` (must start with `http://` or `https://`) points it somewhere else; the integration tests use it to reach a fake host. Normal use does not set it, and `docker-compose.yaml` does not pass it through, so `make up` calls the real iTunes.

**Access log.** The server drops the query string from every request line in its access log (for example `GET /v1/search?q=...` is logged as `GET /v1/search`), because feed URLs and search terms can carry secrets.

**Tests.** `make test` runs the server tests (SQLite) in a container, so it needs only Docker and no `.env`. The host-side `uv run pytest` above still works and is unchanged.

**Integration tests.** `make test-integration` runs the suite in `server/tests_integration/` over HTTP against the real production image and Postgres, with a small fake feed host standing in for publishers, so it needs no internet. The pytest session itself starts and removes the stack, running `docker compose -p podclatch-it -f docker-compose.yaml -f docker-compose.integration.yaml ...` through one helper, which is why the "Raw command" cell is the `uv` command and not a `docker compose` one. The project name `podclatch-it` gives it its own containers, network, and Postgres volume, so your dev stack and its data are never touched. It covers subscribe, Episode state, Sync, unsubscribe, conflicting writes, Refresh, persistence across a restart and a full down and up, authentication, and feed URL secrecy. `uv run pytest` and `make test` do not run it.

- **Prerequisites:** Docker, [uv](https://docs.astral.sh/uv/), and a root `.env` with both `PODCLATCH_TOKEN` and `POSTGRES_PASSWORD` (the process environment is read first, then `.env`, as Compose does). Without them the run stops at once with a message naming `.env` and `.env.example`, before any container is created.
- **Port:** the test stack publishes the server on `127.0.0.1:8180` (`PODCLATCH_PORT`), the same default as the dev stack. If the dev stack (`make up`) or anything else holds that port, the run fails fast and changes nothing; run `make down` or set `PODCLATCH_PORT=8181` for the run. The fake feed host is published only on a Docker-assigned loopback port, and Postgres is not published.
- **Runtime:** about 1 minute once the image is built, a few minutes on a first run that has to build it and pull base images.
- **Failures:** if a test fails or the stack does not start, the logs of all services are printed before the stack is removed. The stack is always removed (`down -v --remove-orphans`), also after a failure.

## Android Auto

PodClatch shows up in Android Auto as a media app. In the car you can browse the Queue and the Feeds (two tabs, served from the phone's local cache, so it works offline), start an Episode, and use Android Auto's standard playback screen with skip back and skip forward. There is no search and no custom car screen. Playing from the car follows the phone's rules: the download starts, the Queue advances, and the saved position and Played work as on the phone. After the app was stopped, connecting to the car or pressing Play resumes the Episode you last left unfinished, or else the first Queue item.

Android Auto only lists apps from the Play Store unless you allow others, so a debug or sideloaded build needs this once, on the phone:

1. Install the debug build of PodClatch.
2. Open the Android Auto settings and tap "Version" about ten times to turn on developer mode.
3. Open the three-dot menu, choose "Developer settings", and turn on "Unknown sources".

To try it without a car, use the Desktop Head Unit:

1. In the Android SDK Manager (SDK Tools), install "Android Auto Desktop Head Unit Emulator".
2. In the phone's Android Auto developer settings, choose "Start head unit server".
3. Connect the phone to the PC with USB and run `adb forward tcp:5277 tcp:5277`.
4. Run `desktop-head-unit` from `<Android SDK>/extras/google/auto/`. PodClatch appears in the media apps list.

## Known limitations

These are deliberate trade-offs for a single-user app, recorded so they are not mistaken for oversights.

- **`lastRefreshedAt` can be stale in Sync.** When the server refreshes a Feed and nothing else about it changed, it updates that Feed's `lastRefreshedAt` but does not count it as a change for Sync. Counting it would make every refresh look like a change to every Feed and resend the whole subscription list. The app does not display this field, and `GET /v1/feeds` always returns the current value, so the lag only shows in the phone's local cache. If a screen ever needs it, read it from `GET /v1/feeds` or send it in Sync only when something else changes.
- **Deletion records (tombstones) are never pruned.** Unsubscribing leaves a small record for the Feed and each of its Episodes, so a phone that was offline learns to delete its local copy and audio files. These records are tiny, and only a full re-sync from the beginning transfers all of them, so the cost is negligible at single-user scale. If it ever matters, delete tombstones older than a cutoff (for example 90 days) and make any phone offline longer than that do a full re-sync.

## Status

Pre-development: only the specification and the agent-team documents exist so far. The feature list, screenshots, and run steps will be added when the app and server exist.
