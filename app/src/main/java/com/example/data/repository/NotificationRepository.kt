package com.example.data.repository

import android.util.Log
import com.example.data.model.PushNotification
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class NotificationRepository {

    private fun getFirestore(): FirebaseFirestore? {
        return runCatching { FirebaseFirestore.getInstance() }.getOrNull()
    }

    suspend fun getNotifications(limit: Long = 30): Result<List<PushNotification>> = withContext(Dispatchers.IO) {
        val fs = getFirestore() ?: return@withContext Result.failure(
            IllegalStateException("Firebase is uninitialized.")
        )
        try {
            val snapshot = try {
                fs.collection("notifications")
                    .orderBy("created_at", Query.Direction.DESCENDING)
                    .limit(limit)
                    .get()
                    .await()
            } catch (_: Exception) {
                fs.collection("notifications").limit(limit).get().await()
            }

            val list = snapshot.documents.mapNotNull { doc ->
                try {
                    doc.toObject(PushNotification::class.java)?.copy(id = doc.id)
                } catch (e: Exception) {
                    Log.w("NotificationRepo", "Failed to deserialize notification ${doc.id}: ${e.message}")
                    null
                }
            }
            Result.success(list)
        } catch (e: Exception) {
            Log.e("NotificationRepo", "Failed to get notifications", e)
            Result.failure(e)
        }
    }

    suspend fun queueNotification(notification: PushNotification, adminEmail: String): Result<String> = withContext(Dispatchers.IO) {
        val fs = getFirestore() ?: return@withContext Result.failure(
            IllegalStateException("Firebase is uninitialized.")
        )
        try {
            val payload = mapOf(
                "event_type" to notification.eventType,
                "target" to notification.target,
                "title" to notification.title,
                "body" to notification.body,
                "deep_link" to notification.deepLink,
                "image_url" to notification.imageUrl,
                "status" to "queued",
                "created_by" to adminEmail,
                "created_at" to FieldValue.serverTimestamp()
            )

            val docRef = fs.collection("notifications").add(payload).await()
            Result.success(docRef.id)
        } catch (e: Exception) {
            Log.e("NotificationRepo", "Failed to queue push notification", e)
            Result.failure(e)
        }
    }

    suspend fun cancelQueuedNotification(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        val fs = getFirestore() ?: return@withContext Result.failure(
            IllegalStateException("Firebase is uninitialized.")
        )
        try {
            fs.collection("notifications").document(id).delete().await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
