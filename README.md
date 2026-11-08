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


## What it does

The terms below are the ones the app, the server, and the specification use: a **Feed** is a subscribed podcast, an **Episode** is one item of a Feed, the **Queue** is the list that fills itself, **Feed Priority** ranks the Feeds, **Listen Order** says which Episode of a Feed comes first, an Episode is **New** or **Played**, a **Sync** is the app and the server agreeing on state, and a **Refresh** is the server re-reading Feeds' RSS to find new Episodes.

**The Android app**

- **Search and subscribe.** Search for a podcast by name (the app asks the server, and the server asks the iTunes directory), preview the result, and subscribe, or paste a feed address and subscribe to that. A new Feed goes to the top of the Feed Priority list, and all of its existing Episodes start as New.
- **Feed Priority and Listen Order.** Drag Feeds up and down on the Feeds tab to rank them. Each Feed has its own Listen Order, Newest first or Oldest first. Both are kept on the server, so they survive a reinstall.
- **A Queue that fills itself.** The Queue is the New Episodes that are downloaded or downloading, ordered by Feed Priority and then by each Feed's Listen Order. There is no "add to queue" and no "play next"; the Queue is worked out from the rules above. The Queue tab is the home screen, and a mini player stays above the tabs.
- **Background playback.** Playback runs in a Media3 service, so it continues with the screen off and has a media notification and lock-screen, Bluetooth, and headset controls. Position is saved as you listen and resumes where you left off. When an Episode ends it is marked Played and the next Queue item starts.
- **Speed.** One button toggles between your assigned speed (1.3x until you change it) and normal speed. Long-press it to change the assigned speed. Skip back and skip forward step by the number of seconds set in Settings.
- **Downloads.** Audio is downloaded straight from the podcast's publisher, never through the server. By default the app keeps up to 3 New Episodes of each Feed downloaded ahead of time, downloads only on Wi-Fi, and deletes downloads of Played Episodes. Starting an Episode by hand starts its download immediately and plays by streaming until the download finishes.
- **New and Played.** Mark an Episode Played or New by swiping its row or from its menu, or mark a whole Feed with "Mark all Played" or "Mark all New". The unplayed count shows on each Feed.
- **Sync and Refresh.** The app Syncs on launch, when it returns to the foreground, on a schedule you choose in Settings (every 1, 3, 6, or 12 hours), and when you ask. A long pull-down (more than half the screen) on the Queue, Feeds, or Feed Detail list asks the server to Refresh (every Feed, or just that Feed in Feed Detail), then Syncs. Everything except search, subscribing, unsubscribing, reordering, changing a Listen Order, and Refresh also works offline from the cache.
- **Settings.** Server connection, skip seconds, assigned speed, Wi-Fi-only downloads, how many Episodes to download ahead per Feed, deleting played downloads, the Sync interval, and optional notifications about new Episodes.
- **Android Auto.** Browse the Queue and the Feeds in the car, play an Episode, and use the Speed button on the playback screen. See [Android Auto](#android-auto) for how far this has been verified.
- **Accessibility and resilience.** Content descriptions, minimum touch targets, and text scaling, and clear messages (with a retry) when the network is down, the server is unreachable, the token is rejected, or a download or playback fails. The app also follows the system light and dark theme.

**The server**

- Searches the iTunes podcast directory on the app's behalf.
- Fetches and parses RSS feeds, discovers Episodes, and Refreshes every Feed on a schedule (every 3 hours by default), whether or not the app is running.
- Stores subscriptions, Feed Priority, Listen Order, and each Episode's New or Played state and position, and answers the app's Sync.
- Protects everything with one static bearer token. It never fetches, stores, or serves podcast audio.
- Runs on SQLite by default and on Postgres in Docker Compose and on AWS, and ships as a container image.

## Screenshots

These are real captures from an Android emulator, taken against a local server with made-up Feeds (see [docs/demo.md](docs/demo.md) for how). Every Episode is a generated tone, and the artwork is a plain coloured square.

| | | |
| --- | --- | --- |
| <img src="docs/screenshots/connection.png" alt="The connection screen: a server address, a masked token field, and the message Connected to 127.0.0.1:18232." width="240"><br>Connect to a server | <img src="docs/screenshots/feeds.png" alt="The Feeds tab: three Feeds in priority order, each with its unplayed count and a drag handle." width="240"><br>Feeds, with unplayed counts | <img src="docs/screenshots/subscribe-search.png" alt="The Subscribe screen with the search term garden and four results." width="240"><br>Subscribe: search |
| <img src="docs/screenshots/feed-detail.png" alt="Feed Detail for Garden Gossip: Listen Order buttons, mark-all buttons, one New Episode with a progress bar, one Played Episode, and another New Episode." width="240"><br>Feed Detail, New and Played | <img src="docs/screenshots/queue.png" alt="The Queue: the playing Episode highlighted as Now playing, a third Episode with a download progress bar, then the other queued Episodes, with the mini player below." width="240"><br>Queue, with a download in progress | <img src="docs/screenshots/queue-dark.png" alt="The same Queue in the dark theme." width="240"><br>Queue, dark theme |
| <img src="docs/screenshots/player.png" alt="The full player: artwork, title, a seek bar, skip back 15 and skip forward 30 buttons, and a 1.3x speed button." width="240"><br>Full player, with the Speed button | <img src="docs/screenshots/mini-player.png" alt="Feed Detail with the mini player at the bottom showing the playing Episode and a pause button." width="240"><br>Mini player | <img src="docs/screenshots/settings.png" alt="Settings: Playback, Downloads, and Sync and notifications sections." width="240"><br>Settings |

### Demo

[docs/demo.md](docs/demo.md) is a ten-step annotated walkthrough: connect, Sync, search, subscribe, open the Feed, play an Episode, look at the Queue, and mark an Episode Played, with the server call behind each step. [docs/demo/walkthrough.mp4](docs/demo/walkthrough.mp4) is a 58-second screen recording of the same flow.

Both were recorded on an emulator against a local server on the author's machine, **without sound**. They do not show the deployed server, because nothing is deployed yet (see [Deployment](#deployment)).

## Architecture

```mermaid
flowchart LR
  subgraph Phone["Android phone"]
    UI["Compose UI"]
    Repos["Repositories (the only callers of the server API)"]
    Room[("Room cache and outbox")]
    Work["WorkManager: Sync, downloads, cleanup"]
    Media["Media3 service (MediaLibraryService)"]
    UI --> Repos
    Repos --> Room
    Work --> Repos
    UI --> Media
    Media --> Room
  end
  Auto["Android Auto"] --> Media
  subgraph Host["PodClatch Server (FastAPI)"]
    Api["REST API, bearer token"]
    Sched["Scheduled Refresh"]
    Db[("Postgres, or SQLite when run plainly")]
    Api --> Db
    Sched --> Db
  end
  Repos -- "Sync, subscribe, search, Refresh (JSON)" --> Api
  Api -- "podcast search" --> ITunes["iTunes directory"]
  Api -- "fetch RSS on subscribe and Refresh" --> Pubs["Podcast publishers"]
  Sched -- "fetch RSS" --> Pubs
  Work -- "download audio directly" --> Pubs
  Media -- "stream audio while downloading" --> Pubs
  UI -- "artwork" --> Pubs
```

Feeds go in through the server, but audio never does: the app downloads or streams it straight from the publisher, and the server neither stores nor serves audio. Only repositories call the server API, and the Room cache lets everything except the server-backed actions work offline.

The delivery path, from a push to `main` to a running server (this is built and checked, but nothing has been deployed to AWS; see [Deployment](#deployment)):

```mermaid
flowchart LR
  Push["Push to main"] --> CI["Server CI passes"]
  CI --> Deploy["Deploy workflow"]
  Deploy -- "push image: commit SHA and dev tags" --> ECR[("Amazon ECR podclatch-server")]
  ECR -- "App Runner watches the dev tag" --> Dev["App Runner podclatch-dev"]
  Dev --> DevDb[("RDS PostgreSQL")]
  Promote["Promote workflow (manual)"] -- "retag the dev image as prod" --> ECR
  ECR -- "App Runner watches the prod tag" --> Prod["App Runner podclatch-prod"]
  Prod --> ProdDb[("RDS PostgreSQL")]
```

## Run it locally

You need Git, [Docker](https://www.docker.com/) with Compose v2 (for the server stack), and, for the app, Android Studio (or JDK 21 plus the Android SDK, with `ANDROID_HOME` set) and an emulator or an Android phone (Android 8.0, API 26, or newer). The whole path is two steps: start the server, then install the app and point it at the server.

### 1. Start the server

From the repository root:

```
cp .env.example .env
```

Edit `.env` and set `PODCLATCH_TOKEN` and `POSTGRES_PASSWORD` (any values you choose; letters, digits, `-`, and `_` only for the password), then:

```
make up
curl -i http://localhost:8180/healthz
```

The `curl` returns 200 and `{"status":"ok"}`. The sections [Running the server with Docker Compose](#running-the-server-with-docker-compose) and [Running the server](#running-the-server) below hold the full details, the `make` targets, and the same server without Docker.

### 2. Install the app and connect it

From `android/`, with the emulator running (or a phone connected with USB debugging):

```
./gradlew installDebug
```

If more than one device is attached, name the one to use first: `ANDROID_SERIAL=<serial> ./gradlew installDebug` (see `adb devices`). On Windows, `adb` is in `<Android SDK>\platform-tools`.

Let the device reach the server on your computer:

```
adb reverse tcp:8180 tcp:8180
```

Then, in the app:

1. Open PodClatch. On the first run it shows the connection screen.
2. Server URL: `http://127.0.0.1:8180` (use the port you set in `PODCLATCH_PORT` if you changed it).
3. Token: the `PODCLATCH_TOKEN` from your `.env`.
4. Press **Test connection**; it should say "Connected to 127.0.0.1:8180." Then press **Save and continue**.
5. To subscribe to a first Feed, open the **Feeds** tab and press **+**. Type a podcast name under "Search podcasts" (the server needs internet access to reach the iTunes directory), tap a result, and press **Subscribe**, or paste a feed address under "Feed address" and press **Subscribe**. The new Feed goes to the top, its Episodes appear as New, and the app starts downloading the first few, which then show up in the **Queue** tab.

Notes on addresses:

- `http://10.0.2.2:8180` also reaches a server on your computer from an emulator, without `adb reverse`. On Android 17 (API 37) and newer the system blocks connections to private-network addresses unless the app holds the "Local network access" permission, so PodClatch explains this and asks for it when you press Test connection. Loopback addresses such as `127.0.0.1` (the `adb reverse` path) need no permission.
- To use a phone over Wi-Fi, set `PODCLATCH_BIND_ADDRESS=0.0.0.0` in `.env` (read the warning under Ports below) and enter the computer's LAN address. Plain `http://` to anything other than `localhost`, `127.0.0.1`, and `10.0.2.2` works only in a debug build; a release build requires `https://`.

### Running the server

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
3. `make up` builds the image, starts the server, Postgres, and the Grafana stack, and returns once all are healthy.
4. Check it (use your token; replace 8180 if you set `PODCLATCH_PORT`):
   - `curl -i http://localhost:8180/healthz` returns 200 and `{"status":"ok"}`.
   - `curl -i -H "Authorization: Bearer <your token>" http://localhost:8180/v1/feeds` returns 200 and `[]` on a fresh volume.
   - `curl -i http://localhost:8180/v1/feeds` (no token) returns 401 with `{"detail":"Not authenticated."}`.

   **PowerShell:** `curl` is an alias for `Invoke-WebRequest` there and rejects `-i` and `-H`. Use `curl.exe` in place of `curl` in the three commands above (it ships with Windows 10 and later). If you prefer PowerShell's own commands: `Invoke-RestMethod http://localhost:8180/healthz`, and `Invoke-RestMethod http://localhost:8180/v1/feeds -Headers @{ Authorization = "Bearer <your token>" }`.

| Target | Raw command | What it does |
| --- | --- | --- |
| `make up` | `docker compose up -d --build --wait` | Build, start, and wait until all services (server, Postgres, and the Grafana stack) are healthy |
| `make down` | `docker compose down` | Remove the containers and network; the Postgres data and the telemetry data are kept |
| `make logs` | `docker compose logs -f server postgres` | Follow the server and Postgres logs |
| `make test` | `docker compose -f docker-compose.test.yaml run --rm --build test` | Run the server tests in a container |
| `make test-integration` | `uv run --directory server pytest tests_integration` | Run the end-to-end tests against a throwaway stack (server, Postgres, and a fake feed host) |
| `make test-e2e` | `python3 -I android/tools/e2e.py` | Run the real Android app on an attached emulator against its own throwaway stack (project `podclatch-e2e`, port 18180) |
| `make clean` | `docker compose down -v --remove-orphans` | Also delete the Postgres volume (**this erases all data**) and the telemetry volume |

**Ports.** The server listens on `127.0.0.1:8180` by default. It is not 8000 because other local projects commonly use 8000 (and 3000, 9090, 5432, and 5433, which this setup also avoids). Set `PODCLATCH_PORT` in `.env` to change it. It is bound to loopback only; to reach it from a phone on your LAN, set `PODCLATCH_BIND_ADDRESS=0.0.0.0` (anyone on the network can then reach the token-protected API, so only do this on a network you trust). Grafana is published on `127.0.0.1:3180` (change with `PODCLATCH_GRAFANA_PORT`; not 3000, for the same reason). Its bind address is intentionally not configurable: Grafana runs with anonymous admin access, so keep it on loopback. Postgres is not published to the host by default. To open it for debugging, add the override file, which publishes it on `127.0.0.1:5442` (change with `PODCLATCH_POSTGRES_PORT`): `docker compose -f docker-compose.yaml -f docker-compose.debug.yaml up -d`, then `psql -h localhost -p 5442 -U podclatch podclatch`.

**Telemetry.** `make up` also starts `lgtm` (the pinned `grafana/otel-lgtm` image: an OpenTelemetry Collector with Tempo, Loki, Prometheus, and Grafana). The server sends its traces, metrics, and logs to it over OTLP/HTTP on the Compose network; the OTLP ports are not published to the host, and nothing leaves your machine. Open Grafana at <http://localhost:3180> (no login). Data is exported about every 15 s, so wait about 30 s after a request.

- **Traces:** Explore, data source Tempo, search by service `podclatch-server`. A `POST /v1/refresh` request shows up as a trace whose root span is named `POST /v1/refresh`. `/healthz` is not traced.
- **Metrics:** Explore, data source Prometheus. Search for `podclatch_http_server_requests_total` (the request counter) and `podclatch_http_server_duration_seconds_bucket` (the latency histogram). The server names them `podclatch.http.server.requests` and `podclatch.http.server.duration`; Prometheus turns the dots into underscores and adds the suffixes.
- **Logs:** the server writes JSON logs to stderr (with `trace_id` and `span_id` while a span is active); read them with `make logs`. Because `OTEL_EXPORTER_OTLP_ENDPOINT` is set, the same records are also exported over OTLP to Loki: Explore, data source Loki, query `{service_name="podclatch-server"}`. Match a record's `trace_id` against the trace in Tempo.
- **Errors:** when a request fails with an unhandled exception (a 5xx), or a Feed fetch fails during a Refresh, the failure is exported so you can see what went wrong without the container. The server span (or the `fetch feed` span) gets an `exception` event and its error status description; the server also logs the request failure on the `podclatch_server.telemetry` logger, so Loki has a record with the same `trace_id`. What is exported: the exception type, the message, and a frame summary (`exception.type`, `exception.message`, `exception.stacktrace`). The message includes the `__cause__` and `__context__` chain (up to 5 links, each once) and is scrubbed and then cut to 1000 characters (the raw text is first cut to 2000 characters per exception, so scrubbing time is bounded), with `...[truncated]` marking a cut. The scrubbing, in `server/podclatch_server/scrub.py` (one function for every export path): the configured `PODCLATCH_TOKEN` and `TELEMETRY_GATEWAY_TOKEN` become `[redacted]` wherever they appear; every URL of any scheme is reduced to `scheme://host` (userinfo, port, path, query string, and fragment dropped; a bracketed IPv6 host is kept; a host written without `scheme://` or percent-encoded is not recognized as a URL); from a word such as `Authorization`, `Bearer`, `Basic`, `X-Api-Key`, or `Cookie` (any case, quoting, or spacing) to the end of the message becomes `[redacted]`, so ordinary text after those words is lost too; and any run of 24 or more characters of `A-Z a-z 0-9 _ -` (so also hex strings and tokens, and a snake_case name that long) becomes `[redacted]`. The frame summary is `path/module.py:line in function`, innermost last, at most 20 frames. Never exported: the raw exception text, source lines, local variables and arguments, request and response bodies, the original feed URL, tokens, or the traceback that `uvicorn.error` writes (that logger is not exported). The stderr log (`make logs`) still carries only the exception type name, so it differs from the exported record. **Limit:** the scrubber cannot recognize episode titles or descriptions. If a parser exception quotes feed text, up to 1000 characters of it can reach Loki and Tempo; the cap and review of the messages our own code raises are the controls, and an allowlist of exception types was considered and not chosen (issue #123).
- **Storage:** data lives in the named volume `podclatch-lgtm-data` and is disposable. `make down` keeps it, `make clean` deletes it.
- **Optional:** the server does not wait for `lgtm`, so `docker compose stop lgtm` leaves the API working. The test stacks (`make test-integration`, `make test-e2e`) do not publish Grafana, so they never collide with this port.

#### Telemetry gateway

`make up` also starts `telemetry-gateway` (`ops/telemetry-gateway/gateway.py`, standard-library Python in the `python:3.12-slim` image already used by the alert receiver): a read-only, bearer-token gateway to the Loki, Tempo, and Prometheus inside `lgtm`, so the on-call agent (#35) can query telemetry without Grafana (anonymous admin) or the raw backends being exposed. It is published on `127.0.0.1:8380` only (change with `PODCLATCH_TELEMETRY_GATEWAY_PORT`).

- **Token:** generate one yourself (`openssl rand -hex 32`) and write it into the git-ignored `.env` as `TELEMETRY_GATEWAY_TOKEN=...`; then `docker compose up -d telemetry-gateway`. With the token unset, `make up` still works and the gateway answers every request with 503 (fail closed). A request without `Authorization: Bearer <token>`, or with a wrong one, gets 401 (compared in constant time) and nothing is sent upstream.
- **Paths** (one prefix per backend): Loki `/loki/loki/api/v1/{query,query_range,labels}` and `/loki/loki/api/v1/label/<name>/values`; Tempo `/tempo/api/traces/<hex id>` and `/tempo/api/search`; Prometheus `/prom/api/v1/{query,query_range}`. Only `GET` and `HEAD`. Everything else (Grafana, Loki push, admin, `/metrics`, any other method) gets 403 or 405. The raw path is matched as-is against the allowlist and never decoded or normalised, so a path with a `..` segment or a `%2f` is refused (a leading `//` is collapsed to `/` by Python's HTTP server before the check, which does not widen the allowlist). A query string must be printable ASCII with no spaces (percent-encode the rest) or the gateway answers 400, and a client that stays silent for 5 s is disconnected.
- **Limits:** a response over **4 MiB** is refused (502) instead of streamed, and an upstream call is cut off after **20 s** (504).
- **Logs:** `docker compose logs telemetry-gateway` shows the method, path, and status only, never the query string or the `Authorization` header.
- **Example, bash** (reads the token from `.env` without printing it):

  ```bash
  TOKEN=$(grep '^TELEMETRY_GATEWAY_TOKEN=' .env | cut -d= -f2- | tr -d '\r')
  curl -sG -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8380/loki/loki/api/v1/query_range \
    --data-urlencode 'query={service_name="podclatch-server"}' --data-urlencode 'limit=5'
  ```

- **Example, PowerShell** (use `curl.exe`: plain `curl` is a PowerShell alias for `Invoke-WebRequest`; the token is read from `.env` without printing it):

  ```powershell
  $t = ((Get-Content .env | Where-Object { $_ -like 'TELEMETRY_GATEWAY_TOKEN=*' }) -replace '^TELEMETRY_GATEWAY_TOKEN=','').Trim('"')
  curl.exe -sG -H "Authorization: Bearer $t" http://127.0.0.1:8380/loki/loki/api/v1/query_range --data-urlencode 'query={service_name="podclatch-server"}' --data-urlencode 'limit=5'
  ```

  A wrong token must answer `401` (nothing is sent upstream):

  ```powershell
  curl.exe -s -o NUL -w "%{http_code}`n" -H "Authorization: Bearer wrong" http://127.0.0.1:8380/loki/loki/api/v1/query_range
  ```

- **Warning:** the gateway is the only thing that may ever be tunnelled. Never expose Grafana (3180) or the raw Loki, Tempo, or Prometheus ports. The tunnel itself is #119.
- **Tests:** `make test-integration` and `make test-e2e` do not start it (its profile is never enabled there). `ops/tests/test_telemetry_gateway.py` checks it statically and, when Docker is available, against a throwaway project `podclatch-t117`.

#### Alerting

`make up` also provisions an alert in Grafana, defined as code in `ops/grafana/provisioning/alerting/` (mounted read-only into `lgtm`; no clicking in the UI). It fires when the server is failing its users.

- **Rule** `PodClatch Server 5xx ratio high` (Grafana, Alerting, Alert rules, folder `PodClatch`): more than **5%** of all requests, over all routes, in a **5 minute** window are answered with a 5xx (`podclatch_http_server_requests_total{http_response_status_class="5xx"}`), and this lasts **5 minutes** (`for: 5m`). The rule is evaluated every 1 minute.
- **Not on idle traffic:** the rule also needs **at least 3 requests** in the window. One failed request on an idle server, or no requests at all, stays Normal.
- **Labels and annotations:** `service=podclatch-server`, `severity=critical`, plus `summary` and `description` in plain words. They carry no token, feed URL, or episode title.
- **Webhook:** the notification policy sends it to the contact point `podclatch-webhook`, a POST to `http://alert-receiver:8080/`. `alert-receiver` is a tiny test receiver in the Compose stack (`ops/alert-receiver/receiver.py`, standard-library Python, no secret, no internet). It prints each payload to its log and keeps the latest one at `GET /last`. It is published on `127.0.0.1:8280` only (change with `PODCLATCH_ALERT_RECEIVER_PORT`); the test stacks do not start it. If it is stopped, the server and `make up` are not affected. The same alert also goes to GitHub (see "Dispatch to GitHub" below).

**Make it fire, and clear it.** Run from the repository root, with `make up` done and your token in `.env`. The server answers 500 while Postgres is down, because it cannot reach its database.

1. `docker compose stop postgres`
2. Send an authenticated request every 15 seconds so there is traffic to count (bash; replace the port and token, and leave it running):
   `while true; do curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer <your token>" http://localhost:8180/v1/feeds; sleep 15; done`
   (PowerShell: `while ($true) { curl.exe -s -o NUL -w "%{http_code}`n" -H "Authorization: Bearer <your token>" http://localhost:8180/v1/feeds; Start-Sleep 15 }`.) Each request answers `500` after a few seconds.
3. Watch Grafana, Alerting, Alert rules: the rule goes Normal, then Pending, then Firing. Expect Pending about 1 to 2 minutes after the first failure (metric export about 15 s, Prometheus scrape, then the 1 minute evaluation) and Firing about 5 minutes later, so roughly 6 to 8 minutes in total.
4. `docker compose logs alert-receiver` (or `curl http://localhost:8280/last`) shows the webhook JSON with `"status": "firing"` shortly after the rule fires.
5. To clear it: `docker compose start postgres`. The requests answer 200 again, the failures age out of the 5 minute window, the rule returns to Normal, and the receiver gets `"status": "resolved"`. Expect this within roughly 5 to 7 minutes. Stop the loop with Ctrl+C.

**Observed (one run on a fresh stack):** first 500 at 0:00, rule Pending at about 2 minutes, Firing and the `firing` webhook at about 7 minutes, Postgres started right after, and Grafana sent the `resolved` webhook about 6 minutes after that (about 13 minutes end to end). Grafana's links use `http://localhost:3000/` (its address inside the container), so open the rule from Alerting at port 3180 instead of following them.

**Example payload** (a real capture, shortened to the fields the on-call agent, #35, matches on; `alertname`, the label names, and the annotation names are a stable contract; the `resolved` payload has the same shape with `"status": "resolved"` and a real `endsAt`):

```json
{
  "receiver": "podclatch-webhook",
  "status": "firing",
  "alerts": [
    {
      "status": "firing",
      "labels": {
        "alertname": "PodClatch Server 5xx ratio high",
        "grafana_folder": "PodClatch",
        "service": "podclatch-server",
        "severity": "critical"
      },
      "annotations": {
        "summary": "PodClatch Server is answering requests with server errors",
        "description": "More than 5% of the requests to PodClatch Server were answered with a 5xx error over the last 5 minutes (at least 3 requests in that window), and this has lasted 5 minutes. Users are likely seeing failures. Check the server and Postgres logs."
      },
      "startsAt": "2026-10-31T22:08:40Z",
      "endsAt": "0001-01-01T00:00:00Z",
      "generatorURL": "http://localhost:3000/alerting/grafana/podclatch-5xx-ratio/view?orgId=1",
      "ruleUID": "podclatch-5xx-ratio",
      "values": { "A": 1, "B": 1, "C": 1 }
    }
  ],
  "groupLabels": { "alertname": "PodClatch Server 5xx ratio high", "service": "podclatch-server" },
  "externalURL": "http://localhost:3000/",
  "title": "[FIRING:1] PodClatch Server 5xx ratio high podclatch-server (PodClatch critical)",
  "state": "alerting"
}
```

**Dispatch to GitHub (issue #113).** The notification policy sends every PodClatch alert to two contact points: `podclatch-webhook` (the local receiver above, unchanged) and `podclatch-github-dispatch`, which POSTs to `https://api.github.com/repos/jdobbelaar/podclatch/dispatches` (the `repository_dispatch` API) so a GitHub Actions workflow can be woken. This goes out from your machine to GitHub; GitHub never calls Grafana, so no tunnel is needed for this part.

- **Only the 5xx alert is dispatched:** the dispatch route also matches `alertname = PodClatch Server 5xx ratio high`, so `DatasourceError` and `NoData` alerts reach only the local receiver.
- **Event type:** `podclatch-alert`. The workflow `.github/workflows/oncall.yml` (see "On-call agent" below) runs on `repository_dispatch` with `types: [podclatch-alert]`. A workflow runs only once it is on `main`.
- **Body:** `{"event_type": "podclatch-alert", "client_payload": {...}}`. `client_payload` has 7 properties (GitHub allows 10): `status` (`firing` or `resolved`), `alertname`, `severity`, `service`, `summary` (query strings removed, cut to 300 characters), `started_at`, and `rule_uid`. It is rendered by the template in `ops/grafana/provisioning/alerting/templates.yaml` and serialized as JSON, so quotes and newlines in a value cannot break it. It carries no token and no feed URL.
- **Token:** put a fine-grained GitHub token (this repository only, Contents read and write, 30-day expiry) in your git-ignored `.env` as `GITHUB_DISPATCH_TOKEN` (see `.env.example`). `docker-compose.yaml` passes it to the `lgtm` service only, and Grafana reads it as `$__env{GITHUB_DISPATCH_TOKEN}` and sends it as `Authorization: Bearer`. The value is never in a file in the repository. Do not print it: no `cat .env`, `docker compose config`, `docker inspect`, or `env` inside `lgtm`.
- **Unset is fine:** if `GITHUB_DISPATCH_TOKEN` is not set, `make up` still works; the dispatch contact point has an empty credential, GitHub is expected to answer 401 (expected, not verified), and Grafana logs a notification error. The local receiver still gets the alert, and the alert state is not affected. The same happens if GitHub is unreachable.
- **Rotation:** the token expires after 30 days. Grafana stores the token (encrypted) in its own database the first time it provisions the contact point and does not refresh it from the environment on later starts, so replacing the value in `.env` and running `docker compose up -d lgtm` alone is not enough (the old token keeps being sent; removing the variable keeps it too). To rotate, replace the value in `.env`, then delete only Grafana's own database file, which is rebuilt from the provisioning files on the next start (Prometheus, Loki, and Tempo data and your Postgres data are not touched; the alert history and any silences are reset):

  ```
  docker compose stop lgtm
  docker compose run --rm --no-deps --entrypoint rm lgtm -f /data/grafana/data/grafana.db
  docker compose up -d lgtm
  ```

  When dispatch stops working, suspect an expired token first (HTTP 401 from GitHub is expected, not verified; visible in `docker compose logs lgtm` as a notification error on `podclatch-github-dispatch`).

**Dry run (does not call GitHub).** The contact point `podclatch-dispatch-dryrun` uses the same payload template but sends to the local receiver, and is not in the notification policy. In Grafana, Alerting, Contact points, `podclatch-dispatch-dryrun`, press Test, then read the rendered body with `curl http://localhost:8280/last`. The checks in `ops/tests/test_alerting.py` do the same for hard cases (quotes, a backslash, newlines, a 2000 character summary, a query string):

```
PODCLATCH_DRYRUN_GRAFANA=http://localhost:3180 PODCLATCH_DRYRUN_RECEIVER=http://localhost:8280 \
  uv run --no-project --with pytest --with pyyaml pytest ops/tests -k live_dry_run
```

A rendered body (token-free) looks like this; the `resolved` body has the same shape with `"status": "resolved"`:

```json
{
  "client_payload": {
    "alertname": "PodClatch Server 5xx ratio high",
    "rule_uid": "podclatch-5xx-ratio",
    "service": "podclatch-server",
    "severity": "critical",
    "started_at": "2026-10-31T23:16:40Z",
    "status": "firing",
    "summary": "PodClatch Server is answering requests with server errors"
  },
  "event_type": "podclatch-alert"
}
```

**One real dispatch (manual, once, after the workflow is on `main`).** With the stack up and the token in `.env`, make the alert fire (the steps above) or press Test on `podclatch-github-dispatch` in Grafana, then look for the run: `gh run list --workflow oncall.yml`. Do not loop it.

**Search directory.** `GET /v1/search` calls the iTunes directory at `https://itunes.apple.com/search`. The optional variable `ITUNES_SEARCH_URL` (must start with `http://` or `https://`) points it somewhere else; the integration tests use it to reach a fake host. Normal use does not set it, and `docker-compose.yaml` does not pass it through, so `make up` calls the real iTunes.

**Access log.** The server drops the query string from every request line in its access log (for example `GET /v1/search?q=...` is logged as `GET /v1/search`), because feed URLs and search terms can carry secrets.

**Tests.** `make test` runs the server tests (SQLite) in a container, so it needs only Docker and no `.env`. The host-side `uv run pytest` above still works and is unchanged.

**Integration tests.** `make test-integration` runs the suite in `server/tests_integration/` over HTTP against the real production image and Postgres, with a small fake feed host standing in for publishers, so it needs no internet. The pytest session itself starts and removes the stack, running `docker compose -p podclatch-it -f docker-compose.yaml -f docker-compose.integration.yaml ...` through one helper, which is why the "Raw command" cell is the `uv` command and not a `docker compose` one. The project name `podclatch-it` gives it its own containers, network, and Postgres volume, so your dev stack and its data are never touched. It covers subscribe, Episode state, Sync, unsubscribe, conflicting writes, Refresh, persistence across a restart and a full down and up, authentication, and feed URL secrecy. `uv run pytest` and `make test` do not run it.

- **Prerequisites:** Docker, [uv](https://docs.astral.sh/uv/), and a root `.env` with both `PODCLATCH_TOKEN` and `POSTGRES_PASSWORD` (the process environment is read first, then `.env`, as Compose does). Without them the run stops at once with a message naming `.env` and `.env.example`, before any container is created.
- **Port:** the test stack publishes the server on `127.0.0.1:8180` (`PODCLATCH_PORT`), the same default as the dev stack. If the dev stack (`make up`) or anything else holds that port, the run fails fast and changes nothing; run `make down` or set `PODCLATCH_PORT=8181` for the run. The fake feed host is published only on a Docker-assigned loopback port, and Postgres is not published.
- **Runtime:** about 1 minute once the image is built, a few minutes on a first run that has to build it and pull base images.
- **Failures:** if a test fails or the stack does not start, the logs of all services are printed before the stack is removed. The stack is always removed (`down -v --remove-orphans`), also after a failure.

### On-call agent

When the Grafana alert "PodClatch Server 5xx ratio high" fires, a GitHub Actions workflow (`.github/workflows/oncall.yml`) can run Claude Code headlessly, diagnose the fault from telemetry, and open a pull request with a failing test and a fix. **The agent never merges: the human reads and merges.** This section covers the workflow, the prompt, and the guard rails (US-38, issue #35). The read-only telemetry gateway (#117), the seeded fault (#118), and the tunnel with the live demo (#119) are separate issues; until they are done, the agent can only be exercised with the dry run and the fixtures below.

**Data path.** Grafana (your laptop) -> `repository_dispatch` (event type `podclatch-alert`) -> GitHub runner. The runner then calls back to the laptop through a tunnel and the read-only telemetry gateway, which sits in front of Loki, Tempo, and Prometheus. Grafana (anonymous admin) and the raw backends are never tunnelled. `repository_dispatch` only runs the copy of the workflow on the default branch.

**Jobs.** `gate` validates the payload with `ops/oncall/validate_payload.py` (only a firing alert named "PodClatch Server 5xx ratio high" for `podclatch-server`, with strict patterns for `rule_uid` and `started_at` and a summary of at most 300 characters; anything else is "skipped" and no agent runs), applies the kill switch, and runs the duplicate and rate guards. `diagnose` has `contents: read` only, runs the agent with a tool allowlist, and uploads the prompt, the command, the report, and a patch as an artifact. `open-pr` has `contents: write` and `pull-requests: write` but no agent and no key: it re-validates the patch with `ops/oncall/validate_patch.py` (only `server/podclatch_server/` and `server/tests/`, no dependency files, no renames, binaries, or symlinks, at most 300 changed lines, no secret or token-shaped text), applies it on a branch `oncall/podclatch-server-5xx-ratio-high-<run id>`, and opens a pull request labelled `oncall-agent`. Neither job uses `pull_request_target` or a self-hosted runner.

**Names to set on the repository (values are never in git).**

| Kind | Name | Purpose |
|---|---|---|
| Secret | `ANTHROPIC_API_KEY` | A dedicated key with a spend limit set in the Anthropic console. Only the "Run the agent" step sees it. |
| Secret | `TELEMETRY_GATEWAY_TOKEN` | Bearer token for the gateway (same value as in `.env`, see #117). Only the "Run the agent" and "Check the telemetry gateway" steps see it. |
| Variable | `TELEMETRY_URL` | Base URL of the tunnel to the gateway. Unset means fixture mode (see below). |
| Variable | `ONCALL_ENABLED` | Kill switch. Anything other than exactly `true` makes every real run do nothing. A dry run is not blocked, because it costs nothing and needs no secret. |
| Variable | `ONCALL_BASE_BRANCH` | Optional. Branch the agent works from and its pull request targets; default `main`. |
| Setting | Settings -> Actions -> General -> "Allow GitHub Actions to create and approve pull requests" | Needed for `open-pr`. |

**Turn it on and off.** On: set `ONCALL_ENABLED` to `true`. Off: delete the variable or set it to anything else; a dispatch then ends in the `gate` job with "skipped" and nothing else runs.

**Dry run (no secret, no tunnel, no money).** Actions -> "On-call agent" -> Run workflow, with `dry_run` on (the default), or `gh workflow run oncall.yml` (it needs to be on the default branch, or pass `--ref <branch>`). It validates the payload (`ops/oncall/example-payload.json` unless you paste one into `payload`), then writes the exact prompt and the exact `claude` command line to the `oncall-out` artifact and stops: no Anthropic call, no tunnel, no branch, no pull request. `check_only` with `dry_run` off only runs `python3 ops/oncall/query.py --check` against the gateway and reports HTTP 200 with the token and 401 without it (used by #119).

**Fixture mode (no tunnel).** With `TELEMETRY_URL` unset, `ops/oncall/query.py` serves the canned log, trace, and metric exports in `ops/oncall/fixtures/` instead of calling a gateway, so the agent can run end to end without a tunnel. It needs `ANTHROPIC_API_KEY`, `ONCALL_ENABLED`, and `dry_run` off. Use `base_branch` to aim the pull request at a throwaway branch, and close that pull request yourself.

**The agent's limits.** Runner: the pinned `@anthropic-ai/claude-code` CLI (2.1.316) run as `claude -p`, not a third-party action, so the tool allowlist and the turn limit are plain command-line flags. Model `claude-sonnet-5-5`, `--max-turns 30`, `--max-budget-usd 3`, `--permission-mode dontAsk` (anything not allowed is denied; there is no bypass mode). Allowed tools: Read, Grep, Glob; Edit and Write only under `server/podclatch_server/` and `server/tests/`; Bash only for `uv run pytest`, `uv run ruff`, `git diff`, `git status`, and `python3 ops/oncall/query.py`. No WebFetch, WebSearch, `curl`, `wget`, `gh`, `git push`, or MCP servers. Telemetry only reaches the agent through `ops/oncall/query.py`, which talks only to the configured host, has named read-only queries (`logs`, `trace`, `metric`) with bounded time ranges and limits, caps and truncates its output, never prints the token, and wraps every result as untrusted data. Rate and cost limits: one run at a time (`concurrency` group, a second dispatch queues), at most 3 agent runs per 24 hours, no new run while an open `oncall-agent` pull request exists for the same alert (both show as "skipped" with the reason in the run summary), a 20 minute job timeout, and only the 5xx alert dispatches. Cost of one run: not measured yet; the ceiling is the `--max-budget-usd 3` flag, and the first real run's `run-stats.json` in the artifact (turns and cost) will replace this sentence.

**Residual risk.** `uv run pytest` runs code the agent just wrote, inside the step that holds `ANTHROPIC_API_KEY` and `TELEMETRY_GATEWAY_TOKEN`, so a successful prompt injection could in principle read both. That is why the Anthropic key must be spend-limited and the telemetry credential read-only and revocable, and why the gateway is the only thing ever tunnelled. A pull request opened with `GITHUB_TOKEN` does not trigger CI; the agent runs the server tests and ruff itself and says so in the pull request. Read the diff like any other change before you merge it.

### On-call agent demo

The fault is deliberate: it is seeded on purpose so the on-call agent has something real to diagnose, and it must never reach `main`.

- **Branch:** `demo/seeded-fault`, cut from `main`, with exactly one fault commit on top (`git diff origin/main...origin/demo/seeded-fault` shows all of it). Never merge it and never open a pull request from it into `main`. Ask the engineer to rebase it when `main` moves. CI on this branch may fail on purpose, and no required check depends on it.
- **The fault:** in `server/podclatch_server/feeds.py`, an `<itunes:duration>` of the shape `H:MM:SS.fff` (fractional seconds, for example `01:02:03.500`) raises an unhandled `ValueError` instead of falling back to "no duration". `POST /v1/feeds` for such a feed answers 500. (A scheduled Refresh of an already-subscribed feed catches the error per Feed and reports it in its result, so it does not answer 5xx; a feed with this duration cannot be subscribed in the first place.) The exception message names the field and the shape. `server/tests/test_seeded_fault.py` on that branch proves it.
- **Fixture:** `server/tests/fixtures/seeded_fault_fractional_duration.xml`, on the demo branch only (no real feed URL, no secret).
- **Trigger script:** `ops/oncall/trigger-fault.sh` (on `main`). The fixture is not on `main`, so the script takes it from a checkout of the demo branch: run it from a checkout (or `git worktree add ../podclatch-demo demo/seeded-fault`) of `demo/seeded-fault`, or point `ONCALL_FIXTURE` at a copy. The server you test must be built from that branch too.

  ```bash
  git worktree add ../podclatch-demo demo/seeded-fault   # once
  cd ../podclatch-demo && cp /path/to/your/.env .env && docker compose -p podclatch-demo up -d --build --wait
  bash ops/oncall/trigger-fault.sh                        # Ctrl+C to stop
  docker compose -p podclatch-demo down -v                # afterwards: removes only the demo's volumes
  ```

  Use `-p podclatch-demo`, not `make up`: `docker-compose.yaml` fixes `name: podclatch`, so `make up` in the demo checkout would run the fault server on your real Postgres volume. The demo stack needs the same host ports as your own, so run `docker compose stop` in your own checkout first (it keeps the containers and volumes) and `docker compose start` afterwards.

  It serves the fixture from a throwaway web server on `127.0.0.1:8480`, subscribes to it with the token from `.env` (read from the file, handed to curl on stdin, never printed) every 15 seconds, and prints only the HTTP status. It refuses any server URL whose host is not `localhost` or `127.0.0.1`. The server (in Docker) reaches the fixture through `host.docker.internal` (Docker Desktop); on Linux set `ONCALL_FIXTURE_HOST` to the host's address on the Docker network. Other knobs are listed at the top of the script (`ONCALL_SERVER_URL`, `ONCALL_FIXTURE_PORT`, `ONCALL_INTERVAL`, `ONCALL_MAX_REQUESTS`, `ONCALL_ENV_FILE`).
- **What you will see** (one run in a throwaway Compose project built from the demo branch; first 500 at 0:00): the alert rule was Pending 30 seconds later and Firing after 5 minutes 30 seconds, and the local receiver got the `firing` webhook. `docker compose logs server` shows the traceback ending in `ValueError: itunes:duration with fractional seconds is not handled (shape H:MM:SS.fff, value '01:02:03.500')`. Loki (`{service_name="podclatch-server"}`) has the access-log line `POST /v1/feeds ... 500` but not the exception text (the server deliberately never exports exception messages, decision #27). Tempo (`{ status = error }`) has a `POST /v1/feeds` trace with status Error, `error.type=ValueError`, and `http.response.status_code=500`. So the telemetry points to the route and the exception type, and the agent finds the cause in the code.
- **Stop it:** Ctrl+C the script. The fault then stops, and the alert clears about 5 to 7 minutes later. Nothing is stored (the subscribe never succeeds).

#### Tunnel to the laptop with Tailscale (US-38c, issue #119)

The chosen tunnel is Tailscale (option A on #35): a private network ("tailnet") that only your own devices and the tagged runner join. Nothing is public. Facts below were checked against Tailscale's documentation on 2026-11-07; if the admin console differs, follow the console.

**What is exposed, for how long, how to revoke.** Exposed: the `telemetry-gateway` only (read-only log, trace, and metric queries behind the bearer token and a path allowlist), reachable only from a GitHub runner that joined your tailnet with the tag `tag:podclatch-runner`, on one port. Not exposed: Grafana (3180, anonymous admin), Loki, Tempo, Prometheus, the server, and Postgres. It is up only while `tailscale serve` is running; the runner's node is ephemeral and disappears when its job ends (the OAuth credential, however, lives until you delete it). Revoke with section 8 below. `tailscale serve` shares inside the tailnet; never use `tailscale funnel`, which publishes to the whole internet.

**Free-plan limits (Personal plan, from the pricing page):** up to 6 users, unlimited user devices, 50 tagged resources included, and 1,000 ephemeral-resource minutes per month; one check or demo run uses about a minute. The pricing page does not mention grants, OAuth clients, or Serve, so the admin console is the authority on those. Nothing here needs a paid plan as far as the documentation shows. Creating the account is the human's job.

Run the steps in order. Laptop commands are PowerShell, from the repository root.

1. **Install the client and sign in** (Windows): install from <https://tailscale.com/download>, sign in, and confirm with `tailscale status`. In the admin console (<https://login.tailscale.com/admin>), on the DNS page, make sure MagicDNS and HTTPS Certificates are enabled (`tailscale serve` uses HTTPS on your `*.ts.net` name).
2. **Tags and access rules.** In the admin console open Access controls and add the contents of [`ops/oncall/tailnet-policy.example.json`](ops/oncall/tailnet-policy.example.json) to your policy file (merge the `tagOwners` and `grants` entries into what is there; do not delete your existing rules, and remove the default allow-all rule only if you understand what else relies on it). It declares two tags and exactly one grant: `tag:podclatch-runner` may reach `tag:podclatch-laptop` on `tcp:443`, and nothing else. Port 443 is the port `tailscale serve` listens on, not the gateway's 8380.
3. **Tag the laptop.** Admin console, Machines, your laptop, Edit ACL tags, add `tag:podclatch-laptop`. (A tagged device is owned by the tag, not by you, so your own devices, such as your phone, cannot see or reach it until a grant allows them: see [Use the app on your phone over Tailscale](#use-the-app-on-your-phone-over-tailscale). To undo the tag, remove it and re-authenticate the client.)
4. **Create the OAuth client.** Admin console, Settings, Trust credentials, Credential, OAuth. Give it only the scope `auth_keys` with write access, and choose the tag `tag:podclatch-runner`. Generate it and copy the client ID and secret straight into GitHub (next step); the secret cannot be shown again. Do not paste either into chat, an issue, or a file.
5. **GitHub settings** (Settings, Secrets and variables, Actions; or `gh secret set NAME` and `gh variable set NAME`, which prompt for the value so it never lands in your shell history):

   | Kind | Name | Value |
   |---|---|---|
   | Secret | `TS_OAUTH_CLIENT_ID` | The client ID from step 4 |
   | Secret | `TS_OAUTH_SECRET` | The client secret from step 4 |
   | Secret | `TELEMETRY_GATEWAY_TOKEN` | The same value as in your local `.env` (generate with `openssl rand -hex 32` if unset, then `docker compose up -d telemetry-gateway`) |
   | Variable | `TELEMETRY_URL` | `https://<laptop>.<tailnet>.ts.net` from step 6 (no path, no trailing slash) |
   | Variable | `ONCALL_ENABLED` | `true` (the kill switch; set it just before the live run) |
   | Secret | `ANTHROPIC_API_KEY` | A dedicated, spend-limited key (the live run only; the check does not need it) |
   | Setting | Settings, Actions, General | Tick "Allow GitHub Actions to create and approve pull requests" (the live run only) |

   The secret names `TS_OAUTH_CLIENT_ID` and `TS_OAUTH_SECRET` are the ones `tailscale/github-action` examples use; the workflow must read exactly these names (see the note after step 7).
6. **Start the stack, then the tunnel, pointing only at the gateway.**

   ```powershell
   make up
   docker compose ps telemetry-gateway      # it must be running (published on 127.0.0.1:8380)
   tailscale serve --bg --https=443 http://127.0.0.1:8380
   tailscale serve status
   ```

   `tailscale serve status` prints the `https://<laptop>.<tailnet>.ts.net` address and shows that it proxies to `127.0.0.1:8380` and to nothing else. If anything else is listed, run `tailscale serve reset` and start again. Use that address for `TELEMETRY_URL`. Never serve 3180 (Grafana), the Loki, Tempo, or Prometheus ports, or the server's own port.
7. **Check the connection (no agent, no Anthropic call).** Actions, "On-call agent", Run workflow, with `dry_run` off and `check_only` on (or `gh workflow run oncall.yml -f dry_run=false -f check_only=true`; needs `ONCALL_ENABLED=true`). The runner joins the tailnet as an ephemeral node tagged `tag:podclatch-runner`, then `python3 ops/oncall/query.py --check` must report HTTP 200 with the token and 401 without it. Put the run link in the PR or issue. You can also test from another tailnet device: `Invoke-WebRequest https://<laptop>.<tailnet>.ts.net/` should be refused by the grant (a device that is not `tag:podclatch-runner` has no access).

   **The workflow joins the tailnet itself.** The `diagnose` job in `.github/workflows/oncall.yml` has a "Join the tailnet" step (the official `tailscale/github-action`, with `TS_OAUTH_CLIENT_ID`, `TS_OAUTH_SECRET`, and `tags: tag:podclatch-runner`) that runs for `check_only` and the live run, before the gateway is called. It is skipped when the `TELEMETRY_URL` variable is unset (fixture mode), so step 7 needs `TELEMETRY_URL` set.
8. **Stop the tunnel and revoke.** Right after the demo:

   ```powershell
   tailscale serve reset                     # stop serving; the gateway is no longer reachable from the tailnet
   gh variable delete ONCALL_ENABLED         # the kill switch off
   gh variable delete TELEMETRY_URL
   gh secret delete TELEMETRY_GATEWAY_TOKEN
   gh secret delete TS_OAUTH_SECRET
   gh secret delete TS_OAUTH_CLIENT_ID
   gh secret delete ANTHROPIC_API_KEY
   ```

   Then in the admin console: delete the OAuth client (Settings, Trust credentials), remove the grant and the two tags from the policy file, and remove the laptop's tag. Finally rotate the gateway token locally: write a new `TELEMETRY_GATEWAY_TOKEN` in `.env` (or remove the line to make the gateway answer 503 to everything) and run `docker compose up -d telemetry-gateway`. To leave the tailnet entirely, run `tailscale down` or log out in the client. The OAuth client is the standing credential, so deleting it is the step that matters most.

## Run the tests

| Command | Run from | What it covers | What it needs |
| --- | --- | --- | --- |
| `./gradlew build test` | `android/` | Builds the app and runs its JVM unit tests (business rules, repositories, screen controllers, the Sync engine, the download engine, and the Android Auto browse tree) | JDK 21, Android SDK |
| `./gradlew lint` | `android/` | Android lint | JDK 21, Android SDK |
| `./gradlew connectedDebugAndroidTest` | `android/` | The instrumented suite: Room, Compose screens, the playback service, a UI smoke test with a fake server API. Android's summary also counts the end-to-end class as one skipped "failure" when it is run without the `make test-e2e` arguments; the build is still green | One emulator or device; set `ANDROID_SERIAL` so a phone is never picked by accident |
| `uv run pytest` | `server/` | The server's unit and API tests on SQLite; the Postgres variants are skipped unless `PODCLATCH_TEST_DATABASE_URL` is set | [uv](https://docs.astral.sh/uv/) |
| `uv run ruff check .` | `server/` | Server lint | uv |
| `make test` | repository root | The same server tests, in a container | Docker |
| `make test-integration` | repository root | The server and Postgres from Compose, with a fake feed host, over HTTP | Docker, uv, a root `.env` (see above) |
| `make test-e2e` | repository root | The real app on an emulator against the real server in its own Compose project: connect, subscribe, download, play, see the Queue, mark Played, and check that the state reached the server | An emulator (exactly one device, or `ANDROID_SERIAL`), Docker, Python 3, JDK 21, Android SDK |
| `uvx openapi-spec-validator@0.7.2 openapi.yaml` | repository root | The API contract is a valid OpenAPI file | uv |
| `uvx cfn-lint==1.58.0 infra/bootstrap.yaml infra/app.yaml`, then `uv run --no-project --with pytest --with pyyaml pytest infra/tests` | repository root | The AWS templates, checked statically (no AWS account is used) | uv |
| `python -m unittest discover -s .github/scripts` | repository root | The helper script the deploy and promote workflows call | Python 3 |
| `python -I -m unittest discover -s android/tools -p "test_*.py"` | repository root | The test fixtures themselves (the audio server and the end-to-end helper). Not run in CI | Python 3 |

CI runs most of these (see below). `make test-e2e` runs in CI only on pushes to `main` and by hand, not on pull requests.

## CI/CD

All workflows are in `.github/workflows/`.

| Workflow (file) | Runs when | What it does |
| --- | --- | --- |
| Android CI (`android-ci.yml`) | Every pull request and every push to `main` | `./gradlew build test`, then uploads the debug APK as the artifact `podclatch-debug-apk` (kept 14 days) |
| Server CI (`server-ci.yml`) | Every pull request and every push to `main` | Four jobs: pytest and ruff on SQLite; the same on Postgres; build the image, start the Compose stack, and smoke-test it; and `make test-integration` |
| OpenAPI CI (`openapi-ci.yml`) | Every pull request and every push to `main` | Validates `openapi.yaml` |
| Infra CI (`infra-ci.yml`) | Pull requests and pushes to `main` that touch `infra/` | `cfn-lint` and the template tests |
| Scripts CI (`scripts-ci.yml`) | Pull requests and pushes to `main` that touch `.github/scripts/` | Unit tests of the deploy helper |
| End-to-end test (`e2e.yml`) | Pushes to `main`, and by hand | Runs `make test-e2e` on an emulator on a Linux runner (about 10 to 25 minutes) |
| Deploy (`deploy.yml`) | After Server CI succeeds on a push to `main` | Builds the server image once, pushes it to ECR tagged with the commit SHA and `dev`, waits for App Runner to deploy it, and checks `/healthz` |
| Promote (`promote.yml`) | By hand only | Moves the `prod` tag to the image that currently carries `dev` (no rebuild), waits for App Runner, and checks `/healthz` |
| Release APK (`release-apk.yml`) | When a tag starting with `v` is pushed | Builds a signed release APK and attaches it to a GitHub Release |

**Until you configure AWS, Deploy and Promote are skipped, not failed.** Their jobs only run when the repository variable `AWS_DEPLOY_ROLE_ARN` is set (and, for Promote, `PROD_URL`), so `main` stays green. The AWS login uses GitHub's OpenID Connect, so no AWS key is stored anywhere. **Release APK** fails on purpose, with a clear message, if a tag is pushed without the signing secrets.

What the author sets up once (the exact steps are in the comments at the top of each workflow file, and the `TODO(human)` items in [Deployment](#deployment) and [Get the app](#get-the-app)):

- In Settings, Environments, create `dev` and `production`, and for both limit "Deployment branches and tags" to `main`.
- In Settings, Secrets and variables, Actions, add the repository variables `AWS_REGION`, `AWS_DEPLOY_ROLE_ARN`, `DEV_URL`, and `PROD_URL`.
- For signed releases, add the four repository secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`.

**Rolling back.** Promote refuses any image that is not the one currently tagged `dev`, so it cannot be pointed at an older image. To roll back, revert the change on `main`, let Deploy run (that puts the reverted code on `dev`), and then run Promote. Two more limits of the deploy check: if App Runner rolls a failed deploy back by itself, the pipeline cannot see it, and pushing an image identical to the running one starts no deployment.

## Deployment

**Not deployed yet.** The deployed server's address will go here once it exists, and the app has so far been run only against local servers.

`TODO(human)`: deploy `dev` and put its address here. The steps, with every command, are in [infra/README.md](infra/README.md): create the AWS account setup and a budget alert (section 2), run the bootstrap stack (section 3), push the first image (section 4), deploy the `podclatch-dev` stack (section 5), and read the access token (section 6). Then replace this paragraph with the `ServiceUrl` output (an `https://...awsapprunner.com` address), confirm `curl -i <ServiceUrl>/healthz` returns 200, and set the repository variables named under CI/CD so Deploy stops being skipped.

What the templates build, so you know what you are agreeing to:

- **Shape.** The server runs on AWS App Runner, which gives an HTTPS address with a valid certificate and no domain name, and the data lives in Amazon RDS for PostgreSQL (`db.t4g.micro`, one Availability Zone, 20 GB). The three secrets (the access token, the database password, and the database URL) live in AWS Secrets Manager. There are two stacks from one template, `podclatch-dev` and `podclatch-prod`.
- **Cost.** About **22 to 29 US dollars per month per environment** while it runs. This is an estimate from list prices, not checked against a bill. The cheapest path is to deploy `dev` only, for the demo and grading, and tear it down afterwards (section 10 of `infra/README.md`). `prod` is written and checked but need not be deployed.
- **HTTPS is required.** A release build of the app refuses plain `http://` to any address other than `localhost`, `127.0.0.1`, and `10.0.2.2`, which is why a load balancer or a bare server address would not work with it.
- **The database is reachable from the internet.** App Runner without a private network has no fixed outbound addresses, so the database allows connections from anywhere, protected by forced TLS and a long generated password. This is a demo trade-off for a single-user, throw-away deployment, not something to leave running.
- **Idle CPU is throttled.** App Runner slows an idle instance's CPU, so the server's own scheduled Refresh may not run until a request arrives. The pull-down Refresh in the app works.
- **Nothing has been deployed by an agent**, and the templates were checked only with static tools (`cfn-lint` and tests), with no AWS account.

## Get the app

**Debug APK (any build of `main`).** Every run of Android CI uploads the APK as an artifact:

1. On GitHub, open the repository's **Actions** tab and choose **Android CI**.
2. Open the latest run on `main` (a green check) and scroll to **Artifacts**.
3. Download **podclatch-debug-apk**, which is a zip file (you must be signed in to GitHub, and artifacts are deleted after 14 days; push a commit or re-run the workflow to get a fresh one). Unzip it to get `app-debug.apk`.
4. Install it: `adb install -r app-debug.apk` (add `-s <serial>` to pick a device), or copy the file to the phone, open it, and allow "Install unknown apps" for the app you opened it from.
5. Open PodClatch and enter a server address and token. A debug build accepts plain `http://` to a server on your network; a server on the internet needs `https://`.

#### Use the app on your phone over Tailscale

The server in `docker-compose.yaml` listens on `127.0.0.1:8180` only, so a phone cannot reach it directly. Tailscale gives the phone a private, HTTPS address for the laptop's server without exposing anything to the internet. It works only while the laptop is on, awake, and running the stack; without the server the app still plays what it has downloaded (specification decision 22).

1. Install Tailscale on the laptop and the phone and sign in with the same account on both, so they share one tailnet. In the admin console, DNS page, make sure MagicDNS and HTTPS Certificates are enabled.
2. If the laptop carries `tag:podclatch-laptop` (the on-call demo setup above), add this grant to the policy under Access controls, `grants`. Without it a tagged laptop is hidden from your phone, and the phone's device list shows only the phone. `autogroup:member` means your own devices; `tcp:443` is the one port `tailscale serve` publishes:

   ```json
   { "src": ["autogroup:member"], "dst": ["tag:podclatch-laptop"], "ip": ["tcp:443"] }
   ```

3. On the laptop, in the repository root with the stack running (PowerShell; if `tailscale` is not found in an old terminal, open a new one or use `& 'C:\Program Files\Tailscale	ailscale.exe'`):

   ```powershell
   tailscale serve --bg --https=443 http://127.0.0.1:8180
   tailscale serve status        # shows https://<laptop>.<tailnet>.ts.net (tailnet only)
   ```

   Never use `tailscale funnel` (it publishes to the whole internet), and do not point this at the telemetry gateway on 8380 or at Grafana on 3180. The server answers 401 to any request without the access token.
4. In PodClatch on the phone, enter `https://<laptop>.<tailnet>.ts.net` and the access token (`PODCLATCH_TOKEN` in `.env`). Check it first in the phone's browser: `https://<laptop>.<tailnet>.ts.net/healthz` should answer 200.
5. Stop sharing with `tailscale serve --https=443 off`.

If the phone's installed app was built on this laptop (`./gradlew assembleDebug` in `android/`, with `JAVA_HOME` pointing at a JDK such as Android Studio's `jbr`), `adb install -r app/build/outputs/apk/debug/app-debug.apk` updates it in place and keeps its data. The APK from GitHub is signed with a different debug key, so installing it over that app fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`; the only way through is to uninstall first, which deletes the app's local cache, downloads, and settings (subscriptions and Queue come back from the server on the next Sync).

**Signed release APK.** None exists yet; the release link is pending. `TODO(human)`: create the signing key and the four repository secrets named under CI/CD (`keytool -genkeypair -v -keystore podclatch-release.jks -alias podclatch -keyalg RSA -keysize 4096 -validity 10000`, keep a backup of the file outside the repository, and add `ANDROID_KEYSTORE_BASE64` with the output of `base64 -w0 podclatch-release.jks`, or in PowerShell `[Convert]::ToBase64String([IO.File]::ReadAllBytes("podclatch-release.jks"))`), then run `git tag v0.1.0 && git push origin v0.1.0`. The Release APK workflow then attaches `podclatch-v0.1.0.apk` to a GitHub Release, and this paragraph can link to it. A release build needs an `https://` server.

## Android Auto

PodClatch shows up in Android Auto as a media app. In the car you can browse the Queue and the Feeds (two tabs, served from the phone's local cache, so it works offline), start an Episode, and use Android Auto's standard playback screen with skip back and skip forward. There is no search and no custom car screen. Playing from the car follows the phone's rules: the download starts, the Queue advances, and the saved position and Played work as on the phone. After the app was stopped, connecting to the car or pressing Play resumes the Episode you last left unfinished, or else the first Queue item.

The playback screen also carries a Speed button that switches between your assigned speed and normal speed; its icon shows the current speed. There is no long-press and no way to assign a speed in the car, because that is done on the phone.

**How far this has been verified.** The browse tree, resumption, and the Speed button are covered by automated tests that exercise the service directly on an emulator, and the Speed button's action also shows in the phone's notification shade there. **It has not been tried on the Desktop Head Unit or in a car**, so treat Android Auto as built but unverified on a head unit. `TODO(human)`: test it with the steps below (and the checklists in issues #26 and #80), then replace this paragraph with what you saw, including how many custom actions your head unit shows and whether Speed is among them.

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
- **One user, one server.** There are no accounts, only one access token, and the app talks to exactly one server. Changing the server clears the local cache and downloads.
- **HTTPS for a release build.** A release build refuses plain `http://` to any address other than `localhost`, `127.0.0.1`, and `10.0.2.2`. A server on a LAN address over `http://` works in a debug build only.
- **Android 17 and the local network.** On Android 17 (API 37) and newer, connecting to a private-network address needs the "Local network access" permission, which the app asks for. If you refuse it, servers on the local network will not work (the `adb reverse` path to `127.0.0.1` is not affected).
- **The deployed server may delay the scheduled Refresh.** App Runner throttles an idle instance's CPU, so the 3-hourly Refresh may wait until a request arrives.
- **The deployed database is open to the internet.** It sits behind forced TLS and a long random password, which is a demo trade-off (see Deployment).
- **Not deployed yet.** No deployed server, signed release, or demo against a deployed server exists until the author provides them (see the `TODO(human)` items).
- **Features left out on purpose.** There is no sleep timer, no OPML import or export, and no Storage screen (specification sections 8 and 9), no manual Queue editing, and no video podcasts. The server never proxies, caches, or stores podcast audio.
- **Android Auto is unverified on a head unit.** It was tested through the service and on an emulator only (see Android Auto).
- **The demo has no sound.** The screen recording comes from a silent emulator, and the demo Episodes are generated tones.
- **No observability, alerting, or on-call agent yet.** The specification plans them as a later milestone (US-36 to US-38); none of them exists.

## How this was built

PodClatch was built by a team of AI agents following a written process, so that the work can be traced:

- [`AGENTS.md`](AGENTS.md) is the entry point for any agent: the repository layout, the commands, and the rules.
- [`_docs/podclatch-spec.md`](_docs/podclatch-spec.md) is the product specification and the source of truth.
- [`_docs/process.md`](_docs/process.md) describes how work is organized: the orchestrator, the lifecycle of a task, and the checkpoints where the human decides.
- [`_docs/team/`](_docs/team/) holds the role instructions for the PM, the software engineer, and the QA engineer, and [`_docs/task-template.md`](_docs/task-template.md) is the shape of every task.
- [`_docs/full-score-criteria.md`](_docs/full-score-criteria.md) is the author's plan for meeting the class's scoring criteria.
- The task tracker is the repository's GitHub Issues and milestones (M0 to M10, one issue per user story). Each issue carries the PM's acceptance criteria and a comment from each role, and each pull request says `Closes #n`.

## Submission checklist for the author

Delete this section after submitting. Only the author can do these.

- [ ] Make the repository public. First check the history for secrets and feed URLs: `git log --all -p | grep -i -E "token|secret|password|bearer"` and a search of all issues and comments for feed URLs with embedded tokens; rotate anything that leaked. Required reviewers on the `production` environment only work for public repositories.
- [ ] The task tracker is public and shows the agents' evidence: every issue has the PM, engineer, QA, and orchestrator comments, and every pull request says `Closes #n`.
- [ ] Review three other projects on the course platform. The class requires this; the project fails without it, whatever its score.
- [ ] Set the AWS budget alert, deploy `dev`, record the demo against it, then tear it down after grading (`infra/README.md`).
- [ ] Remove the DRAFT banners from `AGENTS.md`, `_docs/process.md`, and `_docs/team/*` if they are still there (`_docs/full-score-criteria.md` notes this).
- [ ] Submit the repository link on the course platform.
