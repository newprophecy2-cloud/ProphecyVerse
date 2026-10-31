package com.example.data.firebase

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import com.google.firebase.auth.EmailAuthProvider
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
        get() = auth.currentUser?.isAnonymous != false

    val currentUid: String
        get() = auth.currentUser?.uid ?: "guest_${InstallationManager.getInstance(context).installationId}"

    init {
        updateAuthState(auth.currentUser)
        auth.addAuthStateListener { firebaseAuth -> updateAuthState(firebaseAuth.currentUser) }
    }

    private fun updateAuthState(user: FirebaseUser?) {
        if (user == null || user.isAnonymous) {
            _authState.value = AuthState.Guest(user?.uid ?: currentUid)
        } else {
            _authState.value = AuthState.Authenticated(user, user.email ?: "")
            syncUserProfile(user)
        }
    }

    /** Uses Firebase Anonymous Auth for guest cloud records when enabled in the Firebase project.
     * If the provider is disabled or temporarily unavailable, the app remains usable locally.
     */
    suspend fun ensureGuestSignIn(): Result<Unit> {
        return try {
            val user = auth.currentUser ?: auth.signInAnonymously().await().user
                ?: throw IllegalStateException("Anonymous sign-in returned no user")
            updateAuthState(user)
            InstallationManager.getInstance(context).initializeAndSync()
            Result.success(Unit)
        } catch (e: Exception) {
            _authState.value = AuthState.Guest(currentUid)
            Log.w(TAG, "Firebase guest sign-in unavailable; continuing with local guest mode")
            Result.failure(e)
        }
    }

    suspend fun registerWithEmail(email: String, pass: String): Result<FirebaseUser> {
        return try {
            val normalizedEmail = email.trim()
            val anonymousUser = auth.currentUser?.takeIf { it.isAnonymous }
            val result = if (anonymousUser != null) {
                anonymousUser.linkWithCredential(EmailAuthProvider.getCredential(normalizedEmail, pass)).await()
            } else {
                auth.createUserWithEmailAndPassword(normalizedEmail, pass).await()
            }
            val user = result.user ?: throw IllegalStateException("Registration returned null user")
            updateAuthState(user)
            syncUserProfile(user)
            InstallationManager.getInstance(context).initializeAndSync()
            AppAnalytics.getInstance(context).logEvent("register_success")
            Result.success(user)
        } catch (e: Exception) {
            Log.e(TAG, "Register failed", e)
            Result.failure(e)
        }
    }

    suspend fun loginWithEmail(email: String, pass: String): Result<FirebaseUser> {
        return try {
            if (auth.currentUser?.isAnonymous == true) {
                InstallationManager.getInstance(context).deleteCurrentRegistration()
            }
            val normalizedEmail = email.trim()
            val result = auth.signInWithEmailAndPassword(normalizedEmail, pass).await()
            val user = result.user ?: throw IllegalStateException("Login returned null user")
            updateAuthState(user)
            syncUserProfile(user)
            InstallationManager.getInstance(context).initializeAndSync()
            AppAnalytics.getInstance(context).logEvent("login_success")
            Result.success(user)
        } catch (e: Exception) {
            Log.e(TAG, "Login failed", e)
            Result.failure(e)
        }
    }

    suspend fun sendPasswordReset(email: String): Result<Unit> {
        return try {
            auth.sendPasswordResetEmail(email.trim()).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Password reset failed", e)
            Result.failure(e)
        }
    }

    fun signOut() {
        scope.launch {
            try {
                InstallationManager.getInstance(context).deleteCurrentRegistration()
                auth.signOut()
                val guest = try {
                    auth.signInAnonymously().await().user
                } catch (e: Exception) {
                    Log.w(TAG, "Could not start Firebase guest session after sign-out")
                    null
                }
                updateAuthState(guest)
                InstallationManager.getInstance(context).initializeAndSync()
            } catch (e: Exception) {
                Log.e(TAG, "Sign out error", e)
                _authState.value = AuthState.Guest(currentUid)
            }
        }
    }

    private fun syncUserProfile(user: FirebaseUser) {
        if (user.isAnonymous) return
        scope.launch {
            try {
                val instId = InstallationManager.getInstance(context).installationId
                val data = hashMapOf<String, Any>(
                    "uid" to user.uid,
                    "is_anonymous" to false,
                    "installation_id" to instId,
                    "last_login_at" to System.currentTimeMillis(),
                    "app_version" to BuildConfig.VERSION_NAME,
                    "app_version_code" to BuildConfig.VERSION_CODE
                )
                user.email?.let { data["email"] = it }

                firestore.collection("users")
                    .document(user.uid)
                    .set(data, SetOptions.merge())
                    .await()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync user profile to Firestore: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "AuthManager"

        @Volatile
        private var instance: AuthManager? = null

        fun getInstance(context: Context): AuthManager {
            return instance ?: synchronized(this) {
                instance ?: AuthManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
