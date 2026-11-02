package com.example.data.firebase

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.example.BuildConfig
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import com.google.firebase.Timestamp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class DownloadSource(
    val id: String = "",
    val enabled: Boolean = true,
    val label: String = "",
    val url: String = "",
    val priority: Int = 1,
    val isDirectApk: Boolean = false
)

data class ReleaseNotes(
    val bn: String = "",
    val en: String = ""
)

data class VersionControlConfig(
    val enabled: Boolean = true,
    val forceUpdate: Boolean = false,
    val latestVersionName: String = "3.0.0",
    val latestVersionCode: Int = 5,
    val minSupportedVersionCode: Int = 5,
    val packageName: String = "com.aistudio.prophecyverse.vykqtb",
    val downloads: List<DownloadSource> = emptyList(),
    val sha256: String = "",
    val signingCertificateSha256: String = "",
    val releaseNotes: ReleaseNotes = ReleaseNotes(),
    val updatedAt: Long = 0L,
    val updatedBy: String = ""
)

internal fun parseAdminUpdatedAtMillis(value: Any?): Long = when (value) {
    is Timestamp -> value.toDate().time
    is Number -> value.toLong()
    else -> 0L
}

internal fun requiresDownloadMetadata(currentCode: Int, latestCode: Int, minimumCode: Int): Boolean =
    currentCode < latestCode || currentCode < minimumCode

sealed class VersionCheckState {
    data object Checking : VersionCheckState()
    data class UpToDate(val config: VersionControlConfig) : VersionCheckState()
    data class OptionalUpdate(val config: VersionControlConfig) : VersionCheckState()
    data class ForceUpdate(val config: VersionControlConfig) : VersionCheckState()
    data class VerificationFailed(
        val message: String,
        val isNetworkError: Boolean
    ) : VersionCheckState()
}

sealed class ApkDownloadState {
    data object Idle : ApkDownloadState()
    data class Downloading(val progressPercent: Int, val bytesDownloaded: Long, val totalBytes: Long) : ApkDownloadState()
    data object Verifying : ApkDownloadState()
    data class ReadyToInstall(val apkFile: File) : ApkDownloadState()
    data class Error(val message: String) : ApkDownloadState()
}

class VersionControlManager private constructor(private val context: Context) {
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val scope = CoroutineScope(Dispatchers.IO)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val _versionState = MutableStateFlow<VersionCheckState>(VersionCheckState.Checking)
    val versionState: StateFlow<VersionCheckState> = _versionState.asStateFlow()

    private val _apkDownloadState = MutableStateFlow<ApkDownloadState>(ApkDownloadState.Idle)
    val apkDownloadState: StateFlow<ApkDownloadState> = _apkDownloadState.asStateFlow()

    private var activeDownloadJob: Job? = null
    private var activeCheckJob: Job? = null

    val currentVersionCode: Int
        get() = BuildConfig.VERSION_CODE

    val currentVersionName: String
        get() = BuildConfig.VERSION_NAME

    fun getDefaultConfig(): VersionControlConfig {
        return VersionControlConfig(
            enabled = false,
            forceUpdate = false,
            latestVersionName = BuildConfig.VERSION_NAME,
            latestVersionCode = BuildConfig.VERSION_CODE,
            minSupportedVersionCode = BuildConfig.VERSION_CODE,
            packageName = BuildConfig.APPLICATION_ID,
            downloads = emptyList(),
            sha256 = "",
            releaseNotes = ReleaseNotes(),
            updatedAt = System.currentTimeMillis(),
            updatedBy = "system_default"
        )
    }


