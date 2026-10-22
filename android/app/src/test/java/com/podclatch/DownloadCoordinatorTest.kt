package com.podclatch

import androidx.work.NetworkType
import com.podclatch.data.db.DownloadState
import com.podclatch.data.repo.Settings
import com.podclatch.download.DownloadCoordinator
import com.podclatch.download.DownloadPaths
import com.podclatch.download.DownloadReconciler
import com.podclatch.download.JobState
import com.podclatch.download.downloadNetworkType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The request rules, remove, and the startup reconcile, with a fake store, a fake WorkManager, and a temporary directory. */
class DownloadCoordinatorTest {
    @get:Rule val tmp = TemporaryFolder()

    private val store = FakeDownloadStore()
    private val scheduler = FakeDownloadScheduler()
    private val settings = FakeSettings(Settings(serverUrl = TEST_URL, authToken = TEST_TOKEN))
    private val dir by lazy { tmp.newFolder("audio") }
    private val fs by lazy { FakeFileSystem(dir) }
    private val coordinator by lazy { DownloadCoordinator(store, scheduler, fs, settings) }
    private val reconciler by lazy { DownloadReconciler(store, scheduler, fs, settings) }

    private fun automatic(id: Long = 10) = runBlocking { coordinator.requestAutomatic(id) }
    private fun manual(id: Long = 10) = runBlocking { coordinator.requestManual(id) }
    private fun remove(id: Long = 10) = runBlocking { coordinator.remove(id) }
    private fun reconcile() = runBlocking { reconciler.run() }

    private fun finalFile(id: Long = 10, content: String = "audio") =
        DownloadPaths.finalFile(dir, id).also { it.writeText(content) }

    // ---- the Wi-Fi-only constraint ----

    @Test
    fun theNetworkConstraintDependsOnWifiOnlyAndTheKindOfRequest() {
        assertEquals(NetworkType.UNMETERED, downloadNetworkType(wifiOnly = true, manual = false))
        assertEquals(NetworkType.CONNECTED, downloadNetworkType(wifiOnly = false, manual = false))
        assertEquals(NetworkType.CONNECTED, downloadNetworkType(wifiOnly = true, manual = true))
        assertEquals(NetworkType.CONNECTED, downloadNetworkType(wifiOnly = false, manual = true))
    }

    @Test
    fun anAutomaticRequestUsesTheStoredWifiOnlySettingAndAManualOneNeverWaitsForWifi() {
        store.put(downloadRow(10, state = DownloadState.NONE))
        store.put(downloadRow(11, state = DownloadState.NONE))
        automatic(10)
        manual(11)
        assertEquals(NetworkType.UNMETERED, scheduler.jobs.getValue(10).network)
        assertEquals(NetworkType.CONNECTED, scheduler.jobs.getValue(11).network)
        store.put(downloadRow(12, state = DownloadState.NONE))
        runBlocking { settings.setDownloadWifiOnly(false) }
        automatic(12)
        assertEquals(NetworkType.CONNECTED, scheduler.jobs.getValue(12).network)
    }

    // ---- requests ----

    @Test
    fun anAutomaticRequestQueuesAndAManualOneGoesStraightToDownloading() {
        store.put(downloadRow(10, state = DownloadState.NONE))
        store.put(downloadRow(11, state = DownloadState.NONE))
        automatic(10)
        manual(11)
        assertEquals(DownloadState.QUEUED, store.row(10)!!.downloadState)
        assertEquals(DownloadState.DOWNLOADING, store.row(11)!!.downloadState)
        assertFalse(scheduler.jobs.getValue(10).manual)
        assertTrue(scheduler.jobs.getValue(11).manual)
    }

    @Test
    fun aSecondRequestOfEitherKindForAQueuedOrDownloadingEpisodeCreatesNoSecondJob() {
        store.put(downloadRow(10, state = DownloadState.NONE))
        automatic()
        scheduler.start(10)
        store.overwrite(10) { it.copy(downloadState = DownloadState.DOWNLOADING) }
        automatic()
        manual()
        assertEquals(1, scheduler.enqueued.size)
        assertEquals(JobState.RUNNING, scheduler.jobs.getValue(10).state)
        assertEquals(DownloadState.DOWNLOADING, store.row(10)!!.downloadState)
    }

    @Test
    fun aManualRequestReplacesAnAutomaticJobThatIsStillWaiting() {
        store.put(downloadRow(10, state = DownloadState.NONE))
        automatic()
        manual()
        assertEquals(2, scheduler.enqueued.size)
        val job = scheduler.jobs.getValue(10)
        assertTrue(job.manual)
        assertEquals(NetworkType.CONNECTED, job.network)
        assertEquals(DownloadState.DOWNLOADING, store.row(10)!!.downloadState)
    }

