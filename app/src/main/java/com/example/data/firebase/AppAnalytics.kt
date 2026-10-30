package com.example.data.firebase

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class AppAnalytics private constructor(private val context: Context) {
    private val firebaseAnalytics: FirebaseAnalytics by lazy { FirebaseAnalytics.getInstance(context) }
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val scope = CoroutineScope(Dispatchers.IO)

    fun logEvent(eventName: String, params: Map<String, Any> = emptyMap()) {
        scope.launch {
            try {
                // 1. Firebase Analytics SDK
                val bundle = Bundle()
                params.forEach { (k, v) ->
                    when (v) {
                        is String -> bundle.putString(k, v)
                        is Int -> bundle.putInt(k, v)
                        is Long -> bundle.putLong(k, v)
                        is Double -> bundle.putDouble(k, v)
                        is Boolean -> bundle.putBoolean(k, v)
                        else -> bundle.putString(k, v.toString())
                    }
                }
                firebaseAnalytics.logEvent(eventName, bundle)

                // 2. Real-time Firestore analytics log for instant Admin APK view
                val instId = InstallationManager.getInstance(context).installationId
                val uid = AuthManager.getInstance(context).currentUid
                val eventDoc = hashMapOf<String, Any>(
                    "event_name" to eventName,
                    "installation_id" to instId,
                    "user_id" to uid,
                    "timestamp" to System.currentTimeMillis(),
                    "params" to params
                )
                firestore.collection("analytics_events")
                    .document(UUID.randomUUID().toString())
                    .set(eventDoc)
                    .await()
            } catch (e: Exception) {
                Log.w("AppAnalytics", "Failed to log event $eventName: ${e.message}")
            }
        }
    }

    fun recordWatchTime(secondsWatched: Long, animeId: String, episodeId: String) {
        if (secondsWatched <= 0) return
        scope.launch {
            try {
                val instId = InstallationManager.getInstance(context).installationId
                val uid = AuthManager.getInstance(context).currentUid
                val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

                // 1. Increment user's total watch time
                if (uid.isNotEmpty() && !uid.startsWith("guest_")) {
                    firestore.collection("users")
                        .document(uid)
                        .set(
                            mapOf("total_watch_seconds" to FieldValue.increment(secondsWatched)),
                            SetOptions.merge()
                        )
                }

                // 2. Increment installation's total watch time
                firestore.collection("installations")
                    .document(instId)
                    .set(
                        mapOf(
                            "total_watch_seconds" to FieldValue.increment(secondsWatched),
                            "last_active_at" to System.currentTimeMillis()
                        ),
                        SetOptions.merge()
                    )

                // 3. Increment daily aggregated metrics
                firestore.collection("analytics_daily")
                    .document(today)
                    .set(
                        mapOf(
                            "date" to today,
                            "total_watch_seconds" to FieldValue.increment(secondsWatched),
                            "last_updated_at" to System.currentTimeMillis()
                        ),
                        SetOptions.merge()
                    )
            } catch (e: Exception) {
                Log.w("AppAnalytics", "Error recording watch time: ${e.message}")
            }
        }
    }

    companion object {
        @Volatile
        private var instance: AppAnalytics? = null

        fun getInstance(context: Context): AppAnalytics {
            return instance ?: synchronized(this) {
                instance ?: AppAnalytics(context.applicationContext).also { instance = it }
            }
        }
    }
}
