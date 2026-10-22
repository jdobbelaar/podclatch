package com.podclatch

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.podclatch.playback.NotificationPermissionPolicy
import com.podclatch.playback.PlaybackController
import com.podclatch.ui.AppRoot
import com.podclatch.ui.ConnectionViewModel
import com.podclatch.ui.MainViewModel
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

    @Inject lateinit var playback: PlaybackController
    @Inject lateinit var notificationPermission: NotificationPermissionPolicy

    // The answer needs no handling: playback runs either way, and the system remembers a denial.
    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PodClatchTheme {
                AppRoot(main, connection)
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

    private fun notificationsGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
