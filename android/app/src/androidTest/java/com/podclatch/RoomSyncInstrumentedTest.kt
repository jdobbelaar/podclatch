package com.podclatch

import android.database.Cursor
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.podclatch.data.db.CacheStore
import com.podclatch.data.db.CacheTransaction
import com.podclatch.data.db.DownloadState
import com.podclatch.data.db.Episode
import com.podclatch.data.db.EpisodeStatus
import com.podclatch.data.db.Feed
import com.podclatch.data.db.ListenOrder
import com.podclatch.data.db.PendingStateWrite
import com.podclatch.data.db.PodClatchDatabase
import com.podclatch.data.db.RoomCacheStore
import com.podclatch.data.network.EpisodeDto
import com.podclatch.data.network.EpisodeStateItem
import com.podclatch.data.network.EpisodeStateResult
import com.podclatch.data.network.FeedDto
import com.podclatch.data.network.PodClatchApi
import com.podclatch.data.network.RefreshResultDto
import com.podclatch.data.network.ServerTarget
import com.podclatch.data.network.StateOutcome
import com.podclatch.data.network.SyncPageDto
import com.podclatch.data.network.TombstoneDto
import com.podclatch.data.network.TombstoneEntityType
import com.podclatch.data.repo.EpisodeStateRepository
import com.podclatch.data.repo.MarkResult
import com.podclatch.data.repo.Settings
import com.podclatch.data.repo.SettingsRepository
import com.podclatch.domain.DiskFileDeleter
import com.podclatch.domain.SyncEngine
import com.podclatch.domain.SyncResult
import com.podclatch.domain.SyncTrigger
import com.podclatch.domain.TokenRejection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException

