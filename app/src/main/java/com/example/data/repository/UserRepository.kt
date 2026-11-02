package com.example.data.repository

import android.util.Log
import com.example.data.model.UserAccount
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import com.google.firebase.firestore.DocumentSnapshot

data class UserCounts(
    val total: Int = 0,
    val active: Int = 0,
    val suspended: Int = 0
)

class UserRepository {

    private fun getFirestore(): FirebaseFirestore? {
        return runCatching { FirebaseFirestore.getInstance() }.getOrNull()
    }

    suspend fun getUsers(pageSize: Long = 50): Result<List<UserAccount>> = withContext(Dispatchers.IO) {
        val fs = getFirestore() ?: return@withContext Result.failure(
            IllegalStateException("Firebase is uninitialized. Connect google-services.json to view real users.")
        )
        try {
            // Do not order by last_login_at: Firestore omits documents without that field.
            // Older valid user records must remain visible even when the field is absent.
            val snapshot = fs.collection("users").limit(pageSize).get().await()

            val users = snapshot.documents.mapNotNull { doc ->
                try {
                    doc.toObject(UserAccount::class.java)?.copy(uid = doc.id)
                } catch (e: Exception) {
                    Log.w("UserRepository", "Failed to deserialize user doc ${doc.id}: ${e.message}")
                    null
                }
            }
            Result.success(users.sortedByDescending { it.lastLoginMillis })
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
                var query: Query = fs.collection("users").limit(500)
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
                val user = doc.toObject(UserAccount::class.java)?.copy(uid = doc.id)
                if (user != null) {
                    return@withContext Result.success(listOf(user))
                }
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
                d.toObject(UserAccount::class.java)?.copy(uid = d.id)
            }.orEmpty()

            if (found.isNotEmpty()) {
                return@withContext Result.success(found)
            }

            // 3. Fallback in-memory search over current collection
            val allSnapshot = fs.collection("users").limit(100).get().await()
            val matches = allSnapshot.documents.mapNotNull { d ->
                d.toObject(UserAccount::class.java)?.copy(uid = d.id)
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
            val user = doc.toObject(UserAccount::class.java)?.copy(uid = doc.id)
            if (user != null) {
                Result.success(user)
            } else {
                Result.failure(NoSuchElementException("User document $uid not found"))
            }
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
