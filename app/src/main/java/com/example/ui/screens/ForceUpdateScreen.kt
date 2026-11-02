package com.example.ui.screens

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import com.example.BuildConfig
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.firebase.ApkDownloadState
import com.example.data.firebase.DownloadSource
import com.example.data.firebase.VersionCheckState
import com.example.data.firebase.VersionControlConfig
import com.example.data.firebase.VersionControlManager
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

@Composable
fun ForceUpdateScreen(
    versionState: VersionCheckState,
    onDismissOptional: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val versionManager = remember { VersionControlManager.getInstance(context) }
    val apkDownloadState by versionManager.apkDownloadState.collectAsState()

    // Only strict mandatory updates lock the hardware back button completely
    val isMandatory = versionState is VersionCheckState.ForceUpdate
    BackHandler(enabled = isMandatory) {
        // Strict Invariant: Normal navigation forbidden while mandatory update is active
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SpaceDark)
            .testTag("force_update_screen")
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        when (versionState) {
            is VersionCheckState.VerificationFailed -> {
                VerificationFailedCard(
                    errorMessage = versionState.message,
                    onRetry = { versionManager.checkForUpdates() }
                )
            }
            is VersionCheckState.ForceUpdate -> {
                UpdateCard(
                    config = versionState.config,
                    isMandatory = true,
                    apkDownloadState = apkDownloadState,
                    onDownloadSourceClick = { source ->
                        versionManager.startDirectApkDownload(source, versionState.config)
                    },
                    onCancelApkDownload = { versionManager.cancelApkDownload() },
                    onInstallApk = { file -> versionManager.launchPackageInstaller(file) },
                    onDismiss = null
                )
            }
            is VersionCheckState.OptionalUpdate -> {
                UpdateCard(
                    config = versionState.config,
                    isMandatory = false,
                    apkDownloadState = apkDownloadState,
                    onDownloadSourceClick = { source ->
                        versionManager.startDirectApkDownload(source, versionState.config)
                    },
                    onCancelApkDownload = { versionManager.cancelApkDownload() },
                    onInstallApk = { file -> versionManager.launchPackageInstaller(file) },
                    onDismiss = onDismissOptional
                )
            }
            else -> {
                CircularProgressIndicator(color = AccentCyan)
            }
        }
    }
}

