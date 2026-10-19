# PodClatch — Product Specification (Native Android app + PodClatch Server)

## 1. Overview

PodClatch is a personal, single-user podcast player for Android, built around
a **Queue that fills itself**. The user subscribes to **Feeds**, ranks them by
**Feed Priority**, and picks a **Listen Order** per feed. PodClatch downloads
the next episodes automatically, builds the Queue from them, plays them in
the background (including in the car via Android Auto), and cleans up played
downloads.

It has two parts:

- **PodClatch Server** (`server/`): a small single-user web service. It
  proxies Apple iTunes podcast search, fetches and parses each Feed's RSS,
  refreshes Feeds on a schedule, and stores the user's subscriptions, Feed
  Priority, Listen Order, and per-episode New/Played state and position.
- **PodClatch Android app** (`android/`): talks only to the server. It keeps
  an offline cache of everything it has synced, downloads audio **directly
  from the podcast publishers** (audio never passes through the server), and
  does all playback, queue building, and download management on the device.

There are no user accounts: the server is protected by one static bearer
token that the user enters in the app along with the server's URL.

**Vocabulary** (use these terms in UI and code): *Feed* (a subscribed
podcast), *Episode*, *Queue*, *Feed Priority*, *Listen Order*, *New* / *Played*,
*Sync*, *Refresh*.

- **Refresh** = the server re-fetches Feeds' RSS to discover new Episodes.
- **Sync** = the app exchanges changes with the server so both agree.

## 2. Instructions for Claude Code

1. Read this whole spec first.
2. Create a GitHub **milestone** per phase in section 6 (M0-M10) and one
   GitHub **issue** per user story (US-xx), titled `US-xx: <summary>`. Copy the
   acceptance criteria into the issue body as a checklist. Label with
   `phase:Mx`, one of `feature`, `infra`, `test`, and one of `area:android`,
   `area:server`, `area:ops`. Do not create issues for US-10, US-18, or US-19
   (removed or deferred; see §6 and §9).
3. Work issues in milestone order. One branch and PR per issue
   (`us-xx-short-name`), referencing the issue (`Closes #n`).
4. Definition of Done for every issue: builds cleanly, acceptance criteria
   met, unit tests for logic added, no new lint errors, CI green on the PR,
   and (Android stories) runs on a device or emulator.
5. Don't build anything in section 8 (Non-Goals). If a decision is
   ambiguous, pick the simplest option, note it in the PR, and move on.
6. **Contract first**: the HTTP API between app and server is defined in
   `openapi.yaml` (US-22). Any story that changes the API changes
   `openapi.yaml` in the same PR, before the code that uses it.

## 3. Decisions & Assumptions

### Android app

1. Language/UI: **Kotlin + Jetpack Compose** (Material 3). Single Gradle
   module in `android/`.
2. Playback: **Media3** (ExoPlayer) hosted in a **`MediaLibraryService`** from
   day one. This one service provides background audio, the media
   notification, lock-screen/Bluetooth controls, and **Android Auto**
   browsing (see US-16). Do not use a plain `MediaSessionService`.
3. Storage: **Room** (SQLite) as an **offline cache** of the server's Feeds
   and Episodes plus device-only download state; **DataStore** for settings;
   downloaded audio in app-specific external storage.
4. Networking: **OkHttp** + kotlinx.serialization, used only for the
   PodClatch Server API and for downloading audio from publishers. The app
   never contacts iTunes or parses RSS itself.
5. DI: **Hilt**. Architecture: MVVM, Repository pattern, Coroutines/Flow.
6. Background work: **WorkManager** (sync, downloads, cleanup).
7. Images: **Coil**.
8. `minSdk 26`, `targetSdk` = latest stable. Package: `com.podclatch`.
9. **All server access goes through one `PodClatchApi` interface used only by
   repositories** (never by ViewModels or UI). Tests use a fake
   implementation or `MockWebServer`. This also keeps a future serverless
   mode possible (§9).

### Behavior

10. Audio-only. Video podcasts are out of scope.
11. Episode identity is the RSS `<guid>` (fallback: enclosure URL), resolved
    by the server; the app uses server-assigned numeric ids.
12. Playback position is saved to Room every ~5 seconds while playing and on
    pause/stop. It is pushed to the server (via the Sync outbox, §5) on
    pause, stop, episode change, and Sync, not on every 5-second tick.
13. **Played** is set when playback reaches the end, or when playback stops,
    pauses, or switches episodes at 95% or beyond. It can also be set
    manually. Setting Played records `playedAt = now`.
14. **The Queue is derived, not stored** (see §5). There is no "Add to queue"
    or "Play next".
15. When an episode is started manually, its download begins immediately and
    ignores the Wi-Fi-only setting (the user asked for it). While it
    downloads, it plays by streaming.
16. All existing episodes of a newly subscribed Feed start as **New**. The
    user can mark them all Played from Feed Detail.
