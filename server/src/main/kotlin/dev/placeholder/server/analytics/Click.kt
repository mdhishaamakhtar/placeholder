package dev.placeholder.server.analytics

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * One visit to a short link, from the request that saw it to the daily rollup. [id] is minted where the visit
 * happens, so every redelivery downstream carries the same id and can be recognised as a repeat.
 * [visitor] is an anonymous id from [Visitors], never an IP address.
 */
@Serializable
data class Click(
    @Serializable(with = UuidSerializer::class) val id: UUID,
    val slug: String,
    val at: Long,
    val visitor: String,
) {
    fun toJson(): String = JSON.encodeToString(serializer(), this)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        /** Reads a message back. Null for one this code can't read, e.g. written by a future version. */
        fun fromJson(json: String): Click? =
            try {
                JSON.decodeFromString(serializer(), json)
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
    }
}

private object UuidSerializer : KSerializer<UUID> {
    override val descriptor = PrimitiveSerialDescriptor("java.util.UUID", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: UUID,
    ) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): UUID = UUID.fromString(decoder.decodeString())
}
