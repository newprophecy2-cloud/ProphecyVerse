package com.example.data.remote

import com.example.core.network.BackendConfig
import com.example.data.remote.session.PvSessionManager
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
    private const val PROPHECY_BASE_URL = BackendConfig.BASE_URL
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
                DirectPlayRequest::class.java -> createDirectPlayRequestAdapter(moshi)
                else -> null
            }
        }

        private fun createDirectPlayRequestAdapter(moshi: Moshi): JsonAdapter<DirectPlayRequest> {
            val stringAdapter = moshi.adapter(String::class.java)
            val options = JsonReader.Options.of("source_id", "anime_id", "episode_id")
            return object : JsonAdapter<DirectPlayRequest>() {
                override fun fromJson(reader: JsonReader): DirectPlayRequest? {
                    if (reader.peek() == JsonReader.Token.NULL) return reader.nextNull()
                    var sourceId = ""
                    var animeId = ""
                    var episodeId = ""
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.selectName(options)) {
                            0 -> sourceId = if (reader.peek() == JsonReader.Token.NULL) {
                                reader.nextNull<String?>()
                                ""
                            } else {
                                stringAdapter.fromJson(reader).orEmpty()
                            }
                            1 -> animeId = if (reader.peek() == JsonReader.Token.NULL) {
                                reader.nextNull<String?>()
                                ""
                            } else {
                                stringAdapter.fromJson(reader).orEmpty()
                            }
                            2 -> episodeId = if (reader.peek() == JsonReader.Token.NULL) {
                                reader.nextNull<String?>()
                                ""
                            } else {
                                stringAdapter.fromJson(reader).orEmpty()
                            }
                            else -> {
                                reader.skipName()
                                reader.skipValue()
                            }
                        }
                    }
                    reader.endObject()
                    return DirectPlayRequest(animeId = animeId, episodeId = episodeId, sourceId = sourceId)
                }

                override fun toJson(writer: JsonWriter, value: DirectPlayRequest?) {
                    if (value == null) {
                        writer.nullValue()
                        return
                    }
                    writer.beginObject()
                    writer.name("source_id")
                    writer.value(value.sourceId.trim())
                    writer.endObject()
                }
            }
        }

        private fun createEpisodeFullAdapter(moshi: Moshi): JsonAdapter<ProphecyEpisodeFullResponse> {
            val animeAdapter = moshi.adapter(ProphecyAnimeDetailsResponse::class.java)
            val episodeAdapter = moshi.adapter(ProphecyEpisodeDto::class.java)
            val sourcesListType = Types.newParameterizedType(List::class.java, ProphecySourceDto::class.java)
            val sourcesAdapter = moshi.adapter<List<ProphecySourceDto>>(sourcesListType)
            val providerStatusType = Types.newParameterizedType(Map::class.java, String::class.java, String::class.java)
            val providerStatusAdapter = moshi.adapter<Map<String, String>>(providerStatusType)
            val stringAdapter = moshi.adapter(String::class.java)
            val booleanAdapter = moshi.adapter(Boolean::class.javaObjectType)
            val options = JsonReader.Options.of(
                "anime",
                "selected_episode",
                "sources",
                "sources_status",
                "sources_partial",
                "source_error",
                "provider_status"
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
                    var providerStatus: Map<String, String>? = null

                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.selectName(options)) {
                            0 -> anime = animeAdapter.fromJson(reader)
                            1 -> selectedEpisode = episodeAdapter.fromJson(reader)
                            2 -> sources = sourcesAdapter.fromJson(reader)
                            3 -> sourcesStatus = if (reader.peek() == JsonReader.Token.NULL) reader.nextNull() else stringAdapter.fromJson(reader)
                            4 -> sourcesPartial = if (reader.peek() == JsonReader.Token.NULL) reader.nextNull() else booleanAdapter.fromJson(reader)
                            5 -> sourceError = readFlexibleSourceError(reader)
                            6 -> providerStatus = if (reader.peek() == JsonReader.Token.NULL) reader.nextNull() else readFlexibleProviderStatus(reader)
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
                        sourceError = sourceError,
                        providerStatus = providerStatus
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
                    if (value.providerStatus != null) {
                        writer.name("provider_status")
                        providerStatusAdapter.toJson(writer, value.providerStatus)
                    }
                    writer.endObject()
                }
            }
        }

        private fun readFlexibleProviderStatus(reader: JsonReader): Map<String, String>? {
            if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
                reader.skipValue()
                return null
            }
            val map = linkedMapOf<String, String>()
            reader.beginObject()
            while (reader.hasNext()) {
                val key = reader.nextName()
                val value = when (reader.peek()) {
                    JsonReader.Token.NULL -> {
                        reader.nextNull<Any?>()
                        null
                    }
                    JsonReader.Token.BEGIN_OBJECT -> {
                        var status: String? = null
                        var msg: String? = null
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "status", "state" -> status = readScalarAsString(reader) ?: status
                                "message", "error" -> msg = readScalarAsString(reader) ?: msg
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                        status ?: msg
                    }
                    else -> readScalarAsString(reader)
                }
                if (!value.isNullOrBlank()) {
                    map[key] = value
                }
            }
            reader.endObject()
            return map
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
                        "message", "detail" -> message = readScalarAsString(reader) ?: message
                        "code" -> code = readScalarAsString(reader) ?: code
                        "provider" -> provider = readScalarAsString(reader) ?: provider
                        "error" -> {
                            when (reader.peek()) {
                                JsonReader.Token.STRING -> message = reader.nextString().takeIf { it.isNotBlank() } ?: message
                                JsonReader.Token.BEGIN_OBJECT -> {
                                    reader.beginObject()
                                    while (reader.hasNext()) {
                                        when (reader.nextName()) {
                                            "message", "detail" -> message = readScalarAsString(reader) ?: message
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
                "expires_at",
                "source_id",
                "provider_name",
                "provider",
                "audio_language"
            )
            return object : JsonAdapter<ProphecySourceDto>() {
                override fun fromJson(reader: JsonReader): ProphecySourceDto? {
                    if (reader.peek() == JsonReader.Token.NULL) return reader.nextNull()
                    var id = ""
                    var fallbackSourceId = ""
                    var label: String? = null
                    var fallbackProviderName: String? = null
                    var fallbackProvider: String? = null
                    var quality: String? = null
                    var codec: String? = null
                    var language: String? = null
                    var fallbackLanguage: String? = null
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
                                stringAdapter.fromJson(reader)?.trim().orEmpty()
                            }
                            1 -> label = if (reader.peek() == JsonReader.Token.NULL) reader.nextNull() else stringAdapter.fromJson(reader)
                            2 -> quality = if (reader.peek() == JsonReader.Token.NULL) reader.nextNull() else stringAdapter.fromJson(reader)
                            3 -> codec = if (reader.peek() == JsonReader.Token.NULL) reader.nextNull() else stringAdapter.fromJson(reader)
                            4 -> language = if (reader.peek() == JsonReader.Token.NULL) reader.nextNull() else stringAdapter.fromJson(reader)
                            5 -> sizeBytes = if (reader.peek() == JsonReader.Token.NULL) reader.nextNull() else longAdapter.fromJson(reader)
                            6 -> downloadable = if (reader.peek() == JsonReader.Token.NULL) reader.nextNull() else boolAdapter.fromJson(reader)
                            7 -> expiresAt = if (reader.peek() == JsonReader.Token.NULL) reader.nextNull() else stringAdapter.fromJson(reader)
                            8 -> fallbackSourceId = if (reader.peek() == JsonReader.Token.NULL) {
                                reader.nextNull<String?>()
                                ""
                            } else {
                                stringAdapter.fromJson(reader)?.trim().orEmpty()
                            }
                            9 -> fallbackProviderName = if (reader.peek() == JsonReader.Token.NULL) reader.nextNull() else stringAdapter.fromJson(reader)
                            10 -> fallbackProvider = if (reader.peek() == JsonReader.Token.NULL) reader.nextNull() else stringAdapter.fromJson(reader)
                            11 -> fallbackLanguage = if (reader.peek() == JsonReader.Token.NULL) reader.nextNull() else stringAdapter.fromJson(reader)
                            else -> {
                                reader.skipName()
                                reader.skipValue()
                            }
                        }
                    }
                    reader.endObject()
                    return ProphecySourceDto(
                        id = id.ifEmpty { fallbackSourceId },
                        label = label?.takeIf { it.isNotBlank() }
                            ?: fallbackProviderName?.takeIf { it.isNotBlank() }
                            ?: fallbackProvider?.takeIf { it.isNotBlank() },
                        quality = quality,
                        codec = codec,
                        language = language?.takeIf { it.isNotBlank() }
                            ?: fallbackLanguage?.takeIf { it.isNotBlank() },
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
            .add(com.example.data.d1.D1BooleanAdapterFactory)
            .addLast(KotlinJsonAdapterFactory())
            .build()
    }

    private val sharedConnectionPool: okhttp3.ConnectionPool by lazy {
        okhttp3.ConnectionPool(8, 5, TimeUnit.MINUTES)
    }

    @Volatile
    private var diskHttpCache: okhttp3.Cache? = null

    @Volatile
    private var appContext: android.content.Context? = null

    fun initializeHttpCache(context: android.content.Context) {
        val appCtx = context.applicationContext
        appContext = appCtx
        if (diskHttpCache != null) return
        synchronized(this) {
            if (diskHttpCache == null) {
                val cacheDir = java.io.File(appCtx.cacheDir, "prophecy_http_cache")
                diskHttpCache = okhttp3.Cache(cacheDir, 10L * 1024L * 1024L)
            }
        }
        d1DataSource.attachDigestStore(com.example.data.d1.SharedPreferencesD1DigestStore(appCtx))
    }

    private const val D1_BASE_URL = "https://prophecy-verse-db.newprophecy4.workers.dev/v1/"

    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(sharedConnectionPool)
            .apply { diskHttpCache?.let { cache(it) } }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val orig = chain.request()
                val builder = orig.newBuilder()
                    .header("User-Agent", "ProphecyVerse/${com.example.BuildConfig.VERSION_NAME} (Android)")
                if (orig.url.host.equals(BackendConfig.HOST, ignoreCase = true)) {
                    PvSessionManager.currentSessionId()?.let { sid ->
                        builder.header(PvSessionManager.HEADER_NAME, sid)
                    }
                }
                chain.proceed(builder.build())
            }
            .addInterceptor(HttpLoggingInterceptor().apply {
                // Keep BASIC without headers/bodies so transient URLs or tokens are never logged
                level = HttpLoggingInterceptor.Level.NONE
            })
            .build()
    }

    /**
     * Dedicated streaming OkHttpClient for Media3 OkHttpDataSource:
     * shares the warm TLS/HTTP2 connection pool, avoids OkHttp 10MB API disk cache contention,
     * enables cross-host CDN redirects, and uses callTimeout=0 so sustained Range video transfers
     * are never killed mid-stream while connect/read timeouts bound hung sockets.
     */
    val streamingOkHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(sharedConnectionPool)
            .cache(null)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    val d1OkHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(sharedConnectionPool)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .callTimeout(18, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", "ProphecyVerse/${com.example.BuildConfig.VERSION_NAME} (Android)")
                    .header("Accept", "application/json")
                    .build()
                chain.proceed(request)
            }
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

    val d1Api: com.example.data.d1.CloudflareD1ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(D1_BASE_URL)
            .client(d1OkHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(com.example.data.d1.CloudflareD1ApiService::class.java)
    }

    val d1DataSource: com.example.data.d1.CloudflareD1DataSource by lazy {
        val store = appContext?.let { com.example.data.d1.SharedPreferencesD1DigestStore(it) }
            ?: com.example.data.d1.InMemoryD1DigestStore()
        com.example.data.d1.CloudflareD1DataSource(d1Api, digestStore = store)
    }
}
