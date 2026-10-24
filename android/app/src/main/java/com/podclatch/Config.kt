package com.podclatch

/** Client constants (spec section 4). */
object Config {
    /** Maximum combined changes requested per `GET /v1/sync` page; the contract caps it at 500. */
    const val SYNC_PAGE_LIMIT = 500

    /** The server rejects a state batch above this many items with 422. */
    const val PUSH_BATCH_LIMIT = 1000

    const val CONNECT_TIMEOUT_SECONDS = 10L

    /** Longer than the server's 60-second refresh cap so the later Refresh stories do not time out first. */
    const val CALL_TIMEOUT_SECONDS = 90L

    /** Whole-call timeout for the connection screen's "Test connection". */
    const val CONNECTION_TEST_TIMEOUT_SECONDS = 15L

    const val DEFAULT_SYNC_INTERVAL_HOURS = 1

    /** Spec section 4 defaults for the skip buttons. */
    const val DEFAULT_SKIP_BACK_SEC = 15
    const val DEFAULT_SKIP_FORWARD_SEC = 30

    /** WorkManager refuses a repeat interval below this. */
    const val MIN_PERIODIC_INTERVAL_MINUTES = 15L

    const val PERIODIC_SYNC_WORK_NAME = "podclatch-periodic-sync"

    const val DATABASE_NAME = "podclatch.db"

    /** Longest server `detail` text kept in an error. */
    const val MAX_ERROR_DETAIL_LENGTH = 200

    /** A downward drag must exceed this fraction of the screen height to start a Refresh (decision #20). */
    const val PULL_REFRESH_THRESHOLD_FRACTION = 0.5f

    /** A left swipe on a Feed bar past this fraction of its width asks to unsubscribe. */
    const val SWIPE_UNSUBSCRIBE_FRACTION = 0.4f

    /** A swipe on an Episode row past this fraction of its width marks it Played (left) or New (right). */
    const val SWIPE_EPISODE_FRACTION = 0.4f

    /** A Feed description longer than this many lines is collapsed behind "Show more". */
    const val DESCRIPTION_COLLAPSED_LINES = 4

    /** Most failed Feeds listed by name in the Refresh error message. */
    const val MAX_REFRESH_ERROR_LINES = 3

    /** Longest per-Feed refresh error shown. */
    const val MAX_REFRESH_ERROR_LENGTH = 200

    /** A search request is sent this long after the last change to the search term. */
    const val SEARCH_DEBOUNCE_MS = 400L

    /** The server rejects a search term longer than this many characters (after trimming) with 422. */
    const val SEARCH_MAX_TERM_LENGTH = 200

    /** Size of Coil's disk cache for artwork. */
    const val IMAGE_DISK_CACHE_BYTES = 50L * 1024 * 1024

    /** Whole-call timeout for one artwork request. */
    const val IMAGE_CALL_TIMEOUT_SECONDS = 15L

    /** Directory under `cacheDir` that holds the artwork disk cache. */
    const val IMAGE_CACHE_DIR = "artwork"

    /** The `User-Agent` of every audio request (fixed; it identifies the app, never the user). */
    const val PLAYBACK_USER_AGENT = "PodClatch/0.1 (Android)"

    /** Read timeout of the audio data source; the whole-call timeout is disabled for streaming. */
    const val PLAYBACK_READ_TIMEOUT_SECONDS = 30L

    /** Id of the media notification channel. */
    const val PLAYBACK_CHANNEL_ID = "podclatch_playback"

    /** Id of the media notification. */
    const val PLAYBACK_NOTIFICATION_ID = 1001

    /** The first `play` waits at most this long for the service connection. */
    const val PLAYBACK_CONNECT_TIMEOUT_MS = 10_000L

    /** The assigned speed until the user assigns another (spec section 4). */
    const val DEFAULT_ASSIGNED_SPEED = 1.25f

    /** The assigned speed ranges from [SPEED_MIN] to [SPEED_MAX] in [SPEED_STEP] steps. */
    const val SPEED_MIN = 0.5f
    const val SPEED_MAX = 3.0f
    const val SPEED_STEP = 0.1f

    /** The content authority that serves the generated Speed button icon (US-16). */
    const val SPEED_ICON_AUTHORITY = "com.podclatch.speedicon"

    /** The full player and the mini player read the playback position this often. */
    const val PLAYER_POSITION_POLL_MS = 500L

    /** While an Episode plays, its position is saved this often (playing time, not wall time). */
    const val POSITION_SAVE_INTERVAL_MS = 5_000L

    /** Pausing, stopping, or switching at or beyond this percent of the duration marks the Episode Played. */
    const val PLAYED_THRESHOLD_PERCENT = 95

    /** At most this many automatic (not user-started) downloads run at the same time. */
    const val MAX_CONCURRENT_AUTOMATIC_DOWNLOADS = 2

    /** Download progress is written to the database at most this often. */
    const val DOWNLOAD_PROGRESS_INTERVAL_MS = 1_000L

    /** Connect and read timeouts of the download client; there is no whole-call timeout. */
    const val DOWNLOAD_CONNECT_TIMEOUT_SECONDS = 15L
    const val DOWNLOAD_READ_TIMEOUT_SECONDS = 30L

    /** Directory name under the app's external files directory that holds downloaded audio. */
    const val DOWNLOAD_DIR_NAME = "audio"

    /** Bytes copied per read while downloading. */
    const val DOWNLOAD_CHUNK_BYTES = 32 * 1024

    /** Prefix of the unique WorkManager job name of one Episode's download. */
    const val DOWNLOAD_WORK_NAME_PREFIX = "podclatch-download-"

    /** Spec section 4 default for `downloadAheadPerFeed`; the top-up clamps whatever is stored to the range below. */
    const val DEFAULT_DOWNLOAD_AHEAD_PER_FEED = 3
    const val MIN_DOWNLOAD_AHEAD_PER_FEED = 1
    const val MAX_DOWNLOAD_AHEAD_PER_FEED = 10

    /** Id of the "New episodes" notification channel (separate from the playback channel). */
    const val NEW_EPISODES_CHANNEL_ID = "podclatch_new_episodes"

    /** The one fixed id of the new-episodes notification, so a later Sync replaces it. */
    const val NEW_EPISODES_NOTIFICATION_ID = 1002

    /** A Played Episode's download is deleted once it has been Played at least this long (spec section 4). */
    val PLAYED_DELETE_DELAY: kotlin.time.Duration = kotlin.time.Duration.parse("1h")

    /** The Android Auto browse tree lists at most this many New Episodes of one Feed (the Queue has no cap). */
    const val BROWSE_FEED_EPISODE_CAP = 100

    /**
     * After a Play key found nothing to resume, the service waits this long and then ends itself (it was started
     * in the foreground and has no notification to show; the system allows about 10 seconds).
     */
    const val NOTHING_TO_RESUME_STOP_DELAY_MS = 1_500L

    /** Changes to the browse tree within this window are told to subscribed clients once per node. */
    const val BROWSE_NOTIFY_DEBOUNCE_MS = 300L
}
