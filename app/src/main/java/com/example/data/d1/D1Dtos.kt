package com.example.data.d1

import com.squareup.moshi.FromJson
import com.squareup.moshi.Json
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonClass
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.ToJson
import java.lang.reflect.Type

/**
 * SQLite / Cloudflare D1 stores booleans as 0/1 integers cleanly while JSON callers may send true/false.
 */
object D1BooleanAdapter : JsonAdapter<Boolean>() {
    @FromJson
    override fun fromJson(reader: JsonReader): Boolean {
        return when (reader.peek()) {
            JsonReader.Token.BOOLEAN -> reader.nextBoolean()
            JsonReader.Token.NUMBER -> reader.nextInt() != 0
            JsonReader.Token.STRING -> {
                val raw = reader.nextString().trim().lowercase()
                raw == "1" || raw == "true" || raw == "yes"
            }
            JsonReader.Token.NULL -> {
                reader.nextNull<Unit>()
                false
            }
            else -> {
                reader.skipValue()
                false
            }
        }
    }

    @ToJson
    override fun toJson(writer: JsonWriter, value: Boolean?) {
        writer.value(if (value == true) 1 else 0)
    }
}

object NullableD1BooleanAdapter : JsonAdapter<Boolean?>() {
    @FromJson
    override fun fromJson(reader: JsonReader): Boolean? {
        return when (reader.peek()) {
            JsonReader.Token.NULL -> {
                reader.nextNull<Unit>()
                null
            }
            else -> D1BooleanAdapter.fromJson(reader)
        }
    }

    @ToJson
    override fun toJson(writer: JsonWriter, value: Boolean?) {
        if (value == null) writer.nullValue() else writer.value(if (value) 1 else 0)
    }
}

object D1BooleanAdapterFactory : JsonAdapter.Factory {
    override fun create(type: Type, annotations: MutableSet<out Annotation>, moshi: Moshi): JsonAdapter<*>? {
        return when (type) {
            Boolean::class.javaPrimitiveType -> D1BooleanAdapter
            Boolean::class.javaObjectType -> NullableD1BooleanAdapter
            else -> null
        }
    }
}

@JsonClass(generateAdapter = true)
data class D1TablesResponse(
    val success: Boolean = false,
    val count: Int? = null,
    val tables: List<String> = emptyList(),
    val error: String? = null
)

@JsonClass(generateAdapter = true)
data class D1ListResponse<T>(
    val success: Boolean = false,
    val table: String? = null,
    val total: Int? = null,
    val limit: Int? = null,
    val offset: Int? = null,
    val count: Int? = null,
    val data: List<T>? = null,
    val error: String? = null
)

@JsonClass(generateAdapter = true)
data class D1RecordResponse<T>(
    val success: Boolean = false,
    val table: String? = null,
    val message: String? = null,
    val data: T? = null,
    val error: String? = null
)

/**
 * Cloudflare D1 Stream URL cache record.
 * Stores resolved Stream URLs only, keyed by (anime_id, episode_id, source_id),
 * with optional expires_at recorded ONLY when actual expiry is known.
 */
@JsonClass(generateAdapter = true)
data class D1StreamUrlRecordDto(
    @Json(name = "id") val id: String? = null,
    @Json(name = "anime_id") val animeId: String = "",
    @Json(name = "episode_id") val episodeId: String = "",
    @Json(name = "source_id") val sourceId: String = "",
    @Json(name = "stream_url") val streamUrl: String = "",
    @Json(name = "expires_at") val expiresAt: String? = null
)