17. **Download-ahead limit (assumption — adjust if wrong):** per Feed, the app
    keeps up to **3** New episodes downloaded at a time (setting
    `downloadAheadPerFeed`), chosen in that Feed's Listen Order. Without a
    limit, a feed with hundreds of episodes would download all of them.
18. **Refresh and Sync cadence**: the server refreshes all Feeds every 3
    hours on its own (`REFRESH_INTERVAL_HOURS`), whether or not the app is
    running. The app syncs on launch, when it returns to the foreground,
    every `syncIntervalHours` (default 1; WorkManager, network required), and
    on manual refresh. Auto-delete of played downloads and the auto-download
    top-up run after each Sync. Client constants live in one `Config.kt`.
19. UI follows system dark/light theme.
20. **Big-swipe refresh**: wherever this spec says "big swipe down", it means
    a pull-down gesture that must travel **more than half the screen height**
    before release to trigger a refresh. Use a custom threshold (the default
    Material pull-to-refresh threshold is far too small) and show a clear
    progress indicator that fills as the pull approaches the threshold, so
    shorter pulls visibly do nothing.
21. **Swipe actions** (Feeds tab, Feed Detail, Queue) use swipe-to-dismiss
    style rows that **snap back** after the action fires or is cancelled; a
    row is never removed from the list by the swipe animation alone (state
    changes come from the data layer).
22. **Online-only vs. offline-capable**: search, subscribe, unsubscribe,
    reorder Feeds, change Listen Order, and Refresh need the server and show a
    clear error if it is unreachable. Everything else (playback, Queue,
    downloads, marking Played/New, position saving) works offline from the
    cache; state changes wait in the outbox until the next Sync.

### PodClatch Server

23. Stack: **Python 3.12, FastAPI, SQLAlchemy 2 + Alembic, `httpx`,
    `feedparser`**, managed with **uv**, tested with **pytest**, linted with
    **ruff**. Code in `server/`. Database selected by `DATABASE_URL`: SQLite
    for local dev and unit tests, **Postgres** in Docker Compose and AWS.
24. Auth: every endpoint except `GET /healthz` requires
    `Authorization: Bearer <token>`, compared in constant time against the
    `PODCLATCH_TOKEN` environment variable. Missing or wrong token → 401. The
    server refuses to start without a token configured outside test mode.
25. All times are UTC epoch milliseconds. All configuration comes from
    environment variables (`DATABASE_URL`, `PODCLATCH_TOKEN`,
    `REFRESH_INTERVAL_HOURS`, `LOG_LEVEL`, and later the telemetry endpoint);
    a `.env.example` is committed, real `.env` files never are.
26. Outbound fetches (feeds, iTunes) use a 15-second timeout, follow
    redirects, and cap response size at 10 MB.
27. **Feed URLs can contain secrets** (private/Patreon feeds embed tokens in
    the URL). The server must never write full feed URLs to logs, traces, or
    metrics; log the Feed id, or the URL with its query string removed.
28. The server never fetches, stores, or serves podcast audio.
29. Single user, single server instance. No horizontal-scaling concerns.

## 4. Data Model

### Server database (Postgres/SQLite)

```
Feed
  id: Long (PK)
  feedUrl: String (unique)
  title, author, description: String
  artworkUrl: String
  priority: Int                 // 0 = highest Feed Priority; unique, contiguous
  listenOrder: NEWEST_FIRST | OLDEST_FIRST    // default NEWEST_FIRST
  lastRefreshedAt: Long?
  subscribedAt: Long
  rev: Long                     // change counter, see Sync (§5)

Episode
  id: Long (PK)
  feedId: Long (FK, cascade delete)
  guid: String (unique per feed)
  title, description: String
  audioUrl: String
  publishedAt: Long
  durationSec: Int?
  artworkUrl: String?           // falls back to Feed artwork
  status: NEW | PLAYED          // default NEW
  playedAt: Long?               // set when status becomes PLAYED, else null
  positionSec: Int = 0
  stateUpdatedAt: Long          // when status/position last changed (client time)
  rev: Long

Tombstone                       // so Sync can tell the app what was deleted
  entityType: FEED | EPISODE
  entityId: Long
  rev: Long
```

`rev` comes from one global, monotonically increasing counter, bumped on every
insert, update, or delete of a Feed or Episode.

### Android Room cache

```
Feed, Episode            // same columns as the server, plus on Episode:
  downloadState: NONE | QUEUED | DOWNLOADING | DOWNLOADED | FAILED   // device-only
  localPath: String?                                                // device-only

PendingStateWrite        // the Sync outbox; one row per episode (latest wins)
  episodeId: Long (PK)
  status, playedAt, positionSec, stateUpdatedAt
```

### Settings (DataStore, device-only)

```
serverUrl: String
authToken: String             // stored in app-private storage; never logged
assignedSpeed: Float = 1.25   // the global "assigned" speed, 0.5-3.0 in 0.1 steps
speedMode: ASSIGNED | NORMAL = ASSIGNED   // NORMAL = temporarily 1.0 (§5)
skipBackSec: Int = 15
skipForwardSec: Int = 30
downloadWifiOnly: Boolean = true
autoDeletePlayedDownloads: Boolean = true
downloadAheadPerFeed: Int = 3
syncIntervalHours: Int = 1
notifyNewEpisodes: Boolean = false
lastSyncRev: Long = 0         // Sync cursor (§5)
```

