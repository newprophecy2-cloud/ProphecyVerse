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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.components.ConfirmationDialog
import com.example.ui.components.StatusBadge
import com.example.ui.theme.InstitutionalGreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserDetailScreen(
    uid: String,
    viewModel: UserViewModel,
    adminEmail: String,
    onBack: () -> Unit
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
                title = { Text("User Profile") },
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
                            Text(
                                text = user.displayName.ifBlank { "User Account" },
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                            )
                            StatusBadge(status = user.status)
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Email: ${user.email.ifBlank { "No email associated" }}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = "UID: ${user.uid}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = "Platform: ${user.platform} • App Version: ${user.clientVersion.ifBlank { "Unknown" }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
                        verticalArrangement = Arrangement.spacedBy(12.dp)
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
                            text = "Administrative actions are logged to the tamper-resistant audit trail. In accordance with zero-trust architecture, sensitive account changes are verified by server-side rules.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Suspend / Restore Action
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Account Access",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                                )
                                Text(
                                    text = if (user.status == "ACTIVE") "Account is in good standing" else "Account is currently suspended",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Button(
                                onClick = { showSuspendConfirm = true },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (user.status == "ACTIVE") MaterialTheme.colorScheme.error else InstitutionalGreen
                                ),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.testTag("toggle_suspend_btn")
                            ) {
                                Text(if (user.status == "ACTIVE") "Suspend" else "Restore")
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
                                    text = if (user.isDownloadBlocked) "Downloads blocked" else "Downloads permitted",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            OutlinedButton(
                                onClick = { showDownloadBlockConfirm = true },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.testTag("toggle_download_block_btn")
                            ) {
                                Text(if (user.isDownloadBlocked) "Allow Downloads" else "Block Downloads")
                            }
                        }
                    }
                }
            }
        }
    }

    // Confirmation dialogs
    if (showSuspendConfirm && user != null) {
        val willSuspend = user.status == "ACTIVE"
        ConfirmationDialog(
            title = if (willSuspend) "Suspend User Account" else "Restore User Account",
            message = if (willSuspend)
                "Are you sure you want to suspend UID ${user.uid}? The user will be locked out of Prophecy Verse services."
            else
                "Restore full application access for UID ${user.uid}?",
            confirmText = if (willSuspend) "Suspend User" else "Restore User",
            isDestructive = willSuspend,
            onConfirm = {
                viewModel.moderateUserStatus(
                    uid = user.uid,
                    newStatus = if (willSuspend) "SUSPENDED" else "ACTIVE",
                    reason = "Administrative review by $adminEmail",
                    adminEmail = adminEmail
                )
                showSuspendConfirm = false
            },
            onDismiss = { showSuspendConfirm = false }
        )
    }

    if (showDownloadBlockConfirm && user != null) {
        val willBlock = !user.isDownloadBlocked
        ConfirmationDialog(
            title = if (willBlock) "Block Offline Downloads" else "Restore Download Access",
            message = "Update download permission for user ${user.uid}?",
            confirmText = if (willBlock) "Block Downloads" else "Restore Access",
            isDestructive = willBlock,
            onConfirm = {
                viewModel.toggleDownloadBlock(
                    uid = user.uid,
                    currentBlocked = user.isDownloadBlocked,
                    adminEmail = adminEmail
                )
                showDownloadBlockConfirm = false
            },
            onDismiss = { showDownloadBlockConfirm = false }
        )
    }
}
