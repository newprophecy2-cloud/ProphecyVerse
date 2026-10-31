package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.preferences.AudioPreferences
import com.example.data.preferences.AudioPriorityConfig
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.AccentIndigo
import com.example.ui.theme.PlayableGreen
import com.example.ui.theme.SpaceDark
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.SurfaceHighlight
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun AudioPriorityDialog(
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current
    val audioPreferences = remember { AudioPreferences.getInstance(context) }
    val currentConfig by audioPreferences.config.collectAsState()

    var p1 by remember(currentConfig) { mutableStateOf(currentConfig.priority1) }
    var p2 by remember(currentConfig) { mutableStateOf(currentConfig.priority2) }
    var p3 by remember(currentConfig) { mutableStateOf(currentConfig.priority3) }

    val languages = AudioPreferences.AVAILABLE_LANGUAGES

    AlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.testTag("audio_priority_dialog"),
        containerColor = SurfaceDark,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(AccentCyan.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Audiotrack,
                        contentDescription = "Audio Priority",
                        tint = AccentCyan,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Audio Track Priority",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Auto-selection for multi-audio streams",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp)
            ) {
                Text(
                    text = "When an anime has multiple audio dubs, Prophecy Verse will select the first available language in your priority order.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    lineHeight = 18.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Priority 1
                AudioPrioritySelectorRow(
                    priorityLabel = "Priority 1 (Highest)",
                    badgeColor = AccentCyan,
                    currentLanguage = p1,
                    availableLanguages = languages,
                    onSelect = { selected ->
                        if (selected == p2) p2 = p1
                        else if (selected == p3) p3 = p1
                        p1 = selected
                    }
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Priority 2
                AudioPrioritySelectorRow(
                    priorityLabel = "Priority 2",
                    badgeColor = AccentIndigo,
                    currentLanguage = p2,
                    availableLanguages = languages,
                    onSelect = { selected ->
                        if (selected == p1) p1 = p2
                        else if (selected == p3) p3 = p2
                        p2 = selected
                    }
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Priority 3
                AudioPrioritySelectorRow(
                    priorityLabel = "Priority 3",
                    badgeColor = TextMuted,
                    currentLanguage = p3,
                    availableLanguages = languages,
                    onSelect = { selected ->
                        if (selected == p1) p1 = p3
                        else if (selected == p2) p2 = p3
                        p3 = selected
                    }
                )

                Spacer(modifier = Modifier.height(18.dp))

                Text(
                    text = "Quick Presets",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Preset 1: Hindi -> English -> Japanese (Default)
                PresetChip(
                    title = "Hindi → English → Japanese",
                    subtitle = "Default recommended priority",
                    isSelected = p1 == "Hindi" && p2 == "English" && p3 == "Japanese",
                    onClick = {
                        p1 = "Hindi"
                        p2 = "English"
                        p3 = "Japanese"
                    }
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Preset 2: English -> Hindi -> Japanese
                PresetChip(
                    title = "English → Hindi → Japanese",
                    subtitle = "English dub first",
                    isSelected = p1 == "English" && p2 == "Hindi" && p3 == "Japanese",
                    onClick = {
                        p1 = "English"
                        p2 = "Hindi"
                        p3 = "Japanese"
                    }
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Preset 3: Japanese -> English -> Hindi
                PresetChip(
                    title = "Japanese → English → Hindi",
                    subtitle = "Original Japanese audio first",
                    isSelected = p1 == "Japanese" && p2 == "English" && p3 == "Hindi",
                    onClick = {
                        p1 = "Japanese"
                        p2 = "English"
                        p3 = "Hindi"
                    }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    audioPreferences.updatePriority(p1, p2, p3)
                    onDismissRequest()
                },
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.testTag("save_audio_priority_button")
            ) {
                Text(text = "Save Priority", color = SpaceDark, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest,
                modifier = Modifier.testTag("cancel_audio_priority_button")
            ) {
                Text(text = "Cancel", color = TextSecondary)
            }
        }
    )
}

@Composable
private fun AudioPrioritySelectorRow(
    priorityLabel: String,
    badgeColor: Color,
    currentLanguage: String,
    availableLanguages: List<String>,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = true },
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceHighlight)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = priorityLabel,
                    fontSize = 11.sp,
                    color = badgeColor,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = currentLanguage,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
            }

            Box {
                Button(
                    onClick = { expanded = true },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark)
                ) {
                    Text(text = "Change", color = AccentCyan, fontSize = 12.sp)
                }

                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.background(SurfaceDark)
                ) {
                    availableLanguages.forEach { lang ->
                        DropdownMenuItem(
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = lang,
                                        color = if (lang == currentLanguage) AccentCyan else TextPrimary,
                                        fontWeight = if (lang == currentLanguage) FontWeight.Bold else FontWeight.Normal
                                    )
                                    if (lang == currentLanguage) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Selected",
                                            tint = AccentCyan,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            },
                            onClick = {
                                onSelect(lang)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetChip(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .border(
                width = 1.dp,
                color = if (isSelected) AccentCyan else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            ),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) AccentCyan.copy(alpha = 0.12f) else SurfaceHighlight.copy(alpha = 0.6f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isSelected) AccentCyan else TextPrimary
                )
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = TextMuted
                )
            }
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Active preset",
                    tint = AccentCyan,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
