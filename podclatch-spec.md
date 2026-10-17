# PodClatch — Product Specification (Native Android, Kotlin)

## 1. Overview

PodClatch is a personal, single-user podcast player for Android, built around
a **Queue that fills itself**. The user subscribes to **Feeds**, ranks them by
**Feed Priority**, and picks a **Listen Order** per feed. PodClatch downloads
the next episodes automatically, builds the Queue from them, plays them in
the background (including in the car via Android Auto), and cleans up played
downloads.

No backend, accounts, or cloud sync. All data lives on the device. Discovery
uses the free Apple iTunes Search API; episodes come directly from each Feed's
RSS URL.

**Vocabulary** (use these terms in UI and code): *Feed* (a subscribed
podcast), *Episode*, *Queue*, *Feed Priority*, *Listen Order*, *New* / *Played*.

## 2. Instructions for Claude Code

1. Read this whole spec first.
2. Create a GitHub **milestone** per phase in section 6 (M0-M5) and one GitHub
   **issue** per user story (US-xx), titled `US-xx: <summary>`. Copy the
   acceptance criteria into the issue body as a checklist. Label with
   `phase:Mx` and one of `feature`, `infra`, `test`.
3. Work issues in milestone order. One branch and PR per issue
   (`us-xx-short-name`), referencing the issue (`Closes #n`).
4. Definition of Done for every issue: builds cleanly, acceptance criteria
   met, unit tests for logic added, no new lint errors, runs on a device or
   emulator.
5. Don't build anything in section 8 (Non-Goals). If a decision is
   ambiguous, pick the simplest option, note it in the PR, and move on.

## 3. Decisions & Assumptions

1. Language/UI: **Kotlin + Jetpack Compose** (Material 3). Single Gradle module.
2. Playback: **Media3** (ExoPlayer) hosted in a **`MediaLibraryService`** from
   day one. This one service provides background audio, the media
   notification, lock-screen/Bluetooth controls, and **Android Auto**
   browsing (see US-16). Do not use a plain `MediaSessionService`.
3. Storage: **Room** (SQLite) for Feeds/Episodes; **DataStore** for settings;
   downloaded audio in app-specific external storage.
4. Networking: **OkHttp** (+ kotlinx.serialization for the iTunes API). RSS
   parsed with a custom `XmlPullParser`-based parser.
5. DI: **Hilt**. Architecture: MVVM, Repository pattern, Coroutines/Flow.
6. Background work: **WorkManager** (refresh, downloads, cleanup).
7. Images: **Coil**.
8. `minSdk 26`, `targetSdk` = latest stable. Package: `com.podclatch`.
9. Audio-only. Video podcasts are out of scope.
10. Episode identity is the RSS `<guid>` (fallback: enclosure URL).
11. Playback position is saved every ~5 seconds while playing and on
    pause/stop.
12. **Played** is set when playback reaches the end, or when playback stops,
    pauses, or switches episodes at 95% or beyond. It can also be set
    manually. Setting Played records `playedAt = now`.
13. **The Queue is derived, not stored** (see §5). There is no "Add to queue"
    or "Play next".
14. When an episode is started manually, its download begins immediately and
    ignores the Wi-Fi-only setting (the user asked for it). While it
    downloads, it plays by streaming.
15. All existing episodes of a newly subscribed Feed start as **New**. The
    user can mark them all Played from Feed Detail.
16. **Download-ahead limit (assumption — adjust if wrong):** per Feed, the app
    keeps up to **3** New episodes downloaded at a time (setting
    `downloadAheadPerFeed`), chosen in that Feed's Listen Order. Without a
    limit, a feed with hundreds of episodes would download all of them.
17. Refresh runs every 3 hours (WorkManager, network required) and on manual
    refresh. Auto-delete of played downloads runs as part of Refresh. Constants
    live in one `Config.kt`.
18. UI follows system dark/light theme.
19. **Big-swipe refresh**: wherever this spec says "big swipe down", it means
    a pull-down gesture that must travel **more than half the screen height**
    before release to trigger a refresh. Use a custom threshold (the default
    Material pull-to-refresh threshold is far too small) and show a clear
    progress indicator that fills as the pull approaches the threshold, so
    shorter pulls visibly do nothing.
