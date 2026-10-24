package com.podclatch

import com.podclatch.data.db.DownloadState
import com.podclatch.data.db.EpisodeStatus
import com.podclatch.data.repo.EpisodeStateRepository
import com.podclatch.data.repo.FeedsRepository
import com.podclatch.domain.DiskFileDeleter
import com.podclatch.domain.PlaybackSpeedUseCase
import com.podclatch.domain.SyncEngine
import com.podclatch.domain.TokenRejection
import com.podclatch.playback.PlaybackError
import com.podclatch.playback.PlaybackPhase
import com.podclatch.playback.PlaybackUiState
import com.podclatch.ui.feeddetail.DownloadFailureLabel
import com.podclatch.ui.feeddetail.FeedDetailController
import com.podclatch.ui.feeddetail.canRetryDownload
import com.podclatch.ui.feeddetail.downloadFailureLabel
import com.podclatch.ui.feeddetail.episodeDescription
import com.podclatch.ui.feeds.reorderByOne
import com.podclatch.ui.player.PlayerController
import com.podclatch.ui.player.PlayerErrorMessage
import com.podclatch.ui.player.PlayerErrorUi
import com.podclatch.ui.player.playerErrorUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** US-20 part A: the pure rules for the download failure label, the retry menu, the player error, and Move up/down. */
class ResilienceRulesTest {
    @Test
    fun onlyAFailedNewEpisodeShowsAFailureLabel() {
        for (status in EpisodeStatus.entries) for (state in DownloadState.entries) for (permanent in listOf(false, true)) {
            val expected = when {
                status != EpisodeStatus.NEW || state != DownloadState.FAILED -> null
                permanent -> DownloadFailureLabel.UNAVAILABLE
                else -> DownloadFailureLabel.FAILED
            }
            assertEquals("$status $state $permanent", expected, downloadFailureLabel(status, state, permanent))
        }
    }

    @Test
    fun retryDownloadIsOfferedExactlyForFailedNewEpisodes() {
        for (status in EpisodeStatus.entries) for (state in DownloadState.entries) {
            assertEquals("$status $state", status == EpisodeStatus.NEW && state == DownloadState.FAILED, canRetryDownload(status, state))
        }
    }

    @Test
    fun theRowDescriptionIncludesTheFailureLabelOnlyWhenThereIsOne() {
        assertEquals("T, D, 5 min, New", episodeDescription("T", "D", "5 min", "New", null))
        assertEquals("T, D, New, Download failed", episodeDescription("T", "D", null, "New", null, "Download failed"))
    }

    @Test
    fun playbackErrorsMapToAMessageAndARetryRule() {
        assertNull(playerErrorUi(null))
        assertEquals(PlayerErrorUi(PlayerErrorMessage.NETWORK, true), playerErrorUi(PlaybackError.NETWORK))
        assertEquals(PlayerErrorUi(PlayerErrorMessage.UNKNOWN, true), playerErrorUi(PlaybackError.UNKNOWN))
        assertEquals(PlayerErrorUi(PlayerErrorMessage.UNSUPPORTED, false), playerErrorUi(PlaybackError.UNSUPPORTED))
    }

    @Test
    fun moveByOneMovesOnePlaceAndStopsAtTheEnds() {
        val ids = listOf(1L, 2L, 3L)
        assertEquals(listOf(2L, 1L, 3L), reorderByOne(ids, 1, 1))
        assertEquals(listOf(1L, 3L, 2L), reorderByOne(ids, 3, -1))
        assertNull(reorderByOne(ids, 1, -1))
        assertNull(reorderByOne(ids, 3, 1))
        assertNull(reorderByOne(ids, 99, 1))
        assertNull(reorderByOne(ids, 2, 0))
    }
}

/** Retry in the full player and Retry download in Feed Detail, with fakes. */
class ResilienceControllersTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun failed(playback: FakePlaybackController, error: PlaybackError?) = playback.setState(
        PlaybackUiState(episodeId = 1, phase = PlaybackPhase.IDLE, title = "Episode 1", error = error),
    )

    private fun player(playback: FakePlaybackController): PlayerController {
        val settings = FakeSettings()
        val ticks = Channel<Unit>(Channel.UNLIMITED).receiveAsFlow()
        val c = PlayerController(playback, PlaybackSpeedUseCase(settings), settings, scope, scope, ticks)
        scope.launch { c.uiState.collect { } }
        return c
    }

    @Test
    fun retryAsksForTheLoadedEpisodeOnceAndTheErrorGoesAway() {
        val playback = FakePlaybackController(knownIds = setOf(1L))
        val c = player(playback)
        failed(playback, PlaybackError.NETWORK)
        c.retry()
        awaitState(c.uiState, "error cleared after the retry") { it.playback.episodeId == 1L && it.playback.error == null }
        assertEquals(listOf(1L), playback.played)
    }

    @Test
    fun retryIsIgnoredForAnUnsupportedErrorAndWhenNothingIsLoaded() {
        val playback = FakePlaybackController(knownIds = setOf(1L))
        val c = player(playback)
        c.retry()
        failed(playback, PlaybackError.UNSUPPORTED)
        c.retry()
        assertTrue(playback.played.isEmpty())
    }

    @Test
    fun retryDownloadMakesOneManualRequestForAFailedNewEpisodeOnly() {
        val settings = FakeSettings()
        val api = FakeApi()
        val store = FakeCacheStore()
        val rejection = TokenRejection(settings)
        val engine = SyncEngine(api, settings, store, { }, { 5_000 }, rejection, DiskFileDeleter(), Dispatchers.Unconfined)
        val downloads = RecordingDownloads(store)
        store.feeds[1] = cachedFeed(1)
        store.episodes[10] = cachedEpisode(10, 1, downloadState = DownloadState.FAILED)
        store.episodes[11] = cachedEpisode(11, 1, downloadState = DownloadState.NONE)
        store.episodes[12] = cachedEpisode(12, 1, EpisodeStatus.PLAYED, playedAt = 5, downloadState = DownloadState.FAILED)
        val c = FeedDetailController(
            1, FeedsRepository(api, store, engine, rejection), EpisodeStateRepository(store) { 1 }, engine,
            FakePlaybackController(), scope, scope, downloads,
        )
        c.await("three rows") { it.episodes.size == 3 }
        c.retryDownload(11)
        c.retryDownload(12)
        c.retryDownload(99)
        assertTrue(downloads.manual.isEmpty())
        c.retryDownload(10)
        awaitCondition("one manual request for 10") { downloads.manual == listOf(10L) }
        assertTrue(downloads.automatic.isEmpty())
    }
}
