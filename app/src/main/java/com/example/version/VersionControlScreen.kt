package com.example.version

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.model.VersionConfig
import com.example.ui.components.ConfirmationDialog
import com.example.ui.theme.InstitutionalGreen

@Composable
fun VersionControlScreen(
    viewModel: VersionViewModel,
    adminEmail: String
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var versionName by remember { mutableStateOf(state.config.versionName) }
    var versionCode by remember { mutableLongStateOf(state.config.versionCode) }
    var minVersionCode by remember { mutableLongStateOf(state.config.minSupportedVersionCode) }
    var isMandatory by remember { mutableStateOf(state.config.isMandatory) }
    var updateUrl by remember { mutableStateOf(state.config.updateUrl) }
    var updateMessage by remember { mutableStateOf(state.config.updateMessage) }
    var sha256 by remember { mutableStateOf(state.config.sha256) }

    var showConfirmDialog by remember { mutableStateOf(false) }
    var showPreviewDialog by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }

    // Sync local form state when remote config loads
    LaunchedEffect(state.config) {
        versionName = state.config.versionName
        versionCode = state.config.versionCode
        minVersionCode = state.config.minSupportedVersionCode
        isMandatory = state.config.isMandatory
        updateUrl = state.config.updateUrl
        updateMessage = state.config.updateMessage
        sha256 = state.config.sha256
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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .testTag("version_control_screen"),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SystemUpdate,
                            contentDescription = null,
                            tint = InstitutionalGreen
                        )
                        Text(
                            text = "Prophecy Verse V3 Update Channel",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Configures remote version checks for the User APK. Dual-writes to app_config/version and version_config/latest.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Version details form
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Release Target",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )

                    OutlinedTextField(
                        value = versionName,
                        onValueChange = {
                            versionName = it
                            validationError = null
                        },
                        label = { Text("Version Name *") },
                        placeholder = { Text("e.g., 3.1.0") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("version_name_input"),
                        singleLine = true
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            value = versionCode.toString(),
                            onValueChange = {
                                versionCode = it.toLongOrNull() ?: versionCode
                                validationError = null
                            },
                            label = { Text("Version Code *") },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("version_code_input"),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = minVersionCode.toString(),
                            onValueChange = {
                                minVersionCode = it.toLongOrNull() ?: minVersionCode
                            },
                            label = { Text("Min Version Code") },
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Mandatory Update",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Text(
                                text = "Forces users to upgrade before opening app",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = isMandatory,
                            onCheckedChange = { isMandatory = it },
                            modifier = Modifier.testTag("mandatory_switch")
                        )
                    }
                }
            }

            // Distribution & Security Checksum
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Distribution & Integrity",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )

                    OutlinedTextField(
                        value = updateUrl,
                        onValueChange = {
                            updateUrl = it
                            validationError = null
                        },
                        label = { Text("Update Download URL *") },
                        placeholder = { Text("https://github.com/prophecyverse/releases/download/...") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("update_url_input"),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = updateMessage,
                        onValueChange = { updateMessage = it },
                        label = { Text("Changelog / Update Message") },
                        placeholder = { Text("What's new in this release...") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3
                    )

                    OutlinedTextField(
                        value = sha256,
                        onValueChange = {
                            sha256 = it.trim()
                            validationError = null
                        },
                        label = { Text("APK SHA-256 Checksum (Hex)") },
                        placeholder = { Text("64-character hex hash for integrity check") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }

            validationError?.let { err ->
                Text(
                    text = err,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { showPreviewDialog = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(imageVector = Icons.Default.Visibility, contentDescription = null)
                    Spacer(modifier = Modifier.padding(start = 4.dp))
                    Text("Preview")
                }

                Button(
                    onClick = {
                        if (versionName.isBlank()) {
                            validationError = "Version name cannot be blank."
                            return@Button
                        }
                        if (versionCode <= 0) {
                            validationError = "Version code must be a positive integer."
                            return@Button
                        }
                        if (!updateUrl.startsWith("http://") && !updateUrl.startsWith("https://")) {
                            validationError = "Update URL must be a valid http or https URL."
                            return@Button
                        }
                        if (sha256.isNotBlank() && sha256.length != 64) {
                            validationError = "SHA-256 checksum must be exactly 64 hexadecimal characters."
                            return@Button
                        }
                        showConfirmDialog = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = InstitutionalGreen),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("publish_version_btn"),
                    enabled = !state.isSaving
                ) {
                    if (state.isSaving) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.height(20.dp))
                    } else {
                        Text("Publish Update")
                    }
                }
            }
        }
    }

    // Confirmation dialog
    if (showConfirmDialog) {
        ConfirmationDialog(
            title = "Publish Version $versionName?",
            message = "This will update the version manifest checked by all Prophecy Verse V3 User APKs. Mandatory: $isMandatory.",
            confirmText = "Publish Live",
            onConfirm = {
                val newConfig = VersionConfig(
                    id = "version",
                    versionName = versionName.trim(),
                    versionCode = versionCode,
                    minSupportedVersionCode = minVersionCode,
                    isMandatory = isMandatory,
                    updateUrl = updateUrl.trim(),
                    updateMessage = updateMessage.trim(),
                    sha256 = sha256.trim(),
                    updateSources = listOf("DIRECT", "GITHUB"),
                    downloads = listOf(updateUrl.trim())
                )
                viewModel.saveVersionConfig(newConfig, adminEmail)
                showConfirmDialog = false
            },
            onDismiss = { showConfirmDialog = false }
        )
    }

    // Preview dialog
    if (showPreviewDialog) {
        AlertDialog(
            onDismissRequest = { showPreviewDialog = false },
            title = { Text("Update Prompt Preview") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (isMandatory) "Mandatory Update Required" else "Update Available",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = if (isMandatory) MaterialTheme.colorScheme.error else InstitutionalGreen
                    )
                    Text(
                        text = "Prophecy Verse v$versionName (build $versionCode) is now ready to install.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = updateMessage.ifBlank { "Performance updates and bug fixes." },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (sha256.isNotBlank()) {
                        Text(
                            text = "SHA-256 Verified: ${sha256.take(16)}...",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showPreviewDialog = false }) {
                    Text("Close Preview")
                }
            }
        )
    }
}
