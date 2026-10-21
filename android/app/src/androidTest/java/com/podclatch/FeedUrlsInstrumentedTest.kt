package com.podclatch

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.podclatch.data.db.Feed
import com.podclatch.data.db.ListenOrder
import com.podclatch.data.db.PodClatchDatabase
import com.podclatch.data.db.RoomCacheStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** The `feedUrls` query (the Subscribe view's "Subscribed" marker) against real SQLite. */
class FeedUrlsInstrumentedTest {
    private lateinit var db: PodClatchDatabase
    private lateinit var store: RoomCacheStore

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, PodClatchDatabase::class.java).build()
        store = RoomCacheStore(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun feed(id: Long, url: String) = Feed(
        id = id, feedUrl = url, title = "Feed $id", author = "", description = "", artworkUrl = "",
        priority = 0, listenOrder = ListenOrder.NEWEST_FIRST, lastRefreshedAt = null, subscribedAt = 1, rev = 1,
    )

    /** Waits for exactly [expected] (as a set), failing after 5 s with the last value instead of hanging. */
    private fun awaitUrls(expected: Set<String>) = runBlocking {
        var last = emptyList<String>()
        try {
            withTimeout(5_000) { store.feedUrls().first { last = it; it.toSet() == expected && it.size == expected.size } }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("feedUrls never became $expected; the last value had ${last.size} addresses")
        }
    }

    @Test
    fun emptyCacheHasNoAddresses() {
        awaitUrls(emptySet())
    }

    @Test
    fun allCachedAddressesAreReadExactlyAsStoredAndFollowAddAndRemove() {
        runBlocking {
            db.feedDao().insertIfAbsent(feed(1, "https://a.example.com/1.xml"))
            db.feedDao().insertIfAbsent(feed(2, "HTTP://B.example.com:80/Two.xml?t=1"))
        }
        awaitUrls(setOf("https://a.example.com/1.xml", "HTTP://B.example.com:80/Two.xml?t=1"))
        runBlocking { db.feedDao().insertIfAbsent(feed(3, "https://c.example.com/3.xml")) }
        awaitUrls(setOf("https://a.example.com/1.xml", "HTTP://B.example.com:80/Two.xml?t=1", "https://c.example.com/3.xml"))
        runBlocking { db.feedDao().deleteById(2) }
        awaitUrls(setOf("https://a.example.com/1.xml", "https://c.example.com/3.xml"))
        runBlocking { db.feedDao().deleteAll() }
        awaitUrls(emptySet())
        assertEquals(emptyList<String>(), runBlocking { store.feedUrls().first() })
    }
}
