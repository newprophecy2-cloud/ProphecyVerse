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

class VersionControlRepository {

    private fun getFirestore(): FirebaseFirestore? {
        return try {
            if (FirebaseApp.getApps(com.google.firebase.FirebaseApp.getInstance().applicationContext).isNotEmpty()) {
                FirebaseFirestore.getInstance()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun getVersionConfig(): Result<VersionConfig> = withContext(Dispatchers.IO) {
        val fs = getFirestore() ?: return@withContext Result.failure(
            IllegalStateException("Firebase is uninitialized. Connect google-services.json to read remote configuration.")
        )
        try {
            // First attempt app_config/version
            var doc = fs.collection("app_config").document("version").get().await()
            if (!doc.exists()) {
                // Secondary check for version_config/latest
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

    suspend fun saveVersionConfig(config: VersionConfig): Result<Unit> = withContext(Dispatchers.IO) {
        val fs = getFirestore() ?: return@withContext Result.failure(
            IllegalStateException("Firebase is uninitialized. Cannot write version configuration.")
        )
        try {
            // Preserve dual compatibility with both `update_sources` and parsed `downloads`
            val unifiedSources = config.updateSources.ifEmpty { listOf("DIRECT", "GITHUB") }
            val unifiedDownloads = config.downloads.ifEmpty { listOf(config.updateUrl) }

            val payload = mapOf(
                "versionName" to config.versionName,
                "versionCode" to config.versionCode,
                "minSupportedVersionCode" to config.minSupportedVersionCode,
                "isMandatory" to config.isMandatory,
                "updateUrl" to config.updateUrl,
                "updateMessage" to config.updateMessage,
                "sha256" to config.sha256,
                "updateSources" to unifiedSources,
                "downloads" to unifiedDownloads,
                "releaseDate" to (config.releaseDate ?: FieldValue.serverTimestamp()),
                "updatedAt" to FieldValue.serverTimestamp()
            )

            // Write to app_config/version
            fs.collection("app_config").document("version")
                .set(payload, SetOptions.merge())
                .await()

            // Also keep legacy version_config/latest in sync for compatibility
            fs.collection("version_config").document("latest")
                .set(payload, SetOptions.merge())
                .await()

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("VersionControlRepo", "Failed to save version config", e)
            Result.failure(e)
        }
    }
}