    @Test
    fun aManualRequestTakesOverAnAutomaticJobThatWaitsForASlot() {
        // Running as far as WorkManager knows, but still QUEUED in the database: it waits for one of two slots.
        store.put(downloadRow(10, state = DownloadState.NONE))
        automatic()
        scheduler.start(10)
        manual()
        assertEquals(2, scheduler.enqueued.size)
        assertTrue(scheduler.jobs.getValue(10).manual)
    }

    @Test
    fun aDownloadedEpisodeWhoseFileExistsIsLeftAlone() {
        val file = finalFile()
        store.put(downloadRow(10, state = DownloadState.DOWNLOADED, localPath = file.absolutePath))
        automatic()
        manual()
        assertTrue(scheduler.enqueued.isEmpty())
        assertEquals(DownloadState.DOWNLOADED, store.row(10)!!.downloadState)
    }

    @Test
    fun aDownloadedEpisodeWhoseFileIsMissingOrEmptyIsDownloadedAgain() {
        store.put(downloadRow(10, state = DownloadState.DOWNLOADED, localPath = File(dir, "gone.audio").absolutePath))
        store.put(downloadRow(11, state = DownloadState.DOWNLOADED, localPath = finalFile(11, "").absolutePath))
        automatic(10)
        manual(11)
        assertEquals(DownloadState.QUEUED, store.row(10)!!.downloadState)
        assertNull(store.row(10)!!.localPath)
        assertEquals(DownloadState.DOWNLOADING, store.row(11)!!.downloadState)
        assertEquals(setOf(10L, 11L), scheduler.jobs.keys)
    }

    @Test
    fun anAutomaticRequestSkipsAPermanentFailureAndAManualOneClearsTheMark() {
        store.put(downloadRow(10, state = DownloadState.FAILED, permanent = true))
        automatic()
        assertTrue(scheduler.enqueued.isEmpty())
        assertEquals(DownloadState.FAILED, store.row(10)!!.downloadState)
        manual()
        assertEquals(DownloadState.DOWNLOADING, store.row(10)!!.downloadState)
        assertFalse(store.row(10)!!.downloadPermanentFailure)
        assertEquals(1, scheduler.enqueued.size)
    }

    @Test
    fun aTransientlyFailedEpisodeIsQueuedAgainByAnAutomaticRequestAndKeepsItsProgress() {
        store.put(downloadRow(10, state = DownloadState.FAILED, bytes = 500, total = 900))
        automatic()
        assertEquals(DownloadState.QUEUED, store.row(10)!!.downloadState)
        assertEquals(500L, store.row(10)!!.bytesReceived)
    }

    @Test
    fun aRequestForAnUnknownEpisodeDoesNothing() {
        automatic(99)
        manual(99)
        assertTrue(scheduler.enqueued.isEmpty())
    }

    // ---- remove ----

    @Test
    fun removingADownloadedEpisodeDeletesTheFileAndResetsTheRow() {
        val file = finalFile()
        store.put(downloadRow(10, state = DownloadState.DOWNLOADED, localPath = file.absolutePath, bytes = 5, total = 5))
        remove()
        assertFalse(file.exists())
        val row = store.row(10)!!
        assertEquals(DownloadState.NONE, row.downloadState)
        assertNull(row.localPath)
        assertEquals(0L, row.bytesReceived)
        assertNull(row.totalBytes)
    }

    @Test
    fun removingAQueuedOrDownloadingEpisodeCancelsTheJobAndDeletesThePartialFile() {
        for ((id, state) in listOf(10L to DownloadState.QUEUED, 11L to DownloadState.DOWNLOADING)) {
            store.put(downloadRow(id, state = state, bytes = 3))
            DownloadPaths.partial(dir, id).writeText("abc")
            manualJob(id)
            remove(id)
            assertTrue(id in scheduler.cancelled)
            assertFalse(id in scheduler.jobs.keys)
            assertFalse(DownloadPaths.partial(dir, id).exists())
            assertEquals(DownloadState.NONE, store.row(id)!!.downloadState)
            assertEquals(0L, store.row(id)!!.bytesReceived)
        }
    }

    private fun manualJob(id: Long) = runBlocking {
        scheduler.enqueue(id, NetworkType.CONNECTED, manual = false, replace = false)
    }

