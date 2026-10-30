package com.example.data.firebase

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Locale
import java.util.UUID

class InstallationManager private constructor(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("prophecy_install_prefs", Context.MODE_PRIVATE)
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val scope = CoroutineScope(Dispatchers.IO)

    val installationId: String

    init {
        var existing = prefs.getString("installation_uuid", null)
        if (existing.isNullOrBlank()) {
            existing = UUID.randomUUID().toString()
            prefs.edit().putString("installation_uuid", existing).apply()
        }
        installationId = existing
    }

    fun initializeAndSync() {
        scope.launch {
            try {
                val firstSeen = prefs.getLong("first_seen_timestamp", 0L)
                val isFirstLaunch = firstSeen == 0L
                val now = System.currentTimeMillis()
                if (isFirstLaunch) {
                    prefs.edit().putLong("first_seen_timestamp", now).apply()
                }

                val docData = hashMapOf<String, Any>(
                    "installation_id" to installationId,
                    "device_manufacturer" to Build.MANUFACTURER,
                    "device_model" to Build.MODEL,
                    "device_brand" to Build.BRAND,
                    "android_version" to Build.VERSION.RELEASE,
                    "sdk_int" to Build.VERSION.SDK_INT,
                    "app_version_name" to "1.0",
                    "app_version_code" to 1,
                    "locale" to Locale.getDefault().toLanguageTag(),
                    "last_active_at" to now,
                    "status" to "active"
                )

                if (isFirstLaunch) {
                    docData["installed_at"] = now
                }

                val token = prefs.getString("fcm_token", null)
                if (!token.isNullOrBlank()) {
                    docData["fcm_token"] = token
                }

                firestore.collection("installations")
                    .document(installationId)
                    .set(docData, SetOptions.merge())
                    .await()

                Log.d("InstallationManager", "Installation synced: $installationId")
            } catch (e: Exception) {
                Log.w("InstallationManager", "Failed to sync installation info: ${e.message}")
            }
        }
    }

    fun updateFcmToken(token: String) {
        prefs.edit().putString("fcm_token", token).apply()
        scope.launch {
            try {
                firestore.collection("installations")
                    .document(installationId)
                    .set(
                        mapOf(
                            "fcm_token" to token,
                            "fcm_updated_at" to System.currentTimeMillis()
                        ),
                        SetOptions.merge()
                    ).await()
            } catch (e: Exception) {
                Log.w("InstallationManager", "Failed to update FCM token in installation: ${e.message}")
            }
        }
    }

    fun recordHeartbeat() {
        scope.launch {
            try {
                val now = System.currentTimeMillis()
                firestore.collection("installations")
                    .document(installationId)
                    .set(
                        mapOf(
                            "last_active_at" to now,
                            "status" to "active"
                        ),
                        SetOptions.merge()
                    ).await()
            } catch (e: Exception) {
                // Heartbeat silent fail is normal when offline
            }
        }
    }

    companion object {
        @Volatile
        private var instance: InstallationManager? = null

        fun getInstance(context: Context): InstallationManager {
            return instance ?: synchronized(this) {
                instance ?: InstallationManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