    fun checkForUpdates() {
        if (activeCheckJob?.isActive == true) return
        activeCheckJob = scope.launch {
            try {
                _versionState.value = VersionCheckState.Checking

                val snapshot = try {
                    firestore.collection("app_config")
                        .document("version_control")
                        .get(Source.SERVER)
                        .await()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to reach Firestore for version control: ${e.message}")
                    val networkError = e !is FirebaseFirestoreException || e.code in setOf(
                        FirebaseFirestoreException.Code.UNAVAILABLE,
                        FirebaseFirestoreException.Code.DEADLINE_EXCEEDED,
                        FirebaseFirestoreException.Code.ABORTED
                    )
                    _versionState.value = VersionCheckState.VerificationFailed(
                        if (networkError) "Unable to reach the version service. Check your connection and retry."
                        else "Access to the version configuration was denied. Contact support or retry later.",
                        networkError
                    )
                    return@launch
                }
                if (!snapshot.exists()) {
                    _versionState.value = VersionCheckState.VerificationFailed(
                        "Version configuration is unavailable. Retry later.", false
                    )
                    return@launch
                }

                // Parse config with support for both V3 camelCase schema and V2 snake_case fallback
                val enabled = snapshot.getBoolean("enabled")
                    ?: throw IllegalArgumentException("Missing or invalid enabled flag")
                if (!enabled) {
                    _versionState.value = VersionCheckState.UpToDate(
                        VersionControlConfig(
                            enabled = false,
                            latestVersionName = currentVersionName,
                            latestVersionCode = currentVersionCode,
                            minSupportedVersionCode = currentVersionCode,
                            packageName = BuildConfig.APPLICATION_ID
                        )
                    )
                    return@launch
                }
                val forceUpdate = snapshot.getBoolean("force_update")
                    ?: snapshot.getBoolean("forceUpdate")
                    ?: throw IllegalArgumentException("Missing force_update")
                val latestCodeLong = (snapshot.getLong("latest_version_code")
                    ?: snapshot.getLong("latestVersionCode")
                    ?: throw IllegalArgumentException("Missing or invalid latest_version_code"))
                val minCodeLong = (snapshot.getLong("min_supported_version_code")
                    ?: snapshot.getLong("minSupportedVersionCode")
                    ?: snapshot.getLong("minimum_version_code")
                    ?: throw IllegalArgumentException("Missing or invalid min_supported_version_code"))
                require(latestCodeLong in 1L..Int.MAX_VALUE.toLong()) {
                    "latest_version_code is outside the supported numeric range"
                }
                require(minCodeLong in 1L..Int.MAX_VALUE.toLong()) {
                    "min_supported_version_code is outside the supported numeric range"
                }
                val latestCode = latestCodeLong.toInt()
                val minCode = minCodeLong.toInt()
                val latestName = snapshot.getString("latest_version_name")
                    ?: snapshot.getString("latestVersionName")
                    ?: throw IllegalArgumentException("Missing latest_version_name")
                val pkgName = snapshot.getString("package_name")
                    ?: snapshot.getString("packageName")
                    ?: throw IllegalArgumentException("Missing package_name")
                val sha256 = snapshot.getString("artifact_sha256")
                    ?: snapshot.getString("sha256") ?: ""
                val signingCertificateSha256 = snapshot.getString("signing_certificate_sha256")
                    ?: snapshot.getString("signingCertificateSha256") ?: ""
                require(latestCode >= minCode && latestCode > 0 && minCode > 0)
                require(pkgName == BuildConfig.APPLICATION_ID)

                // A current/supported installation does not need release-download fields to determine
                // its state. Older builds still require fully verified download metadata before prompting.
                if (!requiresDownloadMetadata(currentVersionCode, latestCode, minCode)) {
                    _versionState.value = VersionCheckState.UpToDate(
                        VersionControlConfig(
                            enabled = enabled,
                            forceUpdate = forceUpdate,
                            latestVersionName = latestName,
                            latestVersionCode = latestCode,
                            minSupportedVersionCode = minCode,
                            packageName = pkgName,
                            updatedAt = parseAdminUpdatedAtMillis(
                                snapshot.get("updated_at") ?: snapshot.get("updatedAt")
                            )
                        )
                    )
                    return@launch
                }
                require(sha256.matches(Regex("(?i)^[a-f0-9]{64}$"))) {
                    "Missing or invalid artifact_sha256 (expected 64 hexadecimal characters)"
                }
                require(signingCertificateSha256.replace(":", "").matches(Regex("(?i)^[a-f0-9]{64}$"))) {
                    "Missing or invalid signing_certificate_sha256 (expected 64 hexadecimal characters)"
                }

                // Release notes (map or strings)
                val releaseNotesRaw = (snapshot.get("release_notes") ?: snapshot.get("releaseNotes")) as? Map<*, *>
                val bnNotes = releaseNotesRaw?.get("bn")?.toString()
                    ?: snapshot.getString("release_notes_bn")
                    ?: "Prophecy Verse V3"
                val enNotes = releaseNotesRaw?.get("en")?.toString()
                    ?: snapshot.getString("release_notes_en")
                    ?: snapshot.getString("update_message")
                    ?: "A new version of Prophecy Verse is available."

                // Parse downloads map
                val downloadsList = mutableListOf<DownloadSource>()
                val downloadsMap = snapshot.get("downloads") as? Map<*, *>
                if (downloadsMap != null) {
                    downloadsMap.forEach { (key, value) ->
                        val itemMap = value as? Map<*, *>
                        if (itemMap != null) {
                            val id = key?.toString() ?: "source"
                            val isSourceEnabled = itemMap["enabled"] as? Boolean ?: true
                            val label = itemMap["label"]?.toString() ?: id.replaceFirstChar { it.uppercase() }
                            val url = itemMap["url"]?.toString() ?: ""
                            val priority = (itemMap["priority"] as? Number)?.toInt() ?: 10
                            val uri = runCatching { Uri.parse(url) }.getOrNull()
                            val validHttps = uri?.scheme == "https" && !uri.host.isNullOrBlank()
                            val isDirect = itemMap["isDirectApk"] == true ||
                                uri?.path?.endsWith(".apk", ignoreCase = true) == true

                            if (isSourceEnabled && validHttps && url.isNotBlank()) {
                                downloadsList.add(
                                    DownloadSource(
                                        id = id,
                                        enabled = true,
                                        label = label,
                                        url = url,
                                        priority = priority,
                                        isDirectApk = isDirect
                                    )
                                )
                            }
                        }
                    }
                }

                // If downloads map wasn't in doc, check fallback legacy fields
                if (downloadsList.isEmpty()) {
                    throw IllegalArgumentException("Version configuration has no enabled HTTPS download sources")
                }
                require(downloadsList.any { it.isDirectApk }) {
                    "Version configuration has no enabled direct APK URL; no in-app Update button can be shown"
                }

                // Sort by priority ascending
                downloadsList.sortBy { it.priority }

                val config = VersionControlConfig(
                    enabled = enabled,
                    forceUpdate = forceUpdate,
                    latestVersionName = latestName,
                    latestVersionCode = latestCode,
                    minSupportedVersionCode = minCode,
                    packageName = pkgName,
                    downloads = downloadsList,
                    sha256 = sha256,
                    releaseNotes = ReleaseNotes(bn = bnNotes, en = enNotes),
                    signingCertificateSha256 = signingCertificateSha256,
                    // The Admin publisher writes FieldValue.serverTimestamp(), which resolves to Timestamp.
                    updatedAt = parseAdminUpdatedAtMillis(snapshot.get("updated_at") ?: snapshot.get("updatedAt"))
                        .takeIf { it > 0L } ?: System.currentTimeMillis(),
                    updatedBy = snapshot.getString("updated_by") ?: snapshot.getString("updatedBy") ?: ""
                )

                _versionState.value = evaluateVersionState(currentVersionCode, config)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to verify version configuration", e)
                _versionState.value = VersionCheckState.VerificationFailed(
                    e.message?.let { "Version configuration is invalid: $it" }
                        ?: "Version configuration could not be verified. Retry later.", false
                )
            }
        }
    }

