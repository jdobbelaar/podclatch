package com.podclatch

import com.podclatch.domain.PreparedTerm
import com.podclatch.domain.prepareSearchTerm
import com.podclatch.ui.subscribe.search.isSubscribed
import com.podclatch.ui.subscribe.search.resultRowText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTermTest {
    @Test
    fun blankTextIsBlank() {
        assertEquals(PreparedTerm.Blank, prepareSearchTerm(""))
        assertEquals(PreparedTerm.Blank, prepareSearchTerm("   "))
        assertEquals(PreparedTerm.Blank, prepareSearchTerm(" \t \n  \t"))
    }

    @Test
    fun theTermIsTrimmedAndInnerSpacesAreUntouched() {
        assertEquals(PreparedTerm.Term("rock  &   roll"), prepareSearchTerm("  rock  &   roll\t"))
    }

    @Test
    fun twoHundredCharactersAreAcceptedAndTwoHundredOneAreTooLong() {
        val ok = "a".repeat(200)
        assertEquals(PreparedTerm.Term(ok), prepareSearchTerm(ok))
        assertEquals(PreparedTerm.TooLong, prepareSearchTerm("a".repeat(201)))
        assertEquals(PreparedTerm.Term(ok), prepareSearchTerm("   $ok   "))
        assertEquals(PreparedTerm.TooLong, prepareSearchTerm("   ${"a".repeat(201)}   "))
    }

    @Test
    fun charactersAreCountedAsCodePointsNotUtf16Units() {
        val emoji200 = "🎙".repeat(200)
        assertEquals(400, emoji200.length)
        assertEquals(PreparedTerm.Term(emoji200), prepareSearchTerm(emoji200))
        assertEquals(PreparedTerm.TooLong, prepareSearchTerm(emoji200 + "🎙"))
    }

    @Test
    fun theTermDoesNotShowInToString() {
        assertFalse(PreparedTerm.Term("my secret interest").toString().contains("secret"))
    }

    @Test
    fun rowTextIsTitleCommaAuthorOrJustTheTitle() {
        assertEquals("History Hour, BBC", resultRowText("History Hour", "BBC", "Untitled Feed"))
        assertEquals("History Hour", resultRowText("History Hour", "", "Untitled Feed"))
        assertEquals("History Hour", resultRowText("History Hour", "  ", "Untitled Feed"))
        assertEquals("Untitled Feed, BBC", resultRowText("", "BBC", "Untitled Feed"))
        assertEquals("Untitled Feed", resultRowText(" ", "", "Untitled Feed"))
    }

    @Test
    fun subscribedComparesNormalisedAddresses() {
        val cached = setOf(com.podclatch.domain.FeedUrlNormalizer.normalize("https://example.com/feed.xml"))
        assertTrue(isSubscribed("HTTPS://Example.com:443/feed.xml", cached))
        assertFalse(isSubscribed("https://example.com/Feed.xml", cached))
        assertFalse(isSubscribed("https://example.com/feed.xml", emptySet()))
    }
}
