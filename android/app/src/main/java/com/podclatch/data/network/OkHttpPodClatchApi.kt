package com.podclatch.data.network

import com.podclatch.Config
import com.podclatch.data.repo.SettingsRepository
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OkHttp + kotlinx.serialization implementation. There is no logging interceptor, and nothing here
 * logs. `Authorization: Bearer <token>` is added to every request except `GET /healthz`, with the token
 * read from the settings at call time so a changed token takes effect without a restart.
 */
@Singleton
class OkHttpPodClatchApi @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
    private val settings: SettingsRepository,
) : PodClatchApi {

    override suspend fun health(target: ServerTarget?) {
        val resolved = resolve(target)
        val body = execute(resolved, Request.Builder().url(resolved.url("/healthz")), authenticated = false)
        val status = try {
            json.parseToJsonElement(body).jsonObject["status"]?.jsonPrimitive?.contentOrNull
        } catch (_: Exception) {
            null
        }
        if (status != "ok") throw ApiException(ApiError.Malformed)
    }

    override suspend fun pullSync(since: Long, limit: Int, target: ServerTarget?): SyncPageDto {
        val resolved = resolve(target)
        val url = resolved.url("/v1/sync").newBuilder()
            .addQueryParameter("since", since.toString())
            .addQueryParameter("limit", limit.toString())
            .build()
        val body = execute(resolved, Request.Builder().url(url), authenticated = true)
        return decode(body, SyncPageDto.serializer())
    }

    override suspend fun pushEpisodeState(items: List<EpisodeStateItem>): List<EpisodeStateResult> {
        val resolved = resolve(null)
        val payload = json.encodeToString(ListSerializer(EpisodeStateItem.serializer()), items)
        val request = Request.Builder()
            .url(resolved.url("/v1/episodes/state"))
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
        val body = execute(resolved, request, authenticated = true)
        return decode(body, ListSerializer(EpisodeStateResult.serializer()))
    }

    override suspend fun reorderFeeds(feedIds: List<Long>): List<FeedDto> {
        val resolved = resolve(null)
        val payload = json.encodeToString(ReorderRequest.serializer(), ReorderRequest(feedIds))
        val request = Request.Builder()
            .url(resolved.url("/v1/feeds/order"))
            .put(payload.toRequestBody(JSON_MEDIA_TYPE))
        val body = execute(resolved, request, authenticated = true)
        return decode(body, ListSerializer(FeedDto.serializer()))
    }

    override suspend fun unsubscribeFeed(feedId: Long) {
        val resolved = resolve(null)
        // The 204 has no body, so the answer is not decoded.
        execute(resolved, Request.Builder().url(resolved.url("/v1/feeds/$feedId")).delete(), authenticated = true)
    }

    override suspend fun refreshAllFeeds(): List<RefreshResultDto> {
        val resolved = resolve(null)
        val request = Request.Builder()
            .url(resolved.url("/v1/refresh"))
            .post("".toRequestBody(null))
        val body = execute(resolved, request, authenticated = true)
        return decode(body, ListSerializer(RefreshResultDto.serializer()))
    }

    private class Resolved(val baseUrl: String, val token: String, val client: OkHttpClient) {
        fun url(path: String) = (baseUrl + path).toHttpUrlOrNull()
            ?: throw ApiException(ApiError.Unreachable(timeout = false))
    }

    private suspend fun resolve(target: ServerTarget?): Resolved {
        val (baseUrl, token) = if (target != null) {
            target.baseUrl to target.token
        } else {
            settings.current().let { it.serverUrl to it.authToken }
        }
        val callClient = target?.callTimeoutSeconds?.let {
            client.newBuilder().callTimeout(it, TimeUnit.SECONDS).build()
        } ?: client
        return Resolved(baseUrl.trimEnd('/'), token, callClient)
    }

    private suspend fun execute(resolved: Resolved, builder: Request.Builder, authenticated: Boolean): String {
        if (authenticated) builder.header("Authorization", "Bearer ${resolved.token}")
        val call = resolved.client.newCall(builder.build())
        val (code, text) = try {
            call.await()
        } catch (e: InterruptedIOException) {
            throw ApiException(ApiError.Unreachable(timeout = true))
        } catch (e: IOException) {
            throw ApiException(ApiError.Unreachable(timeout = false))
        }
        return when {
            code in 200..299 -> text
            code == 401 -> throw ApiException(ApiError.Unauthorized)
            else -> throw ApiException(ApiError.Http(code, errorDetail(text, resolved.token)))
        }
    }

    /** The `{"detail": string}` body when it parses, with the token redacted and the text capped; otherwise null. */
    private fun errorDetail(body: String, token: String): String? {
        val detail = try {
            json.parseToJsonElement(body).jsonObject["detail"]?.jsonPrimitive
                ?.takeIf { it.isString }?.content
        } catch (_: Exception) {
            null
        }
        return detail?.redactToken(token)?.take(Config.MAX_ERROR_DETAIL_LENGTH)
    }

    private fun <T> decode(body: String, serializer: kotlinx.serialization.KSerializer<T>): T = try {
        json.decodeFromString(serializer, body)
    } catch (_: SerializationException) {
        throw ApiException(ApiError.Malformed)
    } catch (_: IllegalArgumentException) {
        throw ApiException(ApiError.Malformed)
    }

    /**
     * Runs the call and returns the status code and the whole body. The body is read inside the callback,
     * on OkHttp's own thread: resuming first would read it on the caller's thread, and on the main thread
     * that is a NetworkOnMainThreadException.
     */
    private suspend fun Call.await(): Pair<Int, String> = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use { it.code to it.body.string() }
                } catch (e: IOException) {
                    if (!cont.isCancelled) cont.resumeWithException(e)
                    return
                }
                cont.resume(result)
            }

            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }
        })
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
