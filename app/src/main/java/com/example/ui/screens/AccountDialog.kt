package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.download.DownloadManager
import com.example.data.firebase.AuthManager
import com.example.data.firebase.AuthState
import com.example.data.firebase.CloudHistoryRepository
import com.example.data.firebase.InstallationManager
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.AccentIndigo
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.PlayableGreen
import com.example.ui.theme.SpaceDark
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.SurfaceHighlight
import com.example.ui.theme.SurfaceVariantDark
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@Composable
fun AccountDialog(
    initialLoginMode: Boolean = true,
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val authManager = remember { AuthManager.getInstance(context) }
    val installationManager = remember { InstallationManager.getInstance(context) }
    val downloadManager = remember { DownloadManager.getInstance(context) }
    val authState by authManager.authState.collectAsState()
    val pendingDownload by authManager.pendingDownload.collectAsState()

    var isLoginMode by remember { mutableStateOf(initialLoginMode) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var confirmPasswordVisible by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var isSyncing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showForgotPasswordDialog by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.testTag("account_dialog"),
        containerColor = SurfaceDark,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(AccentCyan.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AccountCircle,
                        contentDescription = "Account",
                        tint = AccentCyan,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Prophecy Verse Account",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = when (authState) {
                            is AuthState.Suspended -> "Account Suspended (Restricted)"
                            is AuthState.Authenticated -> "Cloud Synchronized • Downloads Enabled"
                            else -> "Guest Session • Local Only"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when (authState) {
                            is AuthState.Suspended -> ErrorRed
                            is AuthState.Authenticated -> PlayableGreen
                            else -> AccentCyan
                        }
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(vertical = 4.dp)
            ) {
                // Preserved download notification
                if (pendingDownload != null && authManager.isGuest) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        colors = CardDefaults.cardColors(containerColor = AccentCyan.copy(alpha = 0.12f)),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, AccentCyan)
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Download Pending Sign-In",
                                    color = AccentCyan,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                                Text(
                                    text = "${pendingDownload?.animeTitle} — Ep ${pendingDownload?.episodeNumber}",
                                    color = TextPrimary,
                                    fontSize = 11.sp,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                when (val state = authState) {
                    is AuthState.Suspended -> {
                        // User account suspended view
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = ErrorRed.copy(alpha = 0.15f)),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, ErrorRed)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = "Suspended",
                                        tint = ErrorRed,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Account Suspended",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = ErrorRed,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = state.reason,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextPrimary
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Downloads and account synchronization are blocked for suspended users. Contact administration on Telegram if you believe this is in error.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMuted
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        OutlinedButton(
                            onClick = {
                                authManager.signOut()
                                Toast.makeText(context, "Logged out to guest session", Toast.LENGTH_SHORT).show()
                                onDismissRequest()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(text = "Log Out (Continue as Guest)", color = TextPrimary)
                        }
                    }

                    is AuthState.Authenticated -> {
                        // User is logged in
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = SurfaceHighlight),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.CloudDone,
                                        contentDescription = "Cloud Connected",
                                        tint = PlayableGreen,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Cloud Sync & Downloads Active",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = PlayableGreen,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "Email: ${state.email}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Status: ${state.profile.status.replaceFirstChar { it.uppercase() }}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = PlayableGreen
                                )
                                Text(
                                    text = "User UID: ${state.user.uid.take(16)}...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMuted
                                )
                                Text(
                                    text = "Device ID: ${installationManager.installationId.take(16)}...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMuted
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = {
                                isSyncing = true
                                scope.launch {
                                    CloudHistoryRepository.getInstance(context).pullCloudHistoryAndWatchlist()
                                    isSyncing = false
                                    Toast.makeText(context, "Cloud history & watchlist synchronized!", Toast.LENGTH_SHORT).show()
                                }
                            },
                            enabled = !isSyncing,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("sync_cloud_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            if (isSyncing) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = SpaceDark)
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(imageVector = Icons.Default.Sync, contentDescription = null, tint = SpaceDark, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Sync Cloud Watch History",
                                        color = SpaceDark,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { showForgotPasswordDialog = true },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(text = "Reset Password", fontSize = 12.sp, color = TextPrimary)
                            }

                            OutlinedButton(
                                onClick = {
                                    authManager.signOut()
                                    Toast.makeText(context, "Switched to Guest Session", Toast.LENGTH_SHORT).show()
                                    onDismissRequest()
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .testTag("sign_out_button"),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(text = "Log Out", fontSize = 12.sp, color = ErrorRed)
                            }
                        }
                    }

                    else -> {
                        // User is in Guest mode: Tab bar for Log In vs Sign Up
                        TabRow(
                            selectedTabIndex = if (isLoginMode) 0 else 1,
                            containerColor = SurfaceVariantDark,
                            contentColor = AccentCyan,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp)),
                            indicator = { tabPositions ->
                                TabRowDefaults.SecondaryIndicator(
                                    modifier = Modifier.tabIndicatorOffset(tabPositions[if (isLoginMode) 0 else 1]),
                                    color = AccentCyan
                                )
                            }
                        ) {
                            Tab(
                                selected = isLoginMode,
                                onClick = {
                                    isLoginMode = true
                                    errorMessage = null
                                },
                                text = {
                                    Text(
                                        text = "Sign In",
                                        fontWeight = if (isLoginMode) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 13.sp
                                    )
                                },
                                modifier = Modifier.testTag("tab_login")
                            )
                            Tab(
                                selected = !isLoginMode,
                                onClick = {
                                    isLoginMode = false
                                    errorMessage = null
                                },
                                text = {
                                    Text(
                                        text = "Create Account",
                                        fontWeight = if (!isLoginMode) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 13.sp
                                    )
                                },
                                modifier = Modifier.testTag("tab_signup")
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            text = if (isLoginMode) "Log into your Prophecy Verse account to synchronize watchlist and unlock full downloads."
                            else "Create a free account. Your current guest watch history will be automatically preserved!",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        if (errorMessage != null) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 10.dp),
                                colors = CardDefaults.cardColors(containerColor = ErrorRed.copy(alpha = 0.15f)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Warning, contentDescription = null, tint = ErrorRed, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = errorMessage ?: "",
                                        color = ErrorRed,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }

                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it; errorMessage = null },
                            label = { Text("Email Address") },
                            leadingIcon = {
                                Icon(Icons.Default.Mail, contentDescription = "Email", tint = AccentCyan)
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentCyan,
                                unfocusedBorderColor = SurfaceHighlight,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedLabelColor = AccentCyan,
                                unfocusedLabelColor = TextMuted
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("auth_email_input"),
                            shape = RoundedCornerShape(10.dp)
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it; errorMessage = null },
                            label = { Text("Password (min 6 characters)") },
                            leadingIcon = {
                                Icon(Icons.Default.Lock, contentDescription = "Password", tint = AccentCyan)
                            },
                            trailingIcon = {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(
                                        imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                        contentDescription = "Toggle Password Visibility",
                                        tint = TextMuted
                                    )
                                }
                            },
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentCyan,
                                unfocusedBorderColor = SurfaceHighlight,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedLabelColor = AccentCyan,
                                unfocusedLabelColor = TextMuted
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("auth_password_input"),
                            shape = RoundedCornerShape(10.dp)
                        )

                        if (!isLoginMode) {
                            Spacer(modifier = Modifier.height(10.dp))

                            OutlinedTextField(
                                value = confirmPassword,
                                onValueChange = { confirmPassword = it; errorMessage = null },
                                label = { Text("Confirm Password") },
                                leadingIcon = {
                                    Icon(Icons.Default.Lock, contentDescription = "Confirm Password", tint = AccentCyan)
                                },
                                trailingIcon = {
                                    IconButton(onClick = { confirmPasswordVisible = !confirmPasswordVisible }) {
                                        Icon(
                                            imageVector = if (confirmPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                            contentDescription = "Toggle Confirm Password Visibility",
                                            tint = TextMuted
                                        )
                                    }
                                },
                                visualTransformation = if (confirmPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AccentCyan,
                                    unfocusedBorderColor = SurfaceHighlight,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary,
                                    focusedLabelColor = AccentCyan,
                                    unfocusedLabelColor = TextMuted
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("auth_confirm_password_input"),
                                shape = RoundedCornerShape(10.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = {
                                if (email.isBlank() || password.length < 6) {
                                    errorMessage = "Please enter a valid email and 6+ character password."
                                    return@Button
                                }
                                if (!isLoginMode && password != confirmPassword) {
                                    errorMessage = "Passwords do not match."
                                    return@Button
                                }
                                isLoading = true
                                errorMessage = null
                                scope.launch {
                                    val res = if (isLoginMode) {
                                        authManager.loginWithEmail(email, password)
                                    } else {
                                        authManager.registerWithEmail(email, password)
                                    }
                                    isLoading = false
                                    if (res.isSuccess) {
                                        CloudHistoryRepository.getInstance(context).pullCloudHistoryAndWatchlist()

                                        val pending = authManager.pendingDownload.value
                                        if (pending != null) {
                                            downloadManager.startOrResumeDownload(
                                                animeId = pending.animeId,
                                                episodeId = pending.episodeId,
                                                seasonNumber = pending.seasonNumber,
                                                episodeNumber = pending.episodeNumber,
                                                episodeTitle = pending.episodeTitle,
                                                animeTitle = pending.animeTitle,
                                                posterUrl = pending.posterUrl,
                                                mediaType = pending.mediaType,
                                                preferredQuality = pending.preferredQuality,
                                                preferredLanguage = pending.preferredLanguage,
                                                preferredSourceId = pending.preferredSourceId
                                            )
                                            authManager.clearPendingDownload()
                                            Toast.makeText(context, "Account verified! Download started.", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, if (isLoginMode) "Welcome back!" else "Account created! Watch history preserved.", Toast.LENGTH_SHORT).show()
                                        }
                                        onDismissRequest()
                                    } else {
                                        val exMsg = res.exceptionOrNull()?.localizedMessage ?: "Authentication failed."
                                        errorMessage = when {
                                            exMsg.contains("email address is already in use", ignoreCase = true) ->
                                                "Email already in use. Please switch to Log In."
                                            exMsg.contains("badly formatted", ignoreCase = true) ->
                                                "Invalid email address format."
                                            exMsg.contains("no user record", ignoreCase = true) || exMsg.contains("invalid credential", ignoreCase = true) ->
                                                "Incorrect email or password."
                                            else -> exMsg
                                        }
                                    }
                                }
                            },
                            enabled = !isLoading,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("auth_submit_button"),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = SpaceDark)
                            } else {
                                Text(
                                    text = if (isLoginMode) "Sign In" else "Create Account & Unlock Downloads",
                                    color = SpaceDark,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = {
                                    isLoginMode = !isLoginMode
                                    errorMessage = null
                                },
                                modifier = Modifier.testTag("switch_auth_mode_button")
                            ) {
                                Text(
                                    text = if (isLoginMode) "Need an account? Sign Up" else "Have an account? Sign In",
                                    color = AccentCyan,
                                    fontSize = 12.sp
                                )
                            }

                            if (isLoginMode) {
                                TextButton(
                                    onClick = { showForgotPasswordDialog = true }
                                ) {
                                    Text(text = "Forgot password?", color = TextMuted, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismissRequest,
                modifier = Modifier.testTag("close_account_dialog_button")
            ) {
                Text(text = "Close", color = TextSecondary)
            }
        }
    )

    if (showForgotPasswordDialog) {
        var resetEmail by remember { mutableStateOf(email) }
        var resetSent by remember { mutableStateOf(false) }
        var resetLoading by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showForgotPasswordDialog = false },
            containerColor = SurfaceDark,
            title = { Text("Reset Password", color = TextPrimary) },
            text = {
                Column {
                    Text(
                        text = "Enter your registered email address to receive a password reset link:",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = resetEmail,
                        onValueChange = { resetEmail = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentCyan,
                            unfocusedBorderColor = SurfaceHighlight,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        shape = RoundedCornerShape(8.dp)
                    )
                    if (resetSent) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Reset email sent! Please check your inbox and spam folder.",
                            color = PlayableGreen,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (resetEmail.isNotBlank()) {
                            resetLoading = true
                            scope.launch {
                                authManager.sendPasswordReset(resetEmail)
                                resetLoading = false
                                resetSent = true
                            }
                        }
                    },
                    enabled = !resetLoading,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
                ) {
                    if (resetLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = SpaceDark)
                    } else {
                        Text("Send Link", color = SpaceDark)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showForgotPasswordDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }
}
