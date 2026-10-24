package com.podclatch.ui.nav

import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

/** Type-safe routes of the single NavHost. Later stories add destinations (the full player, Settings). */
@Serializable
data object Queue

@Serializable
data object Feeds

@Serializable
data object Subscribe

@Serializable
data class FeedDetail(val feedId: Long)

/** Settings: a full-screen destination above the tabs, with no bottom bar (the mini player stays). */
@Serializable
data object Settings

/** The full player: a full-screen destination above the tabs, with neither bottom bar nor mini player. */
@Serializable
data object Player

/** Whether a route string is the full player. */
fun isPlayerRoute(route: String?): Boolean = route != null && route == serializer<Player>().descriptor.serialName

/** The action of the intent a tap on the new-episodes notification sends; it asks the app to show the Queue tab. */
const val ACTION_OPEN_QUEUE = "com.podclatch.action.OPEN_QUEUE"

/** Whether [action] is the notification tap's. */
fun isOpenQueueAction(action: String?): Boolean = action == ACTION_OPEN_QUEUE

/**
 * Whether a tap on the notification has to navigate: from any destination except the Queue tab itself
 * (the Feeds tab, Feed Detail, Subscribe, the full player, or no destination yet).
 */
fun needsQueueSwitch(route: String?): Boolean = tabForRoute(route) != Tab.QUEUE

/** The two top-level destinations that show the bottom bar. */
enum class Tab { QUEUE, FEEDS }

/**
 * Which tab a destination's route string belongs to; null for the nested destinations (so the bottom
 * bar is hidden there) and for no route.
 */
fun tabForRoute(route: String?): Tab? = when {
    route == null -> null
    route == serializer<Queue>().descriptor.serialName -> Tab.QUEUE
    route == serializer<Feeds>().descriptor.serialName -> Tab.FEEDS
    else -> null
}
