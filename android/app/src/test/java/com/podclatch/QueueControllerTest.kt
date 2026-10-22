package com.podclatch

import com.podclatch.data.db.DownloadState
import com.podclatch.data.db.EpisodeStatus
import com.podclatch.data.db.ListenOrder
import com.podclatch.data.network.ApiError
import com.podclatch.data.network.ApiException
import com.podclatch.data.network.RefreshResultDto
import com.podclatch.data.repo.EpisodeStateRepository
import com.podclatch.data.repo.FeedsRepository
import com.podclatch.data.repo.QueueRepository
import com.podclatch.domain.DiskFileDeleter
import com.podclatch.domain.SyncEngine
import com.podclatch.domain.TokenRejection
import com.podclatch.playback.PlaybackPhase
import com.podclatch.playback.PlaybackUiState
import com.podclatch.ui.feeds.FeedsMessage
import com.podclatch.ui.queue.QueueController
import com.podclatch.ui.queue.QueueMessage
import com.podclatch.ui.queue.QueueUiState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Queue controller with fakes. The action scope uses `Dispatchers.Unconfined`; states are awaited by their exact end state. */
class QueueControllerTest {
    private val actionScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val stateScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val settings = FakeSettings()
    private val api = FakeApi()
    private val store = FakeCacheStore()
    private val rejection = TokenRejection(settings)
    private val engine = SyncEngine(api, settings, store, { }, { 5_000 }, rejection, DiskFileDeleter(), Dispatchers.Unconfined)
    private val feeds = FeedsRepository(api, store, engine, rejection)
    private var now = 50_000L
    private val states = EpisodeStateRepository(store) { now }
    private val playback = FakePlaybackController(knownIds = setOf(10L, 11L, 12L, 20L))

    init {
        store.feeds[1] = cachedFeed(1).copy(title = "Tones", priority = 0)
        store.feeds[2] = cachedFeed(2).copy(title = "Others", priority = 1)
        // Feed 1, newest first: 12, 11, 10. Episode 13 is not downloaded, 14 is Played.
        store.episodes[10] = cachedEpisode(10, 1, downloadState = DownloadState.DOWNLOADED).copy(publishedAt = 100, title = "Ten")
        store.episodes[11] = cachedEpisode(11, 1, positionSec = 30, downloadState = DownloadState.DOWNLOADING).copy(publishedAt = 200, title = "Eleven", durationSec = 120)
        store.episodes[12] = cachedEpisode(12, 1, downloadState = DownloadState.DOWNLOADED).copy(publishedAt = 300, title = "Twelve")
        store.episodes[13] = cachedEpisode(13, 1, downloadState = DownloadState.QUEUED).copy(publishedAt = 400)
        store.episodes[14] = cachedEpisode(14, 1, EpisodeStatus.PLAYED, playedAt = 5, downloadState = DownloadState.DOWNLOADED).copy(publishedAt = 500)
        store.episodes[20] = cachedEpisode(20, 2, downloadState = DownloadState.DOWNLOADED).copy(publishedAt = 100)
    }

    @After
    fun tearDown() {
        actionScope.cancel()
        stateScope.cancel()
    }

    private fun controller(scope: CoroutineScope = actionScope) =
        QueueController(QueueRepository(store), feeds, states, engine, playback, scope, stateScope)

    private fun QueueController.await(what: String, condition: (QueueUiState) -> Boolean) = awaitState(uiState, what, condition = condition)

    private fun pulls() = api.calls.count { it == "pull" }

    private fun QueueUiState.ids() = items.map { it.episodeId }

    // ---- what is shown ----

    @Test
    fun theQueueHoldsTheDownloadedAndDownloadingNewEpisodesInOrder() {
        val c = controller()
        val state = c.await("loaded with the five queued items") { it.loaded && it.items.size == 4 }
        assertEquals(listOf(12L, 11L, 10L, 20L), state.ids())
        assertEquals("Tones", state.items.first().feedTitle)
        assertNull(state.nowPlayingId)
        assertNull(state.message)
    }

