package com.podclatch.domain

import com.podclatch.data.repo.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** The wall clock in UTC epoch milliseconds; injected so tests control time. */
fun interface Clock {
    fun nowMs(): Long
}

/**
 * Housekeeping that runs after a successful pull. This story provides a no-op; the auto-delete sweep
 * (US-15) and the auto-download top-up (US-14) plug in here.
 */
fun interface LocalUpkeep {
    suspend fun run()
}

class NoOpLocalUpkeep @Inject constructor() : LocalUpkeep {
    override suspend fun run() = Unit
}

/** Deletes local audio files; a missing file is not an error. Injected so tests can use real temp files. */
fun interface FileDeleter {
    fun delete(path: String)
}

class DiskFileDeleter @Inject constructor() : FileDeleter {
    override fun delete(path: String) {
        try {
            File(path).delete()
        } catch (_: SecurityException) {
            // Nothing sensible to do; the next pull retries.
        }
    }
}

/**
 * What happens when the server rejects the token (HTTP 401 on any call other than the connection
 * test): the stored token is cleared (the URL and the cache stay) and the connection screen is shown
 * with a notice. While the token is blank the app counts as not configured, so scheduled Sync does
 * nothing instead of looping on failures.
 */
@Singleton
class TokenRejection @Inject constructor(private val settings: SettingsRepository) {
    private val _rejected = MutableStateFlow(false)

    /** True from a rejection until a new connection is saved; drives the connection screen's notice. */
    val rejected: StateFlow<Boolean> = _rejected.asStateFlow()

    suspend fun onTokenRejected() {
        settings.clearToken()
        _rejected.value = true
    }

    fun clearNotice() {
        _rejected.value = false
    }
}
