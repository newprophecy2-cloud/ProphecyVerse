package com.example.data.firebase

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.FileProvider
import com.example.BuildConfig
import com.google.firebase.FirebaseApp
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
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

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
    val latestVersionName: String = "3.0.1",
    val latestVersionCode: Int = 6,
    val minSupportedVersionCode: Int = 6,
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

internal enum class VersionVerificationFailureKind {
    PERMISSION, NETWORK, TIMEOUT, AUTHENTICATION, CONFIGURATION,
    NOT_FOUND, CANCELLED, PARSING, UNEXPECTED
}

internal data class VersionVerificationFailure(
    val kind: VersionVerificationFailureKind,
    val userMessage: String,
    val isNetworkError: Boolean,
    val firebaseCode: String?,
    val exceptionClass: String,
    val rootCauseClass: String
)

internal fun classifyVersionVerificationFailure(error: Throwable): VersionVerificationFailure {
    if (error is CancellationException) throw error
    val firebaseError = generateSequence(error) { it.cause }
        .filterIsInstance<FirebaseFirestoreException>()
        .firstOrNull()
    val rootCause = generateSequence(error) { it.cause }.last()
    val kind = when {
        firebaseError?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED -> VersionVerificationFailureKind.PERMISSION
        firebaseError?.code == FirebaseFirestoreException.Code.UNAUTHENTICATED -> VersionVerificationFailureKind.AUTHENTICATION
        firebaseError?.code == FirebaseFirestoreException.Code.NOT_FOUND -> VersionVerificationFailureKind.NOT_FOUND
        firebaseError?.code == FirebaseFirestoreException.Code.FAILED_PRECONDITION -> VersionVerificationFailureKind.CONFIGURATION
        firebaseError?.code == FirebaseFirestoreException.Code.DEADLINE_EXCEEDED -> VersionVerificationFailureKind.TIMEOUT
        firebaseError?.code == FirebaseFirestoreException.Code.UNAVAILABLE || firebaseError?.code == FirebaseFirestoreException.Code.ABORTED -> VersionVerificationFailureKind.NETWORK
        firebaseError?.code == FirebaseFirestoreException.Code.CANCELLED -> VersionVerificationFailureKind.CANCELLED
        rootCause is SocketTimeoutException -> VersionVerificationFailureKind.TIMEOUT
        rootCause is UnknownHostException || rootCause is ConnectException -> VersionVerificationFailureKind.NETWORK
        rootCause is SSLException || rootCause is CertificateException -> VersionVerificationFailureKind.NETWORK
        error is IllegalArgumentException -> VersionVerificationFailureKind.PARSING
        error is IllegalStateException && error.message.orEmpty().contains(
            "firebase|firebaseapp|initialize|options".toRegex(RegexOption.IGNORE_CASE)
        ) -> VersionVerificationFailureKind.CONFIGURATION
        else -> VersionVerificationFailureKind.UNEXPECTED
    }
    val message = when (kind) {
        VersionVerificationFailureKind.PERMISSION -> "Access to the version configuration was denied. Contact support or retry later."
        VersionVerificationFailureKind.NETWORK -> "Unable to reach the version service. Check your connection and retry."
        VersionVerificationFailureKind.TIMEOUT -> "The version service timed out. Check your connection and retry."
        VersionVerificationFailureKind.AUTHENTICATION -> "The version service authentication failed. Retry later."
        VersionVerificationFailureKind.CONFIGURATION -> "The version service is not configured correctly. Retry later."
        VersionVerificationFailureKind.NOT_FOUND -> "The version configuration was not found. Retry later."
        VersionVerificationFailureKind.CANCELLED -> "Version verification was cancelled. Retry when ready."
        VersionVerificationFailureKind.PARSING -> "Version configuration is invalid. Retry later."
        VersionVerificationFailureKind.UNEXPECTED -> "Version verification failed unexpectedly. Retry later."
    }
    return VersionVerificationFailure(
        kind, message,
        kind == VersionVerificationFailureKind.NETWORK || kind == VersionVerificationFailureKind.TIMEOUT,
        firebaseError?.code?.name, error.javaClass.simpleName, rootCause.javaClass.simpleName
    )
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
            val startedAt = SystemClock.elapsedRealtime()
            logVersionCheck("START", startedAt)
            try {
                _versionState.value = VersionCheckState.Checking

                val snapshot = try {
                    firestore.collection("app_config")
                        .document("version_control")
                        .get(Source.SERVER)
                        .await()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val failure = classifyVersionVerificationFailure(e)
                    logVersionCheck("FAILED", startedAt, failure)
                    _versionState.value = VersionCheckState.VerificationFailed(
                        failure.userMessage,
                        failure.isNetworkError
                    )
                    return@launch
                }
                if (!snapshot.exists()) {
                    logVersionCheck("FAILED", startedAt, message = "DOCUMENT_NOT_FOUND")
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
                    logVersionCheck("SUCCESS", startedAt, message = "UP_TO_DATE")
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
                // its state. Older builds need at least one actionable HTTPS download source.
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
                    logVersionCheck("SUCCESS", startedAt, message = "UP_TO_DATE")
                    return@launch
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
                val downloadsMap = (snapshot.get("downloads") as? Map<*, *>)
                    ?.takeIf { it.isNotEmpty() }
                    ?: (snapshot.get("update_sources") as? Map<*, *>)
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

                if (downloadsList.isEmpty()) {
                    throw IllegalArgumentException("Version configuration has no enabled HTTPS download sources")
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
                logVersionCheck("SUCCESS", startedAt, message = _versionState.value::class.simpleName ?: "UNKNOWN")

            } catch (e: CancellationException) {
                logVersionCheck("CANCELLED", startedAt, message = "CancellationException")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to verify version configuration", e)
                val failure = classifyVersionVerificationFailure(e)
                logVersionCheck("FAILED", startedAt, failure)
                _versionState.value = VersionCheckState.VerificationFailed(
                    failure.userMessage,
                    failure.isNetworkError
                )
            }
        }
    }

    private fun logVersionCheck(
        result: String,
        startedAt: Long,
        failure: VersionVerificationFailure? = null,
        message: String? = null
    ) {
        val firebaseProject = runCatching { FirebaseApp.getInstance().options.projectId }.getOrNull() ?: "unknown"
        val duration = SystemClock.elapsedRealtime() - startedAt
        Log.i(
            TAG,
            "[VERSION_CHECK] result=$result project=$firebaseProject package=${BuildConfig.APPLICATION_ID} " +
                "path=app_config/version_control source=SERVER durationMs=$duration " +
                "exception=${failure?.exceptionClass ?: "none"} firebaseCode=${failure?.firebaseCode ?: "none"} " +
                "rootCause=${failure?.rootCauseClass ?: "none"} installedCode=$currentVersionCode " +
                "finalState=${message ?: failure?.kind?.name ?: "CHECKING"}"
        )
    }

    /**
     * Downloads an APK file from a direct link, validates APK package ID & numeric version code,
     * and delegates signing continuity checks to Android's package installer.
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
                while (true) {
                    val read = inputStream.read(buffer)
                    if (read == -1) break
                    outputStream.write(buffer, 0, read)
                    downloaded += read

                    val percent = if (totalBytes > 0) ((downloaded * 100) / totalBytes).toInt() else 0
                    _apkDownloadState.value = ApkDownloadState.Downloading(percent, downloaded, totalBytes)
                }

                outputStream.flush()
                outputStream.close()
                inputStream.close()

                _apkDownloadState.value = ApkDownloadState.Verifying

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

                // The Android package installer verifies signing-certificate continuity when
                // installing over this app. Do not require an admin-maintained certificate hash.
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
