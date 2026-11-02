package com.example.notifications

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Send
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.PushNotification
import com.example.ui.components.SectionHeader
import com.example.ui.components.StatusBadge
import com.example.ui.theme.InstitutionalGreen
import java.text.SimpleDateFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationManagementScreen(
    viewModel: NotificationViewModel,
    adminEmail: String
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var selectedEventType by remember { mutableStateOf("general") }
    var target by remember { mutableStateOf("/topics/all") }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var deepLink by remember { mutableStateOf("") }
    var imageUrl by remember { mutableStateOf("") }
    var validationError by remember { mutableStateOf<String?>(null) }

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

    val supportedEventTypes = listOf(
        "general" to "General Announcement",
        "apk_update" to "APK Update Alert",
        "mandatory_update" to "Mandatory Upgrade",
        "new_episode" to "New Episode Released",
        "system_notice" to "System Notice",
        "maintenance" to "Maintenance Notice",
        "account_status" to "Account Status",
        "security_alert" to "Security Alert"
    )

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .testTag("notification_management_screen"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header Card
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.NotificationsActive,
                                contentDescription = null,
                                tint = InstitutionalGreen,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.size(10.dp))
                            Column {
                                Text(
                                    text = "FCM Push Notification Dispatcher",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = "Queues broadcast or targeted push alerts to User APK devices",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Compose Card
            item {
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
                            text = "Compose Push Notification",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        )

                        Text("Event Type (determines User APK handler):", style = MaterialTheme.typography.labelSmall)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            supportedEventTypes.forEach { (typeKey, typeLabel) ->
                                FilterChip(
                                    selected = selectedEventType == typeKey,
                                    onClick = { selectedEventType = typeKey },
                                    label = { Text(typeLabel, fontSize = 11.sp) }
                                )
                            }
                        }

                        OutlinedTextField(
                            value = target,
                            onValueChange = { target = it },
                            label = { Text("Target Audience (e.g. /topics/all or FCM token)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                        )

                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it; validationError = null },
                            label = { Text("Notification Title *") },
                            placeholder = { Text("e.g. Prophecy Verse Update Available!") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("notification_title_input")
                        )

                        OutlinedTextField(
                            value = body,
                            onValueChange = { body = it; validationError = null },
                            label = { Text("Message Body *") },
                            placeholder = { Text("Enter message text shown in system notification shade...") },
                            minLines = 2,
                            maxLines = 4,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("notification_body_input")
                        )

                        OutlinedTextField(
                            value = deepLink,
                            onValueChange = { deepLink = it },
                            label = { Text("Deep Link (Optional, e.g. prophecyverse://update)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = imageUrl,
                            onValueChange = { imageUrl = it },
                            label = { Text("Rich Image URL (Optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        validationError?.let {
                            Text(text = it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }

                        Button(
                            onClick = {
                                if (title.isBlank()) {
                                    validationError = "Title is required"
                                    return@Button
                                }
                                if (body.isBlank()) {
                                    validationError = "Message body is required"
                                    return@Button
                                }
                                viewModel.queueNotification(
                                    eventType = selectedEventType,
                                    target = target,
                                    title = title,
                                    body = body,
                                    deepLink = deepLink,
                                    imageUrl = imageUrl,
                                    adminEmail = adminEmail
                                ) {
                                    title = ""
                                    body = ""
                                    deepLink = ""
                                    imageUrl = ""
                                }
                            },
                            enabled = !state.isSending,
                            colors = ButtonDefaults.buttonColors(containerColor = InstitutionalGreen),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .testTag("dispatch_notification_btn")
                        ) {
                            if (state.isSending) {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
                            } else {
                                Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.size(8.dp))
                                Text("Queue Notification for Dispatch")
                            }
                        }
                    }
                }
            }

            // Notification Queue History Section
            item {
                SectionHeader(
                    title = "Recent Notification Queue",
                    subtitle = "Processed by Prophecy Verse notification worker"
                )
            }

            if (state.isLoading) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = InstitutionalGreen)
                    }
                }
            } else if (state.notifications.isEmpty()) {
                item {
                    Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(imageVector = Icons.Default.Notifications, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("No notifications queued recently", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            } else {
                items(state.notifications, key = { it.id }) { notif ->
                    NotificationQueueItem(
                        notification = notif,
                        onCancel = { viewModel.cancelNotification(notif.id, adminEmail) }
                    )
                }
            }
        }
    }
}

@Composable
fun NotificationQueueItem(
    notification: PushNotification,
    onCancel: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatusBadge(status = notification.eventType.uppercase())
                    StatusBadge(status = notification.status.uppercase())
                }
                if (notification.status == "queued") {
                    IconButton(onClick = onCancel, modifier = Modifier.size(28.dp)) {
                        Icon(imageVector = Icons.Default.Delete, contentDescription = "Cancel", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = notification.title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
            )
            Text(
                text = notification.body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Target: ${notification.target}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                notification.createdAt?.let { ts ->
                    val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(ts.toDate())
                    Text(text = dateStr, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}
