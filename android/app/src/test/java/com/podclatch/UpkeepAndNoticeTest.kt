package com.podclatch

import com.podclatch.data.repo.Settings
import com.podclatch.domain.FeedNewCount
import com.podclatch.domain.InsertedEpisode
import com.podclatch.domain.NEW_EPISODES_TITLE
import com.podclatch.domain.NewEpisodeNotice
import com.podclatch.domain.NewEpisodeNotificationStep
import com.podclatch.domain.OrderedLocalUpkeep
import com.podclatch.domain.PullSummary
import com.podclatch.domain.SyncTrigger
import com.podclatch.domain.UpkeepStep
import com.podclatch.domain.decideNewEpisodeNotice
import com.podclatch.domain.newEpisodeText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OrderedLocalUpkeepTest {
    private val summary = PullSummary(SyncTrigger.MANUAL, firstPull = false)

    @Test
    fun stepsRunInOrderAndEachGetsThePullSummary() = runBlocking {
        val calls = mutableListOf<String>()
        val seen = mutableListOf<PullSummary>()
        val upkeep = OrderedLocalUpkeep(
            listOf(
                "first" to UpkeepStep { calls += "first"; seen += it },
                "second" to UpkeepStep { calls += "second"; seen += it },
            ),
        )
        upkeep.run(summary)
        assertEquals(listOf("first", "second"), calls)
        assertEquals(listOf(summary, summary), seen)
    }

    @Test
    fun aStepThatThrowsIsReportedByNameAndClassOnlyAndDoesNotStopTheNext() = runBlocking {
        val calls = mutableListOf<String>()
        val reports = mutableListOf<Pair<String, String>>()
        val upkeep = OrderedLocalUpkeep(
            listOf(
                "bad" to UpkeepStep { throw IllegalStateException("could not open http://audio.example.com/x.mp3?key=secret") },
                "good" to UpkeepStep { calls += "good" },
            ),
            onStepFailed = { step, errorClass -> reports += step to errorClass },
        )
        upkeep.run(summary)
        assertEquals(listOf("good"), calls)
        assertEquals(listOf("bad" to "IllegalStateException"), reports)
        assertFalse(reports.toString().contains("audio.example.com"))
    }

    @Test
    fun aReporterThatThrowsStillDoesNotBreakTheRun() = runBlocking {
        val calls = mutableListOf<String>()
        val upkeep = OrderedLocalUpkeep(
            listOf("bad" to UpkeepStep { error("boom") }, "good" to UpkeepStep { calls += "good" }),
            onStepFailed = { _, _ -> error("reporter broke") },
        )
        upkeep.run(summary)
        assertEquals(listOf("good"), calls)
    }

    @Test
    fun cancellationIsNotSwallowed() {
        val upkeep = OrderedLocalUpkeep(listOf("c" to UpkeepStep { throw CancellationException("stop") }))
        try {
            runBlocking { upkeep.run(summary) }
            fail("expected cancellation")
        } catch (_: CancellationException) {
            // expected
        }
    }
}

class NewEpisodeNoticeTest {
    private val one = listOf(FeedNewCount("Feed A", 1))

    private fun decide(
        enabled: Boolean = true,
        trigger: SyncTrigger = SyncTrigger.PERIODIC,
        firstPull: Boolean = false,
        counts: List<FeedNewCount> = one,
    ) = decideNewEpisodeNotice(enabled, trigger, firstPull, counts)

    @Test
    fun notifiesForOneEpisodeOnAPeriodicSync() {
        assertEquals(NewEpisodeNotice(NEW_EPISODES_TITLE, "1 new episode from Feed A"), decide())
        assertEquals("New episodes", NEW_EPISODES_TITLE)
    }

    @Test
    fun countsEpisodesAcrossFeeds() {
        val notice = decide(counts = listOf(FeedNewCount("Feed A", 2), FeedNewCount("Feed B", 3)))
        assertEquals("5 new episodes from Feed A and Feed B", notice?.text)
    }

    @Test
    fun manyEpisodesInOneFeed() {
        assertEquals("12 new episodes from Feed A", decide(counts = listOf(FeedNewCount("Feed A", 12)))?.text)
    }

