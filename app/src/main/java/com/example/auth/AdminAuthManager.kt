package com.example.auth

import android.content.Context
import android.util.Log
import com.example.data.model.AdminRoleRecord
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

sealed interface AuthStatus {
    data object UnconfiguredFirebase : AuthStatus
    data object SignedOut : AuthStatus
    data object CheckingRole : AuthStatus
    data class AuthenticatedAdmin(
        val uid: String,
        val email: String,
        val displayName: String,
        val role: AdminRoleRecord
    ) : AuthStatus
    data class Unauthorized(val email: String, val reason: String) : AuthStatus
    data class AuthError(val message: String) : AuthStatus
}

class AdminAuthManager(private val context: Context) {

    private val _authStatus = MutableStateFlow<AuthStatus>(AuthStatus.SignedOut)
    val authStatus: StateFlow<AuthStatus> = _authStatus.asStateFlow()

    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var firestore: FirebaseFirestore? = null

    init {
        initFirebase()
        restoreFirebaseSession()
    }

    fun isFirebaseReady(): Boolean {
        return try {
            FirebaseApp.getApps(context).isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }

    private fun initFirebase() {
        try {
            if (FirebaseApp.getApps(context).isNotEmpty()) {
                firestore = FirebaseFirestore.getInstance()
            }
        } catch (e: Exception) {
            Log.w("AdminAuthManager", "Firebase initialization failed", e)
        }
    }

    private fun restoreFirebaseSession() {
        if (!isFirebaseReady()) {
            _authStatus.value = AuthStatus.UnconfiguredFirebase
            return
        }
        val user = FirebaseAuthClient.currentUser()
        if (user == null) {
            clearCachedSession()
            _authStatus.value = AuthStatus.SignedOut
            return
        }
        _authStatus.value = AuthStatus.CheckingRole
        sessionScope.launch {
            verifyAndAuthorize(
                uid = user.uid,
                email = user.email.orEmpty(),
                displayName = user.displayName.orEmpty().ifBlank { user.email.orEmpty().substringBefore("@") }
            )
        }
    }

    suspend fun loginWithEmail(email: String, password: String): Result<Unit> = withContext(Dispatchers.IO) {
        val cleanEmail = email.trim()
        if (cleanEmail.isBlank() || password.isBlank()) {
            val message = "Email and password cannot be empty."
            _authStatus.value = AuthStatus.AuthError(message)
            return@withContext Result.failure(IllegalArgumentException(message))
        }
        if (!isFirebaseReady()) {
            _authStatus.value = AuthStatus.UnconfiguredFirebase
            return@withContext Result.failure(IllegalStateException("Firebase is not configured for this app."))
        }

        val authResult = FirebaseAuthClient.loginWithPassword(cleanEmail, password)
        if (!authResult.isSuccess) {
            val message = authResult.errorMessage ?: "Authentication failed."
            _authStatus.value = AuthStatus.AuthError(message)
            return@withContext Result.failure(Exception(message))
        }

        val user = FirebaseAuthClient.currentUser()
        if (user == null || user.uid != authResult.localId) {
            FirebaseAuthClient.signOut()
            val message = "Firebase did not retain the authenticated session. Please try again."
            _authStatus.value = AuthStatus.AuthError(message)
            return@withContext Result.failure(IllegalStateException(message))
        }

        verifyAndAuthorize(
            uid = user.uid,
            email = user.email ?: cleanEmail,
            displayName = user.displayName.orEmpty().ifBlank { cleanEmail.substringBefore("@") }
        )

        when (val status = _authStatus.value) {
            is AuthStatus.AuthenticatedAdmin -> Result.success(Unit)
            is AuthStatus.Unauthorized -> Result.failure(SecurityException(status.reason))
            is AuthStatus.AuthError -> Result.failure(IllegalStateException(status.message))
            else -> Result.failure(IllegalStateException("Admin authorization did not complete."))
        }
    }

    /** Admin role assignment must be performed by a trusted server, never by this client. */
    suspend fun registerAdmin(
        name: String,
        email: String,
        password: String,
        role: String,
        accessKey: String
    ): Result<Unit> {
        val message = "Self-service admin registration is disabled. Ask a trusted administrator to provision your Firebase admin role."
        _authStatus.value = AuthStatus.AuthError(message)
        return Result.failure(UnsupportedOperationException(message))
    }

    suspend fun sendPasswordReset(email: String): Result<Unit> = withContext(Dispatchers.IO) {
        val cleanEmail = email.trim()
        if (cleanEmail.isBlank() || !cleanEmail.contains("@")) {
            return@withContext Result.failure(IllegalArgumentException("Please provide a valid email."))
        }
        if (!isFirebaseReady()) {
            return@withContext Result.failure(IllegalStateException("Firebase is not configured for this app."))
        }
        FirebaseAuthClient.sendPasswordReset(cleanEmail)
    }

    private suspend fun verifyAndAuthorize(uid: String, email: String, displayName: String) {
        _authStatus.value = AuthStatus.CheckingRole
        val currentUser = FirebaseAuthClient.currentUser()
        if (currentUser == null || currentUser.uid != uid || email.isBlank()) {
            denySession(email, "Please sign in again to verify your administrator account.")
            return
        }

        val fs = firestore
        if (fs == null) {
            _authStatus.value = AuthStatus.UnconfiguredFirebase
            return
        }

        val roleSnapshot = try {
            fs.collection("admin_roles").document(uid).get().await()
        } catch (e: Exception) {
            Log.w("AdminAuthManager", "Could not verify administrator role", e)
            _authStatus.value = AuthStatus.AuthError("Could not verify your administrator access. Check your connection and Firebase rules.")
            return
        }

        val isPrimaryOwner = email.equals(PRIMARY_OWNER_EMAIL, ignoreCase = true)
        val documentStatus = roleSnapshot.getString("status")
        if (roleSnapshot.exists() && !documentStatus.equals("ACTIVE", ignoreCase = true)) {
            denySession(email, "Your admin account is not active.")
            return
        }

        val role = if (isPrimaryOwner && !roleSnapshot.exists()) {
            "SUPER_ADMIN"
        } else {
            roleSnapshot.getString("role").orEmpty().uppercase()
        }
        if (!roleSnapshot.exists() && !isPrimaryOwner) {
            denySession(email, "This account has not been provisioned as an administrator.")
            return
        }
        if (role !in ALLOWED_ADMIN_ROLES) {
            denySession(email, "This account does not have a recognized administrator role.")
            return
        }

        val savedPermissions = (roleSnapshot.get("permissions") as? List<*>)
            ?.mapNotNull { it as? String }
            .orEmpty()
        val permissions = savedPermissions.ifEmpty { defaultPermissionsFor(role) }
        val roleRecord = AdminRoleRecord(
            uid = uid,
            role = role,
            status = "ACTIVE",
            email = email,
            permissions = permissions
        )

        _authStatus.value = AuthStatus.AuthenticatedAdmin(
            uid = uid,
            email = email,
            displayName = displayName.ifBlank { email.substringBefore("@") },
            role = roleRecord
        )
        logSignInActivity(uid, email)
    }

    private fun denySession(email: String, reason: String) {
        clearCachedSession()
        FirebaseAuthClient.signOut()
        _authStatus.value = AuthStatus.Unauthorized(email, reason)
    }

    private fun defaultPermissionsFor(role: String): List<String> = when (role) {
        "SUPER_ADMIN", "ADMIN" -> listOf(
            "users:read", "users:moderate", "notices:write", "version:write", "logs:read", "logs:write"
        )
        "MODERATOR" -> listOf("users:read", "users:moderate", "logs:read")
        else -> emptyList()
    }

    private fun logSignInActivity(uid: String, email: String) {
        val fs = firestore ?: return
        sessionScope.launch {
            try {
                val logPayload = mapOf(
                    "adminUid" to uid,
                    "adminEmail" to email,
                    "action" to "ADMIN_SIGN_IN",
                    "targetType" to "AUTH",
                    "targetId" to uid,
                    "details" to "Administrator signed in to Admin APK",
                    "result" to "SUCCESS",
                    "timestamp" to FieldValue.serverTimestamp()
                )
                fs.collection("admin_logs").add(logPayload).await()
            } catch (e: Exception) {
                Log.w("AdminAuthManager", "Failed to write sign-in audit log", e)
            }
        }
    }

    private fun clearCachedSession() {
        context.getSharedPreferences("pv_admin_auth", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    fun signOut() {
        clearCachedSession()
        FirebaseAuthClient.signOut()
        _authStatus.value = AuthStatus.SignedOut
    }

    fun getCurrentAdminUid(): String? {
        val current = _authStatus.value
        return if (current is AuthStatus.AuthenticatedAdmin) current.uid else null
    }

    fun getCurrentAdminEmail(): String {
        val current = _authStatus.value
        return if (current is AuthStatus.AuthenticatedAdmin) current.email else ""
    }

    private companion object {
        const val PRIMARY_OWNER_EMAIL = "newprophecy9@gmail.com"
        val ALLOWED_ADMIN_ROLES = setOf("SUPER_ADMIN", "ADMIN", "MODERATOR")
    }
}
