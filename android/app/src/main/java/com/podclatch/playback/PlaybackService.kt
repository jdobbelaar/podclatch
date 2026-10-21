package com.podclatch.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import com.podclatch.Config
import com.podclatch.MainActivity
import com.podclatch.R
import com.podclatch.data.repo.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Hosts the one ExoPlayer and its media session (shared decision 2): it plays in the background as a
 * foreground service with a media notification, and answers lock-screen, headset, and Bluetooth keys.
 * It reads Room and DataStore only through [EpisodeMediaResolver] and [SettingsRepository]; it never calls
 * the PodClatch API. The browse tree is not built here (that is the Android Auto story).
 */
@OptIn(markerClass = [UnstableApi::class])
@AndroidEntryPoint
class PlaybackService : MediaLibraryService() {
    @Inject lateinit var resolver: EpisodeMediaResolver
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var appClient: OkHttpClient

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: MediaLibrarySession? = null
    private var player: ExoPlayer? = null

    @Volatile private var skipBackSec = Config.DEFAULT_SKIP_BACK_SEC

    @Volatile private var skipForwardSec = Config.DEFAULT_SKIP_FORWARD_SEC

    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(Config.PLAYBACK_CHANNEL_ID)
                .setNotificationId(Config.PLAYBACK_NOTIFICATION_ID)
                .setChannelName(R.string.playback_channel_name)
                .build(),
        )
        val exo = buildPlayer()
        player = exo
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // An error leaves playWhenReady set; clear it so no Pause action stays on the notification.
                exo.playWhenReady = false
            }
        })
        val wrapped = SkipForwardingPlayer(exo, { skipBackSec * 1000L }, { skipForwardSec * 1000L })
        val newSession = MediaLibrarySession.Builder(this, wrapped, LibraryCallback())
            .setSessionActivity(sessionActivity())
            .setMediaButtonPreferences(skipButtons())
            .build()
        session = newSession
        scope.launch {
            settings.settings.map { it.skipBackSec to it.skipForwardSec }.distinctUntilChanged().collect { (back, forward) ->
                skipBackSec = back
                skipForwardSec = forward
                newSession.setMediaButtonPreferences(skipButtons())
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val exo = player
        val playing = exo != null && exo.playWhenReady &&
            (exo.playbackState == Player.STATE_READY || exo.playbackState == Player.STATE_BUFFERING)
        if (shouldStopOnTaskRemoved(playing)) pauseAllPlayersAndStopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        session?.release()
        session = null
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun buildPlayer(): ExoPlayer {
        // Derived from the app's client: no Authorization header is ever added to an audio request (the
        // token is set per request in OkHttpPodClatchApi only), redirects are followed, and the
        // whole-call timeout is off because a call lasts as long as the stream is read.
        val audioClient = appClient.newBuilder()
            .followRedirects(true)
            .followSslRedirects(true)
            .callTimeout(0, TimeUnit.SECONDS)
            .readTimeout(Config.PLAYBACK_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
        val upstream = OkHttpDataSource.Factory(audioClient).setUserAgent(Config.PLAYBACK_USER_AGENT)
        return ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, upstream)))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
    }

    private fun sessionActivity(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Skip back and skip forward on the two sides of the notification, labelled with the current seconds. */
    private fun skipButtons(): ImmutableList<CommandButton> = ImmutableList.of(
        CommandButton.Builder(skipIcon(skipBackSec, back = true))
            .setPlayerCommand(Player.COMMAND_SEEK_BACK)
            .setDisplayName(getString(R.string.notification_skip_back, skipBackSec))
            .setSlots(CommandButton.SLOT_BACK)
            .build(),
        CommandButton.Builder(skipIcon(skipForwardSec, back = false))
            .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
            .setDisplayName(getString(R.string.notification_skip_forward, skipForwardSec))
            .setSlots(CommandButton.SLOT_FORWARD)
            .build(),
    )

    private fun skipIcon(seconds: Int, back: Boolean): Int = if (back) {
        when (seconds) {
            5 -> CommandButton.ICON_SKIP_BACK_5
            10 -> CommandButton.ICON_SKIP_BACK_10
            15 -> CommandButton.ICON_SKIP_BACK_15
            30 -> CommandButton.ICON_SKIP_BACK_30
            else -> CommandButton.ICON_SKIP_BACK
        }
    } else {
        when (seconds) {
            5 -> CommandButton.ICON_SKIP_FORWARD_5
            10 -> CommandButton.ICON_SKIP_FORWARD_10
            15 -> CommandButton.ICON_SKIP_FORWARD_15
            30 -> CommandButton.ICON_SKIP_FORWARD_30
            else -> CommandButton.ICON_SKIP_FORWARD
        }
    }

    /**
     * Resolves the id-only items the controller sends. `onGetLibraryRoot` and `onGetChildren` are not
     * overridden: Media3's "not supported" answers stand until the browse tree exists.
     */
    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = scope.future {
            mediaItems.map { item -> resolveItem(item) ?: throw IllegalArgumentException("Unknown episode") }.toMutableList()
        }
    }

    private suspend fun resolveItem(item: MediaItem): MediaItem? {
        val id = item.mediaId.toLongOrNull() ?: return null
        val resolved = resolver.resolve(id) ?: return null
        return mediaItemOf(resolved)
    }
}

@OptIn(markerClass = [UnstableApi::class])
/** The player's item for a resolved Episode: playable, with title, Feed title, artwork, and duration. */
internal fun mediaItemOf(resolved: ResolvedEpisode): MediaItem {
    val uri: Uri = when (val source = resolved.source) {
        is AudioSource.LocalFile -> Uri.fromFile(File(source.path))
        is AudioSource.Stream -> source.url.toUri()
    }
    val metadata = MediaMetadata.Builder()
        .setTitle(resolved.title)
        .setArtist(resolved.feedTitle)
        .setArtworkUri(resolved.artworkUrl?.toUri())
        .setDurationMs(resolved.durationMs)
        .setIsPlayable(true)
        .setIsBrowsable(false)
        .build()
    return MediaItem.Builder().setMediaId(resolved.mediaId).setUri(uri).setMediaMetadata(metadata).build()
}
