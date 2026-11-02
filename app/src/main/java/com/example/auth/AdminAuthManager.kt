package com.example.auth

import android.content.Context
import android.util.Log
import com.example.data.model.AdminRoleRecord
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.AuthCredential
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CancellationException
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
            if (FirebaseApp.getApps(context).isEmpty()) {
                val options = com.google.firebase.FirebaseOptions.Builder()
                    .setApplicationId("1:357805299753:android:0cafd3e3c6dc70244b2438")
                    .setApiKey("AIzaSyBR4eWR7ctDHOcgB0c7yWne3aBwpMyZRe0")
                    .setProjectId("prophecy-verse-483df")
                    .setGcmSenderId("357805299753")
                    .setStorageBucket("prophecy-verse-483df.firebasestorage.app")
                    .build()
                FirebaseApp.initializeApp(context, options)
            }
            firestore = runCatching { FirebaseFirestore.getInstance() }.getOrNull()
        } catch (t: Throwable) {
            Log.w("AdminAuthManager", "Firebase initialization failed: ${t.message}", t)
        }
    }

    private fun restoreFirebaseSession() {
        try {
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
        } catch (t: Throwable) {
            Log.e("AdminAuthManager", "Failed to restore session: ${t.message}", t)
            _authStatus.value = AuthStatus.SignedOut
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
            initFirebase()
            if (!isFirebaseReady()) {
                _authStatus.value = AuthStatus.UnconfiguredFirebase
                return@withContext Result.failure(IllegalStateException("Firebase is not configured for this app."))
            }
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

    suspend fun registerAdmin(
        name: String,
        email: String,
        password: String,
        role: String = "ADMIN",
        accessKey: String = ""
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val cleanEmail = email.trim()
        val cleanName = name.trim().ifBlank { cleanEmail.substringBefore("@") }
        if (cleanEmail.isBlank() || password.length < 6) {
            val msg = "Please enter a valid email and a password of at least 6 characters."
            _authStatus.value = AuthStatus.AuthError(msg)
            return@withContext Result.failure(IllegalArgumentException(msg))
        }

        if (!isFirebaseReady()) {
            initFirebase()
        }

        val isOwner = OWNER_EMAILS.any { it.equals(cleanEmail, ignoreCase = true) }
        val signUpResult = FirebaseAuthClient.signUpWithPassword(cleanEmail, password)
        if (!signUpResult.isSuccess) {
            val err = signUpResult.errorMessage ?: "Account creation failed."
            _authStatus.value = AuthStatus.AuthError(err)
            return@withContext Result.failure(Exception(err))
        }

        val uid = signUpResult.localId ?: FirebaseAuthClient.currentUser()?.uid
        if (uid == null) {
            _authStatus.value = AuthStatus.AuthError("User ID not returned after signup.")
            return@withContext Result.failure(IllegalStateException("No UID"))
        }

        val finalRole = if (isOwner) "SUPER_ADMIN" else "ADMIN"
        val permissions = defaultPermissionsFor(finalRole)

        val fs = firestore
        if (fs != null) {
            try {
                fs.collection("admin_users").document(uid).set(
                    mapOf(
                        "uid" to uid,
                        "email" to cleanEmail,
                        "display_name" to cleanName,
                        "is_admin" to true,
                        "role" to finalRole,
                        "created_at" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                ).await()

                fs.collection("admin_roles").document(uid).set(
                    mapOf(
                        "uid" to uid,
                        "email" to cleanEmail,
                        "role" to finalRole,
                        "status" to "ACTIVE",
                        "permissions" to permissions,
                        "created_at" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                ).await()
            } catch (e: Exception) {
                Log.w("AdminAuthManager", "Could not write admin role docs to Firestore", e)
            }
        }

        verifyAndAuthorize(uid, cleanEmail, cleanName)
        Result.success(Unit)
    }

    suspend fun loginWithGoogleCredential(credential: AuthCredential): Result<Unit> = withContext(Dispatchers.IO) {
        val result = FirebaseAuthClient.signInWithCredential(credential)
        if (!result.isSuccess) {
            val msg = result.errorMessage ?: "Google Sign-In failed."
            _authStatus.value = AuthStatus.AuthError(msg)
            return@withContext Result.failure(Exception(msg))
        }
        val user = FirebaseAuthClient.currentUser()
        if (user == null) {
            _authStatus.value = AuthStatus.AuthError("No user returned from Google sign-in.")
            return@withContext Result.failure(IllegalStateException("No user"))
        }
        verifyAndAuthorize(
            uid = user.uid,
            email = user.email.orEmpty(),
            displayName = user.displayName.orEmpty().ifBlank { user.email.orEmpty().substringBefore("@") }
        )
        Result.success(Unit)
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
        try {
            verifyAndAuthorizeUnsafe(uid, email, displayName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("AdminAuthManager", "Unexpected error while verifying administrator role", e)
            _authStatus.value = AuthStatus.AuthError(
                "Could not verify your administrator access. Please check your connection and credentials."
            )
        }
    }

    private suspend fun verifyAndAuthorizeUnsafe(uid: String, email: String, displayName: String) {
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

        val isPrimaryOwner = OWNER_EMAILS.any { it.equals(email, ignoreCase = true) }

        // 1. Check User APK's official admin collection: admin_users/{uid} (per User APK firestore.rules)
        var isAdminUser = false
        var adminUserRole = "ADMIN"
        try {
            val adminUserSnap = fs.collection("admin_users").document(uid).get().await()
            if (adminUserSnap.exists()) {
                val isAdminFlag = adminUserSnap.getBoolean("is_admin") ?: false
                if (isAdminFlag) {
                    isAdminUser = true
                    adminUserRole = adminUserSnap.getString("role") ?: "ADMIN"
                }
            }
        } catch (e: Exception) {
            Log.w("AdminAuthManager", "Checking admin_users failed: ${e.message}")
        }

        // 2. Check admin_roles/{uid} (Admin APK ruleset)
        var hasActiveAdminRole = false
        var roleRecordFromDb: AdminRoleRecord? = null
        try {
            val roleSnap = fs.collection("admin_roles").document(uid).get().await()
            if (roleSnap.exists()) {
                val status = roleSnap.getString("status") ?: "ACTIVE"
                val role = roleSnap.getString("role")?.uppercase() ?: "ADMIN"
                if (status.equals("ACTIVE", ignoreCase = true) && role in ALLOWED_ADMIN_ROLES) {
                    hasActiveAdminRole = true
                    val permissions = (roleSnap.get("permissions") as? List<*>)
                        ?.mapNotNull { it as? String }
                        .orEmpty()
                        .ifEmpty { defaultPermissionsFor(role) }

                    roleRecordFromDb = AdminRoleRecord(
                        uid = uid,
                        isAdmin = true,
                        role = role,
                        status = "ACTIVE",
                        email = email,
                        permissions = permissions
                    )
                }
            }
        } catch (e: Exception) {
            Log.w("AdminAuthManager", "Checking admin_roles failed: ${e.message}")
        }

        // Auto-provision owner record if missing
        if (isPrimaryOwner && (!isAdminUser || roleRecordFromDb == null)) {
            try {
                fs.collection("admin_users").document(uid).set(
                    mapOf(
                        "uid" to uid,
                        "email" to email,
                        "is_admin" to true,
                        "role" to "SUPER_ADMIN"
                    ),
                    SetOptions.merge()
                ).await()
                fs.collection("admin_roles").document(uid).set(
                    mapOf(
                        "uid" to uid,
                        "email" to email,
                        "role" to "SUPER_ADMIN",
                        "status" to "ACTIVE",
                        "permissions" to defaultPermissionsFor("SUPER_ADMIN")
                    ),
                    SetOptions.merge()
                ).await()
                isAdminUser = true
            } catch (e: Exception) {
                Log.w("AdminAuthManager", "Auto-provisioning owner notice: ${e.message}")
            }
        }

        // 3. Evaluation of authorization
        if (!isPrimaryOwner && !isAdminUser && !hasActiveAdminRole) {
            denySession(email, "This account ($email) is not recognized as an administrator.")
            return
        }

        val finalRole = when {
            isPrimaryOwner -> "SUPER_ADMIN"
            roleRecordFromDb != null -> roleRecordFromDb.role
            isAdminUser -> adminUserRole
            else -> "ADMIN"
        }

        val finalPermissions = roleRecordFromDb?.permissions
            ?: defaultPermissionsFor(finalRole)

        val roleRecord = AdminRoleRecord(
            uid = uid,
            isAdmin = true,
            role = finalRole,
            status = "ACTIVE",
            email = email,
            permissions = finalPermissions
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
        "SUPER_ADMIN" -> listOf(
            "installations:read", "installations:delete", "users:read", "users:moderate",
            "notices:write", "version:write", "notifications:send", "logs:read", "logs:write"
        )
        "ADMIN" -> listOf(
            "installations:read", "installations:delete", "users:read", "users:moderate",
            "notices:write", "version:write", "notifications:send", "logs:read"
        )
        "MODERATOR" -> listOf(
            "installations:read", "users:read", "users:moderate", "notices:write", "logs:read"
        )
        else -> emptyList()
    }

    private fun logSignInActivity(uid: String, email: String) {
        val fs = firestore ?: return
        sessionScope.launch {
            try {
                // Dual-write to security_logs (User APK schema) and admin_logs (Admin schema)
                val securityLog = mapOf(
                    "action" to "ADMIN_SIGN_IN",
                    "details" to "Administrator signed in to Admin APK ($email)",
                    "performed_by" to email,
                    "performed_by_uid" to uid,
                    "timestamp" to FieldValue.serverTimestamp()
                )
                fs.collection("security_logs").add(securityLog).await()

                val adminLog = mapOf(
                    "adminUid" to uid,
                    "adminEmail" to email,
                    "action" to "ADMIN_SIGN_IN",
                    "targetType" to "AUTH",
                    "targetId" to uid,
                    "details" to "Administrator signed in to Admin APK",
                    "result" to "SUCCESS",
                    "timestamp" to FieldValue.serverTimestamp()
                )
                fs.collection("admin_logs").add(adminLog).await()
            } catch (e: Exception) {
                Log.w("AdminAuthManager", "Failed to write sign-in audit log", e)
            }
        }
    }

    private fun clearCachedSession() {
        runCatching {
            context.getSharedPreferences("pv_admin_auth", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply()
        }
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
        val OWNER_EMAILS = setOf(
            "romantichujorovi@gmail.com",
            "newprophecy9@gmail.com"
        )
        val ALLOWED_ADMIN_ROLES = setOf("SUPER_ADMIN", "ADMIN", "MODERATOR")
    }
}
