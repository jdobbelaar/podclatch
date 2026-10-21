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

    /** Most failed Feeds listed by name in the Refresh error message. */
    const val MAX_REFRESH_ERROR_LINES = 3

    /** Longest per-Feed refresh error shown. */
    const val MAX_REFRESH_ERROR_LENGTH = 200

    /** Size of Coil's disk cache for artwork. */
    const val IMAGE_DISK_CACHE_BYTES = 50L * 1024 * 1024

    /** Whole-call timeout for one artwork request. */
    const val IMAGE_CALL_TIMEOUT_SECONDS = 15L

    /** Directory under `cacheDir` that holds the artwork disk cache. */
    const val IMAGE_CACHE_DIR = "artwork"
}
