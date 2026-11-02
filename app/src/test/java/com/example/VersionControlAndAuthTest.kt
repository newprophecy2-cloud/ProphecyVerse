package com.example

import com.example.data.firebase.AuthManager
import com.example.data.firebase.DownloadEligibility
import com.example.data.firebase.DownloadSource
import com.example.data.firebase.UserProfile
import com.example.data.firebase.VersionCheckState
import com.example.data.firebase.VersionControlConfig
import com.example.data.firebase.VersionControlManager
import com.example.data.notice.parsePublishedNotice
import com.google.firebase.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Date

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VersionControlAndAuthTest {

    @Test
    fun `test legacy V2 client code 4 is strictly blocked when min version is 5`() {
        val config = VersionControlConfig(
            latestVersionCode = 5,
            latestVersionName = "3.0.0",
            minSupportedVersionCode = 5,
            forceUpdate = true
        )
        val state = VersionControlManager.evaluateVersionState(currentCode = 4, config = config)
        assertTrue("Client on build 4 must receive ForceUpdate", state is VersionCheckState.ForceUpdate)
    }

    @Test
    fun `test current V3 client code 5 is UpToDate when latest is 5`() {
        val config = VersionControlConfig(
            latestVersionCode = 5,
            latestVersionName = "3.0.0",
            minSupportedVersionCode = 5,
            forceUpdate = false
        )
        val state = VersionControlManager.evaluateVersionState(currentCode = 5, config = config)
        assertTrue("Client on build 5 must be UpToDate", state is VersionCheckState.UpToDate)
    }

    @Test
    fun `test optional update dialog when latest is 6 and forceUpdate is false`() {
        val config = VersionControlConfig(
            latestVersionCode = 6,
            latestVersionName = "3.1.0",
            minSupportedVersionCode = 5,
            forceUpdate = false
        )
        val state = VersionControlManager.evaluateVersionState(currentCode = 5, config = config)
        assertTrue("Client must receive OptionalUpdate", state is VersionCheckState.OptionalUpdate)
        val optional = state as VersionCheckState.OptionalUpdate
        assertEquals("3.1.0", optional.config.latestVersionName)
    }

    @Test
    fun `test force update when latest is 6 and forceUpdate is true`() {
        val config = VersionControlConfig(
            latestVersionCode = 6,
            latestVersionName = "3.1.0",
            minSupportedVersionCode = 5,
            forceUpdate = true
        )
        val state = VersionControlManager.evaluateVersionState(currentCode = 5, config = config)
        assertTrue("Client must receive ForceUpdate when forceUpdate flag is true", state is VersionCheckState.ForceUpdate)
    }

    @Test
    fun `test disabled config suppresses update prompt while missing config remains an error`() {
        val disabledConfig = VersionControlConfig(
            latestVersionCode = 5,
            latestVersionName = "3.0.0",
            enabled = false
        )
        val stateDisabled = VersionControlManager.evaluateVersionState(currentCode = 5, config = disabledConfig)
        assertTrue("Explicitly disabled updates must not block or prompt", stateDisabled is VersionCheckState.UpToDate)

        val stateNull = VersionControlManager.evaluateVersionState(currentCode = 5, config = null)
        assertTrue("Null config must result in VerificationFailed", stateNull is VersionCheckState.VerificationFailed)
    }

    @Test
    fun `published Admin notice maps real fields and optional action aliases`() {
        val notice = parsePublishedNotice(
            "notice-1",
            mapOf(
                "title" to "Service update",
                "message" to "A real announcement from Firestore.",
                "active" to true,
                "published" to true,
                "priority" to 7L,
                "severity" to "warning",
                "action_url" to "https://example.org/status",
                "action_label" to "Status"
            )
        )
        assertEquals("notice-1", notice?.id)
        assertEquals("Service update", notice?.title)
        assertEquals("A real announcement from Firestore.", notice?.message)
        assertEquals(7L, notice?.priority)
        assertEquals("warning", notice?.severity)
        assertEquals("https://example.org/status", notice?.actionUrl)
        assertEquals("Status", notice?.actionLabel)
    }

    @Test
    fun `unpublished or inactive notices are excluded`() {
        assertNull(parsePublishedNotice("unpublished", mapOf("published" to false, "active" to true)))
        assertNull(parsePublishedNotice("inactive", mapOf("published" to true, "active" to false)))
    }

    @Test
    fun `notice parser supports content and type compatibility mirrors`() {
        val notice = parsePublishedNotice(
            "legacy",
            mapOf("published" to true, "content" to "Fallback body", "type" to "info")
        )
        assertEquals("Fallback body", notice?.message)
        assertEquals("info", notice?.severity)
    }

    @Test
    fun `Admin server timestamp is accepted as Timestamp as well as legacy numeric millis`() {
        val timestamp = Timestamp(Date(1_700_000_000_000L))
        assertEquals(1_700_000_000_000L, com.example.data.firebase.parseAdminUpdatedAtMillis(timestamp))
        assertEquals(1_700_000_000_000L, com.example.data.firebase.parseAdminUpdatedAtMillis(1_700_000_000_000L))
    }

    @Test
    fun `test download sources priority sorting`() {
        val sources = listOf(
            DownloadSource(id = "backup", url = "https://cdn.example.com", priority = 3, label = "Backup CDN"),
            DownloadSource(id = "telegram", url = "https://t.me/example", priority = 1, label = "Telegram"),
            DownloadSource(id = "website", url = "https://example.com", priority = 2, label = "Website")
        )
        val sorted = sources.sortedBy { it.priority }
        assertEquals("telegram", sorted[0].id)
        assertEquals("website", sorted[1].id)
        assertEquals("backup", sorted[2].id)
    }

    @Test
    fun `test download eligibility for guest requires login`() {
        val eligibility = AuthManager.evaluateDownloadEligibility(user = null, profile = null)
        assertTrue("Guest user must require login for downloads", eligibility is DownloadEligibility.RequiresLogin)
    }

    @Test
    fun `test download eligibility for suspended user is Suspended`() {
        val suspendedProfile = UserProfile(
            uid = "user_123",
            email = "test@example.com",
            isAnonymous = false,
            status = "suspended",
            downloadBlocked = false
        )
        val eligibility = AuthManager.evaluateDownloadEligibility(user = "dummy_user", profile = suspendedProfile)
        assertTrue("Suspended user must have downloads blocked", eligibility is DownloadEligibility.Suspended)
    }

    @Test
    fun `test download eligibility for blocked user is Suspended`() {
        val blockedProfile = UserProfile(
            uid = "user_123",
            email = "test@example.com",
            isAnonymous = false,
            status = "active",
            downloadBlocked = true
        )
        val eligibility = AuthManager.evaluateDownloadEligibility(user = "dummy_user", profile = blockedProfile)
        assertTrue("User with downloadBlocked=true must have downloads blocked", eligibility is DownloadEligibility.Suspended)
    }

    @Test
    fun `test download eligibility for active user is Eligible`() {
        val activeProfile = UserProfile(
            uid = "user_123",
            email = "active@example.com",
            isAnonymous = false,
            status = "active",
            downloadBlocked = false
        )
        val eligibility = AuthManager.evaluateDownloadEligibility(user = "dummy_user", profile = activeProfile)
        assertTrue("Active user must be eligible for downloads", eligibility is DownloadEligibility.Eligible)
    }
}
