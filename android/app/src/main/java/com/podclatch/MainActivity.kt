package com.podclatch

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.podclatch.playback.NotificationPermissionPolicy
import com.podclatch.playback.PlaybackController
import com.podclatch.ui.AppRoot
import com.podclatch.ui.ConnectionViewModel
import com.podclatch.ui.MainViewModel
import com.podclatch.ui.nav.isOpenQueueAction
import com.podclatch.ui.theme.PodClatchTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val main: MainViewModel by viewModels()
    private val connection: ConnectionViewModel by viewModels()

    private val openQueueRequested = mutableStateOf(false)

    @Inject lateinit var playback: PlaybackController
    @Inject lateinit var notificationPermission: NotificationPermissionPolicy

    // The answer needs no handling: playback runs either way, and the system remembers a denial.
    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only the first creation: a rotation re-delivers the same intent and must not jump to the Queue.
        if (savedInstanceState == null) openQueueRequested.value = isOpenQueueAction(intent?.action)
        setContent {
            PodClatchTheme {
                val requested by openQueueRequested
                AppRoot(main, connection, openQueueRequested = requested, onOpenQueueHandled = { openQueueRequested.value = false })
            }
        }
        // The first time an Episode is loaded, ask for the notification permission (once per process).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                playback.state.map { it.episodeId != null }.distinctUntilChanged().collect { loaded ->
                    if (loaded && notificationPermission.shouldAsk(Build.VERSION.SDK_INT, notificationsGranted())) {
                        requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }
        }
    }

    // The activity is single top, so a notification tap while it is open arrives here.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (isOpenQueueAction(intent.action)) openQueueRequested.value = true
    }

    private fun notificationsGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
