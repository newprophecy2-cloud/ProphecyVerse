package com.example.ui.screens

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.components.SectionHeader
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.AccentGold
import com.example.ui.theme.AccentIndigo
import com.example.ui.theme.AccentPurple
import com.example.ui.theme.PlayableGreen
import com.example.ui.theme.SpaceDark
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.SurfaceHighlight
import com.example.ui.theme.SurfaceVariantDark
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.viewmodels.ProfileViewModel

data class AvatarOption(val id: String, val name: String, val emoji: String, val color: Color)

val AVATAR_OPTIONS = listOf(
    AvatarOption("avatar_pirate", "Pirate Captain", "🏴‍☠️", Color(0xFFFF5252)),
    AvatarOption("avatar_shinobi", "Shadow Shinobi", "🥷", Color(0xFF448AFF)),
    AvatarOption("avatar_sorcerer", "Jujutsu Sorcerer", "🧿", Color(0xFF7C4DFF)),
    AvatarOption("avatar_saiyan", "Super Warrior", "⚡", Color(0xFFFFD700)),
    AvatarOption("avatar_slayer", "Demon Hunter", "⚔️", Color(0xFF00E676)),
    AvatarOption("avatar_alchemist", "State Alchemist", "✨", Color(0xFFFF9100)),
    AvatarOption("avatar_titan", "Scout Trooper", "🛡️", Color(0xFF00B0FF)),
    AvatarOption("avatar_monarch", "Shadow Monarch", "👑", Color(0xFFE040FB))
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    onNavigateToHistory: () -> Unit,
    onNavigateToLibrary: () -> Unit,
    onNavigateToDownloads: () -> Unit,
    onPlayHistoryItem: (animeId: String, episodeId: String) -> Unit
) {
    val context = LocalContext.current
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val watchlist by viewModel.watchlist.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val continueWatching by viewModel.continueWatching.collectAsStateWithLifecycle()
    val storageStats by viewModel.storageStats.collectAsStateWithLifecycle()

    var showEditProfileSheet by remember { mutableStateOf(false) }

    val currentName = profile?.name ?: "Anime Explorer"
    val currentAvatarId = profile?.avatarId ?: "avatar_pirate"
    val currentAvatar = AVATAR_OPTIONS.firstOrNull { it.id == currentAvatarId } ?: AVATAR_OPTIONS.first()
    val favoritesCount = watchlist.count { it.isFavorite }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(SpaceDark)
            .testTag("profile_screen"),
        contentPadding = PaddingValues(bottom = 90.dp)
    ) {
        // Profile Header Card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceVariantDark)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Avatar Circle
                            Surface(
                                modifier = Modifier
                                    .size(68.dp)
                                    .clip(CircleShape),
                                color = currentAvatar.color.copy(alpha = 0.25f),
                                border = androidx.compose.foundation.BorderStroke(2.dp, currentAvatar.color)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = currentAvatar.emoji,
                                        fontSize = 32.sp
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(16.dp))

                            Column {
                                Text(
                                    text = currentName,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = AccentCyan.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = "On-Device Local Profile",
                                        color = AccentCyan,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        IconButton(
                            onClick = { showEditProfileSheet = true },
                            modifier = Modifier.testTag("edit_profile_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit Profile",
                                tint = AccentCyan
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Stats Bar (Watchlist, History, Favorites, Offline)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SurfaceDark, RoundedCornerShape(12.dp))
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        ProfileStatItem(
                            count = watchlist.size.toString(),
                            label = "Watchlist",
                            onClick = onNavigateToLibrary
                        )
                        ProfileStatItem(
                            count = history.size.toString(),
                            label = "History",
                            onClick = onNavigateToHistory
                        )
                        ProfileStatItem(
                            count = favoritesCount.toString(),
                            label = "Favorites",
                            onClick = onNavigateToLibrary
                        )
                        val usedSpace = Formatter.formatFileSize(context, storageStats.usedBytes)
                        ProfileStatItem(
                            count = usedSpace,
                            label = "Downloads",
                            onClick = onNavigateToDownloads
                        )
                    }
                }
            }
        }

        // Continue Watching Quick Rail
        if (continueWatching.isNotEmpty()) {
            item {
                SectionHeader(title = "In Progress", subtitle = "Your local playback resume points")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(continueWatching, key = { it.id }) { item ->
                        ContinueWatchingCard(
                            history = item,
                            onClick = { onPlayHistoryItem(item.animeId, item.episodeId) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        // Quick Access Cards
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ProfileQuickCard(
                    icon = Icons.Default.History,
                    iconTint = AccentIndigo,
                    title = "Watch History",
                    subtitle = "${history.size} episodes watched • Resume points saved",
                    onClick = onNavigateToHistory
                )

                ProfileQuickCard(
                    icon = Icons.Default.Bookmark,
                    iconTint = AccentCyan,
                    title = "My Watchlist & Favorites",
                    subtitle = "${watchlist.size} saved titles • $favoritesCount marked favorites",
                    onClick = onNavigateToLibrary
                )

                ProfileQuickCard(
                    icon = Icons.Default.Download,
                    iconTint = PlayableGreen,
                    title = "Downloads & Offline Storage",
                    subtitle = "Manage app-private downloaded media",
                    onClick = onNavigateToDownloads
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Local Storage & Data Privacy Information
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceVariantDark)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = AccentCyan,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Device-Local Profile Guarantee",
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            fontSize = 13.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "• Private by Design: No cloud registration or login is required to stream or track progress.\n" +
                               "• 100% Offline Compatible: Profile name, avatar, history, and favorites remain available without internet connectivity.\n" +
                               "• App-Private Storage: All records are kept inside the local database on this device.\n" +
                               "• Data Durability Notice: App-private local data may be removed if you manually clear the app's storage in Android Settings or uninstall the application.",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }
            }
        }
    }

    // Edit Profile Modal Bottom Sheet
    if (showEditProfileSheet) {
        val sheetState = rememberModalBottomSheetState()
        var editedName by remember { mutableStateOf(currentName) }
        var selectedAvatarId by remember { mutableStateOf(currentAvatarId) }

        ModalBottomSheet(
            onDismissRequest = { showEditProfileSheet = false },
            sheetState = sheetState,
            containerColor = SurfaceDark
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .padding(bottom = 32.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Edit Local Profile",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    IconButton(onClick = { showEditProfileSheet = false }) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = TextMuted)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Profile Name input
                OutlinedTextField(
                    value = editedName,
                    onValueChange = { editedName = it },
                    label = { Text("Profile Name") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = SurfaceVariantDark,
                        unfocusedContainerColor = SurfaceVariantDark,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedIndicatorColor = AccentCyan,
                        unfocusedIndicatorColor = SurfaceHighlight
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(18.dp))

                Text(
                    text = "Select Avatar",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = AccentCyan
                )
                Spacer(modifier = Modifier.height(10.dp))

                // Grid of 8 anime avatar selections
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    AVATAR_OPTIONS.take(4).forEach { avatar ->
                        AvatarSelectionPill(
                            avatar = avatar,
                            isSelected = selectedAvatarId == avatar.id,
                            onSelect = { selectedAvatarId = avatar.id }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    AVATAR_OPTIONS.takeLast(4).forEach { avatar ->
                        AvatarSelectionPill(
                            avatar = avatar,
                            isSelected = selectedAvatarId == avatar.id,
                            onSelect = { selectedAvatarId = avatar.id }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = {
                        viewModel.updateProfile(
                            name = editedName.ifBlank { "Anime Explorer" },
                            avatarId = selectedAvatarId,
                            bio = "Prophecy Verse Streamer"
                        )
                        showEditProfileSheet = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentIndigo),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                ) {
                    Text("Save Profile Changes", fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }
}

@Composable
fun ProfileStatItem(
    count: String,
    label: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = count,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = AccentCyan
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
            fontSize = 11.sp
        )
    }
}

@Composable
fun ProfileQuickCard(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceVariantDark)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = iconTint.copy(alpha = 0.2f),
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(imageVector = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
fun AvatarSelectionPill(
    avatar: AvatarOption,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(62.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) avatar.color.copy(alpha = 0.35f) else SurfaceVariantDark)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) avatar.color else SurfaceHighlight,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onSelect),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = avatar.emoji, fontSize = 24.sp)
        }
    }
}
