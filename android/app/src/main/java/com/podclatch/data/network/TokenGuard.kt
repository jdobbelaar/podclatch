package com.podclatch.data.network

import com.podclatch.domain.ServerUrlValidator
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.net.ProtocolException

/**
 * The bearer-token rule, enforced in app code (the OS network policy permits cleartext for every build
 * so that podcast audio on plain `http://` hosts plays): the token may be sent over `https`, to the
 * loopback hosts, and always in a debuggable build (the development convenience of `ServerUrlValidator`).
 */
fun tokenMayBeSent(url: HttpUrl, isDebuggable: Boolean): Boolean =
    isDebuggable || url.isHttps || url.host in ServerUrlValidator.CLEARTEXT_HOSTS

/** Thrown by the guard. A [ProtocolException] so that OkHttp does not retry it on another route. */
class CleartextRefusedException : ProtocolException("Refused to send the token over an unencrypted connection.")

/**
 * A network interceptor, so it sees every hop including redirects. A request that carries
 * `Authorization` and may not be sent fails before the request is written. Audio requests carry no
 * `Authorization` header and are never affected.
 */
class TokenGuardInterceptor(private val isDebuggable: Boolean) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header("Authorization") != null && !tokenMayBeSent(request.url, isDebuggable)) {
            throw CleartextRefusedException()
        }
        return chain.proceed(request)
    }
}
