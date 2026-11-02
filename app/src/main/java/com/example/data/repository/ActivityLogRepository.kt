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
        return runCatching { FirebaseFirestore.getInstance() }.getOrNull()
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
            // 1. Write to security_logs (User APK schema: action, details, performed_by, performed_by_uid, timestamp)
            val securityPayload = mapOf(
                "action" to action,
                "details" to details,
                "performed_by" to adminEmail,
                "performed_by_uid" to adminUid,
                "timestamp" to FieldValue.serverTimestamp()
            )
            fs.collection("security_logs").add(securityPayload).await()

            // 2. Write to admin_logs (Admin APK compatibility)
            val adminPayload = mapOf(
                "adminUid" to adminUid,
                "adminEmail" to adminEmail,
                "action" to action,
                "targetType" to targetType,
                "targetId" to targetId,
                "details" to details,
                "result" to result,
                "timestamp" to FieldValue.serverTimestamp()
            )
            fs.collection("admin_logs").add(adminPayload).await()

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
            // Try security_logs first, then admin_logs
            val snapshot = try {
                fs.collection("security_logs")
                    .orderBy("timestamp", Query.Direction.DESCENDING)
                    .limit(limit)
                    .get()
                    .await()
            } catch (_: Exception) {
                fs.collection("admin_logs")
                    .orderBy("timestamp", Query.Direction.DESCENDING)
                    .limit(limit)
                    .get()
                    .await()
            }

            val logs = snapshot.documents.mapNotNull { doc ->
                try {
                    val act = doc.getString("action").orEmpty()
                    val det = doc.getString("details").orEmpty()
                    val perfBy = doc.getString("performed_by") ?: doc.getString("adminEmail").orEmpty()
                    val perfUid = doc.getString("performed_by_uid") ?: doc.getString("adminUid").orEmpty()
                    val targetType = doc.getString("targetType").orEmpty()
                    val targetId = doc.getString("targetId").orEmpty()
                    val result = doc.getString("result") ?: "SUCCESS"
                    val timestamp = doc.getTimestamp("timestamp")

                    AdminActivityLog(
                        id = doc.id,
                        action = act,
                        details = det,
                        performedBy = perfBy,
                        performedByUid = perfUid,
                        adminUid = perfUid,
                        adminEmail = perfBy,
                        targetType = targetType,
                        targetId = targetId,
                        result = result,
                        timestamp = timestamp
                    )
                } catch (e: Exception) {
                    null
                }
            }
            Result.success(logs)
        } catch (e: Exception) {
            Log.e("ActivityLogRepo", "Failed to get activity logs", e)
            Result.failure(e)
        }
    }
}
