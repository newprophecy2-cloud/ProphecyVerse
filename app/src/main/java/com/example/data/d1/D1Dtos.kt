package com.example.data.d1

import com.squareup.moshi.FromJson
import com.squareup.moshi.Json
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonClass
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.ToJson
import com.squareup.moshi.Types
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
        if (annotations.isNotEmpty()) return null
        return when (Types.getRawType(type)) {
            Boolean::class.javaPrimitiveType -> D1BooleanAdapter
            Boolean::class.javaObjectType -> NullableD1BooleanAdapter
            D1StreamSourceRecordDto::class.java -> {
                val delegate = moshi.nextAdapter<D1StreamSourceRecordDto>(this, type, annotations)
                D1StreamSourceRecordJsonAdapter(delegate)
            }
            else -> null
        }
    }
}

/**
 * Ensures `expiresAt` and `deletedAt` are always serialized (including explicit `null`)
 * so Worker `ON CONFLICT DO UPDATE SET` clears stale expiry/deletion timestamps when
 * an entry is re-saved with unknown expiry (`expiresAt = null`) or restored after soft-delete.
 */
private class D1StreamSourceRecordJsonAdapter(
    private val delegate: JsonAdapter<D1StreamSourceRecordDto>
) : JsonAdapter<D1StreamSourceRecordDto>() {
    override fun fromJson(reader: JsonReader): D1StreamSourceRecordDto? = delegate.fromJson(reader)

    override fun toJson(writer: JsonWriter, value: D1StreamSourceRecordDto?) {
        if (value == null) {
            writer.nullValue()
            return
        }
        val previousSerializeNulls = writer.serializeNulls
        writer.serializeNulls = true
        try {
            writer.beginObject()
            writer.name("sourceDocKey"); writer.value(value.sourceDocKey)
            writer.name("scopeKey"); writer.value(value.scopeKey)
            writer.name("providerId"); writer.value(value.providerId)
            writer.name("sourceId"); writer.value(value.sourceId)
            writer.name("sourceName"); writer.value(value.sourceName)
            writer.name("backendEpisodeId")
            if (value.backendEpisodeId == null) writer.nullValue() else writer.value(value.backendEpisodeId)
            writer.name("streamUrl")
            if (value.streamUrl == null) writer.nullValue() else writer.value(value.streamUrl)
            writer.name("urlType"); writer.value(value.urlType)
            value.contentType?.let { writer.name("contentType"); writer.value(it) }
            value.quality?.let { writer.name("quality"); writer.value(it) }
            value.codec?.let { writer.name("codec"); writer.value(it) }
            value.audioLanguage?.let { writer.name("audioLanguage"); writer.value(it) }
            value.downloadable?.let {
                writer.name("downloadable")
                D1BooleanAdapter.toJson(writer, it)
            }
            value.sizeBytes?.let { writer.name("sizeBytes"); writer.value(it) }
            value.safeHeadersJson?.let { writer.name("safeHeadersJson"); writer.value(it) }
            writer.name("expiresAt")
            if (value.expiresAt == null) writer.nullValue() else writer.value(value.expiresAt)
            writer.name("status"); writer.value(value.status)
            writer.name("version"); writer.value(value.version)
            value.lastVerifiedAt?.let { writer.name("lastVerifiedAt"); writer.value(it) }
            value.lastPlaybackSuccessAt?.let { writer.name("lastPlaybackSuccessAt"); writer.value(it) }
            writer.name("updatedAt"); writer.value(value.updatedAt)
            writer.name("deletedAt")
            if (value.deletedAt == null) writer.nullValue() else writer.value(value.deletedAt)
            writer.endObject()
        } finally {
            writer.serializeNulls = previousSerializeNulls
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
    val changes: Int? = null,
    val error: String? = null
)

/**
 * Canonical Cloudflare D1 `stream_sources` table record.
 * Maps the composite identity `(anime_id, episode_id, source_id)` onto `stream_sources` columns:
 * - `sourceDocKey`: deterministic primary key from `(anime_id, episode_id, source_id)`
 * - `scopeKey`: canonical `anime_id`
 * - `backendEpisodeId`: canonical `episode_id`
 * - `sourceId`: canonical `source_id`
 * - `streamUrl`: validated resolved playback URL
 * - `expiresAt`: nullable ISO-8601 expiry timestamp (ONLY set when actual expiry is known)
 */
@JsonClass(generateAdapter = true)
data class D1StreamSourceRecordDto(
    @Json(name = "sourceDocKey") val sourceDocKey: String = "",
    @Json(name = "scopeKey") val scopeKey: String = "",
    @Json(name = "providerId") val providerId: String = "direct",
    @Json(name = "sourceId") val sourceId: String = "",
    @Json(name = "sourceName") val sourceName: String = "",
    @Json(name = "backendEpisodeId") val backendEpisodeId: String? = null,
    @Json(name = "streamUrl") val streamUrl: String? = null,
    @Json(name = "urlType") val urlType: String = "direct",
    @Json(name = "contentType") val contentType: String? = null,
    @Json(name = "quality") val quality: String? = null,
    @Json(name = "codec") val codec: String? = null,
    @Json(name = "audioLanguage") val audioLanguage: String? = null,
    @Json(name = "downloadable") val downloadable: Boolean? = null,
    @Json(name = "sizeBytes") val sizeBytes: Long? = null,
    @Json(name = "safeHeadersJson") val safeHeadersJson: String? = null,
    @Json(name = "expiresAt") val expiresAt: String? = null,
    @Json(name = "status") val status: String = "AVAILABLE",
    @Json(name = "version") val version: Long = 1L,
    @Json(name = "lastVerifiedAt") val lastVerifiedAt: Long? = null,
    @Json(name = "lastPlaybackSuccessAt") val lastPlaybackSuccessAt: Long? = null,
    @Json(name = "updatedAt") val updatedAt: Long = 0L,
    @Json(name = "deletedAt") val deletedAt: Long? = null
)
