package com.example

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.example.data.notice.NoticeLanguage
import com.example.data.notice.NoticeManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NoticeManagerTest {

    private lateinit var context: Context
    private lateinit var noticeManager: NoticeManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        noticeManager = NoticeManager.getInstance(context)
        noticeManager.clearCacheForTesting()
    }

    @After
    fun tearDown() {
        noticeManager.clearCacheForTesting()
    }

    @Test
    fun `country code BD selects Bangla language and complete Bangla copy`() {
        val content = NoticeManager.getContentForCountry("BD")
        assertEquals(NoticeLanguage.BANGLA, content.language)
        assertEquals("📢 জরুরি নোটিশ", content.title)
        assertTrue(content.streamUpdateTitle.contains("🎬"))
        assertTrue(content.streamUpdateBody.contains("Prophecy Verse"))
        assertTrue(content.supportTitle.contains("💬"))
        assertTrue(content.supportBody.contains("টেলিগ্রাম"))
        assertTrue(content.thankYouTitle.contains("💜"))
        assertEquals("টেলিগ্রাম চ্যানেলে যুক্ত হন", content.telegramButtonLabel)
        assertEquals("ঠিক আছে", content.dismissButtonLabel)
        assertEquals("https://t.me/newprophecyverse", content.telegramUrl)
    }

    @Test
    fun `country code BD is case insensitive`() {
        val contentLower = NoticeManager.getContentForCountry("bd")
        assertEquals(NoticeLanguage.BANGLA, contentLower.language)

        val contentWithSpaces = NoticeManager.getContentForCountry("  BD  ")
        assertEquals(NoticeLanguage.BANGLA, contentWithSpaces.language)
    }

    @Test
    fun `non-BD country code selects English language and complete English copy`() {
        val countries = listOf("US", "GB", "IN", "JP", "DE", "FR", "CA", "SG")
        for (code in countries) {
            val content = NoticeManager.getContentForCountry(code)
            assertEquals("Expected ENGLISH for country $code", NoticeLanguage.ENGLISH, content.language)
            assertEquals("📢 Important Notice", content.title)
            assertTrue(content.streamUpdateTitle.contains("🎬"))
            assertTrue(content.supportTitle.contains("💬"))
            assertTrue(content.thankYouTitle.contains("💜"))
            assertEquals("Join Telegram Channel", content.telegramButtonLabel)
            assertEquals("Got It", content.dismissButtonLabel)
            assertEquals("https://t.me/newprophecyverse", content.telegramUrl)
        }
    }

    @Test
    fun `null or empty country code safely defaults to English`() {
        val contentNull = NoticeManager.getContentForCountry(null)
        assertEquals(NoticeLanguage.ENGLISH, contentNull.language)

        val contentEmpty = NoticeManager.getContentForCountry("")
        assertEquals(NoticeLanguage.ENGLISH, contentEmpty.language)

        val contentBlank = NoticeManager.getContentForCountry("   ")
        assertEquals(NoticeLanguage.ENGLISH, contentBlank.language)
    }

    @Test
    fun `testing override forces specific language without network dependency`() = runBlocking {
        // Force Bangladesh override
        NoticeManager.countryOverrideForTesting = "BD"
        noticeManager.checkAndPrepareNotice(forceShow = true)

        val currentNotice = noticeManager.noticeContent.value
        assertEquals(NoticeLanguage.BANGLA, currentNotice.language)
        assertEquals("📢 জরুরি নোটিশ", currentNotice.title)
        assertTrue(noticeManager.isNoticeVisible.value)

        // Force US override
        NoticeManager.countryOverrideForTesting = "US"
        noticeManager.checkAndPrepareNotice(forceShow = true)

        val usNotice = noticeManager.noticeContent.value
        assertEquals(NoticeLanguage.ENGLISH, usNotice.language)
        assertEquals("📢 Important Notice", usNotice.title)
    }

    @Test
    fun `dismissNotice updates visibility state and showNotice reopens it`() {
        noticeManager.showNotice()
        assertTrue(noticeManager.isNoticeVisible.value)

        noticeManager.dismissNotice()
        assertFalse(noticeManager.isNoticeVisible.value)

        noticeManager.showNotice()
        assertTrue(noticeManager.isNoticeVisible.value)
    }

    @Test
    fun `app manifest contains zero device location permissions`() {
        val packageManager = context.packageManager
        val packageInfo = packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS
        )
        val requestedPermissions = packageInfo.requestedPermissions ?: emptyArray()

        assertFalse(
            "ACCESS_FINE_LOCATION must not be requested",
            requestedPermissions.contains("android.permission.ACCESS_FINE_LOCATION")
        )
        assertFalse(
            "ACCESS_COARSE_LOCATION must not be requested",
            requestedPermissions.contains("android.permission.ACCESS_COARSE_LOCATION")
        )
    }

    @Test
    fun `notice telegram intent points to official channel`() {
        assertEquals("https://t.me/newprophecyverse", NoticeManager.TELEGRAM_URL)
    }
}
