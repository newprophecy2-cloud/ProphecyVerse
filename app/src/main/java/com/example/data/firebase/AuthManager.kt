package com.example.data.firebase

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

sealed class AuthState {
    data object Loading : AuthState()
    data class Guest(val guestId: String) : AuthState()
    data class Authenticated(val user: FirebaseUser, val email: String) : AuthState()
    data class Error(val message: String) : AuthState()
}

data class UserProfile(
    val uid: String = "",
    val email: String? = null,
    val isAnonymous: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val lastLoginAt: Long = System.currentTimeMillis(),
    val installationId: String = "",
    val totalWatchTimeSeconds: Long = 0L,
    val role: String = "user"
)

class AuthManager private constructor(private val context: Context) {
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _authState = MutableStateFlow<AuthState>(AuthState.Loading)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    val currentUser: FirebaseUser?
        get() = auth.currentUser

    val isGuest: Boolean
        get() = auth.currentUser == null

    val currentUid: String
        get() = auth.currentUser?.uid ?: "guest_${InstallationManager.getInstance(context).installationId}"

    init {
        // Evaluate initial authentication state immediately
        val user = auth.currentUser
        if (user != null) {
            _authState.value = AuthState.Authenticated(user, user.email ?: "")
            syncUserProfile(user)
        } else {
            _authState.value = AuthState.Guest(currentUid)
        }

        auth.addAuthStateListener { firebaseAuth ->
            val u = firebaseAuth.currentUser
            if (u != null) {
                _authState.value = AuthState.Authenticated(u, u.email ?: "")
                syncUserProfile(u)
            } else {
                _authState.value = AuthState.Guest(currentUid)
            }
        }
    }

    /**
     * Initializes or verifies the local guest session.
     * Uses device-backed UUID identity without requiring anonymous auth tokens from Firebase.
     */
    fun ensureGuestSignIn(): Result<Unit> {
        val user = auth.currentUser
        if (user == null) {
            _authState.value = AuthState.Guest(currentUid)
        } else {
            _authState.value = AuthState.Authenticated(user, user.email ?: "")
        }
        return Result.success(Unit)
    }

    suspend fun registerWithEmail(email: String, pass: String): Result<FirebaseUser> {
        return try {
            val result = auth.createUserWithEmailAndPassword(email.trim(), pass).await()
            val user = result.user ?: throw IllegalStateException("Registration returned null user")
            _authState.value = AuthState.Authenticated(user, email.trim())
            syncUserProfile(user)
            AppAnalytics.getInstance(context).logEvent("register_success")
            Result.success(user)
        } catch (e: Exception) {
            Log.e("AuthManager", "Register failed", e)
            Result.failure(e)
        }
    }

    suspend fun loginWithEmail(email: String, pass: String): Result<FirebaseUser> {
        return try {
            val result = auth.signInWithEmailAndPassword(email.trim(), pass).await()
            val user = result.user ?: throw IllegalStateException("Login returned null user")
            _authState.value = AuthState.Authenticated(user, email.trim())
            syncUserProfile(user)
            AppAnalytics.getInstance(context).logEvent("login_success")
            Result.success(user)
        } catch (e: Exception) {
            Log.e("AuthManager", "Login failed", e)
            Result.failure(e)
        }
    }

    suspend fun sendPasswordReset(email: String): Result<Unit> {
        return try {
            auth.sendPasswordResetEmail(email.trim()).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("AuthManager", "Password reset failed", e)
            Result.failure(e)
        }
    }

    fun signOut() {
        scope.launch {
            try {
                auth.signOut()
                _authState.value = AuthState.Guest(currentUid)
            } catch (e: Exception) {
                Log.e("AuthManager", "Sign out error", e)
            }
        }
    }

    private fun syncUserProfile(user: FirebaseUser) {
        scope.launch {
            try {
                val instId = InstallationManager.getInstance(context).installationId
                val data = hashMapOf<String, Any>(
                    "uid" to user.uid,
                    "is_anonymous" to false,
                    "installation_id" to instId,
                    "last_login_at" to System.currentTimeMillis(),
                    "app_version" to "1.0",
                    "app_version_code" to 1
                )
                user.email?.let { data["email"] = it }

                firestore.collection("users")
                    .document(user.uid)
                    .set(data, SetOptions.merge())
                    .await()
            } catch (e: Exception) {
                Log.w("AuthManager", "Failed to sync user profile to Firestore: ${e.message}")
            }
        }
    }

    companion object {
        @Volatile
        private var instance: AuthManager? = null

        fun getInstance(context: Context): AuthManager {
            return instance ?: synchronized(this) {
                instance ?: AuthManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
