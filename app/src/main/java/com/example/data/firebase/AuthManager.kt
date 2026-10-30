package com.example.data.firebase

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.EmailAuthProvider
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
    data class Guest(val user: FirebaseUser) : AuthState()
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
        get() = auth.currentUser?.isAnonymous ?: true

    val currentUid: String
        get() = auth.currentUser?.uid ?: "guest_${InstallationManager.getInstance(context).installationId}"

    init {
        auth.addAuthStateListener { firebaseAuth ->
            val user = firebaseAuth.currentUser
            if (user == null) {
                // Automatically ensure guest session if not logged in
                scope.launch {
                    ensureGuestSignIn()
                }
            } else {
                if (user.isAnonymous) {
                    _authState.value = AuthState.Guest(user)
                } else {
                    _authState.value = AuthState.Authenticated(user, user.email ?: "")
                }
                syncUserProfile(user)
            }
        }
    }

    suspend fun ensureGuestSignIn(): Result<FirebaseUser> {
        return try {
            val existing = auth.currentUser
            if (existing != null) {
                return Result.success(existing)
            }
            val result = auth.signInAnonymously().await()
            val user = result.user ?: throw IllegalStateException("Guest sign-in returned null user")
            _authState.value = AuthState.Guest(user)
            syncUserProfile(user)
            AppAnalytics.getInstance(context).logEvent("sign_in_guest")
            Result.success(user)
        } catch (e: Exception) {
            Log.e("AuthManager", "Guest sign in failed", e)
            _authState.value = AuthState.Error(e.localizedMessage ?: "Guest login failed")
            Result.failure(e)
        }
    }

    suspend fun registerWithEmail(email: String, pass: String): Result<FirebaseUser> {
        return try {
            val currentUser = auth.currentUser
            val result = if (currentUser != null && currentUser.isAnonymous) {
                // Link current anonymous account to email/pass so watch history is preserved!
                val credential = EmailAuthProvider.getCredential(email.trim(), pass)
                currentUser.linkWithCredential(credential).await()
            } else {
                auth.createUserWithEmailAndPassword(email.trim(), pass).await()
            }
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
                ensureGuestSignIn()
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
                    "is_anonymous" to user.isAnonymous,
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