/** The Room layer and the Sync engine against real SQLite (an in-memory database). */
class RoomSyncInstrumentedTest {
    private lateinit var db: PodClatchDatabase
    private lateinit var store: RoomCacheStore
    private val settings = MemorySettings()
    private val api = ScriptedApi()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, PodClatchDatabase::class.java).build()
        store = RoomCacheStore(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun engine(cache: CacheStore = store) = SyncEngine(
        api, settings, cache, { }, { 9_000 }, TokenRejection(settings), DiskFileDeleter(), Dispatchers.Unconfined,
    )

    private fun sync(cache: CacheStore = store) = runBlocking { engine(cache).sync(SyncTrigger.MANUAL) }

    private fun <T> query(sql: String, read: (Cursor) -> T): List<T> =
        db.openHelper.readableDatabase.query(sql).use { c ->
            val rows = mutableListOf<T>()
            while (c.moveToNext()) rows += read(c)
            rows
        }

    private fun exec(sql: String) = db.openHelper.writableDatabase.execSQL(sql)

    private fun feed(id: Long, title: String = "Feed $id") = Feed(
        id, "https://feeds.example.com/$id.xml", title, "", "", "", 0, ListenOrder.NEWEST_FIRST, null, 1, 1,
    )

    private fun episode(id: Long, feedId: Long, title: String = "Episode $id") = Episode(
        id, feedId, "guid-$id", title, "", "https://audio.example.com/$id.mp3", 2, null, null,
        EpisodeStatus.NEW, null, 0, 1_000, 1,
    )

    private fun seed(feeds: List<Feed> = listOf(feed(1)), episodes: List<Episode> = listOf(episode(10, 1))) = runBlocking {
        store.transaction {
            feeds.forEach { insertFeedIfAbsent(it) }
            episodes.forEach { insertEpisodeIfAbsent(it) }
        }
    }

    private fun episodeRow(id: Long) = runBlocking { db.episodeDao().getById(id) }

    private fun feedDto(id: Long, title: String = "Feed $id") = FeedDto(
        id, "https://feeds.example.com/$id.xml", title, "", "", "", 0, ListenOrder.NEWEST_FIRST, null, 1, id,
    )

    private fun episodeDto(
        id: Long,
        feedId: Long,
        status: EpisodeStatus = EpisodeStatus.NEW,
        stateUpdatedAt: Long = 1_000,
        title: String = "Episode $id",
    ) = EpisodeDto(
        id, feedId, "guid-$id", title, "", "https://audio.example.com/$id.mp3", 2, null, null,
        status, if (status == EpisodeStatus.PLAYED) stateUpdatedAt else null, 0, stateUpdatedAt, id,
    )

    private fun page(
        feeds: List<FeedDto> = emptyList(),
        episodes: List<EpisodeDto> = emptyList(),
        tombstones: List<TombstoneDto> = emptyList(),
        nextRev: Long,
    ) = SyncPageDto(feeds, episodes, tombstones, nextRev, hasMore = false)

    // ---- schema ----

    @Test
    fun schemaHasTheThreeTablesAndForeignKeysAreOn() {
        val tables = query("SELECT name FROM sqlite_master WHERE type = 'table'") { it.getString(0) }
        assertTrue(tables.containsAll(listOf("feed", "episode", "pending_state_write")))
        assertFalse("placeholder" in tables)
        assertEquals(listOf(1), query("PRAGMA foreign_keys") { it.getInt(0) })
        assertEquals(listOf(true), query("PRAGMA foreign_key_list(episode)") { it.getString(6) == "CASCADE" })
    }

    @Test
    fun newRowsGetTheDeviceOnlyDefaults() {
        seed()
        val ep = episodeRow(10)!!
        assertEquals(DownloadState.NONE, ep.downloadState)
        assertNull(ep.localPath)
        assertEquals(listOf("NONE"), query("SELECT downloadState FROM episode") { it.getString(0) })
        assertEquals(listOf(true), query("SELECT localPath IS NULL FROM episode") { it.getInt(0) == 1 })
    }

    @Test
    fun deletingAFeedRowCascadesToItsEpisodes() {
        seed(listOf(feed(1), feed(2)), listOf(episode(10, 1), episode(11, 1), episode(20, 2)))
        runBlocking { db.feedDao().deleteById(1) }
        assertEquals(listOf(20L), query("SELECT id FROM episode") { it.getLong(0) })
    }

    @Test
    fun anEpisodeWithoutItsFeedIsRejectedByTheForeignKey() {
        var failed = false
        try {
            seed(emptyList(), listOf(episode(10, 99)))
        } catch (e: Exception) {
            failed = true
        }
        assertTrue(failed)
        assertTrue(query("SELECT id FROM episode") { it.getLong(0) }.isEmpty())
    }

    // ---- pull ----

    @Test
    fun aPullUpdatesSyncedColumnsAndLeavesDownloadStateAndLocalPathAlone() {
        seed()
        exec("UPDATE episode SET downloadState = 'DOWNLOADED', localPath = '/data/10.mp3' WHERE id = 10")
        api.pull = { _ -> page(listOf(feedDto(1, "Renamed")), listOf(episodeDto(10, 1, title = "Fresh"), episodeDto(11, 1)), nextRev = 12) }
        assertEquals(SyncResult.Success, sync())
        val updated = episodeRow(10)!!
        assertEquals("Fresh", updated.title)
        assertEquals(DownloadState.DOWNLOADED, updated.downloadState)
        assertEquals("/data/10.mp3", updated.localPath)
        assertEquals(DownloadState.NONE, episodeRow(11)!!.downloadState)
        assertEquals("Renamed", query("SELECT title FROM feed") { it.getString(0) }.single())
        // The Feed row was updated in place, so its Episodes were not cascade-deleted.
        assertEquals(2, query("SELECT id FROM episode") { it.getLong(0) }.size)
    }

    @Test
    fun tombstonesDeleteRowsAndAFeedTombstoneCascadesAndFilesAreRemoved() {
        val file = File.createTempFile("podclatch", ".mp3")
        seed(listOf(feed(1), feed(2)), listOf(episode(10, 1), episode(11, 1), episode(20, 2)))
        exec("UPDATE episode SET localPath = '${file.path}', downloadState = 'DOWNLOADED' WHERE id = 10")
        api.pull = { _ ->
            page(tombstones = listOf(TombstoneDto(TombstoneEntityType.FEED, 1, 5), TombstoneDto(TombstoneEntityType.EPISODE, 999, 6)), nextRev = 6)
        }
        assertEquals(SyncResult.Success, sync())
        assertEquals(listOf(2L), query("SELECT id FROM feed") { it.getLong(0) })
        assertEquals(listOf(20L), query("SELECT id FROM episode") { it.getLong(0) })
        assertFalse(file.exists())
    }

    // ---- outbox ----

    @Test
    fun theOutboxKeepsOneRowPerEpisodeAndTheLatestWriteWins() {
        seed()
        runBlocking {
            val dao = db.pendingStateWriteDao()
            dao.put(PendingStateWrite(10, EpisodeStatus.PLAYED, 5, 30, 5))
            dao.put(PendingStateWrite(10, EpisodeStatus.NEW, null, 0, 6))
            val rows = dao.getAll()
            assertEquals(1, rows.size)
            assertEquals(EpisodeStatus.NEW, rows.single().status)
            assertEquals(6L, rows.single().stateUpdatedAt)
        }
    }

    @Test
    fun compareAndDeleteOnlyRemovesTheRowThatWasSent() {
        runBlocking {
            val dao = db.pendingStateWriteDao()
            dao.put(PendingStateWrite(10, EpisodeStatus.NEW, null, 0, 6))
            assertEquals(0, dao.deleteIfSame(10, 5))
            assertEquals(1, dao.getAll().size)
            assertEquals(1, dao.deleteIfSame(10, 6))
            assertTrue(dao.getAll().isEmpty())
        }
    }

    @Test
    fun markPlayedAndMarkNewWriteTheEpisodeAndTheOutboxInOneTransaction() {
        seed()
        var now = 10_000L
        val repo = EpisodeStateRepository(store) { now }
        assertEquals(MarkResult.CHANGED, runBlocking { repo.markPlayed(10) })
        var ep = episodeRow(10)!!
        assertEquals(EpisodeStatus.PLAYED, ep.status)
        assertEquals(10_000L, ep.playedAt)
        assertEquals(10_000L, runBlocking { db.pendingStateWriteDao().getById(10) }!!.stateUpdatedAt)
        assertEquals(MarkResult.CHANGED, runBlocking { repo.markNew(10) })
        ep = episodeRow(10)!!
        assertEquals(EpisodeStatus.NEW, ep.status)
        assertNull(ep.playedAt)
        assertEquals(0, ep.positionSec)
        assertEquals(10_001L, ep.stateUpdatedAt)
        assertEquals(1, runBlocking { db.pendingStateWriteDao().getAll() }.size)
        assertEquals(MarkResult.NOT_FOUND, runBlocking { repo.markPlayed(404) })
        now = 1
    }

    @Test
    fun aPushSettlesTheOutboxAndAStaleResultWritesTheServerState() {
        seed()
        runBlocking { db.pendingStateWriteDao().put(PendingStateWrite(10, EpisodeStatus.PLAYED, 5, 30, 5)) }
        api.push = { items ->
            items.map { EpisodeStateResult(it.episodeId, StateOutcome.STALE, EpisodeStatus.NEW, null, 7, 8_000) }
        }
        assertEquals(SyncResult.Success, sync())
        assertTrue(runBlocking { db.pendingStateWriteDao().getAll() }.isEmpty())
        val ep = episodeRow(10)!!
        assertEquals(EpisodeStatus.NEW, ep.status)
        assertEquals(7, ep.positionSec)
        assertEquals(8_000L, ep.stateUpdatedAt)
    }

    // ---- pull versus pending ----

    private fun localWriteDuringPull(stamp: Long) = runBlocking {
        store.transaction {
            updateEpisodeState(10, EpisodeStatus.PLAYED, stamp, 30, stamp)
            putPending(PendingStateWrite(10, EpisodeStatus.PLAYED, stamp, 30, stamp))
        }
    }

    @Test
    fun theServerWinsATieOrANewerStateAgainstAPendingWrite() {
        for (serverStamp in listOf(5_000L, 4_000L)) {
            db.clearAllTables()
            seed()
            api.pull = { _ ->
                localWriteDuringPull(4_000)
                page(episodes = listOf(episodeDto(10, 1, EpisodeStatus.NEW, serverStamp)), nextRev = 9)
            }
            sync()
            val ep = episodeRow(10)!!
            assertEquals(EpisodeStatus.NEW, ep.status)
            assertEquals(serverStamp, ep.stateUpdatedAt)
            assertTrue(runBlocking { db.pendingStateWriteDao().getAll() }.isEmpty())
        }
    }

    @Test
    fun aNewerPendingWriteSurvivesThePull() {
        seed()
        api.pull = { _ ->
            localWriteDuringPull(6_000)
            page(episodes = listOf(episodeDto(10, 1, EpisodeStatus.NEW, 5_000, title = "Fresh")), nextRev = 9)
        }
        sync()
        val ep = episodeRow(10)!!
        assertEquals(EpisodeStatus.PLAYED, ep.status)
        assertEquals(6_000L, ep.stateUpdatedAt)
        assertEquals("Fresh", ep.title)
        assertEquals(6_000L, runBlocking { db.pendingStateWriteDao().getById(10) }!!.stateUpdatedAt)
    }

    // ---- all or nothing ----

    @Test
    fun aTransactionThatThrowsRollsBackEverythingItWrote() {
        var failed = false
        try {
            runBlocking {
                store.transaction {
                    insertFeedIfAbsent(feed(1))
                    insertEpisodeIfAbsent(episode(10, 1))
                    putPending(PendingStateWrite(10, EpisodeStatus.NEW, null, 0, 1))
                    throw IOException("boom")
                }
            }
        } catch (e: IOException) {
            failed = true
        }
        assertTrue(failed)
        assertTrue(query("SELECT id FROM feed") { it.getLong(0) }.isEmpty())
        assertTrue(query("SELECT id FROM episode") { it.getLong(0) }.isEmpty())
        assertTrue(query("SELECT episodeId FROM pending_state_write") { it.getLong(0) }.isEmpty())
    }

    /** A store whose Episode insert fails for one id, after the Feed and earlier rows were written. */
    private fun failingOn(episodeId: Long) = object : CacheStore by store {
        override suspend fun <R> transaction(block: suspend CacheTransaction.() -> R): R = store.transaction {
            val real = this
            val wrapped = object : CacheTransaction by real {
                override suspend fun insertEpisodeIfAbsent(episode: Episode) {
                    if (episode.id == episodeId) throw IOException("boom")
                    real.insertEpisodeIfAbsent(episode)
                }
            }
            wrapped.block()
        }
    }

    @Test
    fun lastSyncRevOnlyAdvancesAfterTheApplyTransactionCommits() {
        settings.flow.value = settings.flow.value.copy(lastSyncRev = 3)
        api.pull = { _ -> page(listOf(feedDto(1)), listOf(episodeDto(10, 1), episodeDto(11, 1)), nextRev = 12) }

        val failed = sync(failingOn(11))
        assertTrue(failed is SyncResult.Failed)
        assertEquals(3L, settings.flow.value.lastSyncRev)
        assertTrue("nothing from the failed pull is stored", query("SELECT id FROM feed") { it.getLong(0) }.isEmpty())
        assertTrue(query("SELECT id FROM episode") { it.getLong(0) }.isEmpty())

        assertEquals(SyncResult.Success, sync())
        assertEquals(12L, settings.flow.value.lastSyncRev)
        assertEquals(2, query("SELECT id FROM episode") { it.getLong(0) }.size)
        assertEquals(listOf(3L, 3L), api.pullSince)
    }
}