    @Test
    fun removingAnEpisodeThatIsNoneOrDoesNotExistDoesNothingAndDoesNotThrow() {
        store.put(downloadRow(10, state = DownloadState.NONE))
        remove(10)
        remove(99)
        assertEquals(DownloadState.NONE, store.row(10)!!.downloadState)
        assertTrue(scheduler.cancelled.isEmpty())
    }

    @Test
    fun ifTheFileCannotBeDeletedTheRowStaysDownloaded() {
        // A directory at the path cannot be deleted by File.delete() while it holds a file.
        val stuck = File(dir, "10.audio").also { it.mkdir() }
        File(stuck, "inside").writeText("x")
        store.put(downloadRow(10, state = DownloadState.DOWNLOADED, localPath = stuck.absolutePath))
        remove()
        assertEquals(DownloadState.DOWNLOADED, store.row(10)!!.downloadState)
        assertEquals(stuck.absolutePath, store.row(10)!!.localPath)
    }

    @Test
    fun removingAFailedEpisodeKeepsThePermanentMark() {
        store.put(downloadRow(10, state = DownloadState.FAILED, permanent = true))
        remove()
        assertEquals(DownloadState.NONE, store.row(10)!!.downloadState)
        assertTrue(store.row(10)!!.downloadPermanentFailure)
        automatic()
        assertTrue("an automatic request still does not retry it", scheduler.enqueued.isEmpty())
    }

    @Test
    fun discardCancelsTheJobsAndDeletesThePartialFilesWithoutTouchingTheFinalFiles() {
        val partial = DownloadPaths.partial(dir, 10).also { it.writeText("abc") }
        val final = finalFile(11)
        manualJob(10)
        runBlocking { coordinator.discard(listOf(10L, 11L, 12L)) }
        assertFalse(partial.exists())
        assertTrue(final.exists())
        assertTrue(10L in scheduler.cancelled)
    }

    // ---- reconcile ----

    @Test
    fun aQueuedOrDownloadingRowWithNoLiveJobIsEnqueuedAgainAsAnAutomaticJob() {
        store.put(downloadRow(10, state = DownloadState.QUEUED))
        store.put(downloadRow(11, state = DownloadState.DOWNLOADING))
        store.put(downloadRow(12, state = DownloadState.DOWNLOADING))
        manualJob(12)
        reconcile()
        assertEquals(setOf(10L, 11L, 12L), scheduler.jobs.keys)
        assertEquals(NetworkType.UNMETERED, scheduler.jobs.getValue(10).network)
        assertFalse(scheduler.jobs.getValue(11).manual)
        assertEquals("a live job is not enqueued twice", 3, scheduler.enqueued.size)
    }

    @Test
    fun aDownloadedRowWhoseFileIsMissingOrEmptyGoesBackToNone() {
        val good = finalFile(10)
        store.put(downloadRow(10, state = DownloadState.DOWNLOADED, localPath = good.absolutePath))
        store.put(downloadRow(11, state = DownloadState.DOWNLOADED, localPath = File(dir, "11.audio").absolutePath))
        store.put(downloadRow(12, state = DownloadState.DOWNLOADED, localPath = finalFile(12, "").absolutePath))
        reconcile()
        assertEquals(DownloadState.DOWNLOADED, store.row(10)!!.downloadState)
        for (id in listOf(11L, 12L)) {
            assertEquals(DownloadState.NONE, store.row(id)!!.downloadState)
            assertNull(store.row(id)!!.localPath)
        }
    }

    @Test
    fun aPartialFileWithNoEpisodeRowIsDeletedAndOneWithARowIsKept() {
        store.put(downloadRow(10, state = DownloadState.DOWNLOADING))
        val kept = DownloadPaths.partial(dir, 10).also { it.writeText("abc") }
        val orphan = DownloadPaths.partial(dir, 99).also { it.writeText("abc") }
        val unrelated = File(dir, "notes.txt").also { it.writeText("x") }
        reconcile()
        assertTrue(kept.exists())
        assertFalse(orphan.exists())
        assertTrue(unrelated.exists())
    }

    @Test
    fun reconcilingTwiceChangesNothingTheSecondTimeAndNeverThrows() {
        store.put(downloadRow(10, state = DownloadState.QUEUED))
        store.put(downloadRow(11, state = DownloadState.DOWNLOADED, localPath = File(dir, "x").absolutePath))
        reconcile()
        val enqueuedOnce = scheduler.enqueued.size
        val historyOnce = store.history.size
        reconcile()
        assertEquals(enqueuedOnce, scheduler.enqueued.size)
        assertEquals(historyOnce, store.history.size)
        fs.directory = null // unavailable storage must not make it throw
        reconcile()
    }
}
