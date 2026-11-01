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
        return try {
            if (FirebaseApp.getApps(com.google.firebase.FirebaseApp.getInstance().applicationContext).isNotEmpty()) {
                FirebaseFirestore.getInstance()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun getNotices(): Result<List<NoticeItem>> = withContext(Dispatchers.IO) {
        val fs = getFirestore() ?: return@withContext Result.failure(
            IllegalStateException("Firebase is uninitialized. Connect google-services.json to access live notices.")
        )
        try {
            val snapshot = fs.collection("notices")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .get()
                .await()
            val list = snapshot.documents.mapNotNull { doc ->
                doc.toObject(NoticeItem::class.java)?.copy(id = doc.id)
            }
            Result.success(list)
        } catch (e: Exception) {
            Log.e("NoticeRepository", "Failed to get notices", e)
            Result.failure(e)
        }
    }

    suspend fun saveNotice(notice: NoticeItem): Result<String> = withContext(Dispatchers.IO) {
        val fs = getFirestore() ?: return@withContext Result.failure(
            IllegalStateException("Firebase is uninitialized. Cannot write notice.")
        )
        try {
            val collection = fs.collection("notices")
            val docRef = if (notice.id.isNotBlank()) collection.document(notice.id) else collection.document()
            val payload = mapOf(
                "title" to notice.title,
                "content" to notice.content,
                "type" to notice.type,
                "published" to notice.published,
                "priority" to notice.priority,
                "actionUrl" to notice.actionUrl,
                "actionLabel" to notice.actionLabel,
                "targetVersion" to notice.targetVersion,
                "updatedAt" to FieldValue.serverTimestamp()
            ).toMutableMap()

            if (notice.id.isBlank()) {
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
                    "published" to newStatus,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(newStatus)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
