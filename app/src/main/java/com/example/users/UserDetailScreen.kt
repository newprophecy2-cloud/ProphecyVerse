package com.example.users

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileDownloadOff
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.navigation.AdminNavDestination
import com.example.ui.components.ConfirmationDialog
import com.example.ui.components.StatusBadge
import com.example.ui.theme.InstitutionalGreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserDetailScreen(
    uid: String,
    viewModel: UserViewModel,
    adminEmail: String,
    onBack: () -> Unit,
    onNavigateToInstallation: (String) -> Unit = {}
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var showSuspendConfirm by remember { mutableStateOf(false) }
    var showDownloadBlockConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(uid) {
        viewModel.loadUserDetails(uid)
    }

    LaunchedEffect(state.errorMessage, state.successMessage) {
        state.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessages()
        }
        state.successMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessages()
        }
    }

    val user = state.selectedUser ?: state.users.find { it.uid == uid }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("User Account Profile") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (user == null) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(color = InstitutionalGreen)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Loading user profile for UID: $uid")
                    }
                }
            } else {
                // Identity Card
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = user.displayName.ifBlank { "User Account" },
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = user.email.ifBlank { "Anonymous / Guest User" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            StatusBadge(status = user.status.uppercase())
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        DetailRow("UID", user.uid, isMonospace = true)
                        DetailRow("Account Type", if (user.isAnonymous) "Guest / Anonymous" else "Registered Email User")
                        DetailRow("App Version", "${user.appVersion} (Code ${user.appVersionCode})")
                        DetailRow("Registered", user.formattedCreatedAt)
                        DetailRow("Last Login", user.formattedLastLogin)
                        DetailRow("Total Watch Time", user.formattedWatchTime)

                        if (user.installationId.isNotBlank()) {
                            DetailRow("Last Device Installation", user.installationId, isMonospace = true)
                            Spacer(modifier = Modifier.height(6.dp))
                            OutlinedButton(
                                onClick = { onNavigateToInstallation(user.installationId) },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Inspect Linked Installation")
                            }
                        }

                        if (user.isSuspended && user.suspensionReason.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.errorContainer,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(
                                        text = "Suspension Reason:",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    Text(
                                        text = user.suspensionReason,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }
                    }
                }

                // Security & Moderation Panel
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                tint = InstitutionalGreen
                            )
                            Text(
                                text = "Administrative Moderation",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }

                        Text(
                            text = "Actions update the user's status and download_blocked flags in Firestore. The User APK's AuthManager automatically observes status updates in real time.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        // Suspend / Restore Action
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Account Standing",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                                )
                                Text(
                                    text = if (user.isActive) "Account is in good standing" else "Account is currently suspended",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Button(
                                onClick = { showSuspendConfirm = true },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (user.isActive) MaterialTheme.colorScheme.error else InstitutionalGreen
                                ),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.testTag("toggle_suspend_btn")
                            ) {
                                Text(if (user.isActive) "Suspend" else "Restore")
                            }
                        }

                        // Download permission action
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Offline Downloads",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                                )
                                Text(
                                    text = if (user.downloadBlocked) "Downloads are blocked" else "Downloads are permitted",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            OutlinedButton(
                                onClick = { showDownloadBlockConfirm = true },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.testTag("toggle_download_block_btn")
                            ) {
                                Text(if (user.downloadBlocked) "Allow Downloads" else "Block Downloads")
                            }
                        }
                    }
                }
            }
        }
    }

    // Confirmation dialogs
    if (showSuspendConfirm && user != null) {
        val willSuspend = user.isActive
        ConfirmationDialog(
            title = if (willSuspend) "Suspend User Account" else "Restore User Account",
            message = if (willSuspend)
                "Suspend UID ${user.uid}? The user's account will be restricted on Prophecy Verse V3."
            else
                "Restore full application access for UID ${user.uid}?",
            confirmText = if (willSuspend) "Suspend User" else "Restore User",
            isDestructive = willSuspend,
            onConfirm = {
                viewModel.moderateUserStatus(
                    uid = user.uid,
                    newStatus = if (willSuspend) "suspended" else "active",
                    reason = "Administrative review by $adminEmail",
                    adminEmail = adminEmail
                )
                showSuspendConfirm = false
            },
            onDismiss = { showSuspendConfirm = false }
        )
    }

    if (showDownloadBlockConfirm && user != null) {
        val willBlock = !user.downloadBlocked
        ConfirmationDialog(
            title = if (willBlock) "Block Offline Downloads" else "Restore Download Access",
            message = "Update download permission for user ${user.uid}?",
            confirmText = if (willBlock) "Block Downloads" else "Restore Access",
            isDestructive = willBlock,
            onConfirm = {
                viewModel.toggleDownloadBlock(
                    uid = user.uid,
                    currentBlocked = user.downloadBlocked,
                    adminEmail = adminEmail
                )
                showDownloadBlockConfirm = false
            },
            onDismiss = { showDownloadBlockConfirm = false }
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String, isMonospace: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            fontFamily = if (isMonospace) FontFamily.Monospace else FontFamily.Default,
            maxLines = 1
        )
    }
}
