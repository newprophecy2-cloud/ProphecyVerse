package com.example.data.notice

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.example.BuildConfig
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** A notice as published by the Admin APK in notices/{noticeId}. */
data class PublishedNotice(
    val id: String,
    val title: String,
    val message: String,
    val severity: String,
    val priority: Long,
    val actionUrl: String?,
    val actionLabel: String?
)

data class NoticeContent(
    val notices: List<PublishedNotice> = emptyList(),
    val isLoading: Boolean = true,
    val hasLoaded: Boolean = false,
    val errorMessage: String? = null
)

/** Pure parser shared by the Firestore listener and JVM tests. */
internal fun parsePublishedNotice(id: String, data: Map<String, Any?>): PublishedNotice? {
    val published = data["published"] as? Boolean ?: false
    if (!published) return null
    val active = data["active"] as? Boolean ?: published
    if (!active) return null

    val title = (data["title"] as? String).orEmpty().trim()
    val message = ((data["message"] as? String) ?: (data["content"] as? String)).orEmpty().trim()
    if (title.isBlank() && message.isBlank()) return null

    val severity = ((data["severity"] as? String) ?: (data["type"] as? String) ?: "info").trim()
    val priority = (data["priority"] as? Number)?.toLong() ?: 0L
    val actionUrl = ((data["actionUrl"] as? String) ?: (data["action_url"] as? String))
        ?.trim()?.takeIf(String::isNotBlank)
    val actionLabel = ((data["actionLabel"] as? String) ?: (data["action_label"] as? String))
        ?.trim()?.takeIf(String::isNotBlank)

    return PublishedNotice(id, title.ifBlank { "Notice" }, message, severity, priority, actionUrl, actionLabel)
}