@Composable
fun UpdateCard(
    config: VersionControlConfig,
    isMandatory: Boolean,
    apkDownloadState: ApkDownloadState,
    onDownloadSourceClick: (DownloadSource) -> Unit,
    onCancelApkDownload: () -> Unit,
    onInstallApk: (java.io.File) -> Unit,
    onDismiss: (() -> Unit)?
) {
    var selectedLanguage by remember { mutableStateOf("en") } // "en" or "bn"
    val scrollState = rememberScrollState()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(if (isMandatory) "mandatory_update_card" else "optional_update_card"),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (isMandatory) AccentCyan.copy(alpha = 0.4f) else SurfaceHighlight)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header Icon Badge
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                if (isMandatory) AccentIndigo else AccentCyan.copy(alpha = 0.2f),
                                if (isMandatory) AccentCyan else AccentIndigo.copy(alpha = 0.2f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.SystemUpdate,
                    contentDescription = "Update Available",
                    tint = if (isMandatory) SpaceDark else AccentCyan,
                    modifier = Modifier.size(36.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Title
            Text(
                text = if (isMandatory) "Mandatory V3 Update" else "New Version Available",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Version diff pill
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = SurfaceVariantDark,
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Current: v${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                    Text(
                        text = "  ➔  ",
                        color = AccentCyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Latest: v${config.latestVersionName} (code ${config.latestVersionCode})",
                        color = AccentCyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Release Notes Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = SpaceDark)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "What's New in v${config.latestVersionName}:",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    val notesText = if (selectedLanguage == "bn") {
                        config.releaseNotes.bn.ifBlank { config.releaseNotes.en }
                    } else {
                        config.releaseNotes.en.ifBlank { config.releaseNotes.bn }
                    }
                    Text(
                        text = notesText.ifBlank { "Stability improvements, enhanced player UI, improved streaming sources and new downloads." },
                        color = TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Download State / Direct Downloader
            when (val state = apkDownloadState) {
                is ApkDownloadState.Downloading -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceHighlight)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Downloading APK...",
                                    color = TextPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = "${state.progressPercent}%",
                                    color = AccentCyan,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { state.progressPercent / 100f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = AccentCyan,
                                trackColor = SpaceDark
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            val context = LocalContext.current
                            val downloadedStr = Formatter.formatFileSize(context, state.bytesDownloaded)
                            val totalStr = Formatter.formatFileSize(context, state.totalBytes)
                            Text(
                                text = "$downloadedStr / $totalStr",
                                color = TextMuted,
                                fontSize = 11.sp
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            OutlinedButton(
                                onClick = onCancelApkDownload,
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Text("Cancel Download", fontSize = 12.sp)
                            }
                        }
                    }
                }
                is ApkDownloadState.Verifying -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceHighlight)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = AccentCyan)
                            Column {
                                Text(
                                    text = "Verifying APK Integrity...",
                                    color = TextPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = "Checking SHA-256 and package signature",
                                    color = TextMuted,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
                is ApkDownloadState.ReadyToInstall -> {
                    Button(
                        onClick = { onInstallApk(state.apkFile) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("install_apk_button"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PlayableGreen)
                    ) {
                        Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = SpaceDark)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Install Verified Update",
                            color = SpaceDark,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
                is ApkDownloadState.Error -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = ErrorRed.copy(alpha = 0.15f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "Download Verification Failed",
                                color = ErrorRed,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = state.message,
                                color = TextPrimary,
                                fontSize = 12.sp
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    config.downloads.filter { it.isDirectApk }.forEach { source ->
                        DownloadSourceButton(source = source, onClick = { onDownloadSourceClick(source) })
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
                ApkDownloadState.Idle -> {
                    // Show Download Source Buttons
                    Text(
                        text = "Select Download Source:",
                        color = TextMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.align(Alignment.Start)
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    config.downloads.forEach { source ->
                        DownloadSourceButton(
                            source = source,
                            onClick = { onDownloadSourceClick(source) }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }

            // Dismiss option for optional update only
            if (!isMandatory && onDismiss != null && apkDownloadState !is ApkDownloadState.Downloading) {
                Spacer(modifier = Modifier.height(6.dp))
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("optional_update_later_button")
                ) {
                    Text(text = "Remind Me Later", color = TextMuted, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
fun DownloadSourceButton(
    source: DownloadSource,
    onClick: () -> Unit
) {
    val isTelegram = source.id.contains("telegram", ignoreCase = true) || source.url.contains("t.me", ignoreCase = true)
    val isDirect = source.isDirectApk || source.url.endsWith(".apk", ignoreCase = true)

    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .testTag("download_source_${source.id}"),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = when {
                isTelegram -> Color(0xFF229ED9) // Official Telegram Cyan-Blue
                isDirect -> AccentCyan
                else -> AccentIndigo
            }
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = when {
                    isTelegram -> Icons.Default.Send
                    isDirect -> Icons.Default.CloudDownload
                    else -> Icons.Default.Language
                },
                contentDescription = null,
                tint = if (isDirect) SpaceDark else Color.White,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = source.label.ifBlank {
                    when {
                        isTelegram -> "Download via Telegram"
                        isDirect -> "Direct APK Download"
                        else -> "Official Website Download"
                    }
                },
                color = if (isDirect) SpaceDark else Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
fun LanguageTogglePill(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) AccentCyan else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            color = if (isSelected) SpaceDark else TextSecondary,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
fun VerificationFailedCard(
    errorMessage: String,
    onRetry: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("version_check_failed_card"),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = androidx.compose.foundation.BorderStroke(1.dp, ErrorRed.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(ErrorRed.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = "Connection Warning",
                    tint = ErrorRed,
                    modifier = Modifier.size(34.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Version Verification",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onRetry,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("retry_version_check_button"),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, tint = SpaceDark)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Retry Connection",
                    color = SpaceDark,
                    fontWeight = FontWeight.Bold
                )
            }

        }
    }
}
