package com.example.data.repository

import android.util.Log
import com.example.data.model.NoticeItem
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class NoticeRepository {

    private fun getFirestore(): FirebaseFirestore? {
        return runCatching { FirebaseFirestore.getInstance() }.getOrNull()
    }

    suspend fun getNotices(): Result<List<NoticeItem>> = withContext(Dispatchers.IO) {
        val fs = getFirestore() ?: return@withContext Result.failure(
            IllegalStateException("Firebase is uninitialized. Connect google-services.json to access live notices.")
        )
        try {
            val snapshot = try {
                fs.collection("notices")
                    .orderBy("created_at", Query.Direction.DESCENDING)
                    .get()
                    .await()
            } catch (_: Exception) {
                // Fallback if index or created_at is named createdAt
                try {
                    fs.collection("notices")
                        .orderBy("createdAt", Query.Direction.DESCENDING)
                        .get()
                        .await()
                } catch (_: Exception) {
                    fs.collection("notices").get().await()
                }
            }

            val list = snapshot.documents.mapNotNull { doc ->
                try {
                    doc.toObject(NoticeItem::class.java)?.copy(id = doc.id)
                } catch (e: Exception) {
                    Log.w("NoticeRepo", "Failed to deserialize notice ${doc.id}: ${e.message}")
                    null
                }
            }
            Result.success(list)
        } catch (e: Exception) {
            Log.e("NoticeRepository", "Failed to get notices", e)
            Result.failure(e)
        }
    }

    suspend fun saveNotice(notice: NoticeItem, adminEmail: String): Result<String> = withContext(Dispatchers.IO) {
        val fs = getFirestore() ?: return@withContext Result.failure(
            IllegalStateException("Firebase is uninitialized. Cannot write notice.")
        )
        try {
            val collection = fs.collection("notices")
            val docRef = if (notice.id.isNotBlank()) collection.document(notice.id) else collection.document()

            val textContent = notice.message.ifBlank { notice.content }
            val severityType = notice.severity.ifBlank { notice.type }

            val payload = mutableMapOf<String, Any>(
                // Primary fields matching User APK
                "title" to notice.title,
                "message" to textContent,
                "content" to textContent, // compatibility mirror
                "severity" to severityType,
                "type" to severityType, // compatibility mirror
                "active" to notice.active,
                "published" to notice.active, // compatibility mirror
                "priority" to notice.priority,
                "created_by" to adminEmail,
                "updated_at" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp(),
                // Remove stale values when an editor intentionally clears an optional field.
                "actionUrl" to FieldValue.delete(),
                "action_url" to FieldValue.delete(),
                "actionLabel" to FieldValue.delete(),
                "action_label" to FieldValue.delete(),
                "targetVersion" to FieldValue.delete(),
                "target_version" to FieldValue.delete()
            )

            if (!notice.actionUrl.isNullOrBlank()) {
                payload["actionUrl"] = notice.actionUrl
                payload["action_url"] = notice.actionUrl
            }
            if (!notice.actionLabel.isNullOrBlank()) {
                payload["actionLabel"] = notice.actionLabel
                payload["action_label"] = notice.actionLabel
            }
            if (!notice.targetVersion.isNullOrBlank()) {
                payload["targetVersion"] = notice.targetVersion
                payload["target_version"] = notice.targetVersion
            }

            if (notice.id.isBlank()) {
                payload["created_at"] = FieldValue.serverTimestamp()
                payload["createdAt"] = FieldValue.serverTimestamp()
            }

            docRef.set(payload, SetOptions.merge()).await()
            Result.success(docRef.id)
        } catch (e: Exception) {
            Log.e("NoticeRepository", "Failed to save notice", e)
            Result.failure(e)
        }
    }

    suspend fun deleteNotice(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        val fs = getFirestore() ?: return@withContext Result.failure(
            IllegalStateException("Firebase is uninitialized. Cannot delete notice.")
        )
        try {
            fs.collection("notices").document(id).delete().await()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("NoticeRepository", "Failed to delete notice", e)
            Result.failure(e)
        }
    }

    suspend fun togglePublish(id: String, currentPublished: Boolean): Result<Boolean> = withContext(Dispatchers.IO) {
        val fs = getFirestore() ?: return@withContext Result.failure(
            IllegalStateException("Firebase is uninitialized.")
        )
        try {
            val newStatus = !currentPublished
            fs.collection("notices").document(id).update(
                mapOf(
                    "active" to newStatus,
                    "published" to newStatus,
                    "updated_at" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(newStatus)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
