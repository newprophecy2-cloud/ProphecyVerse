package com.example.data.repository

import android.util.Log
import com.example.data.model.InstallationRecord
import com.example.data.model.UserAccount
import com.example.data.model.WatchHistoryEntry
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

data class InstallationsPageResult(
    val items: List<InstallationRecord>,
    val lastSnapshot: DocumentSnapshot?,
    val hasMore: Boolean
)

data class InstallationDetailBundle(
    val installation: InstallationRecord,
    val linkedUser: UserAccount? = null,
    val recentWatchHistory: List<WatchHistoryEntry> = emptyList()
)

data class InstallationStats(
    val totalCount: Int = 0,
    val activeNowCount: Int = 0,
    val recentlyActiveCount: Int = 0,
    val inactiveCount: Int = 0,
    val totalWatchSeconds: Long = 0L,
    val versionDistribution: Map<String, Int> = emptyMap(),
    val countryDistribution: Map<String, Int> = emptyMap(),
    val manufacturerDistribution: Map<String, Int> = emptyMap(),
    val androidVersionDistribution: Map<String, Int> = emptyMap()
)

class InstallationRepository {

    private fun getFirestore(): FirebaseFirestore? {
        return runCatching { FirebaseFirestore.getInstance() }.getOrNull()
    }

    suspend fun getInstallationsPage(
        pageSize: Long = 25,
        startAfter: DocumentSnapshot? = null,
        statusFilter: String = "ALL", // "ALL", "ACTIVE_NOW", "RECENT", "INACTIVE"
        versionFilter: String = "ALL",
        searchQuery: String = ""
    ): Result<InstallationsPageResult> = withContext(Dispatchers.IO) {
        val fs = getFirestore() ?: return@withContext Result.failure(
            IllegalStateException("Firebase is uninitialized.")
        )
        try {
            var query: Query = fs.collection("installations")
                .orderBy("last_active_at", Query.Direction.DESCENDING)

            if (startAfter != null) {
                query = query.startAfter(startAfter)
            }

            // Fetch extra to filter in-memory if needed
            val fetchLimit = if (searchQuery.isNotBlank() || statusFilter != "ALL" || versionFilter != "ALL") 100L else pageSize + 1
            val snapshot = query.limit(fetchLimit).get().await()

            var records = snapshot.documents.mapNotNull { doc ->
                try {
                    doc.toObject(InstallationRecord::class.java)?.copy(id = doc.id)
                } catch (e: Exception) {
                    Log.w("InstallationRepo", "Failed to deserialize doc ${doc.id}: ${e.message}")
                    null
                }
            }

            // Apply search query filter if provided
            if (searchQuery.isNotBlank()) {
                val q = searchQuery.trim().lowercase()
                records = records.filter { rec ->
                    rec.installationId.lowercase().contains(q) ||
                    rec.ownerUid.lowercase().contains(q) ||
                    rec.deviceModel.lowercase().contains(q) ||
                    rec.deviceManufacturer.lowercase().contains(q) ||
                    rec.countryCode.lowercase().contains(q) ||
                    rec.appVersionName.lowercase().contains(q)
                }
            }

            // Apply status filter
            if (statusFilter != "ALL") {
                records = when (statusFilter) {
                    "ACTIVE_NOW" -> records.filter { it.isActiveNow }
                    "RECENT" -> records.filter { it.isRecentlyActive && !it.isActiveNow }
                    "INACTIVE" -> records.filter { !it.isRecentlyActive }
                    else -> records
                }
            }

            // Apply version filter
            if (versionFilter != "ALL") {
                records = records.filter {
                    it.appVersionName.equals(versionFilter, ignoreCase = true) ||
                    "v${it.appVersionName}".equals(versionFilter, ignoreCase = true) ||
                    it.appVersionCode.toString() == versionFilter
                }
            }

            val hasMore = snapshot.documents.size > pageSize
            val finalItems = records.take(pageSize.toInt())
            val lastSnap = snapshot.documents.take(finalItems.size).lastOrNull()

            Result.success(
                InstallationsPageResult(
                    items = finalItems,
                    lastSnapshot = lastSnap,
                    hasMore = hasMore
                )
            )
        } catch (e: Exception) {
            Log.e("InstallationRepo", "Failed to fetch installations page", e)
            Result.failure(e)
        }
    }

