package com.example

import com.example.data.model.InstallationRecord
import com.example.data.model.NoticeItem
import com.example.data.model.PushNotification
import com.example.data.model.UserAccount
import com.example.data.model.VersionConfig
import com.google.firebase.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class AdminFunctionalityTest {

    @Test
    fun testInstallationRecord_activeStatusComputation() {
        val nowMs = System.currentTimeMillis()
        val justNowTimestamp = Timestamp(Date(nowMs - 60_000)) // 1 min ago
        val recentMs = nowMs - 2 * 3600_000L // 2 hours ago
        val staleMs = nowMs - 50 * 3600_000L // 50 hours ago

        val activeRecord = InstallationRecord(
            installationId = "inst_123",
            ownerUid = "uid_456",
            deviceManufacturer = "Google",
            deviceModel = "Pixel 8 Pro",
            appVersionName = "3.0.0",
            appVersionCode = 5,
            lastHeartbeatAt = justNowTimestamp
        )

        assertTrue("Should be active now if heartbeat < 5 mins ago", activeRecord.isActiveNow)
        assertTrue("Should be recently active if heartbeat < 24 hrs ago", activeRecord.isRecentlyActive)
        assertEquals("ACTIVE NOW", activeRecord.statusTag)

        val recentRecord = InstallationRecord(
            installationId = "inst_recent",
            lastActiveAt = recentMs
        )
        assertFalse("Should not be active now", recentRecord.isActiveNow)
        assertTrue("Should be recently active", recentRecord.isRecentlyActive)
        assertEquals("RECENTLY ACTIVE", recentRecord.statusTag)

        val staleRecord = InstallationRecord(
            installationId = "inst_stale",
            lastActiveAt = staleMs
        )
        assertFalse("Should not be active now", staleRecord.isActiveNow)
        assertFalse("Should not be recently active", staleRecord.isRecentlyActive)
        assertEquals("INACTIVE", staleRecord.statusTag)
    }

    @Test
    fun testUserAccount_statusAndPermissions() {
        val activeUser = UserAccount(
            uid = "usr_001",
            displayName = "Eren Yeager",
            email = "eren@paradise.io",
            status = "active",
            downloadBlocked = false,
            totalWatchSeconds = 7200L
        )

        assertTrue(activeUser.isActive)
        assertFalse(activeUser.isSuspended)
        assertFalse(activeUser.downloadBlocked)
        assertEquals("2h 0m", activeUser.formattedWatchTime)

        val suspendedUser = UserAccount(
            uid = "usr_002",
            displayName = "Zeke Yeager",
            email = "zeke@marley.io",
            status = "suspended",
            suspensionReason = "Terms violation",
            downloadBlocked = true
        )

        assertFalse(suspendedUser.isActive)
        assertTrue(suspendedUser.isSuspended)
        assertTrue(suspendedUser.downloadBlocked)
        assertEquals("Terms violation", suspendedUser.suspensionReason)
    }

    @Test
    fun testVersionConfig_userApkPackageMatching() {
        val config = VersionConfig(
            packageName = "com.aistudio.prophecyverse.vykqtb",
            latestVersionName = "3.0.0",
            latestVersionCode = 5L,
            minSupportedVersionCode = 4L,
            forceUpdate = false,
            releaseNotes = mapOf("en" to "Performance improvements and bug fixes"),
            updateSources = mapOf("direct_apk" to "https://prophecyverse.app/download.apk")
        )

        assertEquals("com.aistudio.prophecyverse.vykqtb", config.packageName)
        assertEquals(5L, config.latestVersionCode)
        assertEquals("3.0.0", config.latestVersionName)
        assertEquals(4L, config.minSupportedVersionCode)
        assertFalse(config.forceUpdate)
        assertEquals("Performance improvements and bug fixes", config.releaseNotes["en"])
        assertEquals("https://prophecyverse.app/download.apk", config.updateSources["direct_apk"])
    }

    @Test
    fun testNoticeItem_severityAndMessage() {
        val notice = NoticeItem(
            id = "notice_001",
            title = "Scheduled Maintenance",
            message = "Servers will undergo maintenance tonight at 2 AM UTC.",
            severity = "warning",
            active = true
        )

        assertEquals("Scheduled Maintenance", notice.title)
        assertEquals("Servers will undergo maintenance tonight at 2 AM UTC.", notice.displayContent)
        assertEquals("warning", notice.severity)
        assertTrue(notice.active)
    }

    @Test
    fun testPushNotification_structure() {
        val notification = PushNotification(
            id = "push_101",
            title = "New Episode Out!",
            body = "Episode 12 is now streaming in 1080p.",
            eventType = "new_episode",
            target = "all",
            deepLink = "prophecyverse://anime/solo-leveling/ep/12",
            status = "queued"
        )

        assertEquals("new_episode", notification.eventType)
        assertEquals("all", notification.target)
        assertEquals("prophecyverse://anime/solo-leveling/ep/12", notification.deepLink)
        assertEquals("queued", notification.status)
    }
}
