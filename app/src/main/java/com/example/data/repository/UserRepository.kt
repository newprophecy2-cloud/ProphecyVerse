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

class UserRepository {

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

    suspend fun getUsers(pageSize: Long = 25): Result<List<UserAccount>> = withContext(Dispatchers.IO) {
        val fs = getFirestore() ?: return@withContext Result.failure(
            IllegalStateException("Firebase is uninitialized. Connect google-services.json to view real users.")
        )
        try {
            val snapshot = fs.collection("users")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(pageSize)
                .get()
                .await()

            val users = snapshot.documents.mapNotNull { doc ->
                doc.toObject(UserAccount::class.java)?.copy(uid = doc.id)
            }
            Result.success(users)
        } catch (e: Exception) {
            Log.e("UserRepository", "Failed to fetch users", e)
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

            // Attempt direct document lookup by UID first
            val doc = fs.collection("users").document(cleanQuery).get().await()
            if (doc.exists()) {
                val user = doc.toObject(UserAccount::class.java)?.copy(uid = doc.id)
                if (user != null) {
                    return@withContext Result.success(listOf(user))
                }
            }

            // Search by email prefix
            val emailSnapshot = fs.collection("users")
                .whereGreaterThanOrEqualTo("email", cleanQuery)
                .whereLessThanOrEqualTo("email", cleanQuery + "\uf8ff")
                .limit(20)
                .get()
                .await()

            val users = emailSnapshot.documents.mapNotNull { d ->
                d.toObject(UserAccount::class.java)?.copy(uid = d.id)
            }
            Result.success(users)
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

    suspend fun moderateUser(uid: String, newStatus: String, reason: String): Result<Unit> = withContext(Dispatchers.IO) {
        val fs = getFirestore() ?: return@withContext Result.failure(
            IllegalStateException("Firebase is uninitialized. Server-side Cloud Function required.")
        )
        try {
            fs.collection("users").document(uid).update(
                mapOf(
                    "status" to newStatus,
                    "moderationReason" to reason,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun toggleDownloadBlock(uid: String, isBlocked: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        val fs = getFirestore() ?: return@withContext Result.failure(
            IllegalStateException("Firebase is uninitialized.")
        )
        try {
            fs.collection("users").document(uid).update(
                mapOf(
                    "isDownloadBlocked" to isBlocked,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