    suspend fun getInstallationDetails(id: String): Result<InstallationDetailBundle> = withContext(Dispatchers.IO) {
        val fs = getFirestore() ?: return@withContext Result.failure(
            IllegalStateException("Firebase is uninitialized.")
        )
        try {
            val doc = fs.collection("installations").document(id).get().await()
            if (!doc.exists()) {
                return@withContext Result.failure(NoSuchElementException("Installation $id not found"))
            }

            val record = doc.toObject(InstallationRecord::class.java)?.copy(id = doc.id)
                ?: return@withContext Result.failure(IllegalStateException("Unable to deserialize installation"))

            var linkedUser: UserAccount? = null
            var watchHistory: List<WatchHistoryEntry> = emptyList()

            // If user account is linked to this installation
            if (record.ownerUid.isNotBlank() && !record.ownerUid.startsWith("guest_")) {
                try {
                    val userDoc = fs.collection("users").document(record.ownerUid).get().await()
                    if (userDoc.exists()) {
                        linkedUser = userDoc.toObject(UserAccount::class.java)?.copy(uid = userDoc.id)
                    }
                } catch (e: Exception) {
                    Log.w("InstallationRepo", "Could not load linked user ${record.ownerUid}: ${e.message}")
                }

                try {
                    val historySnap = fs.collection("watch_history")
                        .document(record.ownerUid)
                        .collection("episodes")
                        .orderBy("last_played_at", Query.Direction.DESCENDING)
                        .limit(20)
                        .get()
                        .await()

                    watchHistory = historySnap.documents.mapNotNull { d ->
                        d.toObject(WatchHistoryEntry::class.java)?.copy(id = d.id)
                    }
                } catch (e: Exception) {
                    Log.w("InstallationRepo", "Could not load watch history: ${e.message}")
                }
            }

            Result.success(
                InstallationDetailBundle(
                    installation = record,
                    linkedUser = linkedUser,
                    recentWatchHistory = watchHistory
                )
            )
        } catch (e: Exception) {
            Log.e("InstallationRepo", "Failed to load installation details", e)
            Result.failure(e)
        }
    }

    suspend fun deleteInstallation(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        val fs = getFirestore() ?: return@withContext Result.failure(
            IllegalStateException("Firebase is uninitialized.")
        )
        try {
            fs.collection("installations").document(id).delete().await()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("InstallationRepo", "Failed to delete installation $id", e)
            Result.failure(e)
        }
    }

    suspend fun getInstallationStats(): Result<InstallationStats> = withContext(Dispatchers.IO) {
        val fs = getFirestore() ?: return@withContext Result.failure(
            IllegalStateException("Firebase is uninitialized.")
        )
        try {
            var totalCount = 0
            var activeNow = 0
            var recentlyActive = 0
            var inactive = 0
            var totalWatch = 0L
            val versions = mutableMapOf<String, Int>()
            val countries = mutableMapOf<String, Int>()
            val manufacturers = mutableMapOf<String, Int>()
            val androidVersions = mutableMapOf<String, Int>()
            val now = System.currentTimeMillis()
            var cursor: DocumentSnapshot? = null
            while (true) {
                var query: Query = fs.collection("installations").limit(500)
                cursor?.let { query = query.startAfter(it) }
                val snapshot = query.get().await()
                if (snapshot.isEmpty) break

                for (doc in snapshot.documents) {
                    totalCount++
                    val lastActive = doc.getLong("last_active_at") ?: 0L
                    val installed = doc.getLong("installed_at") ?: 0L
                    totalWatch += doc.getLong("total_watch_seconds") ?: 0L
                    val effectiveTime = if (lastActive > 0L) lastActive else installed
                    when {
                        effectiveTime <= 0L -> inactive++
                        now - effectiveTime <= 5 * 60 * 1000L -> activeNow++
                        now - effectiveTime <= 24 * 60 * 60 * 1000L -> recentlyActive++
                        else -> inactive++
                    }

                    doc.getString("app_version_name")?.takeIf { it.isNotBlank() }?.let { version ->
                        versions[version] = (versions[version] ?: 0) + 1
                    }
                    doc.getString("country_code")?.takeIf { it.isNotBlank() }?.let { country ->
                        countries[country] = (countries[country] ?: 0) + 1
                    }
                    doc.getString("device_manufacturer")?.takeIf { it.isNotBlank() }?.let { manufacturer ->
                        manufacturers[manufacturer] = (manufacturers[manufacturer] ?: 0) + 1
                    }
                    doc.getString("android_version")?.takeIf { it.isNotBlank() }?.let { androidVersion ->
                        androidVersions[androidVersion] = (androidVersions[androidVersion] ?: 0) + 1
                    }
                }

                cursor = snapshot.documents.lastOrNull()
                if (snapshot.size() < 500) break
            }

            Result.success(
                InstallationStats(
                    totalCount = totalCount,
                    activeNowCount = activeNow,
                    recentlyActiveCount = recentlyActive,
                    inactiveCount = inactive,
                    totalWatchSeconds = totalWatch,
                    versionDistribution = versions,
                    countryDistribution = countries,
                    manufacturerDistribution = manufacturers,
                    androidVersionDistribution = androidVersions
                )
            )
        } catch (e: Exception) {
            Log.e("InstallationRepo", "Failed to compute installation stats", e)
            Result.failure(e)
        }
    }
}