### Config.kt constants

```
PLAYED_DELETE_DELAY = 1 hour
```

## 5. Core Business Logic

### 5.1 Who does what

| Server | Android app |
|---|---|
| iTunes search proxy | Download audio from publishers |
| Fetch/parse RSS, discover Episodes | Derive the Queue |
| Subscribe, reorder, unsubscribe, Listen Order | Auto-download top-up, auto-delete sweep |
| Store New/Played state and positions | Playback, speed, Android Auto |
| Scheduled Refresh | Cache, outbox, Sync |

### 5.2 Server behavior

**Search**: `GET /v1/search?q=<q>` calls
`https://itunes.apple.com/search?media=podcast&term=<q>&limit=25` and returns
`feedUrl`, `title` (`collectionName`), `author` (`artistName`), `artworkUrl`
(`artworkUrl600`). Responses are cached in memory for 10 minutes per query. An
iTunes failure returns 502 with a clear message.

**Subscribe** (`POST /v1/feeds {feedUrl}`): fetch and parse the feed; insert
Feed with `priority = 0` and increment every other Feed's priority by 1 (new
Feed goes to the top); insert its Episodes as New. If the feed can't be
fetched or parsed → 422 with a message and nothing changes. Subscribing to an
already-subscribed `feedUrl` → 409. Returns 201 with the Feed.

**Reorder Feeds** (`PUT /v1/feeds/order {feedIds: [...]}`): the list must
contain every subscribed Feed id exactly once (else 422); priorities are
rewritten to 0..n-1 in that order, atomically.

**Listen Order** (`PATCH /v1/feeds/{id} {listenOrder}`): updates the Feed.

**Unsubscribe** (`DELETE /v1/feeds/{id}`): deletes the Feed and its Episodes,
writes Tombstones, and closes the priority gap.

**Feed parsing**: RSS 2.0 with `itunes:` tags; `<enclosure>` for audio URL;
duration as seconds, `mm:ss`, or `hh:mm:ss`; dates in RFC 822 (tolerate
variants); follow redirects; skip items with no audio enclosure; tolerate
missing fields. Episode identity per decision #11.

**Refresh** (per run, in Feed Priority order): fetch each Feed; insert unseen
Episodes (by guid) as New; keep the existing state of known ones; update Feed
metadata and `lastRefreshedAt`. A failing feed doesn't stop the others. Runs
(a) on the server's schedule, (b) on `POST /v1/refresh` (all Feeds), and (c) on
`POST /v1/feeds/{id}/refresh` (one Feed). The manual endpoints run the refresh
before responding (capped at 60 seconds) and return per-Feed results
`[{feedId, ok, error?}]`.

**Episode state** (`POST /v1/episodes/state` with a batch of
`{episodeId, status, playedAt, positionSec, stateUpdatedAt}`): for each item,
apply it only if its `stateUpdatedAt` is newer than the stored one
(last-write-wins); stale items are ignored, not errors. Returns the resulting
state for every item. Unknown episode ids are reported per item.

**Sync pull** (`GET /v1/sync?since=<rev>&limit=500`): returns Feeds and
Episodes with `rev > since`, Tombstones with `rev > since`, and `nextRev` plus
`hasMore`. `since=0` returns everything.

### 5.3 Android behavior

**Sync** (one use case shared by launch, foreground, periodic, and manual
triggers; takes a lock so runs never overlap):
1. **Push**: send every `PendingStateWrite` in one batch; delete the rows the
   server accepted or reported stale (keep rows that failed for network
   reasons).
2. **Pull**: call `GET /v1/sync` with `lastSyncRev`, repeating while
   `hasMore`; upsert Feeds and Episodes into Room (never touching the
   device-only `downloadState`/`localPath`); apply Tombstones (deleting local
   audio files of deleted Episodes); store the new `lastSyncRev` only after
   the whole pull succeeds.
3. **Local upkeep**: auto-delete sweep, then auto-download top-up (below).

If the server state for an Episode is newer than a pending local write, the
server wins on pull (last-write-wins by `stateUpdatedAt`).

**Refresh from the app** (big swipe): call the server's refresh endpoint
(all Feeds, or one), then run Sync. Per-Feed failures from the response are
shown to the user.

**Queue (derived)**: all Episodes where `status = NEW` **and**
`downloadState ∈ {DOWNLOADED, DOWNLOADING}`, ordered by:
1. Feed Priority (ascending), then
2. within a Feed, that Feed's Listen Order (`publishedAt` descending for
   NEWEST_FIRST, ascending for OLDEST_FIRST).

