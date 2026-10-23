package com.podclatch

import com.podclatch.ui.nav.ACTION_OPEN_QUEUE
import com.podclatch.ui.nav.Feeds
import com.podclatch.ui.nav.FeedDetail
import com.podclatch.ui.nav.Player
import com.podclatch.ui.nav.Queue
import com.podclatch.ui.nav.Subscribe
import com.podclatch.ui.nav.isOpenQueueAction
import com.podclatch.ui.nav.needsQueueSwitch
import kotlinx.serialization.serializer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where a tap on the new-episodes notification has to go. */
class NotificationRoutingTest {
    private fun route(name: String) = name

    @Test
    fun theQueueTabStaysWhereItIs() {
        assertFalse(needsQueueSwitch(serializer<Queue>().descriptor.serialName))
    }

    @Test
    fun everyOtherDestinationSwitchesToTheQueue() {
        assertTrue(needsQueueSwitch(serializer<Feeds>().descriptor.serialName))
        assertTrue(needsQueueSwitch(serializer<FeedDetail>().descriptor.serialName))
        assertTrue(needsQueueSwitch(serializer<Subscribe>().descriptor.serialName))
        assertTrue(needsQueueSwitch(serializer<Player>().descriptor.serialName))
        assertTrue(needsQueueSwitch(null))
        assertTrue(needsQueueSwitch(route("something/else")))
    }

    @Test
    fun onlyTheOpenQueueActionIsRecognised() {
        assertTrue(isOpenQueueAction(ACTION_OPEN_QUEUE))
        assertFalse(isOpenQueueAction(null))
        assertFalse(isOpenQueueAction("android.intent.action.MAIN"))
    }
}
