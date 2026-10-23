package com.podclatch.domain

import com.podclatch.data.db.CacheStore
import com.podclatch.data.repo.SettingsRepository

/** The words of the "New episodes" notification. */
data class NewEpisodeNotice(val title: String, val text: String)

/** How many New Episodes one Feed gained in a pull. */
data class FeedNewCount(val feedTitle: String, val count: Int)

/** Posts the notification; it must do nothing (and not crash) when the permission is missing. */
fun interface NewEpisodeNotifier {
    fun post(notice: NewEpisodeNotice)
}

const val NEW_EPISODES_TITLE = "New episodes"

/** Feeds named in the text; any others are counted as "and k more". */
private const val NAMED_FEEDS = 2

/**
 * Whether a Sync brought something worth a notification, and the words. [newInCachedFeeds] counts only the
 * New Episodes that went into Feeds already cached before the pull. It notifies only when [enabled], the
 * [trigger] is PERIODIC (the others have the user in the app), the count is at least 1, and it was not the
 * [firstPull] on this device. The text never holds an Episode title or an address.
 */
fun decideNewEpisodeNotice(
    enabled: Boolean,
    trigger: SyncTrigger,
    firstPull: Boolean,
    newInCachedFeeds: List<FeedNewCount>,
): NewEpisodeNotice? {
    val total = newInCachedFeeds.sumOf { it.count }
    if (!enabled || trigger != SyncTrigger.PERIODIC || firstPull || total < 1) return null
    return NewEpisodeNotice(NEW_EPISODES_TITLE, newEpisodeText(total, newInCachedFeeds.map { it.feedTitle }))
}

/** "1 new episode from A", "3 new episodes from A and B", "5 new episodes from A, B, and 2 more". */
fun newEpisodeText(total: Int, feedTitles: List<String>): String {
    val count = if (total == 1) "1 new episode" else "$total new episodes"
    val named = feedTitles.take(NAMED_FEEDS)
    val more = feedTitles.size - named.size
    val from = when {
        named.isEmpty() -> ""
        more > 0 -> " from ${named.joinToString(", ")}, and $more more"
        named.size == 2 -> " from ${named[0]} and ${named[1]}"
        else -> " from ${named[0]}"
    }
    return count + from
}

/** The upkeep step that turns the pull's inserted Episodes into a notification when [decideNewEpisodeNotice] says so. */
class NewEpisodeNotificationStep(
    private val store: CacheStore,
    private val settings: SettingsRepository,
    private val notifier: NewEpisodeNotifier,
) : UpkeepStep {
    override suspend fun run(pull: PullSummary) {
        val enabled = settings.current().notifyNewEpisodes
        val cachedInserts = pull.insertedNew.filter { it.feedWasCached }
        // Cheap exits first, so the common Sync does no query.
        if (!enabled || pull.trigger != SyncTrigger.PERIODIC || pull.firstPull || cachedInserts.isEmpty()) return

        val counts = cachedInserts.groupingBy { it.feedId }.eachCount()
        val titles = store.feedTitles(counts.keys).associate { it.id to it.title }
        val perFeed = counts.entries
            .sortedWith(compareByDescending<Map.Entry<Long, Int>> { it.value }.thenBy { it.key })
            .mapNotNull { (feedId, n) -> titles[feedId]?.let { FeedNewCount(it, n) } }
        decideNewEpisodeNotice(enabled, pull.trigger, pull.firstPull, perFeed)?.let(notifier::post)
    }
}
