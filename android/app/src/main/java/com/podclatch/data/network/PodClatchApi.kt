package com.podclatch.data.network

import com.podclatch.data.db.ListenOrder

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

    /** `PUT /v1/feeds/order`: the full list of Feed ids in the new order; answers the Feeds in that order. */
    suspend fun reorderFeeds(feedIds: List<Long>): List<FeedDto>

    /** `DELETE /v1/feeds/{id}`: 204 with no body on success. */
    suspend fun unsubscribeFeed(feedId: Long)

    /**
     * `POST /v1/feeds`: subscribes to [feedUrl], sent exactly as given. 201 answers the new Feed; 409
     * (already subscribed) and 422 (not a readable feed) are [ApiError.Http] with the server's detail.
     */
    suspend fun subscribeFeed(feedUrl: String): FeedDto

    /**
     * `GET /v1/search?q=<q>`: at most 25 results in the server's order. [q] is sent as given (the caller
     * trims it); it is never logged.
     */
    suspend fun searchPodcasts(q: String): List<SearchResultDto>

    /** `PATCH /v1/feeds/{id}`: sets the Feed's Listen Order; 200 answers the updated Feed. */
    suspend fun updateFeedListenOrder(feedId: Long, listenOrder: ListenOrder): FeedDto

    /**
     * `POST /v1/feeds/{id}/refresh`: no request body; runs the refresh before answering. 200 answers
     * exactly one result, and a failure of the Feed is `ok: false` inside it.
     */
    suspend fun refreshFeed(feedId: Long): List<RefreshResultDto>

    /** `POST /v1/refresh`: no request body; one result per Feed in Feed Priority order. */
    suspend fun refreshAllFeeds(): List<RefreshResultDto>
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