    @Test
    fun zeroEpisodesNotifiesNothing() {
        assertNull(decide(counts = emptyList()))
        assertNull(decide(counts = listOf(FeedNewCount("Feed A", 0))))
    }

    @Test
    fun settingOffNotifiesNothing() {
        assertNull(decide(enabled = false))
    }

    @Test
    fun onlyThePeriodicTriggerNotifies() {
        assertNull(decide(trigger = SyncTrigger.MANUAL))
        assertNull(decide(trigger = SyncTrigger.LAUNCH_OR_FOREGROUND))
        assertNotNull(decide(trigger = SyncTrigger.PERIODIC))
    }

    @Test
    fun theFirstPullNotifiesNothing() {
        assertNull(decide(firstPull = true))
    }

    @Test
    fun textNamesAtMostTwoFeedsWithAnOxfordCommaForTheRest() {
        assertEquals("1 new episode from A", newEpisodeText(1, listOf("A")))
        assertEquals("2 new episodes from A and B", newEpisodeText(2, listOf("A", "B")))
        assertEquals("3 new episodes from A, B, and 1 more", newEpisodeText(3, listOf("A", "B", "C")))
        assertEquals("9 new episodes from A, B, and 3 more", newEpisodeText(9, listOf("A", "B", "C", "D", "E")))
    }

    @Test
    fun textHoldsNoEpisodeTitleOrAddress() {
        val text = newEpisodeText(4, listOf("A", "B", "C"))
        assertFalse(text.contains("://"))
        assertFalse(text.contains("Episode"))
    }
}

class NewEpisodeNotificationStepTest {
    private val store = FakeCacheStore()
    private val settings = FakeSettings(Settings(serverUrl = TEST_URL, authToken = TEST_TOKEN, notifyNewEpisodes = true))
    private val posted = mutableListOf<NewEpisodeNotice>()
    private val step = NewEpisodeNotificationStep(store, settings) { posted += it }

    init {
        store.feeds[1] = cachedFeed(1).copy(title = "Feed A")
        store.feeds[2] = cachedFeed(2).copy(title = "Feed B")
        store.feeds[3] = cachedFeed(3).copy(title = "Feed C")
    }

    private fun pull(
        trigger: SyncTrigger = SyncTrigger.PERIODIC,
        firstPull: Boolean = false,
        vararg inserted: InsertedEpisode,
    ) = PullSummary(trigger, firstPull, inserted.toList())

    private fun run(summary: PullSummary) = runBlocking { step.run(summary) }

    @Test
    fun postsOncePerSyncWithTheFeedTitles() {
        run(pull(SyncTrigger.PERIODIC, false, InsertedEpisode(10, 1, true), InsertedEpisode(11, 1, true), InsertedEpisode(20, 2, true)))
        assertEquals(listOf(NewEpisodeNotice("New episodes", "3 new episodes from Feed A and Feed B")), posted)
    }

    @Test
    fun aFeedThatWasNewInThisPullIsNotCounted() {
        val inserted = (1L..10).map { InsertedEpisode(it, 3, feedWasCached = false) }
        run(PullSummary(SyncTrigger.PERIODIC, false, inserted))
        assertTrue(posted.isEmpty())

        run(pull(SyncTrigger.PERIODIC, false, InsertedEpisode(20, 1, true), *inserted.toTypedArray()))
        assertEquals("1 new episode from Feed A", posted.single().text)
    }

    @Test
    fun nothingIsPostedForOtherTriggersTheFirstPullOrTheSettingOff() {
        val inserted = InsertedEpisode(10, 1, true)
        run(pull(SyncTrigger.MANUAL, false, inserted))
        run(pull(SyncTrigger.LAUNCH_OR_FOREGROUND, false, inserted))
        run(pull(SyncTrigger.PERIODIC, true, inserted))
        run(pull(SyncTrigger.PERIODIC, false))
        settings.flow.value = settings.flow.value.copy(notifyNewEpisodes = false)
        run(pull(SyncTrigger.PERIODIC, false, inserted))
        assertTrue(posted.isEmpty())
    }
}
