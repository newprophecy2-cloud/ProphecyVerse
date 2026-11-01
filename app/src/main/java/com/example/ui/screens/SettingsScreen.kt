package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.example.ui.viewmodels.LibraryViewModel

@Composable
fun SettingsScreen(
    viewModel: LibraryViewModel
) {
    val context = LocalContext.current
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showAccountDialog by remember { mutableStateOf(false) }
    var showAudioPriorityDialog by remember { mutableStateOf(false) }
    var autoplayNext by remember { mutableStateOf(true) }
    var preferredSub by remember { mutableStateOf("English") }
    var skipInterval by remember { mutableStateOf(10) }

    val audioPreferences = remember { com.example.data.preferences.AudioPreferences.getInstance(context) }
    val audioConfig by audioPreferences.config.collectAsState()

    val authManager = remember { com.example.data.firebase.AuthManager.getInstance(context) }
    val authState by authManager.authState.collectAsState(initial = com.example.data.firebase.AuthState.Loading)
    val installationManager = remember { com.example.data.firebase.InstallationManager.getInstance(context) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(SpaceDark)
            .testTag("settings_screen"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp)
    ) {
        item {
            Text(
                text = "Settings & Preferences",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Account & Cloud Sync Section
        item {
            SettingsCategoryHeader(title = "Account & Cloud Sync")
            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showAccountDialog = true }
                    .testTag("account_settings_card"),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceVariantDark)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (authManager.isGuest) "Guest Session" else "Prophecy Verse Account",
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                fontSize = 16.sp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (authManager.isGuest) AccentCyan.copy(alpha = 0.2f) else PlayableGreen.copy(alpha = 0.2f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = if (authManager.isGuest) "ANONYMOUS" else "SYNCED",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (authManager.isGuest) AccentCyan else PlayableGreen
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (authManager.isGuest) "Tap to link account or sign in to sync watch progress across devices."
                            else "Logged in as ${authManager.currentUser?.email ?: "User"}",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Device ID: ${installationManager.installationId.take(10)}...",
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                    }

                    Button(
                        onClick = { showAccountDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceHighlight),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Text(
                            text = if (authManager.isGuest) "Link / Log In" else "Manage",
                            color = AccentCyan,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }

        // Playback Preferences Section
        item {
            SettingsCategoryHeader(title = "Player Configuration")
            Spacer(modifier = Modifier.height(8.dp))

            SettingsToggleRow(
                title = "Autoplay Next Episode",
                subtitle = "Automatically load next episode if verified source exists",
                checked = autoplayNext,
                onCheckedChange = { autoplayNext = it }
            )

            Spacer(modifier = Modifier.height(8.dp))

            SettingsOptionRow(
                title = "Seek Skip Interval",
                value = "${skipInterval}s",
                onClick = {
                    skipInterval = when (skipInterval) {
                        5 -> 10
                        10 -> 15
                        15 -> 30
                        else -> 5
                    }
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            SettingsOptionRow(
                title = "Audio Track Priority",
                value = audioConfig.displaySummary,
                onClick = { showAudioPriorityDialog = true }
            )

            Spacer(modifier = Modifier.height(8.dp))

            SettingsOptionRow(
                title = "Preferred Subtitles",
                value = preferredSub,
                onClick = {
                    preferredSub = if (preferredSub == "English") "Spanish" else "English"
                }
            )

            Spacer(modifier = Modifier.height(24.dp))
        }

        // Data & Offline Cache Controls
        item {
            SettingsCategoryHeader(title = "Data & Storage")
            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceVariantDark)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CleaningServices,
                            contentDescription = null,
                            tint = AccentCyan,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Local Cache Management",
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "All titles and search results are stored locally in Room. You can clear temporary discovery and search caches without losing your saved watchlist or watch history.",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { showClearCacheDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceHighlight),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("clear_cache_button")
                    ) {
                        Text(
                            text = "Clear Cached Discovery & Search",
                            color = AccentCyan,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }

        // Community & Support Section
        item {
            SettingsCategoryHeader(title = "Community & Support")
            Spacer(modifier = Modifier.height(8.dp))

            SettingsOptionRow(
                title = "📢 Community Notice & Telegram",
                value = "Open Notice",
                onClick = { com.example.data.notice.NoticeManager.getInstance(context).showNotice() }
            )

            Spacer(modifier = Modifier.height(8.dp))

            SettingsOptionRow(
                title = "🚀 Check for Updates",
                value = "v${com.example.BuildConfig.VERSION_NAME} (Build ${com.example.BuildConfig.VERSION_CODE})",
                onClick = {
                    com.example.data.firebase.VersionControlManager.getInstance(context).checkForUpdates()
                    Toast.makeText(context, "Checking for latest Prophecy Verse updates...", Toast.LENGTH_SHORT).show()
                }
            )

            Spacer(modifier = Modifier.height(40.dp))
        }
    }

    if (showAudioPriorityDialog) {
        com.example.ui.components.AudioPriorityDialog(
            onDismissRequest = { showAudioPriorityDialog = false }
        )
    }

    if (showAccountDialog) {
        AccountDialog(onDismissRequest = { showAccountDialog = false })
    }

    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            title = {
                Text(
                    text = "Clear Cached Catalog Data?",
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            },
            text = {
                Text(
                    text = "This will wipe cached discovery lists and search queries. Your watchlist and playback history will remain safe.",
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearCache()
                        showClearCacheDialog = false
                        Toast.makeText(context, "Local cache cleared", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentIndigo)
                ) {
                    Text("Clear Cache")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showClearCacheDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = SurfaceDark
        )
    }
}

@Composable
fun SettingsCategoryHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = AccentCyan,
        fontSize = 13.sp,
        letterSpacing = 0.5.sp
    )
}

@Composable
fun SettingsOptionRow(
    title: String,
    value: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceVariantDark)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = title, color = TextPrimary, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text(text = value, color = AccentCyan, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
    }
}

@Composable
fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceVariantDark)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, color = TextPrimary, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text(text = subtitle, color = TextMuted, fontSize = 11.sp)
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = AccentIndigo,
                    uncheckedThumbColor = TextMuted,
                    uncheckedTrackColor = SurfaceHighlight
                )
            )
        }
    }
}
