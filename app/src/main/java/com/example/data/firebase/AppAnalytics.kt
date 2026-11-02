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
import java.util.UUID

class AppAnalytics private constructor(private val context: Context) {
    private val firebaseAnalytics: FirebaseAnalytics by lazy { FirebaseAnalytics.getInstance(context) }
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val scope = CoroutineScope(Dispatchers.IO)

    fun logEvent(eventName: String, params: Map<String, Any> = emptyMap()) {
        scope.launch {
            try {
                val bundle = Bundle()
                params.forEach { (key, value) ->
                    when (value) {
                        is String -> bundle.putString(key, value)
                        is Int -> bundle.putInt(key, value)
                        is Long -> bundle.putLong(key, value)
                        is Double -> bundle.putDouble(key, value)
                        is Boolean -> bundle.putBoolean(key, value)
                        else -> bundle.putString(key, value.toString())
                    }
                }
                firebaseAnalytics.logEvent(eventName, bundle)

                val user = AuthManager.getInstance(context).currentUser ?: return@launch
                val eventDoc = hashMapOf<String, Any>(
                    "event_name" to eventName,
                    "installation_id" to InstallationManager.getInstance(context).installationId,
                    "user_id" to user.uid,
                    "timestamp" to System.currentTimeMillis(),
                    "params" to params
                )
                firestore.collection("analytics_events")
                    .document(UUID.randomUUID().toString())
                    .set(eventDoc)
                    .await()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to log event $eventName: ${e.message}")
            }
        }
    }

    fun recordWatchTime(secondsWatched: Long, animeId: String, episodeId: String) {
        if (secondsWatched <= 0L) return
        scope.launch {
            try {
                val user = AuthManager.getInstance(context).currentUser ?: return@launch
                InstallationManager.getInstance(context).recordWatchTime(secondsWatched)
                if (!user.isAnonymous) {
                    firestore.collection("users")
                        .document(user.uid)
                        .set(
                            mapOf("total_watch_seconds" to FieldValue.increment(secondsWatched)),
                            SetOptions.merge()
                        )
                        .await()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error recording watch time: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "AppAnalytics"

        @Volatile
        private var instance: AppAnalytics? = null

        fun getInstance(context: Context): AppAnalytics {
            return instance ?: synchronized(this) {
                instance ?: AppAnalytics(context.applicationContext).also { instance = it }
            }
        }
    }
}
