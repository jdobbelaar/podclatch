package com.podclatch.data.network

/**
 * The one door to PodClatch Server. Only repositories and the Sync engine call it; ViewModels and UI
 * never do. Every method throws [ApiException] on failure.
 *
 * This story implements only what it uses; the stories that need other endpoints add them.
 */
interface PodClatchApi {
    /** `GET /healthz`, sent without the token. */
    suspend fun health(target: ServerTarget? = null)

    /** `GET /v1/sync`. */
    suspend fun pullSync(since: Long, limit: Int, target: ServerTarget? = null): SyncPageDto

    /** `POST /v1/episodes/state`: a bare JSON array in, a bare JSON array out. */
    suspend fun pushEpisodeState(items: List<EpisodeStateItem>): List<EpisodeStateResult>
}

/**
 * An explicit server address and token, used by the connection screen to test values that are typed
 * but not saved yet. When no target is given, the saved settings are read at call time.
 */
data class ServerTarget(
    val baseUrl: String,
    val token: String,
    val callTimeoutSeconds: Long? = null,
) {
    override fun toString(): String = "ServerTarget(baseUrl=$baseUrl, token=***)"
}