    /**
     * Downloads an APK file from a direct link, calculates SHA-256 hash,
     * validates APK package ID & version, and triggers the system installer.
     */
    fun startDirectApkDownload(source: DownloadSource, expectedConfig: VersionControlConfig) {
        val sourceUri = runCatching { Uri.parse(source.url) }.getOrNull()
        if (sourceUri?.scheme != "https" || sourceUri.host.isNullOrBlank()) {
            _apkDownloadState.value = ApkDownloadState.Error("Update sources must use HTTPS.")
            return
        }
        if (!source.isDirectApk && sourceUri.path?.endsWith(".apk", ignoreCase = true) != true) {
            openExternalUrl(source.url)
            return
        }
        if (!expectedConfig.sha256.matches(Regex("(?i)^[a-f0-9]{64}$")) ||
            !expectedConfig.signingCertificateSha256.replace(":", "").matches(Regex("(?i)^[a-f0-9]{64}$"))) {
            _apkDownloadState.value = ApkDownloadState.Error("Update integrity metadata is missing or invalid.")
            return
        }

        activeDownloadJob?.cancel()
        activeDownloadJob = scope.launch {
            _apkDownloadState.value = ApkDownloadState.Downloading(0, 0, 0)
            try {
                val apkDir = File(context.cacheDir, "updates").apply { if (!exists()) mkdirs() }
                val targetFile = File(apkDir, "ProphecyVerse_v${expectedConfig.latestVersionName}.apk")
                if (targetFile.exists()) targetFile.delete()

                val request = Request.Builder().url(source.url).build()
                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    _apkDownloadState.value = ApkDownloadState.Error("Server returned code ${response.code}")
                    return@launch
                }

                val body = response.body ?: throw IllegalStateException("Empty download body")
                val totalBytes = body.contentLength()
                val inputStream = body.byteStream()
                val outputStream = FileOutputStream(targetFile)

                val buffer = ByteArray(16 * 1024)
                var downloaded = 0L
                val md = MessageDigest.getInstance("SHA-256")

                while (true) {
                    val read = inputStream.read(buffer)
                    if (read == -1) break
                    outputStream.write(buffer, 0, read)
                    md.update(buffer, 0, read)
                    downloaded += read

                    val percent = if (totalBytes > 0) ((downloaded * 100) / totalBytes).toInt() else 0
                    _apkDownloadState.value = ApkDownloadState.Downloading(percent, downloaded, totalBytes)
                }

                outputStream.flush()
                outputStream.close()
                inputStream.close()

                _apkDownloadState.value = ApkDownloadState.Verifying

                // Calculate calculated SHA-256
                val digest = md.digest()
                val computedSha256 = digest.joinToString("") { "%02x".format(it) }

                if (!computedSha256.equals(expectedConfig.sha256.trim(), ignoreCase = true)) {
                    targetFile.delete()
                    _apkDownloadState.value = ApkDownloadState.Error(
                        "SHA-256 integrity verification failed: downloaded file may be corrupted."
                    )
                    return@launch
                }

                // Verify package ID using package manager
                val pm = context.packageManager
                val archiveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageArchiveInfo(targetFile.absolutePath, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageArchiveInfo(targetFile.absolutePath, 0)
                }

                if (archiveInfo == null) {
                    targetFile.delete()
                    _apkDownloadState.value = ApkDownloadState.Error("Downloaded file is not a valid Android APK.")
                    return@launch
                }

                val archiveVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    archiveInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION") archiveInfo.versionCode.toLong()
                }
                if (archiveInfo.packageName != expectedConfig.packageName ||
                    archiveVersionCode != expectedConfig.latestVersionCode.toLong()
                ) {
                    targetFile.delete()
                    _apkDownloadState.value = ApkDownloadState.Error(
                        "Package mismatch: expected ${expectedConfig.packageName}, found ${archiveInfo.packageName}."
                    )
                    return@launch
                }

                val signingHash = signingCertificateSha256(targetFile)
                if (expectedConfig.signingCertificateSha256.isBlank() ||
                    !signingHash.equals(expectedConfig.signingCertificateSha256.replace(":", "").trim(), ignoreCase = true)
                ) {
                    targetFile.delete()
                    _apkDownloadState.value = ApkDownloadState.Error("APK signing certificate verification failed.")
                    return@launch
                }

                _apkDownloadState.value = ApkDownloadState.ReadyToInstall(targetFile)
                withContext(Dispatchers.Main) {
                    launchPackageInstaller(targetFile)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Direct APK download failed", e)
                _apkDownloadState.value = ApkDownloadState.Error(e.localizedMessage ?: "Download failed.")
            }
        }
    }

    private fun signingCertificateSha256(apkFile: File): String {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageArchiveInfo(
                apkFile.absolutePath,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNATURES)
        } ?: throw IllegalStateException("Unable to read APK signing certificate")
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
                ?: throw IllegalStateException("APK signing information is unavailable")
        } else {
            @Suppress("DEPRECATION")
            info.signatures ?: throw IllegalStateException("APK signatures are unavailable")
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(
            signatures.firstOrNull()?.toByteArray()
                ?: throw IllegalStateException("APK contains no signing certificates")
        )
        return digest.joinToString("") { "%02x".format(it) }
    }
    fun cancelApkDownload() {
        activeDownloadJob?.cancel()
        _apkDownloadState.value = ApkDownloadState.Idle
    }

    fun openExternalUrl(url: String) {
        if (url.isBlank()) return
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open URL: $url", e)
        }
    }

    fun launchPackageInstaller(apkFile: File) {
        if (!apkFile.exists()) {
            _apkDownloadState.value = ApkDownloadState.Error("Verified APK file is no longer available. Download it again.")
            return
        }
        try {
            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package installer", e)
            _apkDownloadState.value = ApkDownloadState.Error(
                "Android could not open the package installer: ${e.localizedMessage ?: "unknown installer error"}"
            )
        }
    }

    companion object {
        private const val TAG = "VersionControlManager"

        @Volatile
        private var instance: VersionControlManager? = null

        fun getInstance(context: Context): VersionControlManager {
            return instance ?: synchronized(this) {
                instance ?: VersionControlManager(context.applicationContext).also { instance = it }
            }
        }

        fun evaluateVersionState(currentCode: Int, config: VersionControlConfig?): VersionCheckState {
            if (config == null) {
                return VersionCheckState.VerificationFailed(
                    message = "Version configuration is unavailable or invalid",
                    isNetworkError = false
                )
            }
            if (!config.enabled) return VersionCheckState.UpToDate(config)
            return when {
                currentCode < config.minSupportedVersionCode -> VersionCheckState.ForceUpdate(config)
                config.forceUpdate && currentCode < config.latestVersionCode -> VersionCheckState.ForceUpdate(config)
                currentCode < config.latestVersionCode -> VersionCheckState.OptionalUpdate(config)
                else -> VersionCheckState.UpToDate(config)
            }
        }
    }
}
