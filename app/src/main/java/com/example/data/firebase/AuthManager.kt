package com.example.data.firebase

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
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
    data class Authenticated(val user: FirebaseUser, val email: String, val profile: UserProfile) : AuthState()
    data class Suspended(val user: FirebaseUser, val reason: String) : AuthState()
    data class Error(val message: String) : AuthState()
}

data class UserProfile(
    val uid: String = "",
    val email: String? = null,
    val isAnonymous: Boolean = true,
    val status: String = "active", // "active" or "suspended"
    val suspensionReason: String = "",
    val downloadBlocked: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val lastLoginAt: Long = System.currentTimeMillis(),
    val installationId: String = "",
    val totalWatchTimeSeconds: Long = 0L
)

sealed class DownloadEligibility {
    data object Eligible : DownloadEligibility()
    data object RequiresLogin : DownloadEligibility()
    data class Suspended(val reason: String) : DownloadEligibility()
}

data class PendingDownload(
    val animeId: String,
    val episodeId: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val episodeTitle: String?,
    val animeTitle: String,
    val posterUrl: String?,
    val mediaType: String? = "anime",
    val preferredQuality: String? = null,
    val preferredLanguage: String? = null,
    val preferredSourceId: String? = null
)

class AuthManager private constructor(private val context: Context) {
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _authState = MutableStateFlow<AuthState>(AuthState.Loading)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _userProfile = MutableStateFlow(UserProfile())
    val userProfile: StateFlow<UserProfile> = _userProfile.asStateFlow()

    // Preserved download action across login / register flow
    private val _pendingDownload = MutableStateFlow<PendingDownload?>(null)
    val pendingDownload: StateFlow<PendingDownload?> = _pendingDownload.asStateFlow()

    private var userDocListener: ListenerRegistration? = null

    val currentUser: FirebaseUser?
        get() = auth.currentUser

    val isGuest: Boolean
        get() = auth.currentUser == null || auth.currentUser?.isAnonymous == true

    val isSuspended: Boolean
        get() = _userProfile.value.status.equals("suspended", ignoreCase = true)

    val currentUid: String
        get() = auth.currentUser?.uid ?: "guest_${InstallationManager.getInstance(context).installationId}"

    init {
        updateAuthState(auth.currentUser)
        auth.addAuthStateListener { firebaseAuth -> updateAuthState(firebaseAuth.currentUser) }
    }

    private fun updateAuthState(user: FirebaseUser?) {
        userDocListener?.remove()
        userDocListener = null

        if (user == null || user.isAnonymous) {
            _userProfile.value = UserProfile(
                uid = user?.uid ?: currentUid,
                isAnonymous = true,
                status = "active"
            )
            _authState.value = AuthState.Guest(user?.uid ?: currentUid)
        } else {
            // Listen for profile changes (including suspension status) in real time
            listenToUserProfile(user)
        }
    }

    private fun listenToUserProfile(user: FirebaseUser) {
        val docRef = firestore.collection("users").document(user.uid)
        userDocListener = docRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.w(TAG, "Error listening to user profile: ${error.message}")
                return@addSnapshotListener
            }

            val status = snapshot?.getString("status") ?: "active"
            val reason = snapshot?.getString("suspension_reason") ?: "Account access restricted by administration."
            val totalWatch = snapshot?.getLong("total_watch_seconds") ?: 0L
            val downloadBlocked = snapshot?.getBoolean("download_blocked") ?: false

            val profile = UserProfile(
                uid = user.uid,
                email = user.email,
                isAnonymous = false,
                status = status,
                suspensionReason = reason,
                downloadBlocked = downloadBlocked,
                createdAt = snapshot?.getLong("created_at") ?: System.currentTimeMillis(),
                lastLoginAt = snapshot?.getLong("last_login_at") ?: System.currentTimeMillis(),
                installationId = snapshot?.getString("installation_id") ?: "",
                totalWatchTimeSeconds = totalWatch
            )
            _userProfile.value = profile

            if (status.equals("suspended", ignoreCase = true)) {
                _authState.value = AuthState.Suspended(user, reason)
            } else {
                _authState.value = AuthState.Authenticated(user, user.email ?: "", profile)
            }
        }

        // Perform initial merge sync
        syncUserProfile(user)
    }

    fun checkDownloadEligibility(): DownloadEligibility {
        return evaluateDownloadEligibility(auth.currentUser, _userProfile.value)
    }

    fun setPendingDownload(pending: PendingDownload?) {
        _pendingDownload.value = pending
    }

    fun clearPendingDownload() {
        _pendingDownload.value = null
    }

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
                userDocListener?.remove()
                userDocListener = null
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

        fun evaluateDownloadEligibility(user: Any?, profile: UserProfile?): DownloadEligibility {
            if (user == null || profile?.isAnonymous == true) {
                return DownloadEligibility.RequiresLogin
            }
            if (profile?.status?.equals("suspended", ignoreCase = true) == true || profile?.downloadBlocked == true) {
                return DownloadEligibility.Suspended(
                    profile.suspensionReason.ifBlank { "Account download permissions are restricted." }
                )
            }
            return DownloadEligibility.Eligible
        }
    }
}
