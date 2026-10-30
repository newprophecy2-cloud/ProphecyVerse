package com.example.data.firebase

import android.content.Context
import android.util.Log
import com.example.data.local.AppDatabase
import com.example.data.local.PlaybackHistory
import com.example.data.local.WatchlistEntry
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class CloudHistoryRepository private constructor(private val context: Context) {
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val database: AppDatabase by lazy { AppDatabase.getInstance(context) }
    private val scope = CoroutineScope(Dispatchers.IO)

    fun syncPlaybackProgress(
        animeId: String,
        episodeId: String,
        seasonNumber: Int,
        episodeNumber: Int,
        episodeTitle: String?,
        positionMs: Long,
        durationMs: Long,
        completed: Boolean
    ) {
        val history = PlaybackHistory(
            id = "${animeId}_$episodeId",
            animeId = animeId,
            episodeId = episodeId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            episodeTitle = episodeTitle,
            positionMs = positionMs,
            durationMs = durationMs,
            completed = completed,
            lastPlayedAt = System.currentTimeMillis()
        )

        scope.launch {
            try {
                // 1. Save locally in Room
                database.playbackHistoryDao().upsertHistory(history)

                // 2. Sync to Firestore in background
                val ownerKey = AuthManager.getInstance(context).currentUid
                val docData = hashMapOf<String, Any>(
                    "id" to history.id,
                    "anime_id" to animeId,
                    "episode_id" to episodeId,
                    "season_number" to seasonNumber,
                    "episode_number" to episodeNumber,
                    "episode_title" to (episodeTitle ?: ""),
                    "position_ms" to positionMs,
                    "duration_ms" to durationMs,
                    "completed" to completed,
                    "last_played_at" to history.lastPlayedAt,
                    "owner_key" to ownerKey
                )

                firestore.collection("watch_history")
                    .document(ownerKey)
                    .collection("episodes")
                    .document(history.id)
                    .set(docData, SetOptions.merge())
                    .await()
            } catch (e: Exception) {
                Log.w("CloudHistoryRepository", "Failed to sync progress to cloud: ${e.message}")
            }
        }
    }

    fun syncWatchlistStatus(animeId: String, status: String, isFavorite: Boolean) {
        scope.launch {
            try {
                val entry = WatchlistEntry(
                    animeId = animeId,
                    status = status,
                    isFavorite = isFavorite,
                    updatedAt = System.currentTimeMillis()
                )
                database.watchlistDao().upsertWatchlistEntry(entry)

                val ownerKey = AuthManager.getInstance(context).currentUid
                val docData = hashMapOf<String, Any>(
                    "anime_id" to animeId,
                    "status" to status,
                    "is_favorite" to isFavorite,
                    "updated_at" to entry.updatedAt,
                    "owner_key" to ownerKey
                )

                firestore.collection("watchlist")
                    .document(ownerKey)
                    .collection("items")
                    .document(animeId)
                    .set(docData, SetOptions.merge())
                    .await()
            } catch (e: Exception) {
                Log.w("CloudHistoryRepository", "Failed to sync watchlist to cloud: ${e.message}")
            }
        }
    }

    fun pullCloudHistoryAndWatchlist() {
        scope.launch {
            try {
                val ownerKey = AuthManager.getInstance(context).currentUid
                // Pull cloud history
                val historyDocs = firestore.collection("watch_history")
                    .document(ownerKey)
                    .collection("episodes")
                    .get()
                    .await()

                val cloudHistories = historyDocs.documents.mapNotNull { doc ->
                    val id = doc.getString("id") ?: return@mapNotNull null
                    val animeId = doc.getString("anime_id") ?: return@mapNotNull null
                    val episodeId = doc.getString("episode_id") ?: return@mapNotNull null
                    val seasonNumber = doc.getLong("season_number")?.toInt() ?: 1
                    val episodeNumber = doc.getLong("episode_number")?.toInt() ?: 1
                    val episodeTitle = doc.getString("episode_title")
                    val positionMs = doc.getLong("position_ms") ?: 0L
                    val durationMs = doc.getLong("duration_ms") ?: 0L
                    val completed = doc.getBoolean("completed") ?: false
                    val lastPlayedAt = doc.getLong("last_played_at") ?: System.currentTimeMillis()

                    PlaybackHistory(
                        id = id,
                        animeId = animeId,
                        episodeId = episodeId,
                        seasonNumber = seasonNumber,
                        episodeNumber = episodeNumber,
                        episodeTitle = episodeTitle,
                        positionMs = positionMs,
                        durationMs = durationMs,
                        completed = completed,
                        lastPlayedAt = lastPlayedAt
                    )
                }

                if (cloudHistories.isNotEmpty()) {
                    database.playbackHistoryDao().upsertHistories(cloudHistories)
                }

                // Pull cloud watchlist
                val watchlistDocs = firestore.collection("watchlist")
                    .document(ownerKey)
                    .collection("items")
                    .get()
                    .await()

                val cloudWatchlist = watchlistDocs.documents.mapNotNull { doc ->
                    val animeId = doc.getString("anime_id") ?: return@mapNotNull null
                    val status = doc.getString("status") ?: "WATCHING"
                    val isFavorite = doc.getBoolean("is_favorite") ?: false
                    val updatedAt = doc.getLong("updated_at") ?: System.currentTimeMillis()

                    WatchlistEntry(
                        animeId = animeId,
                        status = status,
                        isFavorite = isFavorite,
                        updatedAt = updatedAt
                    )
                }

                if (cloudWatchlist.isNotEmpty()) {
                    database.watchlistDao().upsertWatchlistEntries(cloudWatchlist)
                }
            } catch (e: Exception) {
                Log.w("CloudHistoryRepository", "Pull cloud history error: ${e.message}")
            }
        }
    }

    companion object {
        @Volatile
        private var instance: CloudHistoryRepository? = null

        fun getInstance(context: Context): CloudHistoryRepository {
            return instance ?: synchronized(this) {
                instance ?: CloudHistoryRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
