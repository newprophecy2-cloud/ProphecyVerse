package com.example.auth

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

data class AuthResult(
    val isSuccess: Boolean,
    val localId: String? = null,
    val email: String? = null,
    val displayName: String? = null,
    val errorMessage: String? = null
)

object FirebaseAuthClient {
    fun currentUser() = runCatching { FirebaseAuth.getInstance().currentUser }.getOrNull()

    fun signOut() {
        runCatching { FirebaseAuth.getInstance().signOut() }
    }

    suspend fun loginWithPassword(email: String, password: String): AuthResult = withContext(Dispatchers.IO) {
        try {
            val user = FirebaseAuth.getInstance()
                .signInWithEmailAndPassword(email, password)
                .await()
                .user
                ?: return@withContext AuthResult(false, errorMessage = "Firebase sign-in returned no user.")
            AuthResult(
                isSuccess = true,
                localId = user.uid,
                email = user.email,
                displayName = user.displayName
            )
        } catch (e: Exception) {
            AuthResult(false, errorMessage = readableAuthError(e, "Authentication failed."))
        }
    }

    suspend fun signUpWithPassword(email: String, password: String): AuthResult = withContext(Dispatchers.IO) {
        try {
            val user = FirebaseAuth.getInstance()
                .createUserWithEmailAndPassword(email, password)
                .await()
                .user
                ?: return@withContext AuthResult(false, errorMessage = "Firebase user creation returned no user.")
            AuthResult(
                isSuccess = true,
                localId = user.uid,
                email = user.email,
                displayName = user.displayName
            )
        } catch (e: Exception) {
            AuthResult(false, errorMessage = readableAuthError(e, "Account creation failed."))
        }
    }

    suspend fun signInWithCredential(credential: com.google.firebase.auth.AuthCredential): AuthResult = withContext(Dispatchers.IO) {
        try {
            val user = FirebaseAuth.getInstance()
                .signInWithCredential(credential)
                .await()
                .user
                ?: return@withContext AuthResult(false, errorMessage = "Google sign-in returned no user.")
            AuthResult(
                isSuccess = true,
                localId = user.uid,
                email = user.email,
                displayName = user.displayName
            )
        } catch (e: Exception) {
            AuthResult(false, errorMessage = readableAuthError(e, "Google Sign-In failed."))
        }
    }

    suspend fun sendPasswordReset(email: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            FirebaseAuth.getInstance().sendPasswordResetEmail(email).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception(readableAuthError(e, "Failed to send reset email."), e))
        }
    }

    private fun readableAuthError(error: Exception, fallback: String): String = when (error) {
        is FirebaseAuthInvalidCredentialsException -> "Invalid email or password. Please verify your credentials."
        is FirebaseAuthInvalidUserException -> when (error.errorCode) {
            "ERROR_USER_DISABLED" -> "This administrator account has been disabled."
            "ERROR_USER_NOT_FOUND" -> "No administrator account found with this email."
            else -> "This administrator account is unavailable."
        }
        is FirebaseAuthUserCollisionException -> "This email is already registered. Please log in instead."
        is FirebaseAuthWeakPasswordException -> "Password is too weak. Please use at least 6 characters."
        is FirebaseAuthException -> when (error.errorCode) {
            "ERROR_TOO_MANY_REQUESTS" -> "Too many attempts. Please try again later."
            else -> error.localizedMessage ?: fallback
        }
        else -> error.localizedMessage ?: fallback
    }
}
