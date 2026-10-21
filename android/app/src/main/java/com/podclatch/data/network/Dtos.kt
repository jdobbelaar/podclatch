@file:UseSerializers(StrictLongSerializer::class, StrictIntSerializer::class)

package com.podclatch.data.network

import com.podclatch.data.db.EpisodeStatus
import com.podclatch.data.db.ListenOrder
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/*
 * DTOs mirror openapi.yaml exactly: Long for int64, Int for int32, nullable exactly where the contract
 * allows null. A missing required field, a wrong type, or an unknown enum value fails decoding, which
 * the client maps to ApiError.Malformed.
 *
 * kotlinx.serialization would accept a quoted number ("4") for a Long or Int; the contract says integer,
 * so the strict serializers below refuse it.
 */

private fun JsonDecoder.strictNumber(): JsonPrimitive {
    val element = decodeJsonElement()
    if (element !is JsonPrimitive || element.isString) throw SerializationException("Expected a JSON number")
    return element
}

object StrictLongSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("StrictLong", PrimitiveKind.LONG)
    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeLong(value)
    override fun deserialize(decoder: Decoder): Long =
        (decoder as JsonDecoder).strictNumber().longOrNull ?: throw SerializationException("Expected an integer")
}

object StrictIntSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("StrictInt", PrimitiveKind.INT)
    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)
    override fun deserialize(decoder: Decoder): Int =
        (decoder as JsonDecoder).strictNumber().intOrNull ?: throw SerializationException("Expected an integer")
}

@Serializable
data class HealthDto(val status: String)

@Serializable
data class FeedDto(
    val id: Long,
    val feedUrl: String,
    val title: String,
    val author: String,
    val description: String,
    val artworkUrl: String,
    val priority: Int,
    val listenOrder: ListenOrder,
    val lastRefreshedAt: Long?,
    val subscribedAt: Long,
    val rev: Long,
)

@Serializable
data class EpisodeDto(
    val id: Long,
    val feedId: Long,
    val guid: String,
    val title: String,
    val description: String,
    val audioUrl: String,
    val publishedAt: Long,
    val durationSec: Int?,
    val artworkUrl: String?,
    val status: EpisodeStatus,
    val playedAt: Long?,
    val positionSec: Int,
    val stateUpdatedAt: Long,
    val rev: Long,
)

@Serializable
enum class TombstoneEntityType { FEED, EPISODE }

@Serializable
data class TombstoneDto(
    val entityType: TombstoneEntityType,
    val entityId: Long,
    val rev: Long,
)

@Serializable
data class SyncPageDto(
    val feeds: List<FeedDto>,
    val episodes: List<EpisodeDto>,
    val tombstones: List<TombstoneDto>,
    val nextRev: Long,
    val hasMore: Boolean,
)

/** One item of the bare JSON array sent to `POST /v1/episodes/state`; `playedAt` is an explicit null for NEW. */
@Serializable
data class EpisodeStateItem(
    val episodeId: Long,
    val status: EpisodeStatus,
    val playedAt: Long?,
    val positionSec: Int,
    val stateUpdatedAt: Long,
)

@Serializable
enum class StateOutcome { APPLIED, STALE, UNKNOWN_EPISODE }

/** The state fields are absent for [StateOutcome.UNKNOWN_EPISODE]. */
@Serializable
data class EpisodeStateResult(
    val episodeId: Long,
    val outcome: StateOutcome,
    val status: EpisodeStatus? = null,
    val playedAt: Long? = null,
    val positionSec: Int? = null,
    val stateUpdatedAt: Long? = null,
)

/** Body of `PUT /v1/feeds/order`: every subscribed Feed id exactly once, highest priority first. */
@Serializable
data class ReorderRequest(val feedIds: List<Long>)

/** One item of the `POST /v1/refresh` answer; [error] is absent when [ok] is true. */
@Serializable
data class RefreshResultDto(
    val feedId: Long,
    val ok: Boolean,
    val error: String? = null,
)
