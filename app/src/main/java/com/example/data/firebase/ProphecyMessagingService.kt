package com.example.data.firebase

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class ProphecyMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        InstallationManager.getInstance(applicationContext).updateFcmToken(token)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        val data = remoteMessage.data
        val eventType = data["event_type"] ?: data["type"] ?: "announcement"

        // Handle background auto-triggers based on notification event type
        when (eventType) {
            "apk_update", "mandatory_update", "new_apk_version" -> {
                VersionControlManager.getInstance(applicationContext).checkForUpdates()
            }
            "account_status" -> {
                val uid = AuthManager.getInstance(applicationContext).currentUid
                if (uid.isNotBlank()) {
                    // Triggers user profile reload
                    Log.i(TAG, "Received account status notification for $uid")
                }
            }
        }

        val title = remoteMessage.notification?.title
            ?: data["title"]
            ?: when (eventType) {
                "new_episode" -> "New Episode Released!"
                "new_anime" -> "New Anime Added!"
                "mandatory_update", "apk_update" -> "Prophecy Verse Update Available"
                "maintenance" -> "Scheduled Maintenance"
                "stream_issue" -> "Stream Status Notice"
                "download_complete" -> "Download Finished"
                else -> "Prophecy Verse"
            }

        val body = remoteMessage.notification?.body
            ?: data["body"]
            ?: data["message"]
            ?: "New content is ready to stream in Prophecy Verse."

        sendNotification(title, body, data)
    }

    private fun sendNotification(title: String, messageBody: String, data: Map<String, String>) {
        ensureNotificationChannel(this)
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            data.forEach { (key, value) -> putExtra(key, value) }
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            data.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(messageBody)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "POST_NOTIFICATIONS permission not granted; suppressing notification display")
            return
        }

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify((System.currentTimeMillis() % 100000).toInt(), notification)
    }

    companion object {
        private const val TAG = "ProphecyMessagingService"
        const val CHANNEL_ID = "prophecy_verse_notifications"

        fun ensureNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Prophecy Verse Announcements",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for new releases, updates, and maintenance"
            }
            manager.createNotificationChannel(channel)
        }
    }
}
