package com.example.data.firebase

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import com.example.BuildConfig
import com.example.data.metrics.DatabaseMetricsTracker
import com.example.data.notice.NoticeManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import java.util.UUID

class InstallationManager private constructor(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("prophecy_install_prefs", Context.MODE_PRIVATE)
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val metricsTracker: DatabaseMetricsTracker = DatabaseMetricsTracker.getInstance()
    private val scope = CoroutineScope(Dispatchers.IO)
    private val syncMutex = Mutex()

    val installationId: String
    val currentSessionId: String = UUID.randomUUID().toString()
    val sessionStartTimestamp: Long = System.currentTimeMillis()

    init {
        var existing = prefs.getString("installation_uuid", null)
        if (existing.isNullOrBlank()) {
            existing = UUID.randomUUID().toString()
            prefs.edit().putString("installation_uuid", existing).apply()
        }
        installationId = existing
    }

    private fun registrationDocumentId(ownerUid: String): String = "${ownerUid}_$installationId"

    @Volatile
    private var syncedOwnerUidInSession: String? = null

    fun needsSync(): Boolean {
        val uid = auth.currentUser?.uid
        return prefs.getBoolean(KEY_PENDING_SYNC, true) || (uid != null && syncedOwnerUidInSession != uid)
    }

    fun initializeAndSync() {
        scope.launch {
            initializeAndSyncSuspend()
        }
    }

    suspend fun ensureInstallationSynced(): Boolean {
        if (!needsSync()) return true
        return initializeAndSyncSuspend()
    }

    suspend fun initializeAndSyncSuspend(): Boolean = syncMutex.withLock {
        prefs.edit().putBoolean(KEY_PENDING_SYNC, true).apply()
        try {
            val ownerUid = auth.currentUser?.uid ?: try {
                auth.signInAnonymously().await().user?.uid
            } catch (e: Exception) {
                Log.w(TAG, "Guest sign-in not yet complete for installation sync: ${e.message}")
                null
            } ?: return@withLock false
            val refreshedToken = try {
                FirebaseMessaging.getInstance().token.await()
            } catch (e: Exception) {
                Log.w(TAG, "Could not refresh FCM token; using cached value")
                null
            }
            if (!refreshedToken.isNullOrBlank()) {
                prefs.edit().putString("fcm_token", refreshedToken).apply()
            }

            val now = System.currentTimeMillis()
            var firstSeen = prefs.getLong("first_seen_timestamp", 0L)
            if (firstSeen == 0L) {
                firstSeen = now
                prefs.edit().putLong("first_seen_timestamp", firstSeen).apply()
            }

            val cachedCountry = NoticeManager.getInstance(context).detectedCountry.value
                ?: Locale.getDefault().country.takeIf { it.isNotBlank() } ?: "US"

            val tokenValue = prefs.getString("fcm_token", null)
            val tokenStatus = if (!tokenValue.isNullOrBlank()) "registered" else "pending"

            val docData = hashMapOf<String, Any>(
                "owner_uid" to ownerUid,
                "installation_id" to installationId,
                "session_id" to currentSessionId,
                "session_start_at" to sessionStartTimestamp,
                "device_manufacturer" to Build.MANUFACTURER,
                "device_model" to Build.MODEL,
                "device_brand" to Build.BRAND,
                "android_version" to Build.VERSION.RELEASE,
                "sdk_int" to Build.VERSION.SDK_INT,
                "app_version_name" to BuildConfig.VERSION_NAME,
                "app_version_code" to BuildConfig.VERSION_CODE,
                "locale" to Locale.getDefault().toLanguageTag(),
                "country_code" to cachedCountry.uppercase(),
                "installed_at" to firstSeen,
                "last_active_at" to now,
                "last_heartbeat_at" to FieldValue.serverTimestamp(),
                "activity_status" to "active",
                "fcm_status" to tokenStatus,
            )
            if (!tokenValue.isNullOrBlank()) {
                docData["fcm_token"] = tokenValue
            }

            FirestoreRequestGuard.execute("sync installation") {
                firestore.collection("installations")
                    .document(registrationDocumentId(ownerUid))
                    .set(docData, SetOptions.merge())
            }
            metricsTracker.recordFirestoreWrite()
            syncedOwnerUidInSession = ownerUid
            prefs.edit().putBoolean(KEY_PENDING_SYNC, false).apply()
            true
        } catch (e: Exception) {
            metricsTracker.recordFirestoreError()
            // Keep the pending marker so the next launch/heartbeat retries the same
            // stable installation record without creating a second identity.
            Log.w(TAG, "Failed to sync installation metadata: ${e.message}")
            false
        }
    }

    fun updateFcmToken(token: String) {
        if (token.isBlank()) return
        val previousToken = prefs.getString("fcm_token", null)
        if (token == previousToken && !needsSync()) {
            metricsTracker.recordFirestoreWriteSkipped()
            return
        }
        prefs.edit().putString("fcm_token", token).apply()
        initializeAndSync()
    }

    fun recordHeartbeat() {
        scope.launch {
            try {
                val ownerUid = auth.currentUser?.uid ?: return@launch
                val now = System.currentTimeMillis()
                val lastHeartbeatAt = prefs.getLong(KEY_LAST_HEARTBEAT_AT, 0L)
                val lastHeartbeatUid = prefs.getString(KEY_LAST_HEARTBEAT_UID, null)
                val lastHeartbeatVersion = prefs.getInt(KEY_LAST_HEARTBEAT_VERSION, -1)
                if (lastHeartbeatUid == ownerUid &&
                    lastHeartbeatVersion == BuildConfig.VERSION_CODE &&
                    (now - lastHeartbeatAt) in 0 until HEARTBEAT_COOLDOWN_MS
                ) {
                    metricsTracker.recordFirestoreWriteSkipped()
                    return@launch
                }
                val cachedCountry = NoticeManager.getInstance(context).detectedCountry.value
                    ?: Locale.getDefault().country.takeIf { it.isNotBlank() } ?: "US"

                FirestoreRequestGuard.execute("installation heartbeat") {
                    firestore.collection("installations")
                        .document(registrationDocumentId(ownerUid))
                        .set(
                            mapOf(
                            "owner_uid" to ownerUid,
                            "installation_id" to installationId,
                            "last_active_at" to now,
                            "last_heartbeat_at" to FieldValue.serverTimestamp(),
                            "activity_status" to "active",
                            "app_version_name" to BuildConfig.VERSION_NAME,
                            "app_version_code" to BuildConfig.VERSION_CODE,
                            "country_code" to cachedCountry.uppercase(),
                            ),
                            SetOptions.merge()
                        )
                }
                metricsTracker.recordFirestoreWrite()
                prefs.edit()
                    .putLong(KEY_LAST_HEARTBEAT_AT, now)
                    .putString(KEY_LAST_HEARTBEAT_UID, ownerUid)
                    .putInt(KEY_LAST_HEARTBEAT_VERSION, BuildConfig.VERSION_CODE)
                    .apply()
            } catch (e: Exception) {
                metricsTracker.recordFirestoreError()
                Log.w(TAG, "Installation heartbeat failed: ${e.message}")
            }
        }
    }

    fun recordWatchTime(secondsWatched: Long) {
        if (secondsWatched <= 0L) return
        scope.launch {
            try {
                val ownerUid = auth.currentUser?.uid ?: return@launch
                FirestoreRequestGuard.execute("record installation watch time") {
                    firestore.collection("installations")
                        .document(registrationDocumentId(ownerUid))
                        .set(
                            mapOf(
                            "owner_uid" to ownerUid,
                            "installation_id" to installationId,
                            "total_watch_seconds" to FieldValue.increment(secondsWatched),
                            "last_active_at" to System.currentTimeMillis(),
                            "last_heartbeat_at" to FieldValue.serverTimestamp(),
                            "activity_status" to "active"
                            ),
                            SetOptions.merge()
                        )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Installation watch-time update failed: ${e.message}")
            }
        }
    }

    suspend fun deleteCurrentRegistration() {
        val ownerUid = auth.currentUser?.uid ?: return
        try {
            FirestoreRequestGuard.execute("delete installation registration") {
                firestore.collection("installations")
                    .document(registrationDocumentId(ownerUid))
                    .delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not remove prior installation registration: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "InstallationManager"
        private const val KEY_PENDING_SYNC = "pending_installation_sync"
        private const val KEY_LAST_HEARTBEAT_AT = "last_heartbeat_at_ms"
        private const val KEY_LAST_HEARTBEAT_UID = "last_heartbeat_uid"
        private const val KEY_LAST_HEARTBEAT_VERSION = "last_heartbeat_version"
        private const val HEARTBEAT_COOLDOWN_MS = 15 * 60 * 1000L

        @Volatile
        private var instance: InstallationManager? = null

        fun getInstance(context: Context): InstallationManager {
            return instance ?: synchronized(this) {
                instance ?: InstallationManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
