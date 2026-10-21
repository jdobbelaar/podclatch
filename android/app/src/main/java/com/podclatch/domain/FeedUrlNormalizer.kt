package com.podclatch.domain

import java.util.Locale

/**
 * A hand-written copy of the server's `normalize_feed_url` (`server/podclatch_server/store.py`), which
 * is the rule behind its duplicate check: trim; lower-case the scheme and host; drop the default port
 * (80 for http, 443 for https) and the fragment; treat an empty path as `/`; keep the path
 * (case-sensitive), query, and user info exactly as given. An address the server's parser would reject is
 * returned trimmed and otherwise unchanged.
 *
 * OkHttp's `HttpUrl` is deliberately not used: it rewrites paths and percent-encoding. This follows
 * Python's `urlsplit` instead. Exotic addresses may still differ between the two; the server's 409 is the
 * backstop for those.
 */
object FeedUrlNormalizer {
    private val DEFAULT_PORTS = mapOf("http" to "80", "https" to "443")
    private const val MAX_PORT = 65535

    fun normalize(feedUrl: String): String {
        val trimmed = feedUrl.trim()
        // urlsplit drops tabs and line breaks anywhere in the text.
        var rest = trimmed.filter { it != '\t' && it != '\r' && it != '\n' }

        var scheme = ""
        val colon = rest.indexOf(':')
        if (colon > 0 && rest[0].isAsciiLetter() && rest.substring(0, colon).all { it.isAsciiLetter() || it.isAsciiDigit() || it in "+-." }) {
            scheme = rest.substring(0, colon).lowercase(Locale.ROOT)
            rest = rest.substring(colon + 1)
        }

        var netloc = ""
        if (rest.startsWith("//")) {
            var end = rest.length
            for (delimiter in "/?#") {
                val at = rest.indexOf(delimiter, 2)
                if (at >= 0 && at < end) end = at
            }
            netloc = rest.substring(2, end)
            rest = rest.substring(end)
        }
        if (('[' in netloc) != (']' in netloc)) return trimmed

        rest = rest.substringBefore('#')
        val query = if ('?' in rest) rest.substringAfter('?') else ""
        val path = rest.substringBefore('?')

        val userinfo = netloc.substringBeforeLast('@', "")
        val at = if ('@' in netloc) "@" else ""
        val hostport = netloc.substringAfterLast('@')

        val port: String? = portOf(hostport) ?: if (hasInvalidPort(hostport)) return trimmed else null
        var host = (if (port != null) hostport.substringBeforeLast(':') else hostport).lowercase(Locale.ROOT)
        if (port != null && port != DEFAULT_PORTS[scheme]) host = "$host:$port"
        val queryPart = if (query.isNotEmpty()) "?$query" else ""
        return "$scheme://$userinfo$at$host${path.ifEmpty { "/" }}$queryPart"
    }

    /** The port digits as Python's `SplitResult.port` reads them (no leading zeros), or null when there is none. */
    private fun portOf(hostport: String): String? {
        val raw = rawPort(hostport)
        if (raw.isNullOrEmpty() || !raw.all { it.isAsciiDigit() }) return null
        val digits = raw.trimStart('0').ifEmpty { "0" }
        return if (digits.length > 5 || digits.toInt() > MAX_PORT) null else digits
    }

    /** True when a port is written but Python would raise `ValueError` for it. */
    private fun hasInvalidPort(hostport: String): Boolean {
        val raw = rawPort(hostport)
        return !raw.isNullOrEmpty() && portOf(hostport) == null
    }

    private fun rawPort(hostport: String): String? {
        val open = hostport.indexOf('[')
        val afterHost = if (open >= 0) {
            hostport.substring(open + 1).substringAfter(']', "").let { if (':' in it) it.substringAfter(':') else "" }
        } else {
            if (':' in hostport) hostport.substringAfter(':') else ""
        }
        return afterHost.ifEmpty { null }
    }

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'
    private fun Char.isAsciiDigit() = this in '0'..'9'
}
