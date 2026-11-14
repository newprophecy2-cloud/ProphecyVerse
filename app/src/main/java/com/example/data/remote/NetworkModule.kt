package com.example.data.remote

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.lang.reflect.Type
import java.util.concurrent.TimeUnit

object NetworkModule {
    private const val PROPHECY_BASE_URL = "https://api.prophecyverse.tech"
    private const val ANILIST_BASE_URL = "https://graphql.anilist.co"

    internal val prophecyResponseAdapterFactory = object : JsonAdapter.Factory {
        override fun create(
            type: Type,
            annotations: MutableSet<out Annotation>,
            moshi: Moshi
        ): JsonAdapter<*>? {
            if (annotations.isNotEmpty()) return null
            val rawType = Types.getRawType(type)
            return when (rawType) {
                ProphecyEpisodeFullResponse::class.java -> createEpisodeFullAdapter(moshi)
                ProphecySourceDto::class.java -> createSourceDtoAdapter(moshi)
                else -> null
            }
        }

        private fun createEpisodeFullAdapter(moshi: Moshi): JsonAdapter<ProphecyEpisodeFullResponse> {
            val animeAdapter = moshi.adapter(ProphecyAnimeDetailsResponse::class.java)
            val episodeAdapter = moshi.adapter(ProphecyEpisodeDto::class.java)
            val sourcesListType = Types.newParameterizedType(List::class.java, ProphecySourceDto::class.java)
            val sourcesAdapter = moshi.adapter<List<ProphecySourceDto>>(sourcesListType)
            val stringAdapter = moshi.adapter(String::class.java)
            val booleanAdapter = moshi.adapter(Boolean::class.javaObjectType)
            val options = JsonReader.Options.of(
                "anime",
                "selected_episode",
                "sources",
                "sources_status",
                "sources_partial",
                "source_error"
            )
            return object : JsonAdapter<ProphecyEpisodeFullResponse>() {
                override fun fromJson(reader: JsonReader): ProphecyEpisodeFullResponse? {
                    if (reader.peek() == JsonReader.Token.NULL) return reader.nextNull()
                    var anime: ProphecyAnimeDetailsResponse? = null
                    var selectedEpisode: ProphecyEpisodeDto? = null
                    var sources: List<ProphecySourceDto>? = null
                    var sourcesStatus: String? = null
                    var sourcesPartial: Boolean? = null
                    var sourceError: String? = null

                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.selectName(options)) {
                            0 -> anime = animeAdapter.fromJson(reader)
                            1 -> selectedEpisode = episodeAdapter.fromJson(reader)
                            2 -> sources = sourcesAdapter.fromJson(reader)
                            3 -> sourcesStatus = if (reader.peek() == JsonReader.Token.NULL) reader.nextNull() else stringAdapter.fromJson(reader)
                            4 -> sourcesPartial = if (reader.peek() == JsonReader.Token.NULL) reader.nextNull() else booleanAdapter.fromJson(reader)
                            5 -> sourceError = readFlexibleSourceError(reader)
                            else -> {
                                reader.skipName()
                                reader.skipValue()
                            }
                        }
                    }
                    reader.endObject()
                    return ProphecyEpisodeFullResponse(
                        anime = anime,
                        selectedEpisode = selectedEpisode,
                        sources = sources,
                        sourcesStatus = sourcesStatus,
                        sourcesPartial = sourcesPartial,
                        sourceError = sourceError
                    )
                }

                override fun toJson(writer: JsonWriter, value: ProphecyEpisodeFullResponse?) {
                    if (value == null) {
                        writer.nullValue()
                        return
                    }
                    writer.beginObject()
                    writer.name("anime"); animeAdapter.toJson(writer, value.anime)
                    writer.name("selected_episode"); episodeAdapter.toJson(writer, value.selectedEpisode)
                    writer.name("sources"); sourcesAdapter.toJson(writer, value.sources)
                    writer.name("sources_status"); writer.value(value.sourcesStatus)
                    writer.name("sources_partial"); writer.value(value.sourcesPartial)
                    writer.name("source_error"); writer.value(value.sourceError)
                    writer.endObject()
                }
            }
        }

        private fun readFlexibleSourceError(reader: JsonReader): String? = when (reader.peek()) {
            JsonReader.Token.NULL -> reader.nextNull()
            JsonReader.Token.STRING -> reader.nextString().takeIf { it.isNotBlank() }
            JsonReader.Token.BEGIN_OBJECT -> {
                var message: String? = null
                var code: String? = null
                var provider: String? = null
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "message" -> message = readScalarAsString(reader) ?: message
                        "code" -> code = readScalarAsString(reader) ?: code
                        "provider" -> provider = readScalarAsString(reader) ?: provider
                        "error" -> {
                            when (reader.peek()) {
                                JsonReader.Token.STRING -> message = reader.nextString().takeIf { it.isNotBlank() } ?: message
                                JsonReader.Token.BEGIN_OBJECT -> {
                                    reader.beginObject()
                                    while (reader.hasNext()) {
                                        when (reader.nextName()) {
                                            "message" -> message = readScalarAsString(reader) ?: message
                                            "code" -> code = readScalarAsString(reader) ?: code
                                            "provider" -> provider = readScalarAsString(reader) ?: provider
                                            else -> reader.skipValue()
                                        }
                                    }
                                    reader.endObject()
                                }
                                else -> reader.skipValue()
                            }
                        }
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                val core = message?.takeIf { it.isNotBlank() } ?: code?.takeIf { it.isNotBlank() }
                when {
                    core != null && !provider.isNullOrBlank() && !core.contains(provider, ignoreCase = true) -> "$provider: $core"
                    else -> core
                }
            }
            else -> {
                reader.skipValue()
                null
            }
        }

        private fun readScalarAsString(reader: JsonReader): String? = when (reader.peek()) {
            JsonReader.Token.NULL -> reader.nextNull()
            JsonReader.Token.STRING -> reader.nextString().takeIf { it.isNotBlank() }
            JsonReader.Token.NUMBER -> reader.nextString().takeIf { it.isNotBlank() }
            JsonReader.Token.BOOLEAN -> reader.nextBoolean().toString()
            else -> {
                reader.skipValue()
                null
            }
        }

        private fun createSourceDtoAdapter(moshi: Moshi): JsonAdapter<ProphecySourceDto> {
            val stringAdapter = moshi.adapter(String::class.java)
            val longAdapter = moshi.adapter(Long::class.javaObjectType)
            val boolAdapter = moshi.adapter(Boolean::class.javaObjectType)
            val options = JsonReader.Options.of(
                "id",
                "label",
                "quality",
                "codec",
                "language",
                "size_bytes",
                "downloadable",
                "expires_at"
            )
            return object : JsonAdapter<ProphecySourceDto>() {
                override fun fromJson(reader: JsonReader): ProphecySourceDto? {
                    if (reader.peek() == JsonReader.Token.NULL) return reader.nextNull()
                    var id = ""
                    var label: String? = null
                    var quality: String? = null
                    var codec: String? = null
                    var language: String? = null
                    var sizeBytes: Long? = null
                    var downloadable: Boolean? = null
                    var expiresAt: String? = null

                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.selectName(options)) {
                            0 -> id = if (reader.peek() == JsonReader.Token.NULL) {
                                reader.nextNull<String?>()
                                ""
                            } else {
                                stringAdapter.fromJson(reader).orEmpty()
                            }
                            1 -> label = if (reader.peek() == JsonReader.Token.NULL) reader.nextNull() else stringAdapter.fromJson(reader)
                            2 -> quality = if (reader.peek() == JsonReader.Token.NULL) reader.nextNull() else stringAdapter.fromJson(reader)
                            3 -> codec = if (reader.peek() == JsonReader.Token.NULL) reader.nextNull() else stringAdapter.fromJson(reader)
                            4 -> language = if (reader.peek() == JsonReader.Token.NULL) reader.nextNull() else stringAdapter.fromJson(reader)
                            5 -> sizeBytes = if (reader.peek() == JsonReader.Token.NULL) reader.nextNull() else longAdapter.fromJson(reader)
                            6 -> downloadable = if (reader.peek() == JsonReader.Token.NULL) reader.nextNull() else boolAdapter.fromJson(reader)
                            7 -> expiresAt = if (reader.peek() == JsonReader.Token.NULL) reader.nextNull() else stringAdapter.fromJson(reader)
                            else -> {
                                reader.skipName()
                                reader.skipValue()
                            }
                        }
                    }
                    reader.endObject()
                    return ProphecySourceDto(
                        id = id,
                        label = label,
                        quality = quality,
                        codec = codec,
                        language = language,
                        sizeBytes = sizeBytes,
                        downloadable = downloadable,
                        expiresAt = expiresAt
                    )
                }

                override fun toJson(writer: JsonWriter, value: ProphecySourceDto?) {
                    if (value == null) {
                        writer.nullValue()
                        return
                    }
                    writer.beginObject()
                    writer.name("id"); writer.value(value.id)
                    writer.name("label"); writer.value(value.label)
                    writer.name("quality"); writer.value(value.quality)
                    writer.name("codec"); writer.value(value.codec)
                    writer.name("language"); writer.value(value.language)
                    writer.name("size_bytes"); writer.value(value.sizeBytes)
                    writer.name("downloadable"); writer.value(value.downloadable)
                    writer.name("expires_at"); writer.value(value.expiresAt)
                    writer.endObject()
                }
            }
        }
    }

    val moshi: Moshi by lazy {
        Moshi.Builder()
            .add(prophecyResponseAdapterFactory)
            .addLast(KotlinJsonAdapterFactory())
            .build()
    }

    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", "ProphecyVerse/${com.example.BuildConfig.VERSION_NAME} (Android)")
                    .build()
                chain.proceed(request)
            }
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
            .build()
    }

    val prophecyVerseApi: ProphecyVerseApiService by lazy {
        Retrofit.Builder()
            .baseUrl(PROPHECY_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(ProphecyVerseApiService::class.java)
    }

    val aniListApi: AniListGraphQLService by lazy {
        Retrofit.Builder()
            .baseUrl(ANILIST_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(AniListGraphQLService::class.java)
    }
}