When an Episode becomes Played, it leaves the Queue. Playing an episode out of
order (e.g., from Feed Detail) does **not** reorder the Queue. When an
episode finishes, playback advances to the **next** item: the Queue item that
followed the finished episode in Queue order (evaluated just before the
finished episode is marked Played). If the finished episode was the last item,
playback wraps to the **first** item remaining in the Queue; if the Queue is
empty, playback stops. Items play from the local file if downloaded, otherwise
by streaming.

Changing a Feed's Listen Order immediately re-sorts that Feed's items in the
Queue (the Queue is derived, so this happens automatically once the server
confirms the change and Sync applies it). It never interrupts the episode
currently playing.

**Unplayed count (per Feed)**: the number of that Feed's cached Episodes with
`status = NEW` (downloaded or not).

**Mark Played / New**
- Mark Played (single or all episodes in a Feed): `status = PLAYED`,
  `playedAt = now`, `positionSec` kept for single, reset for mark-all.
- Mark New: `status = NEW`, `playedAt = null`, `positionSec = 0`.
- Applied to Room immediately (UI updates at once), written to the outbox, and
  pushed right away if online, otherwise at the next Sync. Always sets
  `stateUpdatedAt = now`.
- If the affected episode is currently playing, playback stops (or advances
  to the next Queue item if it was Marked Played).
- A New episode with no download is picked up by the next auto-download
  top-up per Listen Order.

**Auto-delete sweep** (if `autoDeletePlayedDownloads`): for every Episode
with `status = PLAYED`, `playedAt <= now - PLAYED_DELETE_DELAY`, and a
downloaded file → delete the file, set `downloadState = NONE`,
`localPath = null`. Never delete the episode currently playing.

**Auto-download top-up**: for each Feed, count New episodes that are
DOWNLOADED/DOWNLOADING/QUEUED; if fewer than `downloadAheadPerFeed`,
enqueue downloads for the next New, undownloaded episodes in that Feed's
Listen Order until the limit is met. Honors Wi-Fi-only.

