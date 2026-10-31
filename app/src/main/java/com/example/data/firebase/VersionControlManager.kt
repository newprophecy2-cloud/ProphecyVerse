package com.example.data.firebase

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

data class VersionInfo(
    val latestVersionCode: Int = 1,
    val latestVersionName: String = "1.0",
    val minimumVersionCode: Int = 1,
    val forceUpdate: Boolean = false,
    val updateTitle: String = "New Prophecy Verse Update Available",
    val updateMessage: String = "A new version of Prophecy Verse is available with new features and stability improvements.",
    val downloadUrl: String = "https://t.me/prophecyverse",
    val telegramUrl: String = "https://t.me/prophecyverse",
    val noticeBannerEnabled: Boolean = false,
    val noticeBannerText: String = ""
)

sealed class VersionCheckState {
    data object Checking : VersionCheckState()
    data class UpToDate(val info: VersionInfo) : VersionCheckState()
    data class OptionalUpdate(val info: VersionInfo) : VersionCheckState()
    data class ForceUpdate(val info: VersionInfo) : VersionCheckState()
    data class CheckFailed(val message: String) : VersionCheckState()
}

class VersionControlManager private constructor(private val context: Context) {
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _versionState = MutableStateFlow<VersionCheckState>(VersionCheckState.Checking)
    val versionState: StateFlow<VersionCheckState> = _versionState.asStateFlow()

    private val currentVersionCode = BuildConfig.VERSION_CODE

    fun checkForUpdates() {
        scope.launch {
            try {
                _versionState.value = VersionCheckState.Checking
                val snapshot = firestore.collection("app_config")
                    .document("version_control")
                    .get()
                    .await()

                if (snapshot.exists()) {
                    val latestCode = snapshot.getLong("latest_version_code")?.toInt() ?: 1
                    val minCode = snapshot.getLong("minimum_version_code")?.toInt() ?: 1
                    val isForce = snapshot.getBoolean("force_update") ?: false
                    val latestName = snapshot.getString("latest_version_name") ?: "1.0"
                    val title = snapshot.getString("update_title") ?: "New Prophecy Verse Update"
                    val message = snapshot.getString("update_message") ?: "Please update Prophecy Verse to the latest version for the best streaming experience."
                    val downloadUrl = snapshot.getString("download_url") ?: "https://t.me/prophecyverse"
                    val telegramUrl = snapshot.getString("telegram_url") ?: "https://t.me/prophecyverse"
                    val bannerEnabled = snapshot.getBoolean("notice_banner_enabled") ?: false
                    val bannerText = snapshot.getString("notice_banner_text") ?: ""

                    val info = VersionInfo(
                        latestVersionCode = latestCode,
                        latestVersionName = latestName,
                        minimumVersionCode = minCode,
                        forceUpdate = isForce,
                        updateTitle = title,
                        updateMessage = message,
                        downloadUrl = downloadUrl,
                        telegramUrl = telegramUrl,
                        noticeBannerEnabled = bannerEnabled,
                        noticeBannerText = bannerText
                    )

                    when {
                        currentVersionCode < minCode || (isForce && currentVersionCode < latestCode) -> {
                            _versionState.value = VersionCheckState.ForceUpdate(info)
                        }
                        currentVersionCode < latestCode -> {
                            _versionState.value = VersionCheckState.OptionalUpdate(info)
                        }
                        else -> {
                            _versionState.value = VersionCheckState.UpToDate(info)
                        }
                    }
                } else {
                    // Document does not exist yet (initial launch before Admin configures it)
                    _versionState.value = VersionCheckState.UpToDate(VersionInfo())
                }
            } catch (e: Exception) {
                Log.w("VersionControlManager", "Version check failed: ${e.message}")
                _versionState.value = VersionCheckState.UpToDate(VersionInfo())
            }
        }
    }

    companion object {
        @Volatile
        private var instance: VersionControlManager? = null

        fun getInstance(context: Context): VersionControlManager {
            return instance ?: synchronized(this) {
                instance ?: VersionControlManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
