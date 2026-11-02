package com.example.data.repository

import android.util.Log
import com.example.data.model.UserAccount
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.DocumentSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

data class UserCounts(
    val total: Int = 0,
    val active: Int = 0,
    val suspended: Int = 0
)

data class UserPage(
    val users: List<UserAccount>,
    val lastDocument: DocumentSnapshot?,
    val hasMore: Boolean
)

class UserRepository {

    private fun getFirestore(): FirebaseFirestore? {
        return runCatching { FirebaseFirestore.getInstance() }.getOrNull()
    }

    suspend fun getUsers(pageSize: Long = 50): Result<List<UserAccount>> =
        getUserPage(pageSize = pageSize).map { it.users }

    suspend fun getUserPage(
        pageSize: Long = 50,
        startAfter: DocumentSnapshot? = null
    ): Result<UserPage> = withContext(Dispatchers.IO) {
        val fs = getFirestore() ?: return@withContext Result.failure(
            IllegalStateException("Firebase is uninitialized. Connect google-services.json to view real users.")
        )
        try {
            // Use the document ID as a stable cursor; do not order by last_login_at,
            // because that would omit valid profiles where AuthManager hasn't written it yet.
            var query: Query = fs.collection("users")
                .orderBy(FieldPath.documentId(), Query.Direction.ASCENDING)
                .limit(pageSize + 1)
            startAfter?.let { query = query.startAfter(it) }
            val snapshot = query.get().await()
            val hasMore = snapshot.documents.size.toLong() > pageSize
            val pageDocuments = snapshot.documents.take(pageSize.toInt())

            val users = pageDocuments.mapNotNull { doc ->
                runCatching { parseUserAccount(doc) }
                    .onFailure { Log.w("UserRepository", "Failed to read user doc ${doc.id}: ${it.message}") }
                    .getOrNull()
            }
            Result.success(
                UserPage(
                    users = users.sortedByDescending { it.lastLoginMillis },
                    lastDocument = pageDocuments.lastOrNull().takeIf { hasMore },
                    hasMore = hasMore
                )
            )
        } catch (e: Exception) {
            Log.e("UserRepository", "Failed to fetch users", e)
            Result.failure(e)
        }
    }

    suspend fun getUserCounts(): Result<UserCounts> = withContext(Dispatchers.IO) {
        val fs = getFirestore() ?: return@withContext Result.failure(
            IllegalStateException("Firebase is uninitialized.")
        )
        try {
            var total = 0
            var suspended = 0
            var cursor: DocumentSnapshot? = null
            while (true) {
                var query: Query = fs.collection("users")
                    .orderBy(FieldPath.documentId(), Query.Direction.ASCENDING)
                    .limit(500)
                cursor?.let { query = query.startAfter(it) }
                val snapshot = query.get().await()
                if (snapshot.isEmpty) break
                total += snapshot.size()
                suspended += snapshot.documents.count {
                    it.getString("status").equals("suspended", ignoreCase = true)
                }
                cursor = snapshot.documents.lastOrNull()
                if (snapshot.size() < 500) break
            }
            Result.success(UserCounts(total = total, active = total - suspended, suspended = suspended))
        } catch (e: Exception) {
            Log.e("UserRepository", "Failed to count user profiles", e)
            Result.failure(e)
        }
    }

    suspend fun searchUsers(query: String): Result<List<UserAccount>> = withContext(Dispatchers.IO) {
        val fs = getFirestore() ?: return@withContext Result.failure(
            IllegalStateException("Firebase is uninitialized.")
        )
        try {
            val cleanQuery = query.trim()
            if (cleanQuery.isBlank()) {
                return@withContext getUsers()
            }

            // 1. Direct document lookup by UID
            val doc = fs.collection("users").document(cleanQuery).get().await()
            if (doc.exists()) {
                return@withContext Result.success(listOf(parseUserAccount(doc)))
            }

            // 2. Search by email prefix
            val emailSnapshot = try {
                fs.collection("users")
                    .whereGreaterThanOrEqualTo("email", cleanQuery)
                    .whereLessThanOrEqualTo("email", cleanQuery + "\uf8ff")
                    .limit(25)
                    .get()
                    .await()
            } catch (_: Exception) {
                null
            }

            val found = emailSnapshot?.documents?.mapNotNull { d ->
                runCatching { parseUserAccount(d) }.getOrNull()
            }.orEmpty()

            if (found.isNotEmpty()) {
                return@withContext Result.success(found)
            }

            // 3. Fallback in-memory search over current collection
            val allSnapshot = fs.collection("users").limit(100).get().await()
            val matches = allSnapshot.documents.mapNotNull { d ->
                runCatching { parseUserAccount(d) }.getOrNull()
            }.filter { u ->
                u.email.contains(cleanQuery, ignoreCase = true) ||
                u.uid.contains(cleanQuery, ignoreCase = true) ||
                u.installationId.contains(cleanQuery, ignoreCase = true) ||
                u.displayName.contains(cleanQuery, ignoreCase = true)
            }

            Result.success(matches)
        } catch (e: Exception) {
            Log.e("UserRepository", "Failed to search users", e)
            Result.failure(e)
        }
    }

