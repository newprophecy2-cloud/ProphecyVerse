package com.example.data.notice

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class NoticeLanguage {
    BANGLA,
    ENGLISH
}

data class NoticeContent(
    val language: NoticeLanguage,
    val title: String,
    val streamUpdateTitle: String,
    val streamUpdateBody: String,
    val supportTitle: String,
    val supportBody: String,
    val thankYouTitle: String,
    val thankYouBody: String,
    val telegramButtonLabel: String,
    val dismissButtonLabel: String,
    val telegramUrl: String = NoticeManager.TELEGRAM_URL
)

class NoticeManager private constructor(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _isNoticeVisible = MutableStateFlow(false)
    val isNoticeVisible: StateFlow<Boolean> = _isNoticeVisible.asStateFlow()

    private val _noticeContent = MutableStateFlow(getContentForLanguage(NoticeLanguage.ENGLISH))
    val noticeContent: StateFlow<NoticeContent> = _noticeContent.asStateFlow()

    private val _detectedCountry = MutableStateFlow<String?>(null)
    val detectedCountry: StateFlow<String?> = _detectedCountry.asStateFlow()

    private var sessionDismissed = false

    // Network client with strict 3-second timeout for privacy-safe non-blocking geolocation
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.SECONDS)
            .build()
    }

    fun initNotice(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            checkAndPrepareNotice(forceShow = false)
        }
    }

    suspend fun checkAndPrepareNotice(forceShow: Boolean = false) = withContext(Dispatchers.IO) {
        if (!forceShow && sessionDismissed) {
            return@withContext
        }

        val country = resolveCountryCode()
        _detectedCountry.value = country
        // Respect English language requirement for APK UI
        val content = getContentForLanguage(NoticeLanguage.ENGLISH)
        _noticeContent.value = content

        if (forceShow || !sessionDismissed) {
            _isNoticeVisible.value = true
        }
    }

    fun showNotice() {
        _noticeContent.value = getContentForLanguage(NoticeLanguage.ENGLISH)
        _isNoticeVisible.value = true
    }

    fun dismissNotice() {
        sessionDismissed = true
        _isNoticeVisible.value = false
    }

    fun openTelegramChannel(activityContext: Context = context) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(TELEGRAM_URL)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activityContext.startActivity(intent)
        } catch (_: Exception) {
            // Safe fallback if intent resolution fails
        }
    }

    private suspend fun resolveCountryCode(): String {
        // 1. Check for testing override
        countryOverrideForTesting?.let {
            return it.trim().uppercase()
        }

        // 2. Check local persistent cache (TTL: 24 hours)
        val cachedCountry = getCachedCountry()
        val cacheTimestamp = prefs.getLong(KEY_CACHE_TIME, 0L)
        val now = System.currentTimeMillis()

        if (!cachedCountry.isNullOrBlank() && (now - cacheTimestamp < CACHE_TTL_MS)) {
            return cachedCountry
        }

        // 3. Perform background IP geolocation with 3-second timeout
        val remoteCountry = fetchCountryFromIp()
        if (!remoteCountry.isNullOrBlank()) {
            val sanitized = remoteCountry.trim().uppercase()
            saveCachedCountry(sanitized, now)
            return sanitized
        }

        // 4. Default to English / non-BD on network failure or timeout
        return cachedCountry ?: DEFAULT_COUNTRY
    }

    private fun fetchCountryFromIp(): String? {
        // Primary lightweight service: api.country.is
        try {
            val request = Request.Builder()
                .url("https://api.country.is/")
                .header("User-Agent", "ProphecyVerse/${com.example.BuildConfig.VERSION_NAME}")
                .build()
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (!body.isNullOrBlank()) {
                    val json = JSONObject(body)
                    val country = json.optString("country")
                    if (country.isNotBlank()) {
                        return country
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Primary IP geolocation unavailable: ${e.message}")
        }

        // Fallback secondary service: ipapi.co/country
        try {
            val request = Request.Builder()
                .url("https://ipapi.co/country/")
                .header("User-Agent", "ProphecyVerse/${com.example.BuildConfig.VERSION_NAME}")
                .build()
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val country = response.body?.string()?.trim()
                if (!country.isNullOrBlank() && country.length <= 3) {
                    return country
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Fallback IP geolocation unavailable: ${e.message}")
        }

        return null
    }

    private fun getCachedCountry(): String? {
        return prefs.getString(KEY_CACHED_COUNTRY, null)
    }

    private fun saveCachedCountry(country: String, timestamp: Long) {
        prefs.edit()
            .putString(KEY_CACHED_COUNTRY, country)
            .putLong(KEY_CACHE_TIME, timestamp)
            .apply()
    }

    fun clearCacheForTesting() {
        prefs.edit().clear().apply()
        sessionDismissed = false
        countryOverrideForTesting = null
        _isNoticeVisible.value = false
        _detectedCountry.value = null
    }

    companion object {
        const val TELEGRAM_URL = "https://t.me/newprophecyverse"
        private const val PREFS_NAME = "prophecy_notice_prefs"
        private const val KEY_CACHED_COUNTRY = "cached_country_code"
        private const val KEY_CACHE_TIME = "cached_country_timestamp"
        private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L // 24 Hours
        private const val DEFAULT_COUNTRY = "US"
        private const val TAG = "NoticeManager"

        @Volatile
        private var instance: NoticeManager? = null

        var countryOverrideForTesting: String? = null

        fun getInstance(context: Context): NoticeManager {
            return instance ?: synchronized(this) {
                instance ?: NoticeManager(context.applicationContext).also { instance = it }
            }
        }

        fun getContentForCountry(countryCode: String?): NoticeContent {
            val isBangladesh = countryCode?.trim()?.equals("BD", ignoreCase = true) == true
            return if (isBangladesh) {
                getContentForLanguage(NoticeLanguage.BANGLA)
            } else {
                getContentForLanguage(NoticeLanguage.ENGLISH)
            }
        }

        fun getContentForLanguage(language: NoticeLanguage): NoticeContent {
            return when (language) {
                NoticeLanguage.BANGLA -> NoticeContent(
                    language = NoticeLanguage.BANGLA,
                    title = "📢 জরুরি নোটিশ",
                    streamUpdateTitle = "🎬 অ্যানিমে স্ট্রিমিং ও পর্ব আপডেট",
                    streamUpdateBody = "Prophecy Verse-এ সমস্ত নতুন ও ট্রেন্ডিং অ্যানিমে নিয়মিত আপডেট করা হচ্ছে। যদি কোনো পর্ব প্লেব্যাক হতে সমস্যা হয়, তবে অনুগ্রহ করে কিছু সময় পর আবার চেষ্টা করুন অথবা ভিন্ন সোর্স নির্বাচন করুন।",
                    supportTitle = "💬 অফিসিয়াল টেলিগ্রাম চ্যানেল ও সাপোর্ট",
                    supportBody = "যে কোনো নতুন আপডেট, অ্যানিমে রিকোয়েস্ট এবং সাহায্যের জন্য আমাদের অফিশিয়াল টেলিগ্রাম চ্যানেলে যুক্ত থাকুন।",
                    thankYouTitle = "💜 পাশে থাকার জন্য ধন্যবাদ",
                    thankYouBody = "Prophecy Verse ব্যবহার করার জন্য এবং আমাদের কমিউনিটিকে সমর্থন করার জন্য আপনাকে আন্তরিক ধন্যবাদ!",
                    telegramButtonLabel = "টেলিগ্রাম চ্যানেলে যুক্ত হন",
                    dismissButtonLabel = "ঠিক আছে"
                )
                NoticeLanguage.ENGLISH -> NoticeContent(
                    language = NoticeLanguage.ENGLISH,
                    title = "📢 Important Notice",
                    streamUpdateTitle = "🎬 Anime Streaming & Episode Updates",
                    streamUpdateBody = "All latest and trending anime episodes are continuously updated on Prophecy Verse. If you experience any playback issues, please retry shortly or switch to an alternate streaming source.",
                    supportTitle = "💬 Official Support & Community",
                    supportBody = "Join our official Telegram channel for the latest releases, anime requests, server status, and community support.",
                    thankYouTitle = "💜 Thank You for Being With Us",
                    thankYouBody = "Thank you for choosing Prophecy Verse and being an awesome part of our anime community!",
                    telegramButtonLabel = "Join Telegram Channel",
                    dismissButtonLabel = "Got It"
                )
            }
        }
    }
}
