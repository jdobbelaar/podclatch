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
}
