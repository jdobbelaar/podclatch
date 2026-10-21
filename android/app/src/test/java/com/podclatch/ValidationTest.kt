package com.podclatch

import com.podclatch.data.network.redactToken
import com.podclatch.data.repo.Settings
import com.podclatch.domain.ServerUrlValidator
import com.podclatch.domain.StartedActivityCounter
import com.podclatch.domain.TokenError
import com.podclatch.domain.TokenResult
import com.podclatch.domain.TokenValidator
import com.podclatch.domain.UrlError
import com.podclatch.domain.UrlResult
import com.podclatch.work.periodicIntervalMinutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidationTest {
    private val release = ServerUrlValidator(isDebuggable = false)
    private val debug = ServerUrlValidator(isDebuggable = true)

    private fun ServerUrlValidator.url(input: String) = (validate(input) as UrlResult.Valid).url
    private fun ServerUrlValidator.error(input: String) = (validate(input) as UrlResult.Invalid).error

    // ---- server URL ----

    @Test
    fun normalisesCaseWhitespaceAndTrailingSlashes() {
        assertEquals("https://example.com", release.url("  https://Example.com/ "))
        assertEquals("https://example.com", release.url("HTTPS://EXAMPLE.COM///"))
        assertEquals("https://example.com:8443", release.url("https://example.com:8443/"))
        assertEquals("https://example.com/pod", release.url("https://example.com/pod/"))
        assertEquals("https://example.com/a/b", release.url("https://example.com/a/b//"))
    }

    @Test
    fun aPathPrefixIsAllowed() {
        assertEquals("http://10.0.2.2:8180/podclatch", debug.url("http://10.0.2.2:8180/podclatch"))
    }

    @Test
    fun rejectsEachBadShapeWithoutEchoingTheInput() {
        val cases = mapOf(
            "ftp://x" to UrlError.BAD_SCHEME,
            "example.com" to UrlError.BAD_SCHEME,
            "http://u:p@h" to UrlError.USERINFO,
            "https://user@h" to UrlError.USERINFO,
            "https://h?x=1" to UrlError.QUERY,
            "https://h/?" to UrlError.QUERY,
            "https://h#f" to UrlError.FRAGMENT,
            "" to UrlError.BLANK,
            "   " to UrlError.BLANK,
            "http://" to UrlError.INVALID,
            "https://" to UrlError.INVALID,
            "https:// bad host" to UrlError.INVALID,
        )
        for ((input, expected) in cases) {
            assertEquals(input, expected, debug.error(input))
            assertEquals(input, expected, release.error(input))
        }
        // The result type has no field that could carry the input.
        assertFalse(release.validate("http://u:secret@h").toString().contains("secret"))
    }

    @Test
    fun displayHostIsHostAndPortOnly() {
        assertEquals("10.0.2.2:8180", (debug.validate("http://10.0.2.2:8180/") as UrlResult.Valid).displayHost)
        assertEquals("example.com", (release.validate("https://example.com/pod") as UrlResult.Valid).displayHost)
        assertEquals("example.com", (release.validate("https://example.com:443") as UrlResult.Valid).displayHost)
    }

    @Test
    fun releaseRequiresHttpsExceptForLoopbackAndTheEmulatorHost() {
        assertEquals(UrlError.HTTPS_REQUIRED, release.error("http://example.com"))
        assertEquals(UrlError.HTTPS_REQUIRED, release.error("http://192.168.1.20:8180"))
        assertEquals(UrlError.HTTPS_REQUIRED, release.error("http://localhost.example.com"))
        assertEquals("http://localhost:8180", release.url("http://localhost:8180"))
        assertEquals("http://127.0.0.1:8180", release.url("http://127.0.0.1:8180"))
        assertEquals("http://10.0.2.2:8180", release.url("http://10.0.2.2:8180"))
        assertEquals("https://example.com", release.url("https://example.com"))
    }

    @Test
    fun debugAllowsPlainHttpToAnyHost() {
        assertEquals("http://192.168.1.20:8180", debug.url("http://192.168.1.20:8180"))
        assertEquals("http://example.com", debug.url("http://example.com"))
    }

    // ---- token ----

    @Test
    fun aValidTokenIsTrimmed() {
        assertEquals(TokenResult.Valid("abc-DEF_123.~"), TokenValidator.validate("  abc-DEF_123.~ \n"))
    }

    @Test
    fun tokenRejectsBlankSpacesAndNonAscii() {
        assertEquals(TokenResult.Invalid(TokenError.BLANK), TokenValidator.validate("   "))
        assertEquals(TokenResult.Invalid(TokenError.INVALID_CHARACTERS), TokenValidator.validate("two words"))
        assertEquals(TokenResult.Invalid(TokenError.INVALID_CHARACTERS), TokenValidator.validate("tokén"))
        assertEquals(TokenResult.Invalid(TokenError.INVALID_CHARACTERS), TokenValidator.validate("tab\there"))
        assertEquals(TokenResult.Invalid(TokenError.INVALID_CHARACTERS), TokenValidator.validate("emoji😀"))
        // The rejection does not carry the input.
        assertFalse(TokenValidator.validate("secret value").toString().contains("secret"))
    }

    // ---- redaction ----

    @Test
    fun settingsToStringRedactsTheToken() {
        val text = Settings(serverUrl = TEST_URL, authToken = TEST_TOKEN, lastSyncRev = 9).toString()
        assertFalse(TEST_TOKEN in text)
        assertTrue("***" in text)
        assertTrue(TEST_URL in text)
    }

    @Test
    fun redactTokenReplacesEveryOccurrence() {
        assertEquals("a *** b *** c", "a $TEST_TOKEN b $TEST_TOKEN c".redactToken(TEST_TOKEN))
        assertEquals("unchanged", "unchanged".redactToken(""))
    }

    @Test
    fun configuredNeedsBothNonBlank() {
        assertTrue(Settings("https://h", "t").isConfigured)
        assertFalse(Settings("https://h", "").isConfigured)
        assertFalse(Settings("", "t").isConfigured)
        assertFalse(Settings("  ", "t").isConfigured)
        assertFalse(Settings().isConfigured)
        assertEquals(1, Settings().syncIntervalHours)
        assertEquals(0L, Settings().lastSyncRev)
    }

    // ---- periodic interval ----

    @Test
    fun theIntervalIsHoursInMinutesWithADefaultAndAFloor() {
        assertEquals(60L, periodicIntervalMinutes(1))
        assertEquals(360L, periodicIntervalMinutes(6))
        assertEquals(60L, periodicIntervalMinutes(0))
        assertEquals(60L, periodicIntervalMinutes(-3))
        assertTrue(periodicIntervalMinutes(1) >= Config.MIN_PERIODIC_INTERVAL_MINUTES)
    }

    // ---- foreground counter ----

    @Test
    fun theFirstActivityStartFiresAndASecondActivityDoesNot() {
        val counter = StartedActivityCounter()
        assertTrue(counter.onStarted())
        assertFalse(counter.onStarted())
        counter.onStopped(false)
        counter.onStopped(false)
        assertTrue("returning to the foreground fires", counter.onStarted())
    }

    @Test
    fun aRotationDoesNotFire() {
        val counter = StartedActivityCounter()
        assertTrue(counter.onStarted())
        counter.onStopped(isChangingConfigurations = true)
        assertFalse(counter.onStarted())
        // A later real background and return still fires.
        counter.onStopped(false)
        assertTrue(counter.onStarted())
    }

    @Test
    fun stoppingThenStartingWithoutAConfigChangeFires() {
        val counter = StartedActivityCounter()
        counter.onStarted()
        counter.onStopped(false)
        assertTrue(counter.onStarted())
    }

    @Test
    fun movingBetweenTwoActivitiesDoesNotFire() {
        val counter = StartedActivityCounter()
        assertTrue(counter.onStarted())
        // B starts before A stops.
        assertFalse(counter.onStarted())
        counter.onStopped(false)
        assertFalse(counter.onStarted())
    }
}
