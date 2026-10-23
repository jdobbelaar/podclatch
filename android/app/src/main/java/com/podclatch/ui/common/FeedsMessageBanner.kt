package com.podclatch.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.podclatch.R
import com.podclatch.ui.feeds.FeedsMessage

/**
 * The banner for a [FeedsMessage] with a Dismiss button, shared by the Feeds tab and the Queue tab so a
 * Refresh says the same thing on both. [tag] is the test tag of the banner; the button is `message_dismiss`.
 */
@Composable
fun FeedsMessageBanner(message: FeedsMessage, tag: String, onDismiss: () -> Unit) =
    MessageBanner(feedsMessageLines(message), tag, onDismiss)

/** An error banner of one or more lines with a Dismiss button (`message_dismiss`); [tag] tags the banner. */
@Composable
fun MessageBanner(lines: List<String>, tag: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth().testTag(tag),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }) {
                for (line in lines) Text(line, style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("message_dismiss")) {
                Text(stringResource(R.string.message_dismiss))
            }
        }
    }
}

@Composable
private fun feedsMessageLines(message: FeedsMessage): List<String> = when (message) {
    is FeedsMessage.MoveFailed -> listOf(stringResource(R.string.message_move_failed, message.reason))
    FeedsMessage.ListChanged -> listOf(stringResource(R.string.message_list_changed))
    is FeedsMessage.UnsubscribeFailed ->
        listOf(stringResource(R.string.message_unsubscribe_failed, displayTitle(message.title), message.reason))
    is FeedsMessage.UnsubscribedSyncFailed ->
        listOf(stringResource(R.string.message_unsubscribed_sync_failed, displayTitle(message.title), message.reason))
    is FeedsMessage.RefreshPartial -> buildList {
        add(stringResource(R.string.message_refresh_partial, message.failed, message.total))
        for (line in message.lines) {
            val name = line.title?.let { displayTitle(it) } ?: stringResource(R.string.message_refresh_a_feed)
            add(stringResource(R.string.message_refresh_line, name, line.error))
        }
        if (message.more > 0) add(stringResource(R.string.message_refresh_more, message.more))
    }
    is FeedsMessage.SubscribedSyncFailed -> listOf(stringResource(R.string.message_subscribed_sync_failed, message.reason))
    is FeedsMessage.RefreshFailed -> listOf(stringResource(R.string.message_refresh_failed, message.reason))
    is FeedsMessage.RefreshSyncFailed -> listOf(stringResource(R.string.message_refresh_sync_failed, message.reason))
    FeedsMessage.Unexpected -> listOf(stringResource(R.string.message_unexpected))
}

@Composable
private fun displayTitle(title: String): String = title.ifBlank { stringResource(R.string.feed_untitled) }
