package com.example.data.repository

import android.util.Log
import com.example.data.model.AdminActivityLog
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class ActivityLogRepository {

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

    suspend fun logActivity(
        adminUid: String,
        adminEmail: String,
        action: String,
        targetType: String,
        targetId: String,
        details: String,
        result: String = "SUCCESS"
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val fs = getFirestore() ?: return@withContext Result.failure(
            IllegalStateException("Firebase is uninitialized.")
        )
        try {
            val payload = mapOf(
                "adminUid" to adminUid,
                "adminEmail" to adminEmail,
                "action" to action,
                "targetType" to targetType,
                "targetId" to targetId,
                "details" to details,
                "result" to result,
                "timestamp" to FieldValue.serverTimestamp()
            )
            fs.collection("admin_logs").add(payload).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("ActivityLogRepo", "Failed to record activity log", e)
            Result.failure(e)
        }
    }

    suspend fun getRecentLogs(limit: Long = 50): Result<List<AdminActivityLog>> = withContext(Dispatchers.IO) {
        val fs = getFirestore() ?: return@withContext Result.failure(
            IllegalStateException("Firebase is uninitialized. Connect google-services.json to access audit trail.")
        )
        try {
            val snapshot = fs.collection("admin_logs")
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .limit(limit)
                .get()
                .await()

            val logs = snapshot.documents.mapNotNull { doc ->
                doc.toObject(AdminActivityLog::class.java)?.copy(id = doc.id)
            }
            Result.success(logs)
        } catch (e: Exception) {
            Log.e("ActivityLogRepo", "Failed to get activity logs", e)
            Result.failure(e)
        }
    }
}
