package com.example.data.firebase

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import com.example.BuildConfig
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
import java.util.Locale
import java.util.UUID

class InstallationManager private constructor(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("prophecy_install_prefs", Context.MODE_PRIVATE)
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val scope = CoroutineScope(Dispatchers.IO)

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

    fun initializeAndSync() {
        scope.launch {
            try {
                val ownerUid = auth.currentUser?.uid ?: "guest_$installationId"
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
                    ?: Locale.getDefault().country.ifBlank { "BD" }

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

                firestore.collection("installations")
                    .document(registrationDocumentId(ownerUid))
                    .set(docData, SetOptions.merge())
                    .await()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync installation metadata: ${e.message}")
            }
        }
    }

    fun updateFcmToken(token: String) {
        if (token.isBlank()) return
        prefs.edit().putString("fcm_token", token).apply()
        initializeAndSync()
    }

    fun recordHeartbeat() {
        scope.launch {
            try {
                val ownerUid = auth.currentUser?.uid ?: "guest_$installationId"
                val now = System.currentTimeMillis()
                val cachedCountry = NoticeManager.getInstance(context).detectedCountry.value
                    ?: Locale.getDefault().country.ifBlank { "BD" }

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
                    ).await()
            } catch (e: Exception) {
                Log.w(TAG, "Installation heartbeat failed: ${e.message}")
            }
        }
    }

    fun recordWatchTime(secondsWatched: Long) {
        if (secondsWatched <= 0L) return
        scope.launch {
            try {
                val ownerUid = auth.currentUser?.uid ?: "guest_$installationId"
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
                    ).await()

                // Also update user's profile watch time
                if (ownerUid.isNotBlank() && !ownerUid.startsWith("guest_")) {
                    firestore.collection("users")
                        .document(ownerUid)
                        .set(
                            mapOf("total_watch_seconds" to FieldValue.increment(secondsWatched)),
                            SetOptions.merge()
                        ).await()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Installation watch-time update failed: ${e.message}")
            }
        }
    }

    suspend fun deleteCurrentRegistration() {
        val ownerUid = auth.currentUser?.uid ?: return
        try {
            firestore.collection("installations")
                .document(registrationDocumentId(ownerUid))
                .delete()
                .await()
        } catch (e: Exception) {
            Log.w(TAG, "Could not remove prior installation registration: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "InstallationManager"

        @Volatile
        private var instance: InstallationManager? = null

        fun getInstance(context: Context): InstallationManager {
            return instance ?: synchronized(this) {
                instance ?: InstallationManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
