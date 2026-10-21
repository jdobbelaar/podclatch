package com.podclatch.ui.subscribe.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.podclatch.R
import com.podclatch.data.repo.SearchResult
import com.podclatch.ui.subscribe.SubscribeStatus
import com.podclatch.ui.subscribe.SubscribeUiState

/** The search field with its clear button; the Search key sends the term at once. */
@Composable
fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        label = { Text(stringResource(R.string.search_field_label)) },
        singleLine = true,
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = onClear, modifier = Modifier.size(48.dp).testTag("search_clear")) {
                    Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.search_clear_description))
                }
            }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("search_field"),
    )
}

/** The results area below the search field: one state at a time. */
fun LazyListScope.searchContent(
    content: SearchContent,
    onOpen: (SearchResult) -> Unit,
    onRetry: () -> Unit,
) {
    when (content) {
        SearchContent.Idle -> item(key = "search_state") {
            StateText(stringResource(R.string.search_hint), "search_hint")
        }
        SearchContent.TooLong -> item(key = "search_state") {
            StateText(stringResource(R.string.search_too_long), "search_too_long")
        }
        SearchContent.Loading -> item(key = "search_state") {
            val description = stringResource(R.string.search_loading_description)
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    Modifier.size(32.dp).semantics { contentDescription = description }.testTag("search_loading"),
                )
            }
        }
        SearchContent.Empty -> item(key = "search_state") {
            StateText(stringResource(R.string.search_empty), "search_empty")
        }
        is SearchContent.Error -> item(key = "search_state") {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.search_error, content.reason),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .testTag("search_error"),
                )
                Button(
                    onClick = onRetry,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("search_retry"),
                ) {
                    Text(stringResource(R.string.search_retry))
                }
            }
        }
        is SearchContent.Results -> itemsIndexed(content.results) { index, result ->
            ResultRow(result, Modifier.testTag("search_result_$index"), onClick = { onOpen(result) })
        }
    }
}

@Composable
private fun StateText(text: String, tag: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }.testTag(tag),
    )
}

@Composable
private fun ResultRow(result: SearchResult, modifier: Modifier, onClick: () -> Unit) {
    val untitled = stringResource(R.string.feed_untitled)
    val description = resultRowText(result.title, result.author, untitled)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(result.artworkUrl, description = null, Modifier.size(56.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                result.title.ifBlank { untitled },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (result.author.isNotBlank()) {
                Text(
                    result.author,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Artwork from the shared Coil `ImageLoader` (no `Authorization` header); a plain block when missing or failed. */
@Composable
private fun Artwork(url: String, description: String?, modifier: Modifier) {
    val placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant)
    AsyncImage(
        model = url.ifBlank { null },
        contentDescription = description,
        placeholder = placeholder,
        error = placeholder,
        fallback = placeholder,
        contentScale = ContentScale.Crop,
        modifier = modifier.clip(RoundedCornerShape(8.dp)),
    )
}

/**
 * The preview of one result, laid over the results inside the Subscribe screen (not a dialog), so the
 * list below keeps its scroll position. It shows larger artwork, title, and author, and either the Subscribe
 * button or "Subscribed".
 */
@Composable
fun SearchPreview(
    result: SearchResult,
    subscribed: Boolean,
    subscribeState: SubscribeUiState,
    onSubscribe: () -> Unit,
    onClose: () -> Unit,
) {
    val untitled = stringResource(R.string.feed_untitled)
    val title = result.title.ifBlank { untitled }
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // The preview covers the list; swallow touches so they do not reach the rows beneath.
            .pointerInput(Unit) {}
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("search_preview"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = onClose, modifier = Modifier.size(48.dp).testTag("preview_close")) {
                Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.search_preview_close))
            }
        }
        Artwork(result.artworkUrl, stringResource(R.string.search_artwork_description, title), Modifier.size(200.dp))
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().testTag("preview_title"),
        )
        if (result.author.isNotBlank()) {
            Text(
                result.author,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().testTag("preview_author"),
            )
        }
        if (subscribed) {
            Text(
                stringResource(R.string.search_preview_subscribed),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.heightIn(min = 48.dp).testTag("preview_subscribed"),
            )
        } else {
            Button(
                onClick = onSubscribe,
                enabled = !subscribeState.busy,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("preview_subscribe"),
            ) {
                Text(stringResource(R.string.subscribe_button))
            }
        }
        SubscribeStatus(subscribeState)
    }
}
