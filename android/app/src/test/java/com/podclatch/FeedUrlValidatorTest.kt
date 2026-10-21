package com.podclatch

import com.podclatch.domain.FeedUrlProblem
import com.podclatch.domain.FeedUrlResult
import com.podclatch.domain.FeedUrlValidator
import org.junit.Assert.assertEquals
import org.junit.Test

class FeedUrlValidatorTest {
    private fun accepted(text: String) =
        assertEquals(text, (FeedUrlValidator.validate(text) as? FeedUrlResult.Acceptable)?.url)

    private fun rejected(text: String, problem: FeedUrlProblem) =
        assertEquals(FeedUrlResult.NotAcceptable(problem), FeedUrlValidator.validate(text))

    @Test
    fun acceptsHttpAndHttpsAddresses() {
        listOf(
            "https://example.com/feed.xml",
            "HTTP://Example.com:8080/Feed.xml?a=1#top",
            "http://feedhost:8080/feeds/alpha.xml",
            "https://user@example.com/x",
            "http://127.0.0.1:8190/x.xml",
            "http://[::1]/x.xml",
            "https://example.com",
            "HtTpS://example.com/x",
        ).forEach(::accepted)
    }

    @Test
    fun rejectsOtherSchemes() {
        listOf(
            "example.com/feed.xml", "ftp://example.com/x", "feed://example.com/x",
            "itpc://example.com/x", "https:example.com", "", "   ", "://example.com",
        ).forEach { rejected(it, FeedUrlProblem.BAD_SCHEME) }
    }

    @Test
    fun rejectsIncompleteAddresses() {
        listOf(
            "https://", "http:///x", "https://exa mple.com/x", "https://example.com/a b",
            "http://example.com:99999/x", "https://?x=1", "https://user@/x", "https://:80/x",
        ).forEach { rejected(it, FeedUrlProblem.INCOMPLETE) }
    }

    @Test
    fun trimsFirstAndNeverRewrites() {
        val typed = "  HTTP://Feedhost:8080/Feeds/A.xml?x=1#frag \n"
        assertEquals(
            FeedUrlResult.Acceptable("HTTP://Feedhost:8080/Feeds/A.xml?x=1#frag"),
            FeedUrlValidator.validate(typed),
        )
        rejected("  ftp://example.com/x  ", FeedUrlProblem.BAD_SCHEME)
    }
}
