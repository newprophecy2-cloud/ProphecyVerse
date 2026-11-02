package com.example.data.repository

import android.util.Log
import com.example.data.model.VersionConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.UUID

class VersionControlRepository {
    private val apkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .build()
    }

    private fun getFirestore(): FirebaseFirestore? {
        return runCatching { FirebaseFirestore.getInstance() }.getOrNull()
    }

    suspend fun getVersionConfig(): Result<VersionConfig> = withContext(Dispatchers.IO) {
        val fs = getFirestore() ?: return@withContext Result.failure(
            IllegalStateException("Firebase is uninitialized. Connect google-services.json to read remote configuration.")
        )
        try {
            // 1. Primary document for Prophecy Verse V3 User APK: app_config/version_control
            var doc = fs.collection("app_config").document("version_control").get().await()
            if (!doc.exists()) {
                // 2. Fallback check for app_config/version
                doc = fs.collection("app_config").document("version").get().await()
            }
            if (!doc.exists()) {
                // 3. Fallback check for version_config/latest
                doc = fs.collection("version_config").document("latest").get().await()
            }

            if (doc.exists()) {
                val config = doc.toObject(VersionConfig::class.java)
                if (config != null) {
                    return@withContext Result.success(config.copy(id = doc.id))
                }
            }
            // Return default configuration baseline if document is absent
            Result.success(VersionConfig())
        } catch (e: Exception) {
            Log.e("VersionControlRepo", "Failed to get version config", e)
            Result.failure(e)
        }
    }

    suspend fun saveVersionConfig(config: VersionConfig, adminEmail: String): Result<Unit> = withContext(Dispatchers.IO) {
        val fs = getFirestore() ?: return@withContext Result.failure(
            IllegalStateException("Firebase is uninitialized. Cannot write version configuration.")
        )
        try {
            val updateSourcesMap = config.updateSources
            require(config.enabled) { "Version publishing is disabled." }
            require(config.latestVersionCode > 0L) { "Version code must be positive." }
            require(config.minSupportedVersionCode in 1..config.latestVersionCode) {
                "Minimum supported version code must be positive and no greater than the latest version code."
            }
            require(config.latestVersionName.isNotBlank()) { "Version name cannot be blank." }
            require(config.packageName == "com.aistudio.prophecyverse.vykqtb") {
                "Package name must match the User APK application ID."
            }
            require(config.artifactSha256.matches(Regex("(?i)^[a-f0-9]{64}$"))) {
                "A real 64-character APK SHA-256 fingerprint is required."
            }
            require(config.signingCertificateSha256.replace(":", "").matches(Regex("(?i)^[a-f0-9]{64}$"))) {
                "A real 64-character signing certificate SHA-256 fingerprint is required."
            }
            val directSource = (updateSourcesMap["backup"] as? Map<*, *>)
                ?: (updateSourcesMap["direct_apk"] as? Map<*, *>)
                ?: throw IllegalArgumentException("An enabled direct APK download source is required.")
            require((directSource["enabled"] as? Boolean) == true) {
                "The direct APK download source must be enabled."
            }
            val directApkUrl = directSource["url"]?.toString().orEmpty()
            require(directApkUrl.startsWith("https://", ignoreCase = true) &&
                directApkUrl.substringBefore('?').substringBefore('#').endsWith(".apk", ignoreCase = true)) {
                "A valid HTTPS direct APK URL is required."
            }
            verifyApkUrlMatchesHash(directApkUrl, config.artifactSha256)

            val payload = mutableMapOf<String, Any>(
                // Primary V3 schema matching User APK VersionControlManager.kt
                "enabled" to config.enabled,
                "force_update" to config.forceUpdate,
                "latest_version_code" to config.latestVersionCode,
                "latest_version_name" to config.latestVersionName,
                "min_supported_version_code" to config.minSupportedVersionCode,
                "package_name" to config.packageName,
                "artifact_sha256" to config.artifactSha256,
                "signing_certificate_sha256" to config.signingCertificateSha256,
                "release_notes" to config.releaseNotes,
                "update_sources" to updateSourcesMap,
                "downloads" to updateSourcesMap,
                "updated_at" to FieldValue.serverTimestamp(),
                "updated_by" to adminEmail,

                // Legacy field mirrors for compatibility
                "versionName" to config.latestVersionName,
                "versionCode" to config.latestVersionCode,
                "minSupportedVersionCode" to config.minSupportedVersionCode,
                "isMandatory" to config.forceUpdate,
                "sha256" to config.artifactSha256
            )

            // Primary write to app_config/version_control (REQUIRED BY USER APK).
            val versionDocument = fs.collection("app_config").document("version_control")
            versionDocument
                .set(payload, SetOptions.merge())
                .await()

            // Confirm the exact primary schema and release values are visible from the server.
            val published = versionDocument.get(Source.SERVER).await()
            val publishedDownloads = published.get("downloads") as? Map<*, *>
                ?: throw IllegalStateException("Version write completed, but downloads metadata was not readable back.")
            val publishedDirect = (publishedDownloads["backup"] as? Map<*, *>)
                ?: (publishedDownloads["direct_apk"] as? Map<*, *>)
            require(published.exists() &&
                published.getBoolean("enabled") == config.enabled &&
                published.getBoolean("force_update") == config.forceUpdate &&
                published.getLong("latest_version_code") == config.latestVersionCode &&
                published.getString("latest_version_name") == config.latestVersionName &&
                published.getLong("min_supported_version_code") == config.minSupportedVersionCode &&
                published.getString("package_name") == config.packageName &&
                published.getString("artifact_sha256") == config.artifactSha256 &&
                published.getString("signing_certificate_sha256") == config.signingCertificateSha256 &&
                publishedDirect != null &&
                publishedDirect["url"] == directApkUrl &&
                publishedDirect["enabled"] == true) {
                "Version write completed, but server read-back does not match the release metadata."
            }

            // Record audit history separately; the User APK reads only app_config/version_control.
            val auditId = UUID.randomUUID().toString()
            val historyPayload = HashMap(payload).apply {
                put("release_id", auditId)
                put("published_at", FieldValue.serverTimestamp())
                put("published_by", adminEmail)
            }
            fs.collection("update_history").document(auditId)
                .set(historyPayload)
                .await()

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("VersionControlRepo", "Failed to save version config", e)
            Result.failure(e)
        }
    }

    private fun verifyApkUrlMatchesHash(url: String, expectedSha256: String) {
        val request = Request.Builder()
            .url(url)
            .header("Accept-Encoding", "identity")
            .get()
            .build()
        apkHttpClient.newCall(request).execute().use { response ->
            require(response.isSuccessful) {
                "Direct APK URL is unreachable (HTTP ${response.code}); correct the link and retry."
            }
            val body = response.body ?: throw IllegalArgumentException("Direct APK URL returned an empty response.")
            val contentLength = body.contentLength()
            require(contentLength != 0L && contentLength <= MAX_APK_BYTES) {
                "Direct APK URL returned an empty or unreasonably large file."
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var totalBytes = 0L
            body.byteStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    totalBytes += count
                    require(totalBytes <= MAX_APK_BYTES) { "Direct APK exceeds the 200 MB validation limit." }
                    digest.update(buffer, 0, count)
                }
            }
            require(totalBytes > 0L) { "Direct APK URL returned an empty file." }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            require(actual.equals(expectedSha256.trim(), ignoreCase = true)) {
                "The APK bytes at the direct URL do not match the supplied SHA-256 fingerprint."
            }
        }
    }

    private companion object {
        const val MAX_APK_BYTES = 200L * 1024L * 1024L
    }
}
