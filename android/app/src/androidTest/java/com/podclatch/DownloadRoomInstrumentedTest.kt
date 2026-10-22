package com.podclatch

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.podclatch.data.db.DownloadProgress
import com.podclatch.data.db.DownloadState
import com.podclatch.data.db.Episode
import com.podclatch.data.db.EpisodeStatus
import com.podclatch.data.db.Feed
import com.podclatch.data.db.ListenOrder
import com.podclatch.data.db.MIGRATION_1_2
import com.podclatch.data.db.PodClatchDatabase
import com.podclatch.download.RoomDownloadStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The version 2 schema: the migration from a real version 1 database, and the download queries against real SQLite. */
class DownloadRoomInstrumentedTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), PodClatchDatabase::class.java)

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    // ---- Migration(1, 2) ----

    @Test
    fun migratingFromVersion1KeepsEveryRowAndGivesTheNewColumnsTheirDefaults() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO feed (id, feedUrl, title, author, description, artworkUrl, priority, listenOrder, " +
                    "lastRefreshedAt, subscribedAt, rev) VALUES (1, 'https://feeds.example.com/1.xml', 'One', 'A', 'D', '', 3, " +
                    "'OLDEST_FIRST', NULL, 7, 11)",
            )
            db.execSQL(
                "INSERT INTO episode (id, feedId, guid, title, description, audioUrl, publishedAt, durationSec, artworkUrl, " +
                    "status, playedAt, positionSec, stateUpdatedAt, rev, downloadState, localPath) VALUES " +
                    "(10, 1, 'g10', 'Ten', 'd', 'https://audio.example.com/10.mp3', 2000, 90, NULL, 'NEW', NULL, 12, 1000, 4, " +
                    "'DOWNLOADED', '/data/10.audio')",
            )
            db.execSQL("INSERT INTO pending_state_write (episodeId, status, playedAt, positionSec, stateUpdatedAt) VALUES (10, 'PLAYED', 5, 30, 1500)")
        }

        helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2).use { db ->
            db.query("SELECT title, author, priority, listenOrder, subscribedAt, rev FROM feed WHERE id = 1").use {
                assertTrue(it.moveToFirst())
                assertEquals(listOf("One", "A", "3", "OLDEST_FIRST", "7", "11"), (0..5).map { i -> it.getString(i) })
            }
            db.query(
                "SELECT guid, title, positionSec, stateUpdatedAt, rev, downloadState, localPath, " +
                    "bytesReceived, totalBytes, downloadPermanentFailure FROM episode WHERE id = 10",
            ).use {
                assertTrue(it.moveToFirst())
                assertEquals("g10", it.getString(0))
                assertEquals("Ten", it.getString(1))
                assertEquals(12, it.getInt(2))
                assertEquals(1000L, it.getLong(3))
                assertEquals(4L, it.getLong(4))
                assertEquals("DOWNLOADED", it.getString(5))
                assertEquals("/data/10.audio", it.getString(6))
                assertEquals("0 bytes", 0L, it.getLong(7))
                assertTrue("unknown total", it.isNull(8))
                assertEquals("not marked", 0, it.getInt(9))
            }
            db.query("SELECT status, playedAt, positionSec, stateUpdatedAt FROM pending_state_write WHERE episodeId = 10").use {
                assertTrue(it.moveToFirst())
                assertEquals("PLAYED", it.getString(0))
                assertEquals(5L, it.getLong(1))
                assertEquals(30, it.getInt(2))
                assertEquals(1500L, it.getLong(3))
            }
        }
    }

    @Test
    fun theMigratedDatabaseOpensWithRoomAndTheDownloadQueriesWork() = runBlocking {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO feed (id, feedUrl, title, author, description, artworkUrl, priority, listenOrder, " +
                    "lastRefreshedAt, subscribedAt, rev) VALUES (1, 'https://feeds.example.com/1.xml', 'One', 'A', 'D', '', 0, 'NEWEST_FIRST', NULL, 1, 1)",
            )
            db.execSQL(
                "INSERT INTO episode (id, feedId, guid, title, description, audioUrl, publishedAt, durationSec, artworkUrl, " +
                    "status, playedAt, positionSec, stateUpdatedAt, rev, downloadState, localPath) VALUES " +
                    "(10, 1, 'g10', 'Ten', 'd', 'https://audio.example.com/10.mp3', 2000, NULL, NULL, 'NEW', NULL, 0, 1000, 4, 'NONE', NULL)",
            )
        }
        val migrated = Room.databaseBuilder(context, PodClatchDatabase::class.java, TEST_DB).addMigrations(MIGRATION_1_2).build()
        helper.closeWhenFinished(migrated)
        val store = RoomDownloadStore(migrated)
        store.request(10, DownloadState.QUEUED, clearPermanent = false)
        assertEquals(DownloadState.QUEUED, store.get(10)!!.downloadState)
    }

    // ---- the download queries ----

    private lateinit var room: PodClatchDatabase

    @Before
    fun setUp() {
        room = Room.inMemoryDatabaseBuilder(context, PodClatchDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        room.close()
    }

    private suspend fun seed(): RoomDownloadStore {
        room.feedDao().insertIfAbsent(
            Feed(1, "https://feeds.example.com/1.xml", "One", "", "", "", 0, ListenOrder.NEWEST_FIRST, null, 1, 1),
        )
        room.episodeDao().insertIfAbsent(episode(10))
        room.episodeDao().insertIfAbsent(episode(11))
        return RoomDownloadStore(room)
    }

    private fun episode(id: Long) = Episode(
        id = id, feedId = 1, guid = "g$id", title = "T$id", description = "", audioUrl = "https://audio.example.com/$id.mp3",
        publishedAt = 1, durationSec = null, artworkUrl = null, status = EpisodeStatus.NEW, playedAt = null, positionSec = 0,
        stateUpdatedAt = 1, rev = 1,
    )

    @Test
    fun aDownloadWalksThroughItsStatesAndTheProgressFlowEmitsTheNumbers() = runBlocking {
        val store = seed()
        store.request(10, DownloadState.QUEUED, clearPermanent = false)
        assertTrue(store.markDownloading(10))
        store.saveProgress(10, 500, 2_000)
        assertEquals(
            DownloadProgress(DownloadState.DOWNLOADING, 500, 2_000, false),
            withTimeout(5_000) { store.observe(10).first { it?.bytesReceived == 500L } },
        )
        store.saveProgress(10, 1_500, null)
        assertEquals(1_500L, withTimeout(5_000) { store.observe(10).first { it?.bytesReceived == 1_500L } }!!.bytesReceived)
        assertTrue(store.markDownloaded(10, "/data/10.audio", 1_500))
        val row = store.get(10)!!
        assertEquals(DownloadState.DOWNLOADED, row.downloadState)
        assertEquals("/data/10.audio", row.localPath)
        assertEquals(1_500L, row.totalBytes)
    }

    @Test
    fun theWritesAreNoOpsOutsideTheStatesTheyBelongToAndNeverCreateARow() = runBlocking {
        val store = seed()
        store.saveProgress(10, 100, 200) // NONE: ignored
        assertEquals(0L, store.get(10)!!.bytesReceived)
        assertFalse(store.markDownloading(10))
        assertFalse(store.markDownloaded(10, "/x", 1))
        store.markFailed(10, permanent = true, received = 0, total = null)
        assertEquals(DownloadState.NONE, store.get(10)!!.downloadState)
        assertNull(store.get(99))
        store.request(99, DownloadState.QUEUED, clearPermanent = false)
        assertFalse(store.markDownloading(99))
        assertFalse(store.exists(99))
        assertEquals(2, room.episodeDao().episodesOfFeed(1).size)
    }

    @Test
    fun aPermanentFailureIsMarkedKeptByResetAndClearedByAManualRequest() = runBlocking {
        val store = seed()
        store.request(10, DownloadState.QUEUED, clearPermanent = false)
        store.markFailed(10, permanent = true, received = 0, total = null)
        assertEquals(DownloadState.FAILED, store.get(10)!!.downloadState)
        assertTrue(store.get(10)!!.downloadPermanentFailure)
        store.reset(10)
        assertEquals(DownloadState.NONE, store.get(10)!!.downloadState)
        assertTrue(store.get(10)!!.downloadPermanentFailure)
        store.request(10, DownloadState.DOWNLOADING, clearPermanent = true)
        assertFalse(store.get(10)!!.downloadPermanentFailure)
    }

    @Test
    fun entriesListsOnlyEpisodesWithAnActiveOrFinishedDownloadAndTheFlowEmitsNullWhenTheRowIsGone() = runBlocking {
        val store = seed()
        store.request(10, DownloadState.QUEUED, clearPermanent = false)
        assertEquals(listOf(10L), store.entries().map { it.id })
        assertNotNull(store.observe(11).first())
        room.episodeDao().deleteById(11)
        assertNull(withTimeout(5_000) { store.observe(11).first { it == null } })
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
