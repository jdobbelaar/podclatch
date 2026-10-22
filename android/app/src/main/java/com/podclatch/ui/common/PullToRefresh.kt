package com.podclatch.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.podclatch.Config
import com.podclatch.R

/*
 * The big swipe (decision #20), shared by the Feeds tab and Feed Detail so both behave identically: a
 * pull counts only as overscroll beyond the top of the list, in raw pixels, and triggers only when it is
 * strictly more than half the window height.
 */

/** Pull distance above which a release starts a Refresh. */
fun pullThresholdPx(screenHeightPx: Int): Float = Config.PULL_REFRESH_THRESHOLD_FRACTION * screenHeightPx

/** True only when the pull is strictly beyond the threshold. */
fun shouldRefresh(pullPx: Float, screenHeightPx: Int): Boolean = pullPx > pullThresholdPx(screenHeightPx)

/** 0 at no pull, 1 at and beyond the threshold. */
fun pullProgress(pullPx: Float, screenHeightPx: Int): Float {
    val threshold = pullThresholdPx(screenHeightPx)
    if (threshold <= 0f) return 0f
    return (pullPx / threshold).coerceIn(0f, 1f)
}

/** The integer percent the indicator announces (0 to 100). */
fun pullPercent(pullPx: Float, screenHeightPx: Int): Int = (pullProgress(pullPx, screenHeightPx) * 100).toInt()

/**
 * Tracks the part of a downward drag that the list cannot scroll itself (the list is at the top) as the
 * pull distance, in raw pixels. Release hands the distance to [onRelease] and swallows the fling.
 */
private class PullConnection(
    private val enabled: () -> Boolean,
    private val onPull: (Float) -> Unit,
    private val onRelease: (Float) -> Unit,
) : NestedScrollConnection {
    private var pull = 0f

    private fun set(value: Float) {
        pull = value
        onPull(value)
    }

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (!enabled()) {
            if (pull != 0f) set(0f)
            return Offset.Zero
        }
        if (source == NestedScrollSource.UserInput && available.y < 0f && pull > 0f) {
            val used = minOf(pull, -available.y)
            set(pull - used)
            return Offset(0f, -used)
        }
        return Offset.Zero
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (enabled() && source == NestedScrollSource.UserInput && available.y > 0f) {
            set(pull + available.y)
            return Offset(0f, available.y)
        }
        return Offset.Zero
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (pull <= 0f) return Velocity.Zero
        val released = pull
        set(0f)
        onRelease(released)
        // The whole fling is swallowed: a downward fling after release scrolls and triggers nothing.
        return available
    }
}

/**
 * Wraps a scrolling [content] (a LazyColumn that sets `overscrollEffect = null`) with the big swipe and
 * its indicator (`refresh_indicator`). While [locked] the pull is inert. A release past the threshold
 * calls [onRefresh]; [refreshing] keeps the indicator on while the Refresh runs.
 */
@Composable
fun PullRefreshBox(
    locked: Boolean,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val screenHeight = LocalWindowInfo.current.containerSize.height
    var pull by remember { mutableFloatStateOf(0f) }
    val currentLocked by rememberUpdatedState(locked)
    val currentOnRefresh by rememberUpdatedState(onRefresh)
    val currentScreenHeight by rememberUpdatedState(screenHeight)
    val connection = remember {
        PullConnection(
            enabled = { !currentLocked },
            onPull = { pull = it },
            onRelease = { released -> if (shouldRefresh(released, currentScreenHeight)) currentOnRefresh() },
        )
    }
    Box(modifier.fillMaxSize().nestedScroll(connection)) {
        content()
        RefreshIndicator(
            pull = pull,
            refreshing = refreshing,
            screenHeight = screenHeight,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

@Composable
private fun RefreshIndicator(pull: Float, refreshing: Boolean, screenHeight: Int, modifier: Modifier) {
    val progress = pullProgress(pull, screenHeight)
    val description = when {
        refreshing -> stringResource(R.string.refresh_refreshing)
        shouldRefresh(pull, screenHeight) -> stringResource(R.string.refresh_release)
        else -> stringResource(R.string.refresh_pulling, pullPercent(pull, screenHeight))
    }
    AnimatedVisibility(visible = pull > 0f || refreshing, modifier = modifier.padding(top = 8.dp)) {
        Surface(
            shape = CircleShape,
            shadowElevation = 4.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.semantics { contentDescription = description }.testTag("refresh_indicator"),
        ) {
            Box(Modifier.padding(8.dp)) {
                if (refreshing) {
                    CircularProgressIndicator(Modifier.size(32.dp))
                } else {
                    CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(32.dp))
                }
            }
        }
    }
}