20. **Swipe actions** (Feeds tab, Feed Detail, Queue) use swipe-to-dismiss
    style rows that **snap back** after the action fires or is cancelled; a
    row is never removed from the list by the swipe animation alone (state
    changes come from the data layer).

## 4. Data Model

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
  downloadState: NONE | QUEUED | DOWNLOADING | DOWNLOADED | FAILED
  localPath: String?

Settings (DataStore)
  assignedSpeed: Float = 1.25   // the global "assigned" speed, 0.5-3.0 in 0.1 steps
  speedMode: ASSIGNED | NORMAL = ASSIGNED   // NORMAL = temporarily 1.0 (§5)
  skipBackSec: Int = 15
  skipForwardSec: Int = 30
  downloadWifiOnly: Boolean = true
  autoDeletePlayedDownloads: Boolean = true
  downloadAheadPerFeed: Int = 3
  refreshIntervalHours: Int = 3
  notifyNewEpisodes: Boolean = false

Config.kt constants
  PLAYED_DELETE_DELAY = 1 hour
```

## 5. Core Business Logic

**Search**: GET `https://itunes.apple.com/search?media=podcast&term=<q>&limit=25`;
use `feedUrl`, `collectionName`, `artistName`, `artworkUrl600`.

**Subscribe**: fetch and parse the feed; insert Feed with `priority = 0` and
increment every other Feed's priority by 1 (new Feed goes to the top); insert
its Episodes as New. If the feed can't be fetched or parsed, show an error and
change nothing. Subscribing to an already-subscribed `feedUrl` is rejected
with a message.

**Reorder Feeds**: moving a Feed to a new position rewrites priorities so they
stay contiguous (0..n-1). Persisted immediately.

**Unsubscribe**: deletes the Feed, its Episodes, and their downloaded files;
closes the priority gap.

**Feed parsing**: RSS 2.0 with `itunes:` tags; `<enclosure>` for audio URL;
duration as seconds, `mm:ss`, or `hh:mm:ss`; dates in RFC 822 (tolerate
variants); follow redirects; skip items with no audio enclosure; tolerate
missing fields.

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
Queue (the Queue is derived, so this happens automatically). It never
interrupts the episode currently playing.

**Unplayed count (per Feed)**: the number of that Feed's Episodes with
`status = NEW` (downloaded or not).

**Mark Played / New**
- Mark Played (single or all episodes in a Feed): `status = PLAYED`,
  `playedAt = now`, `positionSec` kept for single, reset for mark-all.
- Mark New: `status = NEW`, `playedAt = null`, `positionSec = 0`.
- If the affected episode is currently playing, playback stops (or advances
  to the next Queue item if it was Marked Played).
- A New episode with no download is picked up by the next auto-download
  top-up per Listen Order.

**Refresh (per run, in Feed Priority order)**
1. Fetch each Feed; insert unseen Episodes (by guid) as New; keep existing
   state of known ones. A failing feed doesn't stop the others.
2. **Auto-delete sweep** (if `autoDeletePlayedDownloads`): for every Episode
   with `status = PLAYED`, `playedAt <= now - PLAYED_DELETE_DELAY`, and a
   downloaded file → delete the file, set `downloadState = NONE`,
   `localPath = null`. Never delete the episode currently playing.
3. **Auto-download top-up**: for each Feed, count New episodes that are
   DOWNLOADED/DOWNLOADING/QUEUED; if fewer than `downloadAheadPerFeed`,
   enqueue downloads for the next New, undownloaded episodes in that Feed's
   Listen Order until the limit is met. Honors Wi-Fi-only.