**Play from Feed Detail**: if the episode is DOWNLOADED, play the local file.
Otherwise start streaming immediately **and** start its download (per
decision #15); it appears in the Queue as soon as its download is
DOWNLOADING.

**Playback speed**: there is one global **assigned speed** (`assignedSpeed`)
and a mode flag. The speed actually applied to the player is:
`currentSpeed = 1.0` if `speedMode = NORMAL`, else `assignedSpeed`.
- **Toggle** (single press/tap): flip `speedMode` between ASSIGNED and NORMAL.
  Nothing else changes. (If `assignedSpeed` is 1.0 the toggle has no audible
  effect, but the mode still flips.)
- **Assign** (phone only, via long-press, US-08): set `assignedSpeed` (0.5-3.0
  in 0.1 steps) and set `speedMode = ASSIGNED`, so the new speed is heard
  immediately.
- Both persist and apply immediately to the active player, and carry across
  episodes and app restarts. Speed is device-only and is not synced.
- Toggle logic lives in one use case shared by the phone Player UI and the
  media session (US-08, US-16).

**Downloads**: one WorkManager job per episode, fetching `audioUrl` directly
from the publisher; resumable via HTTP `Range` where supported; on success set
`DOWNLOADED` + `localPath`; failures set `FAILED` and are retried on the next
Sync.

## 6. Phases & User Stories

Order rationale: the server and its contract come first because this spec
already defines the behavior in full, so the Android work in M4-M8 builds on a
real, tested server (and `MockWebServer`/fakes in unit tests). Story numbers are
stable identifiers, not a sequence: US-31 is worked at the start of M4.

### M0 — Setup

**US-01 Android project scaffold**
- Gradle project (version catalog) in `android/` builds and runs an empty
  Compose app.
- Hilt, Room, Media3, WorkManager, Coil, OkHttp added.
- Packages: `data/` (db, network, repo), `domain/`, `playback/`, `ui/`,
  `work/`.
- GitHub Actions workflow runs `./gradlew build test` on every pull request.

**US-21 Server project scaffold**
- `server/` contains a FastAPI app managed by uv, with `GET /healthz`
  returning `{"status":"ok"}` without auth.
- Configuration from environment variables per decision #25; `.env.example`
  committed.
- `uv run pytest` and `uv run ruff check .` pass; a test covers `/healthz`.
- GitHub Actions workflow runs the server tests and lint on every pull request.
- Root README gains run steps for the server (system-functionality sections
  are written later in US-35).

### M1 — API contract

**US-22 OpenAPI contract**
- `openapi.yaml` at the repository root specifies every endpoint in §5.2:
  method, path, query/body schemas, response schemas, status codes
  (200/201/401/409/422/502), and which endpoints require the bearer token
  (all but `/healthz`).
- Each Feed, Episode, Tombstone, sync-page, and refresh-result shape matches
  §4.
- Includes example responses.
- CI validates the file on every pull request.

### M2 — Server core (in-memory store)

All stories in this milestone use an in-memory store behind a repository
interface so M3 can swap in the database.

**US-23 Feed fetching & parsing**
- Implements §5.2 Feed parsing, using `feedparser` and `httpx` with the limits
  in decision #26.
- Unit tests with fixture feeds: odd durations (`90`, `12:34`, `1:02:03`),
  RFC 822 date variants, missing fields, redirects, items with no enclosure,
  guid fallback to enclosure URL, oversized response, timeout.

**US-24 Feeds API**
- Subscribe, list, reorder, Listen Order, and unsubscribe endpoints behave
  exactly as in §5.2, including priority rewriting, 409 for duplicates, 422
  for unparseable feeds and for an invalid reorder list.
- Tests cover: new Feed lands at priority 0 and the others shift; reorder
  keeps priorities contiguous; unsubscribe closes the gap; failed subscribe
  changes nothing.

**US-25 Episodes, state, Sync & Refresh API**
- `GET /v1/feeds/{id}/episodes`, `POST /v1/episodes/state`, `GET /v1/sync`,
  `POST /v1/refresh`, and `POST /v1/feeds/{id}/refresh` behave as in §5.2.
- Refresh inserts only unseen guids as New, keeps existing state, and
  tolerates a failing Feed.
- A background scheduler refreshes all Feeds every `REFRESH_INTERVAL_HOURS`.
- Tests cover: last-write-wins (newer applied, stale ignored), sync paging with
  `hasMore`, tombstones after unsubscribe, and `since=0` returning everything.

**US-26 Search proxy**
- `GET /v1/search` behaves as in §5.2 (field mapping, 10-minute cache,
  502 on iTunes failure). Tests use a stubbed iTunes response.

**US-27 Authentication**
- Bearer-token check per decision #24 on every endpoint except `/healthz`;
  constant-time comparison; 401 with a `WWW-Authenticate` header otherwise.
- Tests: no header, wrong token, correct token, `/healthz` open.

### M3 — Server persistence & containers

**US-28 Database**
- Replace the in-memory store with SQLAlchemy 2 models and Alembic migrations
  for the §4 server schema; `DATABASE_URL` selects SQLite or Postgres.
- Migrations run automatically at startup; the global `rev` counter is
  transactional and gap-tolerant.
- The M2 tests pass unchanged against SQLite, and the suite can also run
  against Postgres.

**US-29 Docker & Compose**
- `server/Dockerfile` builds a small production image (non-root user).
- `docker-compose.yaml` at the root defines two services, `server` and
  `postgres` (with a volume and health checks); `server` waits for Postgres.
- A root `Makefile` provides `make up`, `make down`, `make test`, and
  `make logs`. `make up` starts the whole system with one command and
  `GET /healthz` answers.

**US-30 Server integration tests**
- Integration tests run against the Compose stack (`make test-integration`),
  using a fake feed/iTunes host container or fixture so no internet is needed.
- Scenarios: subscribe → episodes appear → mark state → sync pull returns
  them; unsubscribe produces tombstones; two clients writing conflicting
  state (last-write-wins); refresh adds a new episode; restarting the server
  container keeps all data; wrong token → 401.

### M4 — Android: Server connection, Sync, Feeds & Subscribe

**US-31 Connection setup & Sync engine** *(work this story first in M4)*
- First launch (or any time `serverUrl`/`authToken` are unset or rejected with
  401) shows a connection screen: server URL and token fields, a "Test
  connection" button that calls `/healthz` and an authenticated endpoint.
- Room cache, `PendingStateWrite` outbox, and `PodClatchApi` (decision #9)
  implemented per §4 and §5.3.
- The Sync use case implements push → pull → local upkeep, never overlaps
  itself, and only advances `lastSyncRev` after a complete pull.
- Sync runs on launch, on returning to the foreground, and periodically
  (`syncIntervalHours`).
- Unit tests with `MockWebServer`/fakes: paging, tombstones deleting local
  files, outbox retained after a network failure, stale write loses to newer
  server state, device-only columns untouched by a pull.

**US-02 Feeds tab**
- Bottom navigation has two tabs: **Queue** (home) and **Feeds**; Feeds is
  built here, Queue is a placeholder until US-13.
- Feeds tab lists subscribed Feeds (from the cache), each a horizontal bar
  with the Feed's thumbnail at left and title/author beside it, in Feed
  Priority order.
- Drag-and-drop (long-press handle) moves a Feed to a new position, sent to
  the server (decision #22); on failure the list snaps back with an error.
  Order persists across restarts.
- Each bar shows the Feed's **unplayed count** (§5.3) as a badge/number; it
  updates live as episodes are marked Played/New or Sync adds episodes.
- **Big swipe down** (decision #20) refreshes **all** Feeds (§5.3 Refresh from
  the app) with a visible in-progress indicator and an error message if any
  feeds failed or the server is unreachable.
- **Swipe left** on a Feed invokes **Unsubscribe**: show a confirmation
  dialog; on confirm, call the server (§5.2) then Sync; on cancel, the row
  snaps back unchanged.
- A visible **Subscribe (+)** button opens the Subscribe view.
- Tapping a bar opens Feed Detail (US-06).

**US-03 Subscribe view: search**
- Search field with ~400 ms debounce calling the server's search endpoint;
  results show artwork, title, author; loading, empty, and error states
  (including "server unreachable").
- Tapping a result shows a preview (description) with a **Subscribe** button;
  already-subscribed Feeds show "Subscribed" instead.

**US-04 Subscribe view: RSS URL**
- The same view lets the user paste a feed URL and subscribe directly.

**US-05 Subscribe action**
- Subscribing calls the server (§5.2, inserted at top Feed Priority), runs
  Sync, then returns to the Feeds tab with the new Feed at the top.
- Server errors (409 duplicate, 422 unparseable, unreachable) are shown to the
  user with a clear message and nothing changes locally.
- Unit tests for the repository's mapping of API responses and errors.

### M5 — Android: Feed Detail & Playback

**US-06 Feed Detail**
- Shows Feed artwork, title, description, and the full list of Episodes
  (title, date, duration, New/Played indicator, progress if partially played).
- Episodes with `status = PLAYED` look visibly different from New ones: a
  fainter text color/weight **and/or** a different row background (must be
  distinguishable at a glance in both light and dark themes while remaining
  readable).
- **Listen Order toggle** (Newest first default / Oldest first): sends the
  change to the server (decision #22), then Sync re-sorts the list **and the
  Queue**, then the auto-download top-up runs for this Feed so the episodes
  downloaded ahead match the new order. Already-downloaded episodes are kept.
- **Mark all Played** and **Mark all New** actions (confirm before applying).
- **Swipe left** on an episode sets `status = PLAYED` (and `playedAt = now`).
  **Swipe right** sets `status = NEW`, clears `playedAt`, and resets
  `positionSec` to 0 (§5.3 Mark Played / New). Rows snap back (decision #21).
  The **⋮ (three-dot) menu** on each episode row also offers a Played/New
  toggle as a non-gesture alternative.
- **Big swipe down** (decision #20) refreshes **this Feed only** (§5.3 Refresh
  from the app), with a visible indicator and error message on failure.
- Tapping an episode starts playback (download behavior added in US-12).
- A **⋮ (three-dot) menu** in the top bar of Feed Detail with one item:
  **Unsubscribe** (confirm; per §5.2).

**US-07 Core playback service**
- `MediaLibraryService` + ExoPlayer; foreground service with media
  notification (artwork, title, play/pause, skip back/forward).
- Continues with screen off and app backgrounded; handles audio focus
  (pause for calls) and pauses on headphone unplug.
- Lock-screen and Bluetooth/headset controls work.

**US-08 Player UI**
- Mini player above the bottom nav on all screens; tap expands to full
  player: artwork, title, seek bar, elapsed/remaining, play/pause, skip
  back/forward (Settings values), and a **Speed button**.
- The Speed button **always shows the current speed** (e.g. "1.25x" or
  "1.0x") and updates the moment it changes.
- **Tap**: toggles between the globally assigned speed and 1.0 (§5.3 Playback
  speed).
- **Long-press** (phone only; not available in Android Auto): opens an
  interface (e.g. a bottom sheet with a slider or +/- stepper, range 0.5x-3.0x
  in 0.1 steps) to change the **globally assigned speed**. Confirming it
  applies the new speed immediately (§5.3 Assign). Include a visible hint or
  tooltip so the long-press is discoverable.
- The same toggle use case backs the Android Auto button (US-16).

**US-09 Resume & Played state**
- Position saved per decision #12; starting an episode seeks to its saved
  position.
- Played is set per decision #13 and recorded through the outbox (§5.3); on
  finish, playback advances per §5.3 Queue (until US-13 exists, stop at end).

**US-10 — removed.** No sleep timer. (The number is intentionally left
unused so other story numbers stay stable. Do not create an issue for it.)

### M6 — Android: Downloads & Queue

**US-11 Download engine**
- Download jobs per §5.3 with progress state in the database; survive app
  restart; resume via `Range` where possible; honor Wi-Fi-only when the
  request is automatic.
- Playback prefers the local file when `DOWNLOADED`.

**US-12 Play from Feed Detail = stream + download + queue**
- Starting a not-yet-downloaded episode streams immediately and starts its
  download at once (decision #15); it then appears in the Queue.
- Starting a downloaded episode plays the local file.

**US-13 Queue (home tab)**
- Queue tab is the app's home screen and lists the derived Queue (§5.3), each
  item a horizontal bar with the **Feed's thumbnail at left**, then episode
  title, Feed name, duration/remaining time, and a download-progress bar if
  still downloading.
- Each item also has a **narrow vertical progress bar** (at the item's
  trailing edge) that shows playback progress at a glance: it is one color
  when the episode is New (no progress); as playback proceeds, a second color
  **fills from the top downward** in proportion to
  `positionSec / durationSec` (full height = fully played). Colors must be
  distinguishable in light and dark themes and not rely on color alone for
  accessibility (give the bar a content description such as "40% played").
  If duration is unknown, show the unfilled bar.
- Tapping an item plays it; the currently playing item is highlighted.
- **Swipe left** on an item marks it Played (it leaves the Queue). **Swipe
  right** marks it New and resets its progress to zero (§5.3 Mark Played / New);
  it stays in the Queue. Rows snap back (decision #21).
- Finished/Played episodes leave the Queue; playback auto-advances to the
  **next** item in Queue order (§5.3); after the last item it wraps to the
  first remaining item, and stops if the Queue is empty.
- **Big swipe down** (decision #20) refreshes **all** Feeds, exactly like the
  Feeds tab (US-02).
- Empty state explains the Queue fills automatically from subscribed Feeds.
- No add-to-queue / play-next / manual reordering controls.

**US-14 Sync trigger wiring & auto-download**
- WorkManager periodic Sync (network required, `syncIntervalHours`) plus the
  big-swipe refresh gestures of US-02 and US-13 (all Feeds) and US-06 (one
  Feed), all calling the same Sync/Refresh use cases from US-31.
- After each Sync the auto-download top-up runs (§5.3), honoring Wi-Fi-only;
  tolerant of the server being unreachable (retry at the next trigger).
- Optional new-episode notification (off by default), raised when a Sync
  brings in New episodes.

**US-15 Auto-delete sweep**
- §5.3 Auto-delete sweep: played downloads are deleted once `playedAt` is at
  least 1 hour old; disabled when the setting is off.
- Unit tests: 59 minutes old (kept), 61 minutes old (deleted), Mark New
  before sweep (kept), currently playing episode (never deleted).

### M7 — Android Auto

**US-16 Android Auto support**
- The `MediaLibraryService` exposes a browse tree (served entirely from the
  local cache, so it works offline):
  - **Root** → **Queue** (playable items in Queue order) and **Feeds**
    (browsable, in Feed Priority order → each Feed's New episodes in that
    Feed's Listen Order, capped at 100).
  - Items carry title, Feed name, and artwork URI.
- Manifest declares the app as an Android Auto media app
  (`automotive_app_desc.xml` with `<uses name="media"/>` and the
  `com.google.android.gms.car.application` meta-data).
- Implements `onPlaybackResumption` so "play" / connecting to the car resumes
  the last Queue item at its saved position.
- Playing an episode from Auto follows the same rules as the phone (US-12,
  Queue advance, Played state).
- **Speed button**: a custom session command/button (Media3 `CommandButton`
  in the session's custom layout) shown on Android Auto's playback screen and
  the phone media notification. Pressing it runs the §5.3 toggle between the
  assigned speed and 1.0. Because Android Auto shows an icon rather than
  text, generate the icon so it **always shows the current speed** (e.g. a
  bitmap icon rendering "1.25x"), refreshed whenever the speed changes; also
  set the label/content description to the same text. There is **no
  long-press** or speed-assignment UI in Android Auto (assigning is phone
  only). Android Auto limits how many custom actions it shows, so this must be
  among the first custom actions; if it cannot be made to appear reliably
  there, the Player UI button (US-08) is the acceptable fallback, but record
  the limitation in the README.
- No custom UI: Android Auto's standard templates only (the custom action
  above is part of those templates).
- Verification notes in the README: test with the Android **Desktop Head
  Unit** emulator; on the phone, enable Android Auto developer mode and
  "Unknown sources" so the debug build appears. (Verification on the Desktop
  Head Unit is a manual check for the human; agents cannot drive it.)

### M8 — Android: Polish

**US-17 Settings**
- Settings screen (top-bar menu) edits everything in §4 Settings except
  `speedMode` (which is only changed by the Speed button) and `lastSyncRev`,
  persisted. Includes the server connection (URL and token, with the same
  "Test connection" as US-31). Skip intervals selectable (10/15/30/45/60 s);
  download-ahead 1-10; sync interval 1/3/6/12 hours.

**US-20 Resilience & accessibility**
- Graceful handling of network loss, server unreachable or returning errors, a
  rejected token (401 → connection screen), bad feeds reported by the server,
  download failures, and playback errors (clear message, retry, no crashes).
- Content descriptions, minimum touch targets, text scaling.
- UI smoke test: subscribe -> play from Feed Detail -> item appears in Queue
  (runs on an emulator against a fake `PodClatchApi`; the full-stack version is
  US-32).

### M9 — Delivery

**US-32 End-to-end test**
- An automated test drives the Android app on an emulator against the real
  server from the Compose stack (with a fake feed host, no internet): connect,
  subscribe, download, play from Feed Detail, see the item in the Queue, mark
  it Played, and confirm via the server API that the state reached the server.
- Runs in CI on a Linux GitHub runner with hardware acceleration; documented
  how to run locally. (The emulator cannot run inside Docker on Windows/Mac,
  so locally this is a documented Gradle command plus `make up`.)

**US-33 AWS deployment**
- AWS CloudFormation template(s) in `infra/` deploy the server image
  (ECR) and Postgres (RDS) with a public HTTPS-or-HTTP URL serving
  `/healthz`; secrets (token, DB password) come from AWS Secrets
  Manager/SSM, never from the repository. Pick the simplest viable compute
  (e.g., ECS Fargate or App Runner) and note the choice in the PR.
- Two stacks or parameter sets: `dev` and `prod`.
- A bootstrap template creates the GitHub OIDC provider and the deploy role
  (the human runs this one once with admin credentials).
- `infra/README.md` documents deploy, update, and tear-down.

**US-34 CI/CD pipeline**
- GitHub Actions: on every PR, run Android build/tests/lint, server
  tests/lint, OpenAPI validation, and the Compose integration tests (US-30)
  and end-to-end test (US-32) where feasible.
- On merge to `main`: build and push the server image, deploy to `dev` using
  OIDC (no stored AWS keys), and verify success via the health endpoint.
- **Production deploy is manual**: a `workflow_dispatch` (or approval-gated
  environment) workflow promotes the tested image to `prod` and verifies the
  health endpoint.
- Android: every build uploads a debug APK as a workflow artifact; a version
  tag publishes a signed release APK to a GitHub Release (signing key stored
  as a secret).

**US-35 Demo & documentation**
- Root README gains: feature list (system functionality), screenshots, how to
  run everything locally (`make up` + `./gradlew installDebug`), how to run all
  tests, architecture diagram, and the deployed server URL.
- A short demo (video or annotated screenshots) shows the app talking to the
  deployed server; linked from the README.

### M10 — Observability & operations

**US-36 Telemetry**
- Key user journey: **Refresh/Sync** (app → server → feed host).
- Server emits OpenTelemetry **traces**, **metrics** (request count/latency,
  refresh success/failure counts, feeds refreshed), and **structured JSON
  logs** that all carry the same `trace_id`.
- The Android OkHttp client starts a span and sends a W3C `traceparent` header
  on API calls so a client request and its server work share one trace.
- No customer data or credentials in telemetry: no tokens, no full feed URLs
  (decision #27), no episode titles or descriptions. A test asserts redaction.
- `docker-compose.yaml` gains an OpenTelemetry Collector and a Grafana-based
  UI (e.g. the `grafana/otel-lgtm` image) so signals are collected, stored, and
  viewable locally; the README shows where to look.

**US-37 Alert on user impact**
- At least one alert fires on user-visible impact, e.g., the server's 5xx rate
  or the refresh-failure ratio above a threshold for 5 minutes. It is defined
  as code (Grafana alert rule or CloudWatch alarm) and sends a webhook.
- Documented how to trigger it on purpose.

**US-38 On-call agent**
- An alert webhook triggers a GitHub Actions workflow (via
  `repository_dispatch`) that runs Claude Code headlessly with the alert
  payload and access to logs/traces. The agent diagnoses the issue, reproduces
  it with a failing test, and opens a PR with the fix; a human merges.
- Demonstrated end to end with a deliberately seeded fault (e.g., a feed shape
  that crashes the parser), documented with links to the alert, workflow run,
  and PR.

## 7. Screen Map

Connection screen (shown when no server is configured or the token is
rejected). Otherwise, bottom navigation: **Queue (home) | Feeds**. Mini player
persistent above it.

- Queue → (full player)
- Feeds → Subscribe view; Feed Detail → (full player)
- Top-bar menu on both tabs → Settings (includes server connection)

## 8. Non-Goals (this phase)

- No user accounts, login, or multi-user support: one static token, one user.
- No sync conflict handling beyond last-write-wins per episode.
- No audio proxying, caching, or storage on the server.
- No serverless/standalone mode (the app requires the server; see §9).
- No video podcasts; no chapters, transcripts, or other Podcasting 2.0 extras.
- No manual queue editing (add, play next, reorder); order comes only from
  Feed Priority and Listen Order.
- No sleep timer.
- No social features, ratings, or comments.
- No Wear OS or Cast support.
- No custom Android Auto UI beyond the standard media templates.
- No Play Store release work.
- No product analytics or crash-reporting services (operational telemetry per
  US-36 is allowed).
- No tablet-specific layouts.
- No non-English localization (use string resources).
- Deferred, not removed: OPML import/export and the Storage screen (§9).

## 9. Future Ideas (not built now)

- **Standalone mode (drop the server)**: a second `PodClatchApi`
  implementation that runs on the device (iTunes search and RSS parsing in
  Kotlin, subscriptions and state kept only in Room), selectable in Settings.
  Decision #9 keeps the app ready for this. Until it exists, the app needs a
  reachable server for search, subscribe, reorder, Listen Order, and Refresh.
- **OPML import/export** (formerly US-19): import via the system file picker
  (subscribes each feed, inserting at the top in file order; progress and
  failure summary); export subscriptions to OPML.
- **Storage screen** (formerly US-18): Settings → Storage lists downloaded
  episodes with sizes and total space used; delete one or all.
- Chapters, silence trimming / voice boost, per-feed playback speed and
  skip-intro, backup/restore of listening state.
