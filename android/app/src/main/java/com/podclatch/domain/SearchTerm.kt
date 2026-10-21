package com.podclatch.domain

import com.podclatch.Config

/** What the typed search text means. */
sealed interface PreparedTerm {
    /** Empty or whitespace only: nothing to search for, no request. */
    data object Blank : PreparedTerm

    /** More than [Config.SEARCH_MAX_TERM_LENGTH] characters after trimming: no request. */
    data object TooLong : PreparedTerm

    /** The trimmed text: exactly what is sent (inner spaces untouched). */
    data class Term(val text: String) : PreparedTerm {
        // Search terms are personal; keep them out of any accidental log line.
        override fun toString(): String = "Term(***)"
    }
}

/**
 * Prepares the typed text: trims it, and counts Unicode code points (as the server counts characters, not
 * UTF-16 units) against [Config.SEARCH_MAX_TERM_LENGTH].
 */
fun prepareSearchTerm(typed: String): PreparedTerm {
    val trimmed = typed.trim()
    if (trimmed.isEmpty()) return PreparedTerm.Blank
    if (trimmed.codePointCount(0, trimmed.length) > Config.SEARCH_MAX_TERM_LENGTH) return PreparedTerm.TooLong
    return PreparedTerm.Term(trimmed)
}