class NoticeManager private constructor(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.SECONDS)
        .build()

    private val _isNoticeVisible = MutableStateFlow(false)
    val isNoticeVisible: StateFlow<Boolean> = _isNoticeVisible.asStateFlow()

    private val _noticeContent = MutableStateFlow(NoticeContent())
    val noticeContent: StateFlow<NoticeContent> = _noticeContent.asStateFlow()

    // Used by InstallationManager for country metadata; retained independently from notice content.
    private val _detectedCountry = MutableStateFlow<String?>(null)
    val detectedCountry: StateFlow<String?> = _detectedCountry.asStateFlow()

    private var listenerRegistration: ListenerRegistration? = null
    private var sessionDismissed = false

    suspend fun checkAndPrepareNotice(forceShow: Boolean = false) = withContext(Dispatchers.IO) {
        _detectedCountry.value = resolveCountryCode()
        startNoticeListener()
        if (forceShow) _isNoticeVisible.value = true
    }

    private fun startNoticeListener() {
        if (listenerRegistration != null) return
        _noticeContent.value = _noticeContent.value.copy(isLoading = true, errorMessage = null)
        try {
            // Admin Firestore rules permit ordinary clients to read published notices.
            // Constrain this query to published=true so it remains valid under those rules.
            listenerRegistration = FirebaseFirestore.getInstance()
                .collection(NOTICES_COLLECTION)
                .whereEqualTo("published", true)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.e(TAG, "Failed to listen for published notices", error)
                        _noticeContent.value = _noticeContent.value.copy(
                            isLoading = false,
                            errorMessage = "Unable to load published notices. Check your connection or access and retry."
                        )
                        return@addSnapshotListener
                    }
                    if (snapshot == null) {
                        _noticeContent.value = _noticeContent.value.copy(
                            isLoading = false,
                            errorMessage = "The notices response was empty. Please retry."
                        )
                        return@addSnapshotListener
                    }

                    val notices = snapshot.documents.mapNotNull { document ->
                        runCatching { parsePublishedNotice(document.id, document.data.orEmpty()) }
                            .onFailure { Log.w(TAG, "Skipping malformed notice ${document.id}", it) }
                            .getOrNull()
                    }.sortedWith(compareByDescending<PublishedNotice> { it.priority }.thenBy { it.id })

                    _noticeContent.value = NoticeContent(
                        notices = notices,
                        isLoading = false,
                        hasLoaded = true,
                        errorMessage = null
                    )
                    LocalNotificationScheduler.schedule(context, notices)
                    if (notices.isNotEmpty() && !sessionDismissed) {
                        _isNoticeVisible.value = true
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Unable to attach notice listener", e)
            _noticeContent.value = _noticeContent.value.copy(
                isLoading = false,
                errorMessage = "Unable to connect to published notices. Please retry."
            )
        }
    }

    fun retryNotices() {
        listenerRegistration?.remove()
        listenerRegistration = null
        _noticeContent.value = NoticeContent()
        startNoticeListener()
    }

    fun showNotice() {
        sessionDismissed = false
        _isNoticeVisible.value = true
        startNoticeListener()
    }

    fun dismissNotice() {
        sessionDismissed = true
        _isNoticeVisible.value = false
    }

    fun openNoticeAction(url: String, activityContext: Context = context) {
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        if (uri?.scheme != "https" || uri.host.isNullOrBlank()) return
        try {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            activityContext.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to open notice action", e)
        }
    }

    private suspend fun resolveCountryCode(): String {
        countryOverrideForTesting?.let { return it.trim().uppercase() }
        val cachedCountry = prefs.getString(KEY_CACHED_COUNTRY, null)
        val cacheTimestamp = prefs.getLong(KEY_CACHE_TIME, 0L)
        val now = System.currentTimeMillis()
        if (!cachedCountry.isNullOrBlank() && now - cacheTimestamp < CACHE_TTL_MS) return cachedCountry

        val remoteCountry = fetchCountryFromIp()
        if (!remoteCountry.isNullOrBlank()) {
            val sanitized = remoteCountry.trim().uppercase()
            prefs.edit().putString(KEY_CACHED_COUNTRY, sanitized).putLong(KEY_CACHE_TIME, now).apply()
            return sanitized
        }
        return cachedCountry ?: DEFAULT_COUNTRY
    }

    private fun fetchCountryFromIp(): String? {
        try {
            val request = Request.Builder()
                .url("https://api.country.is/")
                .header("User-Agent", "ProphecyVerse/${BuildConfig.VERSION_NAME}")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = response.body?.string()
                    val country = if (!json.isNullOrBlank()) JSONObject(json).optString("country") else ""
                    if (country.isNotBlank()) return country
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Primary IP geolocation unavailable: ${e.message}")
        }
        try {
            val request = Request.Builder()
                .url("https://ipapi.co/country/")
                .header("User-Agent", "ProphecyVerse/${BuildConfig.VERSION_NAME}")
                .build()
            httpClient.newCall(request).execute().use { response ->
                val country = if (response.isSuccessful) response.body?.string()?.trim() else null
                if (!country.isNullOrBlank() && country.length <= 3) return country
            }
        } catch (e: Exception) {
            Log.d(TAG, "Fallback IP geolocation unavailable: ${e.message}")
        }
        return null
    }

    fun initNotice(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) { checkAndPrepareNotice(forceShow = false) }
    }

    fun clearCacheForTesting() {
        listenerRegistration?.remove()
        listenerRegistration = null
        prefs.edit().clear().apply()
        sessionDismissed = false
        countryOverrideForTesting = null
        _isNoticeVisible.value = false
        _detectedCountry.value = null
        _noticeContent.value = NoticeContent()
    }

    companion object {
        const val TELEGRAM_URL = "https://t.me/newprophecyverse"
        private const val PREFS_NAME = "prophecy_notice_prefs"
        private const val KEY_CACHED_COUNTRY = "cached_country_code"
        private const val KEY_CACHE_TIME = "cached_country_timestamp"
        private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L
        private const val DEFAULT_COUNTRY = "US"
        private const val TAG = "NoticeManager"
        private const val NOTICES_COLLECTION = "notices"

        @Volatile
        private var instance: NoticeManager? = null
        var countryOverrideForTesting: String? = null

        fun getInstance(context: Context): NoticeManager {
            return instance ?: synchronized(this) {
                instance ?: NoticeManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
