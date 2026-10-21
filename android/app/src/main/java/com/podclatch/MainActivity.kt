package com.podclatch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.podclatch.ui.AppRoot
import com.podclatch.ui.ConnectionViewModel
import com.podclatch.ui.MainViewModel
import com.podclatch.ui.theme.PodClatchTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val main: MainViewModel by viewModels()
    private val connection: ConnectionViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PodClatchTheme {
                AppRoot(main, connection)
            }
        }
    }
}
