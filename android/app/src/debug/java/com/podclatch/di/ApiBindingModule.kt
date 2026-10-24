package com.podclatch.di

import com.podclatch.data.db.ListenOrder
import com.podclatch.data.network.EpisodeStateItem
import com.podclatch.data.network.OkHttpPodClatchApi
import com.podclatch.data.network.PodClatchApi
import com.podclatch.data.network.ServerTarget
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Debug builds bind [PodClatchApi] to a switch that forwards to the HTTP client until a test installs a
 * fake in [DebugApiOverride]. The instrumented UI tests (US-20) use it to run the real app against an
 * in-memory API. Nothing sets the override outside those tests, so a debug build used by hand talks HTTP.
 * The same-named module in `src/release` binds the HTTP client directly.
 */
@Module
@InstallIn(SingletonComponent::class)
object ApiBindingModule {
    @Provides @Singleton
    fun api(real: OkHttpPodClatchApi): PodClatchApi = SwitchablePodClatchApi(real)
}

/** The fake that tests install; null means "use the HTTP client". */
object DebugApiOverride {
    @Volatile var fake: PodClatchApi? = null
}

private class SwitchablePodClatchApi(private val real: PodClatchApi) : PodClatchApi {
    private val current: PodClatchApi get() = DebugApiOverride.fake ?: real

    override suspend fun health(target: ServerTarget?) = current.health(target)
    override suspend fun pullSync(since: Long, limit: Int, target: ServerTarget?) = current.pullSync(since, limit, target)
    override suspend fun pushEpisodeState(items: List<EpisodeStateItem>) = current.pushEpisodeState(items)
    override suspend fun reorderFeeds(feedIds: List<Long>) = current.reorderFeeds(feedIds)
    override suspend fun unsubscribeFeed(feedId: Long) = current.unsubscribeFeed(feedId)
    override suspend fun subscribeFeed(feedUrl: String) = current.subscribeFeed(feedUrl)
    override suspend fun searchPodcasts(q: String) = current.searchPodcasts(q)
    override suspend fun updateFeedListenOrder(feedId: Long, listenOrder: ListenOrder) =
        current.updateFeedListenOrder(feedId, listenOrder)
    override suspend fun refreshFeed(feedId: Long) = current.refreshFeed(feedId)
    override suspend fun refreshAllFeeds() = current.refreshAllFeeds()
}
