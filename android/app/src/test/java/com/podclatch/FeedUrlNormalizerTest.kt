package com.podclatch

import com.podclatch.domain.FeedUrlNormalizer.normalize
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The vectors are the ones in the server's `tests/test_store.py` (`test_normalize_feed_url` and
 * `test_normalize_survives_unparseable_urls`), so the two implementations are held to the same answers.
 */
class FeedUrlNormalizerTest {
    private fun same(a: String, b: String) = assertEquals("$a == $b", normalize(b), normalize(a))
    private fun different(a: String, b: String) =
        org.junit.Assert.assertNotEquals("$a != $b", normalize(b), normalize(a))

    @Test
    fun sameAddresses() {
        same("HTTPS://Example.com:443/feed.xml", " https://example.com/feed.xml ")
        same("http://example.com:80/x", "http://example.com/x")
        same("https://example.com/x#a", "https://example.com/x")
        same("https://example.com", "https://example.com/")
    }

    @Test
    fun differentAddresses() {
        different("https://example.com/Feed.xml", "https://example.com/feed.xml")
        different("https://example.com/x?a=1", "https://example.com/x")
        different("http://example.com/x", "https://example.com/x")
        different("https://u@example.com/x", "https://example.com/x")
        different("https://U@example.com/x", "https://u@example.com/x")
        different("https://example.com:8443/x", "https://example.com/x")
        different("https://example.com:443/x", "http://example.com:443/x")
    }

    @Test
    fun anUnparseableAddressIsReturnedTrimmedAndOtherwiseUnchanged() {
        assertEquals("http://[bad", normalize(" http://[bad "))
        assertEquals("https://example.com:notaport/x", normalize("https://example.com:notaport/x"))
    }

    @Test
    fun theNormalFormMatchesTheServersOutput() {
        // Values produced by the server's normalize_feed_url for the same input.
        assertEquals("https://example.com/feed.xml", normalize("HTTPS://Example.com:443/feed.xml"))
        assertEquals("http://example.com:8080/x?a=B", normalize("HTTP://EXAMPLE.com:8080/x?a=B#frag"))
        assertEquals("https://U@example.com/", normalize("https://U@Example.COM"))
        assertEquals("https://example.com/Path/X", normalize("https://example.com/Path/X"))
    }

    @Test
    fun otherShapesMatchTheServersOutput() {
        assertEquals("://foo", normalize("foo"))
        assertEquals("://host/x", normalize("//host/x"))
        assertEquals("http://[::1]/x", normalize("http://[::1]:80/x"))
        assertEquals("http://[::1]:8080/x", normalize("HTTP://[::1]:8080/x"))
        assertEquals("http://a/b?c?d", normalize("http://a/b?c?d#e"))
        assertEquals("http://host/?x=1", normalize("http://host?x=1"))
        assertEquals("http://u:p@h:81/p", normalize("http://u:p@h:81/p"))
    }

    @Test
    fun portEdgeCases() {
        // Port 0080 is 80; an out-of-range port is unparseable for the server.
        assertEquals("http://example.com/x", normalize("http://example.com:0080/x"))
        assertEquals("http://example.com:99999/x", normalize("http://example.com:99999/x"))
        // An empty port keeps the colon, like the server.
        assertEquals("http://example.com:/x", normalize("http://example.com:/x"))
    }
}
