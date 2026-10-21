package com.podclatch.ui.subscribe.search

import com.podclatch.domain.FeedUrlNormalizer

/** The one accessibility text of a result row: `<title>, <author>`, or just `<title>` when the author is blank. */
fun resultRowText(title: String, author: String, untitled: String): String {
    val shownTitle = title.ifBlank { untitled }
    return if (author.isBlank()) shownTitle else "$shownTitle, $author"
}

/** True when [feedUrl] is, after normalisation, one of the cached Feeds' [subscribedAddresses] (already normalised). */
fun isSubscribed(feedUrl: String, subscribedAddresses: Set<String>): Boolean =
    FeedUrlNormalizer.normalize(feedUrl) in subscribedAddresses