    @Test
    fun beforeTheFirstReadTheQueueIsNotLoaded() {
        assertFalse(QueueUiState().loaded)
    }

    @Test
    fun theQueueFollowsMarksDownloadsAndListenOrderLive() {
        val c = controller()
        c.await("initial") { it.ids() == listOf(12L, 11L, 10L, 20L) }

        store.episodes[13] = store.episodes.getValue(13).copy(downloadState = DownloadState.DOWNLOADING)
        store.touch()
        c.await("13 joins first") { it.ids() == listOf(13L, 12L, 11L, 10L, 20L) }

        store.feeds[1] = store.feeds.getValue(1).copy(listenOrder = ListenOrder.OLDEST_FIRST)
        store.touch()
        c.await("oldest first in feed 1") { it.ids() == listOf(10L, 11L, 12L, 13L, 20L) }

        store.feeds[1] = store.feeds.getValue(1).copy(priority = 5)
        store.touch()
        c.await("feed 2 now first") { it.ids() == listOf(20L, 10L, 11L, 12L, 13L) }

        store.episodes.remove(20)
        store.touch()
        c.await("20 removed") { it.ids() == listOf(10L, 11L, 12L, 13L) }
    }

    @Test
    fun theNowPlayingEpisodeFollowsThePlaybackState() {
        val c = controller()
        c.await("loaded") { it.loaded && it.items.isNotEmpty() }
        playback.setState(PlaybackUiState(episodeId = 11, isPlaying = true, phase = PlaybackPhase.READY))
        c.await("11 playing") { it.nowPlayingId == 11L }
        playback.setState(PlaybackUiState(episodeId = 12, isPlaying = true, phase = PlaybackPhase.READY))
        c.await("12 playing") { it.nowPlayingId == 12L }
        playback.setState(PlaybackUiState(episodeId = 12, isPlaying = false, phase = PlaybackPhase.ENDED))
        c.await("no marker once ended") { it.nowPlayingId == null }
        playback.setState(PlaybackUiState())
        c.await("still none when cleared") { it.nowPlayingId == null }
    }

    // ---- tap to play ----

    @Test
    fun tappingARowPlaysItOnceAndChangesNothingElse() {
        val c = controller()
        c.await("loaded") { it.loaded && it.items.isNotEmpty() }
        val before = store.episodes.toMap()
        c.play(11)
        awaitCondition("play requested") { playback.played == listOf(11L) }
        assertEquals(before, store.episodes.toMap())
        assertEquals(emptyList<String>(), api.calls.filter { it != "pull" })
        assertNull(c.uiState.value.message)
    }

    @Test
    fun aPlayThatFailsShowsAMessage() {
        val c = controller()
        c.play(999)
        val state = c.await("play failed") { it.message == QueueMessage.PlayFailed }
        assertTrue(state.message is QueueMessage.PlayFailed)
        c.dismissMessage()
        c.await("dismissed") { it.message == null }
    }

    // ---- swipes ----

    @Test
    fun markingPlayedWritesRoomAndTheOutboxThenSyncs() {
        val c = controller()
        c.await("loaded") { it.loaded && it.items.isNotEmpty() }
        c.markPlayed(12)
        val state = c.await("12 left the Queue") { it.ids() == listOf(11L, 10L, 20L) }
        val episode = store.episodes.getValue(12)
        assertEquals(EpisodeStatus.PLAYED, episode.status)
        assertEquals(50_000L, episode.playedAt)
        assertEquals(0, episode.positionSec)
        assertNull(state.message)
        awaitCondition("the change was pushed") { api.pushRequests.flatten().map { it.episodeId } == listOf(12L) }
    }

