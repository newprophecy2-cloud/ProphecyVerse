package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.notice.NoticeContent
import com.example.data.notice.PublishedNotice
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.AccentIndigo
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.SurfaceHighlight
import com.example.ui.theme.SurfaceVariantDark
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun BilingualNoticeDialog(
    noticeContent: NoticeContent,
    isVisible: Boolean,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onAction: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (!isVisible) return
    val scrollState = rememberScrollState()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = modifier
                .fillMaxWidth(0.92f)
                .heightIn(max = 620.dp)
                .testTag("bilingual_notice_dialog"),
            shape = RoundedCornerShape(20.dp),
            color = SurfaceDark,
            border = BorderStroke(1.dp, SurfaceHighlight),
            tonalElevation = 10.dp
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier.size(38.dp).clip(CircleShape)
                                .background(Brush.linearGradient(listOf(AccentIndigo, AccentCyan))),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Campaign, contentDescription = null, tint = Color.White)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Published Notices",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            fontSize = 17.sp,
                            modifier = Modifier.testTag("notice_title")
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(36.dp).testTag("notice_close_cross_button")
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextMuted, modifier = Modifier.size(20.dp))
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                Column(
                    modifier = Modifier.weight(1f, fill = false).verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (noticeContent.isLoading && !noticeContent.hasLoaded) {
                        Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = AccentCyan, modifier = Modifier.size(28.dp))
                                Spacer(modifier = Modifier.height(10.dp))
                                Text("Loading published notices…", color = TextSecondary, fontSize = 13.sp)
                            }
                        }
                    }

                    noticeContent.errorMessage?.let { message ->
                        NoticeStatusCard(
                            message = message,
                            testTag = "notice_error_state",
                            isError = true
                        )
                        OutlinedButton(
                            onClick = onRetry,
                            modifier = Modifier.fillMaxWidth().testTag("notice_retry_button"),
                            border = BorderStroke(1.dp, AccentCyan),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = AccentCyan)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Retry", color = AccentCyan, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    if (!noticeContent.isLoading && noticeContent.errorMessage == null &&
                        noticeContent.hasLoaded && noticeContent.notices.isEmpty()) {
                        NoticeStatusCard(
                            message = "There are no published notices right now.",
                            testTag = "notice_empty_state",
                            isError = false
                        )
                    }

                    noticeContent.notices.forEach { notice ->
                        PublishedNoticeCard(notice = notice, onAction = onAction)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                OutlinedButton(
                    onClick = onDismiss,
                    border = BorderStroke(1.dp, SurfaceHighlight),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp).testTag("notice_dismiss_button")
                ) {
                    Text("Close", color = TextSecondary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun NoticeStatusCard(message: String, testTag: String, isError: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag(testTag),
        shape = RoundedCornerShape(14.dp),
        color = if (isError) Color(0x33E53935) else SurfaceVariantDark,
        border = BorderStroke(1.dp, if (isError) Color(0x66E53935) else SurfaceHighlight)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Default.Info, contentDescription = null, tint = if (isError) Color(0xFFFF6B6B) else AccentCyan)
            Text(message, color = TextSecondary, fontSize = 13.sp, lineHeight = 19.sp)
        }
    }
}

@Composable
private fun PublishedNoticeCard(notice: PublishedNotice, onAction: (String) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("published_notice_${notice.id}"),
        shape = RoundedCornerShape(14.dp),
        color = SurfaceVariantDark,
        border = BorderStroke(1.dp, SurfaceHighlight)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = notice.severity.replaceFirstChar { it.uppercase() },
                    color = AccentCyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("notice_severity_${notice.id}")
                )
                Spacer(modifier = Modifier.weight(1f))
                Text("Priority ${notice.priority}", color = TextMuted, fontSize = 10.sp)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = notice.title,
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag("notice_remote_title_${notice.id}")
            )
            if (notice.message.isNotBlank()) {
                Spacer(modifier = Modifier.height(5.dp))
                Text(
                    text = notice.message,
                    color = TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    modifier = Modifier.testTag("notice_remote_message_${notice.id}")
                )
            }
            if (!notice.actionUrl.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = { onAction(notice.actionUrl) },
                    modifier = Modifier.fillMaxWidth().height(44.dp).testTag("notice_action_${notice.id}"),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentIndigo)
                ) {
                    Text(notice.actionLabel ?: "Open link", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
