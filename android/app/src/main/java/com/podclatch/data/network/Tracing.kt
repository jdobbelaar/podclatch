package com.podclatch.data.network

import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.security.SecureRandom
import java.util.ArrayDeque
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * The trace a group of `PodClatchApi` calls belongs to. Only a random 16-byte id: nothing else (no
 * token, URL, or title) ever goes into it. It travels in the coroutine context, so the calls a Sync or a
 * Refresh makes share one trace id without the callers passing anything.
 */
class TraceContext(val traceId: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TraceContext>
}

/**
 * Runs [block] inside one trace. When the caller is already inside a trace (a Refresh that goes on to
 * Sync), that trace is reused; otherwise a new one starts.
 */
suspend fun <T> withTrace(block: suspend () -> T): T =
    if (coroutineContext[TraceContext] != null) block() else withContext(TraceContext(randomHex(TRACE_ID_BYTES))) { block() }

/** A finished client span. Attributes are limited to method, route template, and status code. */
data class ClientSpan(
    val name: String,
    val traceId: String,
    val spanId: String,
    val method: String,
    val route: String,
    /** The HTTP status code, or null when the call failed before a response. */
    val statusCode: Int?,
    val error: Boolean,
) {
    val kind: String get() = "client"
}

/**
 * A bounded in-memory store of the last [capacity] spans. Nothing is exported from the phone; the
 * recorder exists so tests can read what was sent. It holds a short lock only, never throws, and cannot
 * grow without limit.
 */
@Singleton
class SpanRecorder(private val capacity: Int) {
    @Inject constructor() : this(DEFAULT_CAPACITY)

    private val spans = ArrayDeque<ClientSpan>()

    fun record(span: ClientSpan) {
        synchronized(spans) {
            if (spans.size >= capacity) spans.removeFirst()
            spans.addLast(span)
        }
    }

    fun snapshot(): List<ClientSpan> = synchronized(spans) { spans.toList() }

    companion object {
        const val DEFAULT_CAPACITY = 100
    }
}

/**
 * An application interceptor (it runs once per call, so a retry or redirect reuses the call's span id).
 * It adds one W3C `traceparent` header (`00-<trace id>-<span id>-01`) built only from random ids, and
 * records one client span. It sends no `tracestate` or `baggage`, catches nothing it does not rethrow,
 * and logs nothing. Installed only on the client [OkHttpPodClatchApi] uses, never on the shared client.
 */
class TraceparentInterceptor(private val recorder: SpanRecorder) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val traceId = original.tag(TraceContext::class.java)?.traceId ?: randomHex(TRACE_ID_BYTES)
        val spanId = randomHex(SPAN_ID_BYTES)
        val route = routeTemplate(original)
        val method = original.method
        val request = original.newBuilder()
            .removeHeader(TRACEPARENT)
            .removeHeader("tracestate")
            .removeHeader("baggage")
            .header(TRACEPARENT, "00-$traceId-$spanId-01")
            .build()

        var status: Int? = null
        var failed = true
        try {
            val response = chain.proceed(request)
            status = response.code
            failed = response.code >= 400
            return response
        } finally {
            recorder.record(ClientSpan("$method $route", traceId, spanId, method, route, status, failed))
        }
    }

    private fun routeTemplate(request: Request): String =
        request.url.pathSegments.filter { it.isNotEmpty() }
            .joinToString("/", prefix = "/") { if (it.all(Char::isDigit)) "{id}" else it }

    companion object {
        const val TRACEPARENT = "traceparent"
    }
}

private const val TRACE_ID_BYTES = 16
private const val SPAN_ID_BYTES = 8
private val random = SecureRandom()

/** Random lowercase hex of [bytes] bytes, never all zeros (an all-zero id is invalid in W3C Trace Context). */
internal fun randomHex(bytes: Int): String {
    val buf = ByteArray(bytes)
    do {
        random.nextBytes(buf)
    } while (buf.all { it == 0.toByte() })
    return buf.joinToString("") { "%02x".format(it) }
}
