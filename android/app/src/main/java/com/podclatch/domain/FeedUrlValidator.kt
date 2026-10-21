package com.podclatch.domain

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Why a typed feed address is not acceptable. Never carries the text itself (it may hold a secret). */
enum class FeedUrlProblem {
    /** Not `http://` or `https://`. */
    BAD_SCHEME,

    /** The scheme is right but the address is not a complete one (no host, bad port, inner whitespace). */
    INCOMPLETE,
}

sealed interface FeedUrlResult {
    /** [url] is the trimmed text exactly as typed: it is what gets sent to the server. */
    data class Acceptable(val url: String) : FeedUrlResult

    data class NotAcceptable(val problem: FeedUrlProblem) : FeedUrlResult
}

/**
 * The on-device check of a typed feed address. It only judges; it never rewrites the text (a query can be
 * a secret token, and the server normalises addresses itself to detect duplicates).
 */
object FeedUrlValidator {
    fun validate(input: String): FeedUrlResult {
        val text = input.trim()
        val schemeEnd = text.indexOf("://")
        if (schemeEnd <= 0) return bad(FeedUrlProblem.BAD_SCHEME)
        val scheme = text.substring(0, schemeEnd)
        if (!scheme.equals("http", ignoreCase = true) && !scheme.equals("https", ignoreCase = true)) {
            return bad(FeedUrlProblem.BAD_SCHEME)
        }
        if (text.any { it.isWhitespace() }) return bad(FeedUrlProblem.INCOMPLETE)
        val afterScheme = text.substring(schemeEnd + 3)
        // OkHttp skips extra slashes (`http:///x` parses with host `x`), so the host must follow directly.
        if (afterScheme.isEmpty() || afterScheme[0] in "/\\?#") return bad(FeedUrlProblem.INCOMPLETE)
        val authority = afterScheme.takeWhile { it !in "/\\?#" }
        if (authority.substringAfterLast('@').let { it.isEmpty() || it.startsWith(":") }) {
            return bad(FeedUrlProblem.INCOMPLETE)
        }
        val parsed = text.toHttpUrlOrNull() ?: return bad(FeedUrlProblem.INCOMPLETE)
        if (parsed.host.isEmpty()) return bad(FeedUrlProblem.INCOMPLETE)
        return FeedUrlResult.Acceptable(text)
    }

    private fun bad(problem: FeedUrlProblem) = FeedUrlResult.NotAcceptable(problem)
}