private class MemorySettings : SettingsRepository {
    val flow = MutableStateFlow(Settings(serverUrl = "https://pod.example.com", authToken = "test-token"))
    override val settings: Flow<Settings> = flow
    override suspend fun saveConnection(serverUrl: String, authToken: String) {
        flow.value = flow.value.copy(serverUrl = serverUrl, authToken = authToken)
    }

    override suspend fun clearToken() {
        flow.value = flow.value.copy(authToken = "")
    }

    override suspend fun setLastSyncRev(rev: Long) {
        flow.value = flow.value.copy(lastSyncRev = rev)
    }

    override suspend fun setSyncIntervalHours(hours: Int) {
        flow.value = flow.value.copy(syncIntervalHours = hours)
    }

    override suspend fun setSkipIntervals(backSec: Int, forwardSec: Int) {
        flow.value = flow.value.copy(skipBackSec = backSec, skipForwardSec = forwardSec)
    }

    override suspend fun toggleSpeedMode() = Unit

    override suspend fun assignSpeed(speed: Float?) = Unit

    override suspend fun setDownloadWifiOnly(enabled: Boolean) {
        flow.value = flow.value.copy(downloadWifiOnly = enabled)
    }
}

private class ScriptedApi : PodClatchApi {
    var pull: suspend (Long) -> SyncPageDto = { since -> SyncPageDto(emptyList(), emptyList(), emptyList(), since, false) }
    var push: suspend (List<EpisodeStateItem>) -> List<EpisodeStateResult> = { items ->
        items.map { EpisodeStateResult(it.episodeId, StateOutcome.APPLIED, it.status, it.playedAt, it.positionSec, it.stateUpdatedAt) }
    }
    val pullSince = mutableListOf<Long>()

    override suspend fun health(target: ServerTarget?) = Unit

    override suspend fun pullSync(since: Long, limit: Int, target: ServerTarget?): SyncPageDto {
        pullSince += since
        return pull(since)
    }

    override suspend fun pushEpisodeState(items: List<EpisodeStateItem>) = push(items)

    override suspend fun reorderFeeds(feedIds: List<Long>): List<FeedDto> = emptyList()

    override suspend fun unsubscribeFeed(feedId: Long) = Unit

    override suspend fun subscribeFeed(feedUrl: String): FeedDto = throw UnsupportedOperationException()

    override suspend fun searchPodcasts(q: String): List<com.podclatch.data.network.SearchResultDto> =
        throw UnsupportedOperationException()

    override suspend fun updateFeedListenOrder(feedId: Long, listenOrder: ListenOrder): FeedDto =
        throw UnsupportedOperationException()

    override suspend fun refreshFeed(feedId: Long): List<RefreshResultDto> = emptyList()

    override suspend fun refreshAllFeeds(): List<RefreshResultDto> = emptyList()
}