**Play from Feed Detail**: if the episode is DOWNLOADED, play the local file.
Otherwise start streaming immediately **and** start its download (per
decision #14); it appears in the Queue as soon as its download is
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
  episodes and app restarts.
- Toggle logic lives in one use case shared by the phone Player UI and the
  media session (US-08, US-16).

**Downloads**: one WorkManager job per episode; resumable via HTTP `Range`
where supported; on success set `DOWNLOADED` + `localPath`; failures set
`FAILED` and are retried on the next Refresh.

## 6. Phases & User Stories

### M0 — Setup

**US-01 Project scaffold**
- Gradle project (version catalog) builds and runs an empty Compose app.
- Hilt, Room, Media3, WorkManager, Coil, OkHttp added.
- Packages: `data/` (db, network, repo), `domain/`, `playback/`, `ui/`,
  `work/`.
- README with build/run steps; GitHub Actions workflow running
  `./gradlew build test`.

### M1 — Feeds & Subscribe

**US-02 Feeds tab**
- Bottom navigation has two tabs: **Queue** (home) and **Feeds**; Feeds is
  built here, Queue is a placeholder until US-13.
- Feeds tab lists subscribed Feeds, each a horizontal bar with the Feed's
  thumbnail at left and title/author beside it, in Feed Priority order.
- Drag-and-drop (long-press handle) moves a Feed to a new position; order
  persists across restarts.
- Each bar shows the Feed's **unplayed count** (§5) as a badge/number; it
  updates live as episodes are marked Played/New or refresh adds episodes.
- **Big swipe down** (decision #19) refreshes **all** Feeds (§5 Refresh,
  steps 1-3) with a visible in-progress indicator and an error message if any
  feeds failed.
- **Swipe left** on a Feed invokes **Unsubscribe**: show a confirmation
  dialog; on confirm, run Unsubscribe (§5); on cancel, the row snaps back
  unchanged.
- A visible **Subscribe (+)** button opens the Subscribe view.
- Tapping a bar opens Feed Detail (US-06).

**US-03 Subscribe view: search**
- Search field with ~400 ms debounce; results show artwork, title, author
  from the iTunes API; loading, empty, and error states.
- Tapping a result shows a preview (description) with a **Subscribe** button;
  already-subscribed Feeds show "Subscribed" instead.

**US-04 Subscribe view: RSS URL**
- The same view lets the user paste a feed URL and subscribe directly.

**US-05 Subscribe action**
- Subscribing follows §5 (inserted at top Feed Priority), then returns to the
  Feeds tab with the new Feed at the top.
- Errors per §5 shown to the user.
- Unit tests for the RSS parser using fixture feeds (odd durations, missing
  fields, redirects, no-enclosure items).

### M2 — Feed Detail & Playback

**US-06 Feed Detail**
- Shows Feed artwork, title, description, and the full list of Episodes
  (title, date, duration, New/Played indicator, progress if partially played).
- Episodes with `status = PLAYED` look visibly different from New ones: a
  fainter text color/weight **and/or** a different row background (must be
  distinguishable at a glance in both light and dark themes while remaining
  readable).
- **Listen Order toggle** (Newest first default / Oldest first): changes the
  Feed's `listenOrder`, persists, and re-sorts the list **and the Queue**
  immediately (§5), then runs the auto-download top-up for this Feed so the
  episodes downloaded ahead match the new order. Already-downloaded episodes
  are kept.
- **Mark all Played** and **Mark all New** actions (confirm before applying).
- **Swipe left** on an episode sets `status = PLAYED` (and `playedAt = now`).
  **Swipe right** sets `status = NEW`, clears `playedAt`, and resets
  `positionSec` to 0 (§5 Mark Played / New). Rows snap back (decision #20).
  The **⋮ (three-dot) menu** on each episode row also offers a Played/New
  toggle as a non-gesture alternative.
- **Big swipe down** (decision #19) refreshes **this Feed only** (§5 Refresh
  steps 1-3 for this Feed), with a visible indicator and error message on
  failure.
- Tapping an episode starts playback (download behavior added in US-12).
- A **⋮ (three-dot) menu** in the top bar of Feed Detail with one item:
  **Unsubscribe** (confirm; per §5).

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
- **Tap**: toggles between the globally assigned speed and 1.0 (§5 Playback
  speed).
- **Long-press** (phone only; not available in Android Auto): opens an
  interface (e.g. a bottom sheet with a slider or +/- stepper, range 0.5x-3.0x
  in 0.1 steps) to change the **globally assigned speed**. Confirming it
  applies the new speed immediately (§5 Assign). Include a visible hint or
  tooltip so the long-press is discoverable.
- The same toggle use case backs the Android Auto button (US-16).

**US-09 Resume & Played state**
- Position saved per decision #11; starting an episode seeks to its saved
  position.
- Played is set per decision #12; on finish, playback advances per §5 Queue
  (until US-13 exists, stop at end).

**US-10 — removed.** No sleep timer. (The number is intentionally left
unused so other story numbers stay stable. Do not create an issue for it.)

### M3 — Downloads & Queue

**US-11 Download engine**
- Download jobs per §5 with progress state in the database; survive app
  restart; resume via `Range` where possible; honor Wi-Fi-only when the
  request is automatic.
- Playback prefers the local file when `DOWNLOADED`.

**US-12 Play from Feed Detail = stream + download + queue**
- Starting a not-yet-downloaded episode streams immediately and starts its
  download at once (decision #14); it then appears in the Queue.
- Starting a downloaded episode plays the local file.

**US-13 Queue (home tab)**
- Queue tab is the app's home screen and lists the derived Queue (§5), each
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
  right** marks it New and resets its progress to zero (§5 Mark Played / New);
  it stays in the Queue. Rows snap back (decision #20).
- Finished/Played episodes leave the Queue; playback auto-advances to the
  **next** item in Queue order (§5); after the last item it wraps to the first
  remaining item, and stops if the Queue is empty.
- **Big swipe down** (decision #19) refreshes **all** Feeds, exactly like the
  Feeds tab (US-02).
- Empty state explains the Queue fills automatically from subscribed Feeds.
- No add-to-queue / play-next / manual reordering controls.

**US-14 Refresh & auto-download**
- WorkManager periodic refresh (network required, `refreshIntervalHours`) plus
  the big-swipe refresh gestures of US-02 and US-13 (all Feeds) and US-06 (one
  Feed), running §5 Refresh steps 1-3; tolerant of per-feed failures.
- The periodic job and both gestures call the same refresh use case.
- Optional new-episode notification (off by default).

**US-15 Auto-delete sweep**
- §5 Refresh step 2: played downloads are deleted once `playedAt` is at
  least 1 hour old; disabled when the setting is off.
- Unit tests: 59 minutes old (kept), 61 minutes old (deleted), Mark New
  before sweep (kept), currently playing episode (never deleted).

### M4 — Android Auto

**US-16 Android Auto support**
- The `MediaLibraryService` exposes a browse tree:
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
  the phone media notification. Pressing it runs the §5 toggle between the
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
  "Unknown sources" so the debug build appears.

### M5 — Polish

**US-17 Settings**
- Settings screen (top-bar menu) edits everything in §4 Settings except
  `speedMode` (which is only changed by the Speed button), persisted.
  Skip intervals selectable (10/15/30/45/60 s); download-ahead 1-10.

**US-18 Storage screen**
- Settings → Storage lists downloaded episodes with sizes and total space
  used; delete one or all (deleted unplayed episodes will be re-downloaded by
  the next top-up unless marked Played).

**US-19 OPML import/export**
- Feeds tab menu: import OPML via the system file picker (subscribes each
  feed, inserting at the top in file order; progress and failure summary);
  export subscriptions to OPML.

**US-20 Resilience & accessibility**
- Graceful handling of network loss, bad feeds, download failures, and
  playback errors (clear message, retry, no crashes).
- Content descriptions, minimum touch targets, text scaling.
- UI smoke test: subscribe -> play from Feed Detail -> item appears in Queue.

## 7. Screen Map

Bottom navigation: **Queue (home) | Feeds**. Mini player persistent above it.

- Queue → (full player)
- Feeds → Subscribe view; Feed Detail → (full player)
- Top-bar menu on both tabs → Settings → Storage

## 8. Non-Goals (this phase)

- No accounts, login, or cloud/device sync.
- No video podcasts; no chapters, transcripts, or other Podcasting 2.0 extras.
- No manual queue editing (add, play next, reorder); order comes only from
  Feed Priority and Listen Order.
- No sleep timer.
- No social features, ratings, or comments.
- No Wear OS or Cast support.
- No custom Android Auto UI beyond the standard media templates.
- No Play Store release work.
- No analytics or crash-reporting services.
- No tablet-specific layouts.
- No non-English localization (use string resources).

## 9. Future Ideas (not built now)

Chapters, silence trimming / voice boost, per-feed playback speed and
skip-intro, backup/restore of listening state, cross-device sync.
