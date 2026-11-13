package com.example.data.firebase

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.remote.NetworkModule
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Request

class ProphecyMessagingService : FirebaseMessagingService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        InstallationManager.getInstance(applicationContext).updateFcmToken(token)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        val data = remoteMessage.data
        val eventType = data["event_type"] ?: data["type"] ?: "announcement"
        when (eventType) {
            "apk_update", "mandatory_update", "new_apk_version" -> VersionControlManager.getInstance(applicationContext).checkForUpdates()
            "account_status" -> if (AuthManager.getInstance(applicationContext).currentUid.isNotBlank()) Log.i(TAG, "Received account status notification")
        }
        val title = remoteMessage.notification?.title ?: data["title"] ?: when (eventType) {
            "new_episode" -> "New Episode Released!"
            "new_anime" -> "New Anime Added!"
            "mandatory_update", "apk_update" -> "Prophecy Verse Update Available"
            "maintenance" -> "Scheduled Maintenance"
            "stream_issue" -> "Stream Status Notice"
            "download_complete" -> "Download Finished"
            else -> "Prophecy Verse"
        }
        val body = remoteMessage.notification?.body ?: data["body"] ?: data["message"] ?: "New content is ready to stream in Prophecy Verse."
        val imageUrl = remoteMessage.notification?.imageUrl?.toString()?.takeIf(String::isNotBlank)
            ?: data["image_url"]?.takeIf(String::isNotBlank)
        sendNotification(title, body, data, imageUrl)
    }

    private fun sendNotification(title: String, messageBody: String, data: Map<String, String>, imageUrl: String?) {
        ensureNotificationChannel(this)
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            data.forEach { (key, value) -> putExtra(key, value) }
        }
        val pendingIntent = PendingIntent.getActivity(this, data.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        serviceScope.launch {
            val image = imageUrl?.let(::downloadNotificationImage)
            postNotification(title, messageBody, pendingIntent, image)
        }
    }

    private fun downloadNotificationImage(url: String): Bitmap? = runCatching {
        val uri = android.net.Uri.parse(url)
        if (uri.scheme != "https" && uri.scheme != "http") return@runCatching null
        NetworkModule.okHttpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return@runCatching null
            val body = response.body ?: return@runCatching null
            if (body.contentLength() > MAX_IMAGE_BYTES) return@runCatching null
            val bytes = body.bytes()
            if (bytes.size > MAX_IMAGE_BYTES) return@runCatching null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }.getOrNull()

    private fun postNotification(title: String, body: String, pendingIntent: PendingIntent, image: Bitmap?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher).setContentTitle(title).setContentText(body)
            .setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH).setContentIntent(pendingIntent)
        if (image != null) builder.setStyle(NotificationCompat.BigPictureStyle().bigPicture(image).bigLargeIcon(null as Bitmap?))
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify((System.currentTimeMillis() % 100000).toInt(), builder.build())
    }

    companion object {
        private const val TAG = "ProphecyMessagingService"
        private const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
        const val CHANNEL_ID = "prophecy_verse_notifications"
        fun ensureNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Prophecy Verse Announcements", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Notifications for new releases, updates, and maintenance"
            })
        }
    }
}
