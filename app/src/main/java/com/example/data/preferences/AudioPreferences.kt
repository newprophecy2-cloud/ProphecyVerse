package com.example.data.preferences

import android.content.Context
import android.content.SharedPreferences
import com.example.data.local.AppDatabase
import com.example.data.local.UserPreference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AudioPriorityConfig(
    val priority1: String = "Hindi",
    val priority2: String = "English",
    val priority3: String = "Japanese"
) {
    val displaySummary: String
        get() = "1. $priority1 → 2. $priority2 → 3. $priority3"
}

class AudioPreferences private constructor(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("prophecy_audio_prefs", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.IO)
    private val db = AppDatabase.getInstance(context)

    private val _config = MutableStateFlow(loadConfig())
    val config: StateFlow<AudioPriorityConfig> = _config.asStateFlow()

    companion object {
        const val PREF_KEY_PRIORITY_1 = "audio_priority_1"
        const val PREF_KEY_PRIORITY_2 = "audio_priority_2"
        const val PREF_KEY_PRIORITY_3 = "audio_priority_3"

        val AVAILABLE_LANGUAGES = listOf(
            "Hindi",
            "English",
            "Japanese",
            "Tamil",
            "Telugu",
            "Spanish"
        )

        @Volatile
        private var instance: AudioPreferences? = null

        fun getInstance(context: Context): AudioPreferences {
            return instance ?: synchronized(this) {
                instance ?: AudioPreferences(context.applicationContext).also { instance = it }
            }
        }
    }

    private fun loadConfig(): AudioPriorityConfig {
        val p1 = prefs.getString(PREF_KEY_PRIORITY_1, "Hindi") ?: "Hindi"
        val p2 = prefs.getString(PREF_KEY_PRIORITY_2, "English") ?: "English"
        val p3 = prefs.getString(PREF_KEY_PRIORITY_3, "Japanese") ?: "Japanese"
        return AudioPriorityConfig(p1, p2, p3)
    }

    fun updatePriority(p1: String, p2: String, p3: String) {
        prefs.edit()
            .putString(PREF_KEY_PRIORITY_1, p1)
            .putString(PREF_KEY_PRIORITY_2, p2)
            .putString(PREF_KEY_PRIORITY_3, p3)
            .apply()

        val newConfig = AudioPriorityConfig(p1, p2, p3)
        _config.value = newConfig

        scope.launch {
            try {
                db.userPreferenceDao().setPref(UserPreference(PREF_KEY_PRIORITY_1, p1))
                db.userPreferenceDao().setPref(UserPreference(PREF_KEY_PRIORITY_2, p2))
                db.userPreferenceDao().setPref(UserPreference(PREF_KEY_PRIORITY_3, p3))
            } catch (_: Exception) {
            }
        }
    }

    fun applyPreset(preset: AudioPriorityConfig) {
        updatePriority(preset.priority1, preset.priority2, preset.priority3)
    }

    /**
     * Converts language names into RFC 5646 / ISO 639 codes in strict priority order for ExoPlayer.
     * Default priority:
     * 1. Hindi ("hi", "hin")
     * 2. English ("en", "eng")
     * 3. Japanese ("ja", "jpn")
     */
    fun getPreferredAudioLanguageCodes(): List<String> {
        val current = _config.value
        val list = mutableListOf<String>()

        fun addCodes(lang: String) {
            when (lang.lowercase()) {
                "hindi" -> list.addAll(listOf("hi", "hin"))
                "english" -> list.addAll(listOf("en", "eng"))
                "japanese" -> list.addAll(listOf("ja", "jpn"))
                "tamil" -> list.addAll(listOf("ta", "tam"))
                "telugu" -> list.addAll(listOf("te", "tel"))
                "spanish" -> list.addAll(listOf("es", "spa"))
            }
        }

        addCodes(current.priority1)
        addCodes(current.priority2)
        addCodes(current.priority3)

        return list.distinct()
    }
}
