package com.podclatch.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.annotation.OptIn
import androidx.core.app.NotificationManagerCompat
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
import com.podclatch.data.db.CacheStore
import com.podclatch.data.db.EpisodeStatus
import com.podclatch.data.repo.EpisodeStateRepository
import com.podclatch.data.repo.QueueRepository
import com.podclatch.data.repo.SettingsRepository
import com.podclatch.di.ApplicationScope
import com.podclatch.domain.PlaybackSpeedUseCase
import com.podclatch.domain.SyncEngine
import com.podclatch.domain.SyncTrigger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
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
    @Inject lateinit var downloadStarter: PlayDownloadStarter
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var appClient: OkHttpClient
    @Inject lateinit var speed: PlaybackSpeedUseCase
    @Inject lateinit var store: CacheStore
    @Inject lateinit var stateRepository: EpisodeStateRepository
    @Inject lateinit var queueRepository: QueueRepository
    @Inject lateinit var syncEngine: SyncEngine
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: MediaLibrarySession? = null
    private var player: ExoPlayer? = null
    private var speedJob: Job? = null
    private var recorder: ProgressRecorder? = null
    private var watcher: ExternalChangeWatcher? = null

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
        ensureSession()
        scope.launch {
            settings.settings.map { it.skipBackSec to it.skipForwardSec }.distinctUntilChanged().collect { (back, forward) ->
                skipBackSec = back
                skipForwardSec = forward
                session?.setMediaButtonPreferences(skipButtons())
            }
        }
    }

    /** Builds the player and the session if there are none (again after the task-removal teardown). */
    private fun ensureSession(): MediaLibrarySession {
        session?.let { return it }
        val exo = buildPlayer()
        player = exo
        // The speed setting is the only input: any caller changes it, and the player follows (every Episode).
        speedJob = PlaybackSpeedApplier(speed.state, scope) { exo.setPlaybackSpeed(it) }.start()
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // An error leaves playWhenReady set; clear it so no Pause action stays on the notification.
                exo.playWhenReady = false
            }
        })
        attachProgress(exo)
        val wrapped = SkipForwardingPlayer(exo, { skipBackSec * 1000L }, { skipForwardSec * 1000L })
        return MediaLibrarySession.Builder(this, wrapped, LibraryCallback())
            .setSessionActivity(sessionActivity())
            .setMediaButtonPreferences(skipButtons())
            .build()
            .also { session = it }
    }

    /**
     * Releasing the session is what ends the notification and tells every bound controller (the app's own
     * included) that the session is gone, so each can drop its binding. `stopSelf()` alone does not
     * destroy a service that still has a client bound.
     */
    private fun releaseSession() {
        speedJob?.cancel()
        speedJob = null
        player?.let { exo ->
            // The last position is saved, then the recorder takes no more events (queued writes still run).
            if (recordsPlayerEvents(exo.currentMediaItem != null, exo.playerError != null)) recorder?.onStop(exo.currentPosition, durationOf(exo))
        }
        recorder?.close()
        recorder = null
        watcher?.onLoaded(null)
        watcher = null
        session?.release()
        session = null
        player?.release()
        player = null
    }

    /**
     * Attaches position saving, the Played rules, and the "state changed under the player" rule to the
     * player. They run on the application scope (they must outlive the UI); only the reads of the player
     * and the stop-and-clear run on the main thread.
     */
    private fun attachProgress(exo: ExoPlayer) {
        val progress = ProgressRecorder(
            scope = appScope,
            repository = stateRepository,
            source = { withContext(Dispatchers.Main.immediate) { snapshotOf(exo) } },
            fallbackDurationSec = { id -> store.episodeForPlayback(id)?.durationSec },
            sync = { syncEngine.sync(SyncTrigger.MANUAL) },
            onFinished = { id -> advanceFrom(exo, id, alwaysPlay = true) },
            onError = { Log.w(TAG, "Saving playback progress failed: ${it.javaClass.simpleName}") },
        )
        recorder = progress
        val changes = ExternalChangeWatcher(
            scope = appScope,
            observe = store::observeEpisode,
            isOwnStamp = progress::isOwnStamp,
            onExternalChange = { change ->
                progress.detach()
                // Marked Played from anywhere: on to the next Queue item. Anything else stops (spec 5.3).
                if (change.progress?.status == EpisodeStatus.PLAYED) {
                    advanceFrom(exo, change.episodeId, alwaysPlay = false)
                } else {
                    stopAndClear(exo)
                }
            },
        )
        watcher = changes
        exo.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val id = mediaItem?.mediaId?.toLongOrNull()
                progress.onLoaded(id)
                changes.onLoaded(id)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) = progress.onPlaying(isPlaying)

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // Pause is playWhenReady turning off (a buffering stall is not); an error never counts.
                if (!playWhenReady && recordsPlayerEvents(exo.currentMediaItem != null, exo.playerError != null)) {
                    progress.onPause(exo.currentPosition, durationOf(exo))
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (!recordsPlayerEvents(exo.currentMediaItem != null, exo.playerError != null)) return
                when (playbackState) {
                    Player.STATE_ENDED -> progress.onEnded(durationOf(exo), exo.currentPosition)
                    Player.STATE_IDLE -> progress.onStop(exo.currentPosition, durationOf(exo))
                }
            }
        })
    }

    /** Stops the player and empties it, so the notification, the mini player, and the foreground state go. */
    private fun stopAndClear(exo: ExoPlayer) {
        scope.launch(Dispatchers.Main.immediate) {
            if (player === exo) {
                exo.stop()
                exo.clearMediaItems()
            }
        }
    }

    private val advancer by lazy {
        QueueAdvancer(
            queue = queueRepository::queueKeys,
            keyOf = queueRepository::keyOf,
            resolve = resolver::resolve,
            startPositionMs = ::startPositionFor,
        )
    }

    /**
     * Auto-advance (spec 5.3 Queue): the Episode [finishedId] ended or was marked Played, so the next Queue
     * item is loaded at its saved position, or the player is stopped and cleared when none can be played.
     * It runs here, in the service, so it works with the screen off. [alwaysPlay] is true after a natural end;
     * after a Mark the next item plays only if playback was not paused. Nothing happens if the user already
     * loaded something else.
     */
    private fun advanceFrom(exo: ExoPlayer, finishedId: Long, alwaysPlay: Boolean) {
        scope.launch(Dispatchers.Main.immediate) {
            val stillThere = { player === exo && exo.currentMediaItem?.mediaId == finishedId.toString() }
            if (!stillThere()) return@launch
            val play = alwaysPlay || exo.playWhenReady
            val target = try {
                advancer.next(finishedId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Choosing the next Queue item failed: ${e.javaClass.simpleName}")
                null
            }
            if (!stillThere()) return@launch
            if (target == null) {
                exo.stop()
                exo.clearMediaItems()
                return@launch
            }
            val nextId = target.episode.mediaId.toLongOrNull()
            if (nextId != null) appScope.launch { downloadStarter.onPlayRequested(nextId) }
            exo.setMediaItem(mediaItemOf(target.episode), target.startPositionMs)
            exo.prepare()
            exo.playWhenReady = play
        }
    }

    private fun snapshotOf(exo: ExoPlayer): PlaybackSnapshot? = try {
        PlaybackSnapshot(exo.currentMediaItem?.mediaId?.toLongOrNull(), exo.currentPosition, durationOf(exo))
    } catch (_: IllegalStateException) {
        null
    }

    private fun durationOf(exo: ExoPlayer): Long? = exo.duration.takeIf { it != C.TIME_UNSET && it > 0 }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = ensureSession()

    override fun onTaskRemoved(rootIntent: Intent?) {
        val exo = player
        val playing = exo != null && exo.playWhenReady &&
            (exo.playbackState == Player.STATE_READY || exo.playbackState == Player.STATE_BUFFERING)
        if (shouldStopOnTaskRemoved(playing)) {
            pauseAllPlayersAndStopSelf()
            releaseSession()
            cancelNotification()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        releaseSession()
        super.onDestroy()
        // Last, after Media3 has torn down its own notification handling: a notification update that was
        // already queued when the session was released can otherwise re-post it (an orphan whose Play
        // button points at a service that no longer exists).
        cancelNotification()
    }

    private fun cancelNotification() {
        NotificationManagerCompat.from(this).cancel(Config.PLAYBACK_NOTIFICATION_ID)
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

        /** Starts a New Episode at its saved position (any other start stays at 0) and records the Episode left. */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val resolved = mediaItems.map { item -> resolveItem(item) ?: throw IllegalArgumentException("Unknown episode") }
            var index = startIndex
            var position = startPositionMs
            if (resolved.isNotEmpty() && startPositionMs == C.TIME_UNSET) {
                val first = if (startIndex == C.INDEX_UNSET) 0 else startIndex
                val start = startPositionFor(resolved[first].mediaId.toLong())
                if (start > 0) {
                    index = first
                    position = start
                }
            }
            noteSwitchFrom(resolved.firstOrNull()?.mediaId)
            MediaSession.MediaItemsWithStartPosition(resolved, index, position)
        }
    }

    /** The loaded Episode is about to be replaced by [newMediaId]: record it at the position it was left at. */
    private fun noteSwitchFrom(newMediaId: String?) {
        val exo = player ?: return
        val previous = exo.currentMediaItem?.mediaId ?: return
        if (previous == newMediaId) return
        val id = previous.toLongOrNull() ?: return
        recorder?.onSwitch(id, exo.currentPosition, durationOf(exo))
    }

    private suspend fun startPositionFor(episodeId: Long): Long {
        val progress = store.observeEpisode(episodeId).first() ?: return 0L
        val durationSec = store.episodeForPlayback(episodeId)?.durationSec
        return startPositionMs(progress.status, progress.positionSec, effectiveDurationMs(null, durationSec))
    }

    private suspend fun resolveItem(item: MediaItem): MediaItem? {
        val id = item.mediaId.toLongOrNull() ?: return null
        val resolved = resolver.resolve(id) ?: return null
        // After the resolve, so this play uses the state as it was; the request never makes the play wait.
        appScope.launch { downloadStarter.onPlayRequested(id) }
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

private const val TAG = "PlaybackService"
