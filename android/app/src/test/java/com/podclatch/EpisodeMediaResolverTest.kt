package com.podclatch

import com.podclatch.data.db.DownloadState
import com.podclatch.data.db.PlaybackEpisode
import com.podclatch.playback.AudioSource
import com.podclatch.playback.EpisodeMediaResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EpisodeMediaResolverTest {
    @get:Rule val tmp = TemporaryFolder()

    private val stream = "https://audio.example.com/7.mp3"

    private fun episode(
        state: DownloadState = DownloadState.NONE,
        localPath: String? = null,
        artwork: String? = null,
        feedArtwork: String = "",
        durationSec: Int? = null,
    ) = PlaybackEpisode(
        id = 7, title = "The title", feedTitle = "The feed", audioUrl = stream, durationSec = durationSec,
        artworkUrl = artwork, feedArtworkUrl = feedArtwork, downloadState = state, localPath = localPath,
    )

    private fun resolver(episode: PlaybackEpisode?) = EpisodeMediaResolver(
        lookup = { id -> episode?.takeIf { it.id == id } },
        fileUsable = { path -> File(path).let { it.isFile && it.length() > 0 } },
        fileDispatcher = Dispatchers.Unconfined,
    )

    private fun resolve(episode: PlaybackEpisode?, id: Long = 7) = runBlocking { resolver(episode).resolve(id) }

    private fun file(content: String): String = tmp.newFile().also { it.writeText(content) }.absolutePath

    // ---- file or stream ----

    @Test
    fun aDownloadedEpisodeWithAnExistingNonEmptyFilePlaysTheFile() {
        val path = file("audio")
        assertEquals(AudioSource.LocalFile(path), resolve(episode(DownloadState.DOWNLOADED, path))!!.source)
    }

    @Test
    fun aDownloadedEpisodeWithAMissingFileStreams() {
        val missing = File(tmp.root, "gone.mp3").absolutePath
        assertEquals(AudioSource.Stream(stream), resolve(episode(DownloadState.DOWNLOADED, missing))!!.source)
    }

    @Test
    fun aDownloadedEpisodeWithAnEmptyFileStreams() {
        assertEquals(AudioSource.Stream(stream), resolve(episode(DownloadState.DOWNLOADED, file("")))!!.source)
    }

    @Test
    fun aDownloadedEpisodeWithoutALocalPathStreams() {
        assertEquals(AudioSource.Stream(stream), resolve(episode(DownloadState.DOWNLOADED, null))!!.source)
    }

    @Test
    fun everyStateExceptDownloadedStreamsEvenWhenAFileExists() {
        val path = file("partial")
        for (state in listOf(DownloadState.NONE, DownloadState.QUEUED, DownloadState.DOWNLOADING, DownloadState.FAILED)) {
            assertEquals(state.name, AudioSource.Stream(stream), resolve(episode(state, path))!!.source)
        }
    }

    // ---- metadata ----

    @Test
    fun usesTheEpisodesArtworkElseTheFeedsElseNone() {
        assertEquals("https://a.example.com/e.png", resolve(episode(artwork = "https://a.example.com/e.png", feedArtwork = "https://a.example.com/f.png"))!!.artworkUrl)
        assertEquals("https://a.example.com/f.png", resolve(episode(artwork = null, feedArtwork = "https://a.example.com/f.png"))!!.artworkUrl)
        assertEquals("https://a.example.com/f.png", resolve(episode(artwork = "  ", feedArtwork = "https://a.example.com/f.png"))!!.artworkUrl)
        assertNull(resolve(episode(artwork = null, feedArtwork = ""))!!.artworkUrl)
        assertNull(resolve(episode(artwork = "", feedArtwork = " "))!!.artworkUrl)
    }

    @Test
    fun carriesTitleFeedTitleDurationAndTheIdAsMediaId() {
        val resolved = resolve(episode(durationSec = 90))!!
        assertEquals("The title", resolved.title)
        assertEquals("The feed", resolved.feedTitle)
        assertEquals(90_000L, resolved.durationMs)
        assertEquals("7", resolved.mediaId)
    }

    @Test
    fun hasNoDurationWhenTheFeedDoesNotGiveOne() {
        assertNull(resolve(episode(durationSec = null))!!.durationMs)
    }

    @Test
    fun anUnknownIdResolvesToNull() {
        assertNull(resolve(episode(), id = 8))
        assertNull(resolve(null))
    }

    @Test
    fun toStringNeverPrintsAnAddress() {
        val path = file("audio")
        val resolved = resolve(episode(DownloadState.DOWNLOADED, path, artwork = "https://a.example.com/e.png"))!!
        assertFalse(resolved.toString().contains("example.com"))
        assertFalse(resolved.toString().contains(path))
        assertFalse(episode().toString().contains("example.com"))
        assertTrue(resolved.toString().contains("mediaId=7"))
    }
}
