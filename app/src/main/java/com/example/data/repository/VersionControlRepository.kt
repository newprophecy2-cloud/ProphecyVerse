package com.example.data.repository

import android.util.Log
import com.example.data.model.VersionConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.UUID

class VersionControlRepository {

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
            val updateSourcesMap = if (config.updateSources.isNotEmpty()) {
                config.updateSources
            } else {
                mapOf(
                    "telegram" to mapOf(
                        "url" to "https://t.me/newprophecyverse",
                        "enabled" to true,
                        "priority" to 1,
                        "label" to "Telegram Channel"
                    ),
                    "website" to mapOf(
                        "url" to "https://prophecyverse.tech/download",
                        "enabled" to true,
                        "priority" to 2,
                        "label" to "Official Website"
                    )
                )
            }

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

            // 1. Primary write to app_config/version_control (REQUIRED BY USER APK)
            fs.collection("app_config").document("version_control")
                .set(payload, SetOptions.merge())
                .await()

            // 2. Keep app_config/version in sync
            fs.collection("app_config").document("version")
                .set(payload, SetOptions.merge())
                .await()

            // 3. Keep version_config/latest in sync
            fs.collection("version_config").document("latest")
                .set(payload, SetOptions.merge())
                .await()

            // 4. Record audit history in update_history/{uuid}
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
}
