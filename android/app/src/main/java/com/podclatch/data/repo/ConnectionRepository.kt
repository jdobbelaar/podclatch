package com.podclatch.data.repo

import com.podclatch.Config
import com.podclatch.data.db.CacheStore
import com.podclatch.data.network.ApiError
import com.podclatch.data.network.ApiException
import com.podclatch.data.network.PodClatchApi
import com.podclatch.data.network.ServerTarget
import com.podclatch.domain.FileDeleter
import com.podclatch.domain.ServerUrlValidator
import com.podclatch.domain.SyncEngine
import com.podclatch.domain.SyncTrigger
import com.podclatch.domain.TokenError
import com.podclatch.domain.TokenRejection
import com.podclatch.domain.TokenResult
import com.podclatch.domain.TokenValidator
import com.podclatch.domain.UrlError
import com.podclatch.domain.UrlResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What "Test connection" found. None of these carry the token or a query string. */
sealed interface ConnectionTestResult {
    /** [host] is host and port only. */
    data class Success(val host: String) : ConnectionTestResult
    data class InvalidUrl(val error: UrlError) : ConnectionTestResult
    data class InvalidToken(val error: TokenError) : ConnectionTestResult
    data object Unreachable : ConnectionTestResult

    /** The token guard refused to send the token over plain HTTP to this address; nothing was sent. */
    data object CleartextRefused : ConnectionTestResult
    data object Timeout : ConnectionTestResult
    data object TokenRejected : ConnectionTestResult
    data object NotPodClatch : ConnectionTestResult
    data class HttpError(val code: Int, val detail: String?) : ConnectionTestResult
}

sealed interface SaveResult {
    data object Saved : SaveResult
    data class InvalidUrl(val error: UrlError) : SaveResult
    data class InvalidToken(val error: TokenError) : SaveResult
}

enum class TestStage { HEALTH, AUTHENTICATED }

/** Maps a failed call of the connection test to the message the screen shows. */
fun ApiError.toTestResult(stage: TestStage): ConnectionTestResult = when (this) {
    is ApiError.Unreachable -> if (timeout) ConnectionTestResult.Timeout else ConnectionTestResult.Unreachable
    // /healthz is unauthenticated, so a 401 there means the address is not a PodClatch server.
    ApiError.Unauthorized ->
        if (stage == TestStage.HEALTH) ConnectionTestResult.NotPodClatch else ConnectionTestResult.TokenRejected
    ApiError.Malformed -> ConnectionTestResult.NotPodClatch
    ApiError.CleartextRefused -> ConnectionTestResult.CleartextRefused
    is ApiError.Http -> ConnectionTestResult.HttpError(code, detail)
}

/**
 * The connection screen's repository: tests typed values and saves them. This is where a rejected token
 * is *not* handled (the test must not clear the stored token); the Sync engine does that.
 */
class ConnectionRepository(
    private val api: PodClatchApi,
    private val settings: SettingsRepository,
    private val store: CacheStore,
    private val engine: SyncEngine,
    private val tokenRejection: TokenRejection,
    private val fileDeleter: FileDeleter,
    private val urlValidator: ServerUrlValidator,
    private val ioDispatcher: CoroutineDispatcher,
    private val appScope: CoroutineScope,
) {
    /**
     * Validates, then `GET /healthz` without the token, then (only if that succeeded) `GET /v1/sync?since=0&limit=1`
     * with the typed token. Uses the typed values and writes nothing to settings.
     */
    suspend fun test(rawUrl: String, rawToken: String): ConnectionTestResult {
        val url = when (val r = urlValidator.validate(rawUrl)) {
            is UrlResult.Invalid -> return ConnectionTestResult.InvalidUrl(r.error)
            is UrlResult.Valid -> r
        }
        val token = when (val r = TokenValidator.validate(rawToken)) {
            is TokenResult.Invalid -> return ConnectionTestResult.InvalidToken(r.error)
            is TokenResult.Valid -> r.token
        }
        val target = ServerTarget(url.url, token, Config.CONNECTION_TEST_TIMEOUT_SECONDS)
        try {
            api.health(target)
        } catch (e: ApiException) {
            return e.error.toTestResult(TestStage.HEALTH)
        }
        try {
            api.pullSync(since = 0, limit = 1, target = target)
        } catch (e: ApiException) {
            return e.error.toTestResult(TestStage.AUTHENTICATED)
        }
        return ConnectionTestResult.Success(url.displayHost)
    }

    /** True when saving [rawUrl] would switch to a different server than the saved one (the cache would be wiped). */
    suspend fun requiresWipe(rawUrl: String): Boolean {
        val url = (urlValidator.validate(rawUrl) as? UrlResult.Valid)?.url ?: return false
        val old = settings.current().serverUrl
        return old.isNotBlank() && old != url
    }

    /**
     * Saves the normalised URL and the token in one edit and starts a Sync. If the URL differs from the
     * previously saved non-blank one, everything that belongs to the old server is wiped first, under the
     * Sync lock so it cannot overlap a run. The same URL with a new token keeps the cache and the cursor.
     */
    suspend fun save(rawUrl: String, rawToken: String): SaveResult {
        val url = when (val r = urlValidator.validate(rawUrl)) {
            is UrlResult.Invalid -> return SaveResult.InvalidUrl(r.error)
            is UrlResult.Valid -> r.url
        }
        val token = when (val r = TokenValidator.validate(rawToken)) {
            is TokenResult.Invalid -> return SaveResult.InvalidToken(r.error)
            is TokenResult.Valid -> r.token
        }
        engine.exclusive {
            val old = settings.current().serverUrl
            if (old.isNotBlank() && old != url) wipe()
            settings.saveConnection(url, token)
        }
        tokenRejection.clearNotice()
        appScope.launch { engine.sync(SyncTrigger.MANUAL) }
        return SaveResult.Saved
    }

    private suspend fun wipe() {
        // The cursor first: if we stop half way, rows without a cursor are re-pulled, whereas a cursor
        // without rows would never be.
        settings.setLastSyncRev(0)
        val paths = store.allLocalPaths()
        withContext(ioDispatcher) { paths.forEach { fileDeleter.delete(it) } }
        store.transaction { clearAll() }
    }
}
