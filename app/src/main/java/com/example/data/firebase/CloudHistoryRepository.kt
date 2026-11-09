package com.example.data.firebase

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.example.data.local.AppDatabase
import com.example.data.local.PlaybackHistory
import com.example.data.local.WatchlistEntry
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CloudHistoryRepository private constructor(private val context: Context) {
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val database: AppDatabase by lazy { AppDatabase.getInstance(context) }
    private val scope = CoroutineScope(Dispatchers.IO)
    private val pullMutex = Mutex()
    private val syncPreferences by lazy {
        context.getSharedPreferences("cloud_history_sync", Context.MODE_PRIVATE)
    }

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
            id = "history_${animeId}_$episodeId",
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
                // 1. Save locally only if this progress is not older than the stored episode state.
                database.withTransaction {
                    val dao = database.playbackHistoryDao()
                    val local = dao.getHistoryForEpisode(animeId, episodeId)
                    if (local == null || history.lastPlayedAt >= local.lastPlayedAt) {
                        dao.upsertHistory(history.copy(completed = history.completed || (local?.completed == true)))
                    }
                }

                // 2. Sync to Firestore if authenticated
                val currentUser = AuthManager.getInstance(context).currentUser ?: return@launch
                val ownerKey = currentUser.uid
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

                val historyRef = firestore.collection("watch_history")
                    .document(ownerKey)
                    .collection("episodes")
                    .document(history.id)
                FirestoreRequestGuard.execute("sync playback history") {
                    firestore.runTransaction { transaction ->
                        val remote = transaction.get(historyRef)
                        val remoteTimestamp = remote.getLong("last_played_at") ?: Long.MIN_VALUE
                        if (!remote.exists() || history.lastPlayedAt >= remoteTimestamp) {
                            val merged = docData.toMutableMap()
                            merged["completed"] = completed || (remote.getBoolean("completed") == true)
                            transaction.set(historyRef, merged, SetOptions.merge())
                        }
                        null
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
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

                val currentUser = AuthManager.getInstance(context).currentUser ?: return@launch
                val ownerKey = currentUser.uid
                val docData = hashMapOf<String, Any>(
                    "anime_id" to animeId,
                    "status" to status,
                    "is_favorite" to isFavorite,
                    "updated_at" to entry.updatedAt,
                    "owner_key" to ownerKey
                )

                FirestoreRequestGuard.execute("sync watchlist") {
                    firestore.collection("watchlist")
                        .document(ownerKey)
                        .collection("items")
                        .document(animeId)
                        .set(docData, SetOptions.merge())
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.w("CloudHistoryRepository", "Failed to sync watchlist to cloud: ${e.message}")
            }
        }
    }

    fun pullCloudHistoryAndWatchlist() {
        scope.launch {
            pullMutex.withLock {
                try {
                    val currentUser = AuthManager.getInstance(context).currentUser ?: return@withLock
                    val ownerKey = currentUser.uid
                    val prefKey = "last_successful_pull_$ownerKey"
                    val now = System.currentTimeMillis()
                    if (now - syncPreferences.getLong(prefKey, 0L) < MIN_PULL_INTERVAL_MS) return@withLock
                // Pull cloud history
                val historyDocs = FirestoreRequestGuard.execute("pull playback history") {
                    firestore.collection("watch_history")
                        .document(ownerKey)
                        .collection("episodes")
                        .get()
                }

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
                    database.withTransaction {
                        val dao = database.playbackHistoryDao()
                        cloudHistories
                            .map { it.copy(id = "history_${it.animeId}_${it.episodeId}") }
                            .distinctBy { it.id }
                            .forEach { cloud ->
                                val local = dao.getHistoryForEpisode(cloud.animeId, cloud.episodeId)
                                if (local == null || cloud.lastPlayedAt >= local.lastPlayedAt) {
                                    dao.upsertHistory(cloud.copy(completed = cloud.completed || (local?.completed == true)))
                                }
                            }
                    }
                }

                // Pull cloud watchlist
                val watchlistDocs = FirestoreRequestGuard.execute("pull watchlist") {
                    firestore.collection("watchlist")
                        .document(ownerKey)
                        .collection("items")
                        .get()
                }

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
                    syncPreferences.edit().putLong(prefKey, System.currentTimeMillis()).apply()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    Log.w("CloudHistoryRepository", "Pull cloud history error: ${e.message}")
                }
            }
        }
    }

    companion object {
        private const val MIN_PULL_INTERVAL_MS = 5 * 60 * 1000L
        @Volatile
        private var instance: CloudHistoryRepository? = null

        fun getInstance(context: Context): CloudHistoryRepository {
            return instance ?: synchronized(this) {
                instance ?: CloudHistoryRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
