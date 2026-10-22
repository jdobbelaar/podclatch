package com.podclatch.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.podclatch.Config
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [PlaybackController] over a `MediaController` connected to [PlaybackService]. The connection is made
 * on the first [play] (so an unknown id never starts the service) and made again if the service went away.
 * Every Media3 call runs on the main thread.
 */
@Singleton
class MediaControllerPlaybackController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val resolver: EpisodeMediaResolver,
) : PlaybackController {
    private val _state = MutableStateFlow(PlaybackUiState())
    override val state: StateFlow<PlaybackUiState> = _state

    private val connectLock = Mutex()
    private var controller: MediaController? = null

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish(player)
    }

    override suspend fun play(episodeId: Long): PlayResult {
        // Looked up first: an unknown id must not start the service, so no session is created.
        if (resolver.resolve(episodeId) == null) return PlayResult.NotFound
        return withContext(Dispatchers.Main.immediate) {
            val c = connected() ?: return@withContext PlayResult.Unavailable
            val mediaId = episodeId.toString()
            val loaded = c.currentMediaItem?.mediaId == mediaId &&
                c.playbackState != Player.STATE_IDLE && c.playbackState != Player.STATE_ENDED
            if (loaded) {
                c.play()
                PlayResult.Resumed
            } else {
                c.setMediaItem(MediaItem.Builder().setMediaId(mediaId).build())
                c.prepare()
                c.play()
                PlayResult.Started
            }
        }
    }

    override fun pause() {
        onMain { controller?.pause() }
    }

    override fun resume() {
        onMain { controller?.play() }
    }

    private fun onMain(block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) block() else
            android.os.Handler(android.os.Looper.getMainLooper()).post(block)
    }

    private suspend fun connected(): MediaController? = connectLock.withLock {
        controller?.takeIf { it.isConnected } ?: connect()
    }

    private suspend fun connect(): MediaController? {
        controller?.release()
        controller = null
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token)
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    // The session ended (task removed while paused, or the service died). Release the
                    // binding so the service can be destroyed; the next play connects again.
                    if (this@MediaControllerPlaybackController.controller === controller) {
                        this@MediaControllerPlaybackController.controller = null
                        _state.value = PlaybackUiState()
                    }
                    controller.removeListener(playerListener)
                    controller.release()
                }
            })
            .buildAsync()
        val connected = withTimeoutOrNull(Config.PLAYBACK_CONNECT_TIMEOUT_MS) { future.await() }
        if (connected == null) {
            MediaController.releaseFuture(future)
            return null
        }
        connected.addListener(playerListener)
        controller = connected
        publish(connected)
        return connected
    }

    private fun publish(player: Player) {
        val item = player.currentMediaItem
        val meta = item?.mediaMetadata
        _state.value = mapPlaybackState(
            PlayerSnapshot(
                mediaId = item?.mediaId?.takeIf { it.isNotEmpty() },
                title = meta?.title?.toString(),
                artist = meta?.artist?.toString(),
                artworkUrl = meta?.artworkUri?.toString(),
                metadataDurationMs = meta?.durationMs,
                playerDurationMs = player.duration,
                isPlaying = player.isPlaying,
                playbackState = player.playbackState,
                errorCode = player.playerError?.errorCode,
            ),
        )
    }
}
