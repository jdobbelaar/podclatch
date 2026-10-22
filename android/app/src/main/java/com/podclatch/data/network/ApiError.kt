package com.podclatch.data.network

/**
 * Why a call failed. None of these carry the token: the only free text, [Http.detail], has the token
 * replaced with `***` and is cut to [com.podclatch.Config.MAX_ERROR_DETAIL_LENGTH] characters before
 * it is stored here.
 */
sealed interface ApiError {
    /** Any IOException: DNS failure, refused connection, dropped connection, or a timeout. */
    data class Unreachable(val timeout: Boolean) : ApiError

    /** HTTP 401. */
    data object Unauthorized : ApiError

    /** Any other non-2xx answer; [detail] is the `{"detail": string}` body when it parses. */
    data class Http(val code: Int, val detail: String?) : ApiError

    /** A 2xx body that does not decode, or a `/healthz` body that is not `{"status":"ok"}`. */
    data object Malformed : ApiError

    /**
     * The token guard refused to send the token over plain HTTP to a host outside loopback (release
     * builds only). Nothing was sent. Carries no address.
     */
    data object CleartextRefused : ApiError

    /** A short, fixed-form English description that is safe to show or put in an exception. */
    val message: String
        get() = when (this) {
            is Unreachable -> if (timeout) "The server did not answer in time." else "Cannot reach the server."
            Unauthorized -> "The server rejected the token."
            is Http -> if (detail.isNullOrEmpty()) "The server answered with an error (HTTP $code)." else
                "The server answered with an error (HTTP $code): $detail"
            Malformed -> "The server's answer was not understood."
            CleartextRefused ->
                "The app will not send the token over an unencrypted connection to this address. Use an https:// address."
        }
}

class ApiException(val error: ApiError) : Exception(error.message)

/** Replaces every occurrence of [token] with `***`. */
fun String.redactToken(token: String): String = if (token.isEmpty()) this else replace(token, "***")
