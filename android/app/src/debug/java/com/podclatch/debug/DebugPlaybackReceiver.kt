package com.podclatch.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import com.podclatch.data.repo.SettingsRepository
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
        const val EXTRA_EPISODE_ID = "episode_id"
        const val EXTRA_KIND = "kind"
        const val EXTRA_BACK = "back"
        const val EXTRA_FORWARD = "forward"

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        @Volatile var held: AudioFocusRequest? = null
    }
}
