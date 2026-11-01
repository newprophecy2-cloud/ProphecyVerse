package com.example.notices

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import com.example.data.model.NoticeItem
import com.example.ui.components.NoticePreviewDialog
import com.example.ui.theme.InstitutionalGreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoticeEditorScreen(
    noticeId: String?,
    viewModel: NoticeViewModel,
    adminEmail: String,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val existingNotice = state.notices.find { it.id == noticeId }

    var title by remember { mutableStateOf(existingNotice?.title ?: "") }
    var content by remember { mutableStateOf(existingNotice?.content ?: "") }
    var type by remember { mutableStateOf(existingNotice?.type ?: "info") }
    var published by remember { mutableStateOf(existingNotice?.published ?: true) }
    var priority by remember { mutableLongStateOf(existingNotice?.priority ?: 0L) }
    var actionUrl by remember { mutableStateOf(existingNotice?.actionUrl ?: "") }
    var actionLabel by remember { mutableStateOf(existingNotice?.actionLabel ?: "") }
    var targetVersion by remember { mutableStateOf(existingNotice?.targetVersion ?: "") }

    var showPreview by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }

    val currentNoticeDraft = NoticeItem(
        id = noticeId ?: "",
        title = title.trim(),
        content = content.trim(),
        type = type,
        published = published,
        priority = priority,
        actionUrl = actionUrl.trim().ifBlank { null },
        actionLabel = actionLabel.trim().ifBlank { null },
        targetVersion = targetVersion.trim().ifBlank { null }
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (noticeId != null) "Edit Notice" else "New Notice") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("editor_back_btn")) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showPreview = true }, modifier = Modifier.testTag("editor_preview_btn")) {
                        Icon(imageVector = Icons.Default.Visibility, contentDescription = "Preview Notice")
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
                        text = "Notice Details",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )

                    OutlinedTextField(
                        value = title,
                        onValueChange = {
                            title = it
                            validationError = null
                        },
                        label = { Text("Title *") },
                        placeholder = { Text("e.g., Scheduled Maintenance Window") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("notice_title_input"),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = content,
                        onValueChange = {
                            content = it
                            validationError = null
                        },
                        label = { Text("Content / Description *") },
                        placeholder = { Text("Enter notice body text visible to users...") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("notice_content_input"),
                        minLines = 4,
                        maxLines = 8
                    )

                    Text(
                        text = "Notice Type",
                        style = MaterialTheme.typography.labelLarge
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("info", "warning", "alert", "maintenance").forEach { itemType ->
                            FilterChip(
                                selected = type == itemType,
                                onClick = { type = itemType },
                                label = { Text(itemType.replaceFirstChar { it.uppercase() }) },
                                modifier = Modifier.testTag("type_chip_$itemType")
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Publish Immediately",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Text(
                                text = "If enabled, notice is live in User APK",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = published,
                            onCheckedChange = { published = it },
                            modifier = Modifier.testTag("notice_publish_switch")
                        )
                    }
                }
            }

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
                        text = "Targeting & Actions (Optional)",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )

                    OutlinedTextField(
                        value = actionLabel,
                        onValueChange = { actionLabel = it },
                        label = { Text("Button Label") },
                        placeholder = { Text("e.g., Update Now, Read More") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = actionUrl,
                        onValueChange = { actionUrl = it },
                        label = { Text("Button Action URL") },
                        placeholder = { Text("e.g., https://prophecyverse.app/download") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = targetVersion,
                        onValueChange = { targetVersion = it },
                        label = { Text("Target Version Code / Name") },
                        placeholder = { Text("e.g., 3.0.0 or leave empty for all") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = priority.toString(),
                        onValueChange = { priority = it.toLongOrNull() ?: 0L },
                        label = { Text("Display Priority (Higher = First)") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                }
            }

            validationError?.let { err ->
                Text(
                    text = err,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Cancel")
                }

                Button(
                    onClick = {
                        if (title.isBlank()) {
                            validationError = "Title is required"
                            return@Button
                        }
                        if (content.isBlank()) {
                            validationError = "Content is required"
                            return@Button
                        }
                        viewModel.saveNotice(currentNoticeDraft, adminEmail) {
                            onBack()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = InstitutionalGreen),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("save_notice_btn"),
                    enabled = !state.isSaving
                ) {
                    if (state.isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text("Save & Publish")
                    }
                }
            }
        }
    }

    if (showPreview) {
        NoticePreviewDialog(
            notice = currentNoticeDraft,
            onDismiss = { showPreview = false }
        )
    }
}