    suspend fun getUserDetails(uid: String): Result<UserAccount> = withContext(Dispatchers.IO) {
        val fs = getFirestore() ?: return@withContext Result.failure(
            IllegalStateException("Firebase is uninitialized.")
        )
        try {
            val doc = fs.collection("users").document(uid).get().await()
            if (!doc.exists()) return@withContext Result.failure(NoSuchElementException("User document $uid not found"))
            Result.success(parseUserAccount(doc))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun moderateUser(
        uid: String,
        newStatus: String, // "active" or "suspended"
        reason: String,
        adminEmail: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val fs = getFirestore() ?: return@withContext Result.failure(
            IllegalStateException("Firebase is uninitialized.")
        )
        try {
            val normalizedStatus = newStatus.lowercase()
            fs.collection("users").document(uid).update(
                mapOf(
                    "status" to normalizedStatus,
                    "suspension_reason" to reason,
                    "status_updated_at" to FieldValue.serverTimestamp(),
                    "status_updated_by" to adminEmail,
                    // Keep legacy field mirror for backwards compatibility with any existing rules
                    "moderationReason" to reason,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("UserRepository", "Failed to moderate user $uid", e)
            Result.failure(e)
        }
    }

    suspend fun toggleDownloadBlock(
        uid: String,
        isBlocked: Boolean,
        adminEmail: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val fs = getFirestore() ?: return@withContext Result.failure(
            IllegalStateException("Firebase is uninitialized.")
        )
        try {
            fs.collection("users").document(uid).update(
                mapOf(
                    "download_blocked" to isBlocked,
                    // Keep legacy field mirror for backwards compatibility
                    "isDownloadBlocked" to isBlocked,
                    "status_updated_at" to FieldValue.serverTimestamp(),
                    "status_updated_by" to adminEmail,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("UserRepository", "Failed to toggle download block for $uid", e)
            Result.failure(e)
        }
    }
}

/**
 * Decode fields actually written by AuthManager/AppAnalytics, plus admin-controlled
 * moderation fields. UID is the Firestore document ID; fields such as display_name
 * and created_at are optional because the User APK does not currently write them.
 */
private fun parseUserAccount(doc: DocumentSnapshot): UserAccount {
    fun number(vararg keys: String): Long = keys.firstNotNullOfOrNull { key ->
        (doc.get(key) as? Number)?.toLong()
    } ?: 0L

    return UserAccount(
        uid = doc.id,
        email = doc.getString("email").orEmpty(),
        displayName = doc.getString("display_name") ?: doc.getString("displayName").orEmpty(),
        status = doc.getString("status") ?: "active",
        suspensionReason = doc.getString("suspension_reason")
            ?: doc.getString("moderationReason").orEmpty(),
        downloadBlocked = doc.getBoolean("download_blocked")
            ?: doc.getBoolean("isDownloadBlocked") ?: false,
        isAnonymous = doc.getBoolean("is_anonymous")
            ?: doc.getBoolean("isAnonymous") ?: false,
        installationId = doc.getString("installation_id").orEmpty(),
        appVersion = doc.getString("app_version")
            ?: doc.getString("clientVersion").orEmpty(),
        appVersionCode = number("app_version_code").toInt(),
        totalWatchSeconds = number("total_watch_seconds"),
        createdAtRaw = doc.get("created_at") ?: doc.get("createdAt"),
        lastLoginAtRaw = doc.get("last_login_at") ?: doc.get("lastLoginAt"),
        statusUpdatedAt = doc.getTimestamp("status_updated_at")
            ?: doc.getTimestamp("statusUpdatedAt"),
        statusUpdatedBy = doc.getString("status_updated_by")
            ?: doc.getString("statusUpdatedBy").orEmpty()
    )
}
