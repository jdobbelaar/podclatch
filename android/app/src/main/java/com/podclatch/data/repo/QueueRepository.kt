package com.podclatch.data.repo

import com.podclatch.data.db.CacheStore
import com.podclatch.data.db.QueueRow
import com.podclatch.domain.QueueKey
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The derived Queue (spec 5.3) from the Room cache: New Episodes that are downloaded or downloading, in
 * Feed Priority and then Listen Order. It is never stored; it changes when the Episodes change. The Queue
 * screen and the playback service read it here, and Android Auto will too.
 */
@Singleton
class QueueRepository @Inject constructor(private val store: CacheStore) {
    /** The Queue in order; emits again on a download, a Mark, a Sync, a reorder, or a Listen Order change. */
    val queue: Flow<List<QueueRow>> get() = store.observeQueue()

    /** The keys of the Queue as it is right now, in Queue order. */
    suspend fun queueKeys(): List<QueueKey> = store.queue().map { it.key }

    /** The order key of one Episode, in the Queue or not; null when it is not cached. */
    suspend fun keyOf(episodeId: Long): QueueKey? = store.queueRowOf(episodeId)?.key
}
