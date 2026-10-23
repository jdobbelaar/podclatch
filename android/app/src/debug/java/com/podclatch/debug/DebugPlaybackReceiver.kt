package com.podclatch.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import com.podclatch.data.repo.SettingsRepository
import com.podclatch.download.Downloads
import com.podclatch.playback.PlaybackController
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DebugEntryPoint {
    fun playback(): PlaybackController
    fun settings(): SettingsRepository
    fun downloads(): Downloads

    /** For the instrumented tests that run the real service against the real store. */
    fun store(): com.podclatch.data.db.CacheStore
}

/**
 * Debug builds only (it lives in `src/debug`, so a release build has neither the class nor the manifest
 * entry). It lets QA start playback, simulate another app taking audio focus, and set the skip intervals
 * without a screen. Every action logs one line under [TAG] with the result only, never an address.
 *
 * - `com.podclatch.debug.PLAY --el episode_id <id>`
 * - `com.podclatch.debug.FOCUS --es kind transient|permanent|duck|abandon`
 * - `com.podclatch.debug.SET_SKIP --ei back <s> --ei forward <s>`
 * - `com.podclatch.debug.DOWNLOAD --el episode_id <id> [--ez manual true|false]`
 * - `com.podclatch.debug.CANCEL_DOWNLOAD --el episode_id <id>`
 * - `com.podclatch.debug.SET_WIFI_ONLY --ez enabled true|false`
 * - `com.podclatch.debug.SET_DOWNLOAD_AHEAD --ei value <n>`
 * - `com.podclatch.debug.SET_NOTIFY_NEW --ez enabled true|false`
 */
class DebugPlaybackReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val entry = EntryPointAccessors.fromApplication(app, DebugEntryPoint::class.java)
        val pending = goAsync()
        scope.launch {
            try {
                when (intent.action) {
                    ACTION_PLAY -> {
                        val id = intent.getLongExtra(EXTRA_EPISODE_ID, -1L)
                        Log.i(TAG, "PLAY result=${entry.playback().play(id)}")
                    }
                    ACTION_FOCUS -> Log.i(TAG, "FOCUS ${focus(app, intent.getStringExtra(EXTRA_KIND))}")
                    ACTION_SET_SKIP -> {
                        val back = intent.getIntExtra(EXTRA_BACK, -1)
                        val forward = intent.getIntExtra(EXTRA_FORWARD, -1)
                        if (back > 0 && forward > 0) {
                            entry.settings().setSkipIntervals(back, forward)
                            Log.i(TAG, "SET_SKIP result=ok back=$back forward=$forward")
                        } else {
                            Log.i(TAG, "SET_SKIP result=invalid")
                        }
                    }
                    ACTION_DOWNLOAD -> {
                        val id = intent.getLongExtra(EXTRA_EPISODE_ID, -1L)
                        if (id < 0) {
                            Log.i(TAG, "DOWNLOAD result=invalid")
                        } else {
                            val manual = intent.getBooleanExtra(EXTRA_MANUAL, false)
                            if (manual) entry.downloads().requestManual(id) else entry.downloads().requestAutomatic(id)
                            Log.i(TAG, "DOWNLOAD result=requested id=$id manual=$manual")
                        }
                    }
                    ACTION_CANCEL_DOWNLOAD -> {
                        val id = intent.getLongExtra(EXTRA_EPISODE_ID, -1L)
                        if (id < 0) {
                            Log.i(TAG, "CANCEL_DOWNLOAD result=invalid")
                        } else {
                            entry.downloads().remove(id)
                            Log.i(TAG, "CANCEL_DOWNLOAD result=removed id=$id")
                        }
                    }
                    ACTION_SET_WIFI_ONLY -> {
                        if (intent.hasExtra(EXTRA_ENABLED)) {
                            val enabled = intent.getBooleanExtra(EXTRA_ENABLED, true)
                            entry.settings().setDownloadWifiOnly(enabled)
                            Log.i(TAG, "SET_WIFI_ONLY result=ok enabled=$enabled")
                        } else {
                            Log.i(TAG, "SET_WIFI_ONLY result=invalid")
                        }
                    }
                    ACTION_SET_DOWNLOAD_AHEAD -> {
                        val value = intent.getIntExtra(EXTRA_VALUE, -1)
                        if (value > 0) {
                            entry.settings().setDownloadAheadPerFeed(value)
                            Log.i(TAG, "SET_DOWNLOAD_AHEAD result=ok value=$value")
                        } else {
                            Log.i(TAG, "SET_DOWNLOAD_AHEAD result=invalid")
                        }
                    }
                    ACTION_SET_NOTIFY_NEW -> {
                        if (intent.hasExtra(EXTRA_ENABLED)) {
                            val enabled = intent.getBooleanExtra(EXTRA_ENABLED, false)
                            entry.settings().setNotifyNewEpisodes(enabled)
                            Log.i(TAG, "SET_NOTIFY_NEW result=ok enabled=$enabled")
                        } else {
                            Log.i(TAG, "SET_NOTIFY_NEW result=invalid")
                        }
                    }
                    else -> Log.i(TAG, "unknown action")
                }
            } catch (e: Exception) {
                Log.i(TAG, "${intent.action?.substringAfterLast('.')} failed: ${e.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }

    /** A second `AudioManager` client with its own listener, standing in for a call or another app. */
    private fun focus(context: Context, kind: String?): String {
        val audio = context.getSystemService(AudioManager::class.java)
        if (kind == "abandon") {
            val request = held ?: return "kind=abandon result=nothing_held"
            val result = audio.abandonAudioFocusRequest(request)
            held = null
            return "kind=abandon result=${if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) "granted" else "failed"}"
        }
        val gain = when (kind) {
            "transient" -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            "permanent" -> AudioManager.AUDIOFOCUS_GAIN
            "duck" -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            else -> return "kind=unknown result=ignored"
        }
        held?.let { audio.abandonAudioFocusRequest(it) }
        val request = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setOnAudioFocusChangeListener { change -> Log.i(TAG, "FOCUS helper_listener change=$change") }
            .build()
        val result = audio.requestAudioFocus(request)
        held = request
        val name = when (result) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> "granted"
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> "delayed"
            else -> "failed"
        }
        return "kind=$kind result=$name"
    }

    private companion object {
        const val TAG = "PodClatchDebug"
        const val ACTION_PLAY = "com.podclatch.debug.PLAY"
        const val ACTION_FOCUS = "com.podclatch.debug.FOCUS"
        const val ACTION_SET_SKIP = "com.podclatch.debug.SET_SKIP"
        const val ACTION_DOWNLOAD = "com.podclatch.debug.DOWNLOAD"
        const val ACTION_CANCEL_DOWNLOAD = "com.podclatch.debug.CANCEL_DOWNLOAD"
        const val ACTION_SET_WIFI_ONLY = "com.podclatch.debug.SET_WIFI_ONLY"
        const val ACTION_SET_DOWNLOAD_AHEAD = "com.podclatch.debug.SET_DOWNLOAD_AHEAD"
        const val ACTION_SET_NOTIFY_NEW = "com.podclatch.debug.SET_NOTIFY_NEW"
        const val EXTRA_VALUE = "value"
        const val EXTRA_EPISODE_ID = "episode_id"
        const val EXTRA_KIND = "kind"
        const val EXTRA_MANUAL = "manual"
        const val EXTRA_ENABLED = "enabled"
        const val EXTRA_BACK = "back"
        const val EXTRA_FORWARD = "forward"

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        @Volatile var held: AudioFocusRequest? = null
    }
}
