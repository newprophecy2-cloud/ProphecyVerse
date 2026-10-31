package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.preferences.AudioPreferences
import com.example.data.preferences.AudioPriorityConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AudioPriorityTest {

    @Test
    fun testDefaultAudioPriorityIsHindiEnglishJapanese() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AudioPreferences.getInstance(context)
        // Reset to default
        prefs.updatePriority("Hindi", "English", "Japanese")

        val config = prefs.config.value
        assertEquals("Hindi", config.priority1)
        assertEquals("English", config.priority2)
        assertEquals("Japanese", config.priority3)
        assertEquals("1. Hindi → 2. English → 3. Japanese", config.displaySummary)

        val codes = prefs.getPreferredAudioLanguageCodes()
        assertTrue(codes.contains("hi") && codes.contains("hin"))
        assertTrue(codes.contains("en") && codes.contains("eng"))
        assertTrue(codes.contains("ja") && codes.contains("jpn"))

        // Priority 1 (Hindi) should be first
        assertEquals("hi", codes[0])
        assertEquals("hin", codes[1])
        // Priority 2 (English) should be next
        assertEquals("en", codes[2])
        assertEquals("eng", codes[3])
        // Priority 3 (Japanese) should follow
        assertEquals("ja", codes[4])
        assertEquals("jpn", codes[5])
    }

    @Test
    fun testUserCanAdjustAudioPriority() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AudioPreferences.getInstance(context)

        // User changes priority to: 1. English, 2. Hindi, 3. Japanese
        prefs.updatePriority("English", "Hindi", "Japanese")

        val updated = prefs.config.value
        assertEquals("English", updated.priority1)
        assertEquals("Hindi", updated.priority2)
        assertEquals("Japanese", updated.priority3)

        val codes = prefs.getPreferredAudioLanguageCodes()
        assertEquals("en", codes[0])
        assertEquals("eng", codes[1])
        assertEquals("hi", codes[2])
        assertEquals("hin", codes[3])
    }
}
