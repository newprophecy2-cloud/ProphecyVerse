package com.example.notices

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import com.example.data.model.NoticeItem
import com.example.navigation.AdminNavDestination
import com.example.ui.components.ConfirmationDialog
import com.example.ui.components.NoticePreviewDialog
import com.example.ui.components.StatusBadge
import com.example.ui.theme.InstitutionalGreen

@Composable
fun NoticeManagementScreen(
    viewModel: NoticeViewModel,
    adminEmail: String,
    onNavigate: (AdminNavDestination) -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    var noticeToDelete by remember { mutableStateOf<NoticeItem?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

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
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onNavigate(AdminNavDestination.NoticeEditor()) },
                containerColor = InstitutionalGreen,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("New Notice") },
                modifier = Modifier.testTag("create_notice_fab")
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .testTag("notice_management_screen")
        ) {
            if (state.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = InstitutionalGreen)
                }
            } else if (state.notices.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Campaign,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No Notices Published",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Create an in-app notice or announcement for Prophecy Verse V3 users.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { onNavigate(AdminNavDestination.NoticeEditor()) }) {
                        Text("Create First Notice")
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(state.notices, key = { it.id }) { notice ->
                        NoticeCardItem(
                            notice = notice,
                            onTogglePublish = { viewModel.togglePublish(notice, adminEmail) },
                            onPreview = { viewModel.showPreview(notice) },
                            onEdit = { onNavigate(AdminNavDestination.NoticeEditor(notice.id)) },
                            onDelete = { noticeToDelete = notice }
                        )
                    }
                }
            }
        }
    }

    // Delete confirmation dialog
    noticeToDelete?.let { notice ->
        ConfirmationDialog(
            title = "Delete Notice",
            message = "Are you sure you want to permanently delete '${notice.title}'? This action cannot be undone.",
            confirmText = "Delete Notice",
            isDestructive = true,
            onConfirm = {
                viewModel.deleteNotice(notice.id, notice.title, adminEmail)
                noticeToDelete = null
            },
            onDismiss = { noticeToDelete = null }
        )
    }

    // Live preview dialog
    state.selectedNoticeForPreview?.let { notice ->
        NoticePreviewDialog(
            notice = notice,
            onDismiss = { viewModel.showPreview(null) }
        )
    }
}

@Composable
private fun NoticeCardItem(
    notice: NoticeItem,
    onTogglePublish: () -> Unit,
    onPreview: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("notice_item_${notice.id}")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatusBadge(status = notice.type)
                    StatusBadge(status = if (notice.published) "PUBLISHED" else "DRAFT")
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = if (notice.published) "Live" else "Draft",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Switch(
                        checked = notice.published,
                        onCheckedChange = { onTogglePublish() },
                        colors = SwitchDefaults.colors(checkedThumbColor = InstitutionalGreen)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = notice.title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = notice.content,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!notice.actionLabel.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Action: ${notice.actionLabel} (${notice.actionUrl.orEmpty()})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onPreview, modifier = Modifier.testTag("preview_btn_${notice.id}")) {
                    Icon(imageVector = Icons.Default.Visibility, contentDescription = "Preview Notice")
                }
                IconButton(onClick = onEdit, modifier = Modifier.testTag("edit_btn_${notice.id}")) {
                    Icon(imageVector = Icons.Default.Edit, contentDescription = "Edit Notice")
                }
                IconButton(onClick = onDelete, modifier = Modifier.testTag("delete_btn_${notice.id}")) {
                    Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete Notice", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
