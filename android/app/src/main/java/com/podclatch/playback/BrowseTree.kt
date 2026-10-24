package com.podclatch.playback

import com.podclatch.data.db.BrowseRow
import com.podclatch.data.db.FeedListItem
import com.podclatch.domain.QueueOrder

/** The fixed node ids of the Android Auto browse tree. Episode items use the Episode id as a decimal string. */
object BrowseIds {
    const val ROOT = "root"
    const val QUEUE = "queue"
    const val FEEDS = "feeds"
    private const val FEED_PREFIX = "feed:"

    fun feed(feedId: Long) = "$FEED_PREFIX$feedId"

    /** The Feed id of a `feed:<id>` node id, or null for any other string. */
    fun feedIdOf(nodeId: String): Long? =
        if (nodeId.startsWith(FEED_PREFIX) && FEED_ID.matches(nodeId.substring(FEED_PREFIX.length))) {
            nodeId.substring(FEED_PREFIX.length).toLongOrNull()
        } else {
            null
        }

    private val FEED_ID = Regex("[0-9]{1,18}")
}

enum class BrowseKind { FOLDER, FEED, EPISODE }

/**
 * One row of the browse tree, in plain Kotlin (the Media3 item is built from it in `BrowseMediaItems.kt`).
 * [toString] leaves out the titles and the artwork address.
 */
data class BrowseItem(
    val mediaId: String,
    val kind: BrowseKind,
    val title: String,
    /** A Feed's author, or null. */
    val subtitle: String? = null,
    /** An Episode's Feed title, or null. */
    val artist: String? = null,
    val artworkUrl: String? = null,
    val durationMs: Long? = null,
) {
    val browsable: Boolean get() = kind != BrowseKind.EPISODE
    val playable: Boolean get() = kind == BrowseKind.EPISODE

    override fun toString() = "BrowseItem(mediaId=$mediaId, kind=$kind)"
}

/**
 * The Android Auto browse tree (US-16, decision 1): `root` with the two tabs Queue and Feeds, the Queue's
 * Episodes in Queue order, the Feeds in Feed Priority order, and a Feed's New Episodes in its Listen Order
 * (capped). Pure Kotlin on the Room rows given as parameters; it never touches the network. Ordering is
 * `QueueOrder` from `domain/QueueRules.kt`.
 */
class BrowseTree(
    private val queue: suspend () -> List<BrowseRow>,
    private val feeds: suspend () -> List<FeedListItem>,
    private val newEpisodesOfFeed: suspend (Long) -> List<BrowseRow>,
    private val rootTitle: String,
    private val queueTitle: String,
    private val feedsTitle: String,
    private val feedEpisodeCap: Int,
) {
    fun root() = BrowseItem(BrowseIds.ROOT, BrowseKind.FOLDER, rootTitle)

    /** The node or Episode with this id, or null when the tree has none. */
    suspend fun item(id: String): BrowseItem? {
        when (id) {
            BrowseIds.ROOT -> return root()
            BrowseIds.QUEUE -> return queueNode()
            BrowseIds.FEEDS -> return feedsNode()
        }
        BrowseIds.feedIdOf(id)?.let { feedId -> return feeds().firstOrNull { it.id == feedId }?.let(::feedItem) }
        val episodeId = id.toLongOrNull() ?: return null
        return queue().firstOrNull { it.episodeId == episodeId }?.let(::episodeItem)
    }

    /**
     * The children of [parentId], [pageSize] per page (page 0 is the first), or null for a parent id the
     * tree never produces. A Feed that does not exist has no children.
     */
    suspend fun children(parentId: String, page: Int, pageSize: Int): List<BrowseItem>? {
        val all = when {
            parentId == BrowseIds.ROOT -> listOf(queueNode(), feedsNode())
            parentId == BrowseIds.QUEUE -> queue().sortedWith { a, b -> QueueOrder.compare(a.key, b.key) }.map(::episodeItem)
            parentId == BrowseIds.FEEDS -> feeds().map(::feedItem)
            else -> {
                val feedId = BrowseIds.feedIdOf(parentId) ?: return null
                newEpisodesOfFeed(feedId)
                    .sortedWith { a, b -> QueueOrder.compare(a.key, b.key) }
                    .take(feedEpisodeCap)
                    .map(::episodeItem)
            }
        }
        return pageOf(all, page, pageSize)
    }

    private fun queueNode() = BrowseItem(BrowseIds.QUEUE, BrowseKind.FOLDER, queueTitle)

    private fun feedsNode() = BrowseItem(BrowseIds.FEEDS, BrowseKind.FOLDER, feedsTitle)

    private fun feedItem(feed: FeedListItem) = BrowseItem(
        mediaId = BrowseIds.feed(feed.id),
        kind = BrowseKind.FEED,
        title = feed.title,
        subtitle = feed.author.takeIf { it.isNotBlank() },
        artworkUrl = feed.artworkUrl.takeIf { it.isNotBlank() },
    )

    private fun episodeItem(row: BrowseRow) = BrowseItem(
        mediaId = row.episodeId.toString(),
        kind = BrowseKind.EPISODE,
        title = row.title,
        artist = row.feedTitle,
        artworkUrl = row.artworkUrl?.takeIf { it.isNotBlank() } ?: row.feedArtworkUrl.takeIf { it.isNotBlank() },
        durationMs = row.durationSec?.let { it * 1000L },
    )
}

/** Page [page] (from 0) of [pageSize] items; a page size of 0 or less, or a negative page, gives an empty list. */
internal fun <T> pageOf(items: List<T>, page: Int, pageSize: Int): List<T> {
    if (page < 0 || pageSize <= 0) return emptyList()
    val from = page.toLong() * pageSize
    if (from >= items.size) return emptyList()
    val to = minOf(items.size.toLong(), from + pageSize)
    return items.subList(from.toInt(), to.toInt())
}