    @Test
    fun markingNewKeepsTheEpisodeInTheQueueInTheSamePlaceWithPositionZero() {
        val c = controller()
        c.await("loaded") { it.loaded && it.items.isNotEmpty() }
        c.markNew(11)
        c.await("11 reset") { s -> s.items.single { it.episodeId == 11L }.positionSec == 0 }
        val episode = store.episodes.getValue(11)
        assertEquals(EpisodeStatus.NEW, episode.status)
        assertNull(episode.playedAt)
        assertEquals(listOf(12L, 11L, 10L, 20L), c.uiState.value.ids())
    }

    @Test
    fun aNoOpSwipeWritesNothingAndSyncsNothing() {
        val c = controller()
        c.await("loaded") { it.loaded && it.items.isNotEmpty() }
        val before = store.episodes.toMap()
        val callsBefore = api.calls.toList()
        c.markNew(12) // New with position 0: nothing to do.
        c.markPlayed(14) // Already Played.
        assertEquals(before, store.episodes.toMap())
        assertTrue(store.pending.isEmpty())
        assertEquals(callsBefore, api.calls.toList())
    }

    // ---- the big swipe ----

    @Test
    fun aRefreshCallsTheServerOnceThenSyncs() {
        val c = controller()
        c.await("loaded") { it.loaded && it.items.isNotEmpty() }
        assertTrue(c.refresh())
        val state = c.await("idle again") { !it.refreshing && api.calls.contains("refresh") }
        assertEquals(1, api.calls.count { it == "refresh" })
        assertTrue("the Sync follows the refresh", api.calls.indexOf("pull") > api.calls.indexOf("refresh"))
        assertNull(state.message)
    }

    @Test
    fun aSecondRefreshWhileOneRunsDoesNothing() {
        val gate = CompletableDeferred<Unit>()
        val reached = CompletableDeferred<Unit>()
        api.refreshHandler = {
            reached.complete(Unit)
            gate.await()
            emptyList()
        }
        val c = controller()
        c.await("loaded") { it.loaded && it.items.isNotEmpty() }
        assertTrue(c.refresh())
        awaitGate("the refresh reached the server", reached)
        c.await("refreshing") { it.refreshing }
        assertFalse(c.refresh())
        gate.complete(Unit)
        c.await("done") { !it.refreshing && pulls() == 1 }
        assertEquals(1, api.calls.count { it == "refresh" })
    }

    @Test
    fun someFeedsFailingShowsTheSameBannerAsTheFeedsTab() {
        api.refreshHandler = { listOf(RefreshResultDto(1, true), RefreshResultDto(2, false, "no good")) }
        val c = controller()
        c.refresh()
        val state = c.await("partial banner") { it.message is QueueMessage.Refresh }
        val message = (state.message as QueueMessage.Refresh).message as FeedsMessage.RefreshPartial
        assertEquals(1, message.failed)
        assertEquals(2, message.total)
        assertEquals("Others", message.lines.single().title)
        assertEquals("no good", message.lines.single().error)
    }

    @Test
    fun anUnreachableServerShowsTheRefreshFailedBanner() {
        api.refreshHandler = { throw ApiException(ApiError.Unreachable(false)) }
        val c = controller()
        c.refresh()
        val state = c.await("failed banner") { it.message is QueueMessage.Refresh }
        assertTrue((state.message as QueueMessage.Refresh).message is FeedsMessage.RefreshFailed)
        assertFalse(state.refreshing)
        assertEquals(0, pulls())
    }

    @Test
    fun aRejectedTokenShowsNoBanner() {
        api.refreshHandler = { throw ApiException(ApiError.Unauthorized) }
        val c = controller()
        c.refresh()
        c.await("idle with no banner") { !it.refreshing && api.calls.contains("refresh") }
        assertNull(c.uiState.value.message)
    }

    // ---- Sync now ----

    @Test
    fun syncNowRunsAManualSync() {
        val c = controller()
        c.syncNow()
        awaitCondition("a pull ran") { pulls() == 1 }
        assertEquals(1, pulls())
    }
}
