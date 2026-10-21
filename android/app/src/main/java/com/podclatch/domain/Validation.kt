package com.podclatch.domain

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class UrlError { BLANK, BAD_SCHEME, INVALID, USERINFO, QUERY, FRAGMENT, HTTPS_REQUIRED }

sealed interface UrlResult {
    /** [url] is normalised; [displayHost] is host and port only. */
    data class Valid(val url: String, val displayHost: String) : UrlResult

    /** The error says what is wrong, never what was typed. */
    data class Invalid(val error: UrlError) : UrlResult
}

/**
 * The one server URL rule, shared by the connection screen and everything that saves the URL.
 * [isDebuggable] decides the cleartext policy (injected so both modes are testable): a release build
 * accepts `http://` only for loopback and the emulator host. This is the app's rule, not the OS's: the
 * network security config permits cleartext for every build (podcast audio is often plain `http://`),
 * so the token is protected here and by the token guard interceptor (`TokenGuard.kt`).
 */
class ServerUrlValidator(private val isDebuggable: Boolean) {

    fun validate(input: String): UrlResult {
        val text = input.trim()
        if (text.isEmpty()) return invalid(UrlError.BLANK)
        val schemeEnd = text.indexOf("://")
        if (schemeEnd < 0) return invalid(UrlError.BAD_SCHEME)
        val scheme = text.substring(0, schemeEnd).lowercase()
        if (scheme != "http" && scheme != "https") return invalid(UrlError.BAD_SCHEME)
        val rest = text.substring(schemeEnd + 3)
        if ('#' in rest) return invalid(UrlError.FRAGMENT)
        if ('?' in rest) return invalid(UrlError.QUERY)
        val authority = rest.substringBefore('/')
        if ('@' in authority) return invalid(UrlError.USERINFO)
        val parsed = "$scheme://$rest".toHttpUrlOrNull() ?: return invalid(UrlError.INVALID)
        if (parsed.host.isEmpty()) return invalid(UrlError.INVALID)
        if (scheme == "http" && !isDebuggable && parsed.host !in CLEARTEXT_HOSTS) {
            return invalid(UrlError.HTTPS_REQUIRED)
        }
        val normalised = parsed.toString().trimEnd('/')
        return UrlResult.Valid(normalised, displayHost(parsed))
    }

    private fun invalid(error: UrlError) = UrlResult.Invalid(error)

    private fun displayHost(url: HttpUrl): String {
        val host = if (':' in url.host) "[${url.host}]" else url.host
        return if (url.port == HttpUrl.defaultPort(url.scheme)) host else "$host:${url.port}"
    }

    companion object {
        /** Loopback and the emulator's host loopback: the only places a release build may use plain HTTP. */
        val CLEARTEXT_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2")
    }
}

enum class TokenError { BLANK, INVALID_CHARACTERS }

sealed interface TokenResult {
    data class Valid(val token: String) : TokenResult
    data class Invalid(val error: TokenError) : TokenResult
}

/** The token is trimmed, non-empty, and printable ASCII without spaces (anything else would corrupt the header). */
object TokenValidator {
    fun validate(input: String): TokenResult {
        val token = input.trim()
        if (token.isEmpty()) return TokenResult.Invalid(TokenError.BLANK)
        if (token.any { it.code !in 0x21..0x7E }) return TokenResult.Invalid(TokenError.INVALID_CHARACTERS)
        return TokenResult.Valid(token)
    }
}
