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
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.ui.text.font.FontFamily
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

    var versionName by remember { mutableStateOf(state.config.latestVersionName) }
    var versionCode by remember { mutableLongStateOf(state.config.latestVersionCode) }
    var minVersionCode by remember { mutableLongStateOf(state.config.minSupportedVersionCode) }
    var forceUpdate by remember { mutableStateOf(state.config.forceUpdate) }
    var packageName by remember { mutableStateOf(state.config.packageName) }
    var telegramUrl by remember { mutableStateOf(versionSourceUrl(state.config, "telegram")) }
    var websiteUrl by remember { mutableStateOf(versionSourceUrl(state.config, "website")) }
    var directApkUrl by remember { mutableStateOf(versionSourceUrl(state.config, "backup", "direct_apk", "apk")) }
    var changelogEn by remember { mutableStateOf(state.config.releaseNotes["en"] ?: "Prophecy Verse V3 with modern streaming, download manager, and enhanced player UI.") }
    var changelogBn by remember { mutableStateOf(state.config.releaseNotes["bn"] ?: "প্রফেসি ভার্স V3 আধুনিক স্ট্রিমিং ও উন্নত প্লেয়ার ইন্টারফেস।") }
    var sha256 by remember { mutableStateOf(state.config.artifactSha256) }
    var certSha256 by remember { mutableStateOf(state.config.signingCertificateSha256) }

    var showConfirmDialog by remember { mutableStateOf(false) }
    var showPreviewDialog by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }

    // Sync form when config is loaded
    LaunchedEffect(state.config) {
        versionName = state.config.latestVersionName
        versionCode = state.config.latestVersionCode
        minVersionCode = state.config.minSupportedVersionCode
        forceUpdate = state.config.forceUpdate
        packageName = state.config.packageName.ifBlank { "com.aistudio.prophecyverse.vykqtb" }
        telegramUrl = versionSourceUrl(state.config, "telegram")
        websiteUrl = versionSourceUrl(state.config, "website")
        directApkUrl = versionSourceUrl(state.config, "backup", "direct_apk", "apk")
        sha256 = state.config.artifactSha256
        certSha256 = state.config.signingCertificateSha256
        changelogEn = state.config.releaseNotes["en"] ?: changelogEn
        changelogBn = state.config.releaseNotes["bn"] ?: changelogBn
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
                        text = "Configures remote version checks for the User APK. Writes to app_config/version_control and logs release to update_history.",
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
                        label = { Text("Version Name * (e.g. 3.0.0)") },
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
                            label = { Text("Min Version Code *") },
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )
                    }

                    OutlinedTextField(
                        value = packageName,
                        onValueChange = { packageName = it },
                        label = { Text("User APK Package Name *") },
                        placeholder = { Text("com.aistudio.prophecyverse.vykqtb") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Force / Mandatory Update",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Text(
                                text = "Forces user client lock until update is installed",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = forceUpdate,
                            onCheckedChange = { forceUpdate = it },
                            modifier = Modifier.testTag("mandatory_switch")
                        )
                    }
                }
            }

            // Distribution URLs
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
                        text = "Distribution Channels",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )

                    OutlinedTextField(
                        value = telegramUrl,
                        onValueChange = { telegramUrl = it },
                        label = { Text("Telegram Channel Download Link") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = websiteUrl,
                        onValueChange = { websiteUrl = it },
                        label = { Text("Website Download Link") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = directApkUrl,
                        onValueChange = { directApkUrl = it },
                        label = { Text("Direct CDN APK Download Link") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = changelogEn,
                        onValueChange = { changelogEn = it },
                        label = { Text("Changelog / Release Notes (English)") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2
                    )

                    OutlinedTextField(
                        value = changelogBn,
                        onValueChange = { changelogBn = it },
                        label = { Text("Changelog / Release Notes (Bengali)") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2
                    )

                    OutlinedTextField(
                        value = sha256,
                        onValueChange = { sha256 = it.trim() },
                        label = { Text("Artifact SHA-256 (64 hex characters)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = certSha256,
                        onValueChange = { certSha256 = it.trim() },
                        label = { Text("Release Certificate SHA-256") },
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
                        if (packageName.isBlank()) {
                            validationError = "Package name cannot be blank."
                            return@Button
                        }
                        if (packageName.trim() != "com.aistudio.prophecyverse.vykqtb") {
                            validationError = "Package name must match the User APK: com.aistudio.prophecyverse.vykqtb."
                            return@Button
                        }
                        if (minVersionCode <= 0 || minVersionCode > versionCode) {
                            validationError = "Minimum supported version code must be positive and no greater than the latest version code."
                            return@Button
                        }
                        if (!directApkUrl.trim().startsWith("https://", ignoreCase = true) ||
                            !directApkUrl.trim().substringBefore('?').substringBefore('#').endsWith(".apk", ignoreCase = true)) {
                            validationError = "Enter a real HTTPS direct APK URL ending in .apk."
                            return@Button
                        }
                        if (!sha256.trim().matches(Regex("(?i)^[a-f0-9]{64}$"))) {
                            validationError = "Enter the actual APK SHA-256 fingerprint (64 hexadecimal characters)."
                            return@Button
                        }
                        if (!certSha256.trim().replace(":", "").matches(Regex("(?i)^[a-f0-9]{64}$"))) {
                            validationError = "Enter the actual signing certificate SHA-256 fingerprint (64 hexadecimal characters)."
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
            message = "This will update app_config/version_control checked by all Prophecy Verse V3 User APKs. Mandatory: $forceUpdate.",
            confirmText = "Publish Live",
            onConfirm = {
                val sources = buildMap<String, Any> {
                    if (telegramUrl.isNotBlank()) put("telegram", mapOf(
                        "url" to telegramUrl.trim(), "enabled" to true,
                        "priority" to 1, "label" to "Telegram Channel"
                    ))
                    if (websiteUrl.isNotBlank()) put("website", mapOf(
                        "url" to websiteUrl.trim(), "enabled" to true,
                        "priority" to 2, "label" to "Official Website"
                    ))
                    put("backup", mapOf(
                        "url" to directApkUrl.trim(), "enabled" to true,
                        "priority" to 3, "label" to "Direct APK", "isDirectApk" to true
                    ))
                }

                val newConfig = VersionConfig(
                    id = "version_control",
                    enabled = true,
                    forceUpdate = forceUpdate,
                    latestVersionCode = versionCode,
                    latestVersionName = versionName.trim(),
                    minSupportedVersionCode = minVersionCode,
                    packageName = packageName.trim(),
                    artifactSha256 = sha256.trim(),
                    signingCertificateSha256 = certSha256.trim(),
                    releaseNotes = mapOf(
                        "en" to changelogEn.trim(),
                        "bn" to changelogBn.trim()
                    ),
                    updateSources = sources,
                    downloads = sources
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
            title = { Text("User APK Prompt Preview") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (forceUpdate) "Mandatory Update Required" else "Update Available",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = if (forceUpdate) MaterialTheme.colorScheme.error else InstitutionalGreen
                    )
                    Text(
                        text = "Prophecy Verse v$versionName (build $versionCode) is ready to install.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = changelogEn.ifBlank { "Performance updates and bug fixes." },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (sha256.isNotBlank()) {
                        Text(
                            text = "SHA-256: ${sha256.take(16)}...",
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

private fun versionSourceUrl(config: VersionConfig, vararg keys: String): String {
    val sources = config.downloads.takeIf { it.isNotEmpty() } ?: config.updateSources
    return keys.firstNotNullOfOrNull { key ->
        (sources[key] as? Map<*, *>)?.get("url")?.toString()?.takeIf(String::isNotBlank)
    }.orEmpty()
}
