package com.example.data.repository

import android.util.Log
import com.example.data.model.WatchHistoryEntry
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

data class ContentWatchStats(
    val animeId: String,
    val animeTitle: String,
    val totalEpisodesWatched: Int,
    val totalSecondsWatched: Long
)

data class WatchAnalyticsData(
    val totalWatchSeconds: Long = 0L,
    val totalCompletedEpisodes: Int = 0,
    val recentActivityList: List<WatchHistoryEntry> = emptyList(),
    val topSeries: List<ContentWatchStats> = emptyList()
)

class WatchAnalyticsRepository {

    private fun getFirestore(): FirebaseFirestore? =
        runCatching { FirebaseFirestore.getInstance() }.getOrNull()

    private suspend fun readAll(query: Query): List<DocumentSnapshot> {
        val documents = mutableListOf<DocumentSnapshot>()
        var cursor: DocumentSnapshot? = null
        while (true) {
            var pageQuery = query.limit(500)
            cursor?.let { pageQuery = pageQuery.startAfter(it) }
            val page = pageQuery.get().await()
            if (page.isEmpty) break
            documents += page.documents
            cursor = page.documents.lastOrNull()
            if (page.size() < 500) break
        }
        return documents
    }

    suspend fun getWatchAnalytics(): Result<WatchAnalyticsData> = withContext(Dispatchers.IO) {
        val fs = getFirestore() ?: return@withContext Result.failure(
            IllegalStateException("Firebase is uninitialized.")
        )
        try {
            // User APK writes numeric total_watch_seconds to each installation document.
            // Read every page so large installations collections do not produce truncated totals.
            val installationDocs = readAll(fs.collection("installations"))
            val totalSeconds = installationDocs.sumOf { it.getLong("total_watch_seconds") ?: 0L }

            // The User APK stores history at watch_history/{uid}/episodes/{animeId_episodeId}.
            // Rank and count the full set; the UI's recent activity list is the only capped view.
            val episodeDocs = readAll(fs.collectionGroup("episodes"))
            val allEntries = episodeDocs.mapNotNull { doc ->
                try {
                    doc.toObject(WatchHistoryEntry::class.java)?.copy(id = doc.id)
                } catch (e: Exception) {
                    Log.w("WatchAnalyticsRepo", "Failed to parse watch history ${doc.id}: ${e.message}")
                    null
                }
            }
            val recentEntries = allEntries.sortedByDescending { it.lastPlayedAt }.take(50)
            val completedCount = allEntries.count { it.completed }
            val animeMap = mutableMapOf<String, ContentWatchStats>()

            for (entry in allEntries) {
                val current = animeMap[entry.animeId] ?: ContentWatchStats(
                    animeId = entry.animeId,
                    animeTitle = entry.animeId.takeIf { it.isNotBlank() }?.let { "Anime ID $it" } ?: "Unknown anime ID",
                    totalEpisodesWatched = 0,
                    totalSecondsWatched = 0L
                )
                animeMap[entry.animeId] = current.copy(
                    totalEpisodesWatched = current.totalEpisodesWatched + 1,
                    totalSecondsWatched = current.totalSecondsWatched + (entry.positionMs / 1000)
                )
            }

            Result.success(
                WatchAnalyticsData(
                    totalWatchSeconds = totalSeconds,
                    totalCompletedEpisodes = completedCount,
                    recentActivityList = recentEntries,
                    topSeries = animeMap.values.sortedByDescending { it.totalEpisodesWatched }.take(10)
                )
            )
        } catch (e: Exception) {
            Log.e("WatchAnalyticsRepo", "Failed to read Firestore watch analytics", e)
            Result.failure(e)
        }
    }
}
