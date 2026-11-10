package com.example.data.notice

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.R
import org.json.JSONArray
import org.json.JSONObject

/**
 * Schedules published notices locally. Delivery is per installation and best-effort: Android may
 * defer alarms, and 29 slots are not a globally guaranteed delivery count.
 */
object LocalNotificationScheduler {
    private const val PREFS = "prophecy_local_notifications"
    private const val STORED_NOTICES = "stored_notices"
    private const val CHANNEL_ID = "prophecy_local_notices"
    private const val ACTION_FIRE = "com.aistudio.prophecyverse.LOCAL_NOTICE"
    private const val ACTION_RESCHEDULE = "com.aistudio.prophecyverse.RESCHEDULE_NOTICES"
    private const val SLOT_COUNT = 29
    private const val CYCLE_MS = 24L * 60L * 60L * 1000L
    private const val SLOT_INTERVAL_MS = CYCLE_MS / SLOT_COUNT

    data class NoticeSnapshot(val id: String, val title: String, val message: String)

    fun schedule(context: Context, notices: List<PublishedNotice>) {
        val app = context.applicationContext
        val sanitized = notices.map {
            NoticeSnapshot(it.id, it.title, it.message)
        }.filter { it.message.isNotBlank() }
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(STORED_NOTICES, encode(sanitized)).apply()
        cancelExisting(app)
        if (sanitized.isEmpty()) return
        ensureChannel(app)
        val alarmManager = app.getSystemService(AlarmManager::class.java) ?: return
        val firstAt = System.currentTimeMillis() + 5L * 60L * 1000L
        repeat(SLOT_COUNT) { slot ->
            val notice = sanitized[slot % sanitized.size]
            val requestCode = 1000 + slot
            val intent = Intent(app, LocalNoticeReceiver::class.java).apply {
                action = ACTION_FIRE
                putExtra("notice_id", notice.id)
                putExtra("title", notice.title)
                putExtra("message", notice.message)
                putExtra("notification_id", requestCode)
            }
            val pending = PendingIntent.getBroadcast(
                app, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
            )
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                firstAt + slot * SLOT_INTERVAL_MS,
                pending
            )
        }
    }

    fun rescheduleFromStored(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val notices = decode(prefs.getString(STORED_NOTICES, null))
        if (notices.isNotEmpty()) {
            schedule(context, notices.map { PublishedNotice(it.id, it.title, it.message, "info", 0, null, null) })
        }
    }

    private fun cancelExisting(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        repeat(SLOT_COUNT) { slot ->
            val requestCode = 1000 + slot
            val intent = Intent(context, LocalNoticeReceiver::class.java).apply { action = ACTION_FIRE }
            val pending = PendingIntent.getBroadcast(
                context, requestCode, intent, PendingIntent.FLAG_NO_CREATE or immutableFlag()
            )
            if (pending != null) {
                alarmManager.cancel(pending)
                pending.cancel()
            }
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Prophecy Verse notices", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    private fun encode(notices: List<NoticeSnapshot>): String = JSONArray().apply {
        notices.forEach { put(JSONObject().put("id", it.id).put("title", it.title).put("message", it.message)) }
    }.toString()

    private fun decode(raw: String?): List<NoticeSnapshot> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val json = JSONArray(raw)
            (0 until json.length()).mapNotNull { index ->
                val item = json.optJSONObject(index) ?: return@mapNotNull null
                NoticeSnapshot(item.optString("id"), item.optString("title"), item.optString("message"))
            }
        }.getOrDefault(emptyList())
    }

    private fun stableId(noticeId: String, slot: Int): Int = 1000 + ("$noticeId#$slot".hashCode() and 0x3fffffff)
    private fun immutableFlag(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

}

class LocalNoticeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != "com.aistudio.prophecyverse.LOCAL_NOTICE") return
        val app = context.applicationContext
        val title = intent.getStringExtra("title") ?: "Prophecy Verse"
        val message = intent.getStringExtra("message") ?: return
        ensureChannelForReceiver(app)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val notificationId = intent.getIntExtra("notification_id", 1)
        val notification = NotificationCompat.Builder(app, "prophecy_local_notices")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(app).notify(notificationId, notification)
    }

    private fun ensureChannelForReceiver(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(
                NotificationChannel("prophecy_local_notices", "Prophecy Verse notices", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }
}

class NoticeBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            LocalNotificationScheduler.rescheduleFromStored(context)
        }
    }
}
