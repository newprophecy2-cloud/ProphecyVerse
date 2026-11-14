package com.example.data.firebase

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.example.data.local.AppDatabase
import com.example.data.local.PlaybackHistory
import com.example.data.local.WatchlistEntry
import com.example.data.repository.AnimeRepository
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
    private val animeRepository: AnimeRepository by lazy { AnimeRepository(database, applicationContext = context) }
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
        completed: Boolean,
        animeTitle: String? = null,
        posterUrl: String? = null,
        mediaType: String? = null
    ) {
        scope.launch {
            try {
                // Resolve only from locally stored, proven AniList relation metadata.
                val canonicalId = animeRepository.resolveCanonicalRoute(animeId).animeId
                val canonicalHistoryId = "history_${canonicalId}_$episodeId"
                val now = System.currentTimeMillis()
                val anime = database.animeDao().getAnimeById(canonicalId)
                var mergedHistory: PlaybackHistory? = null

                // 1. Save locally only if this progress is not older than the stored episode state,
                // preserving non-blank anime metadata across updates.
                database.withTransaction {
                    val dao = database.playbackHistoryDao()
                    val local = dao.getHistoryForEpisode(canonicalId, episodeId)
                    val resolvedTitle = animeTitle?.takeIf { it.isNotBlank() }
                        ?: local?.animeTitle?.takeIf { it.isNotBlank() }
                        ?: anime?.preferredTitle
                        ?: anime?.titleEnglish
                    val resolvedPoster = posterUrl?.takeIf { it.isNotBlank() }
                        ?: local?.posterUrl?.takeIf { it.isNotBlank() }
                        ?: anime?.posterUrl
                    val resolvedMediaType = mediaType?.takeIf { it.isNotBlank() }
                        ?: local?.mediaType?.takeIf { it.isNotBlank() }
                        ?: anime?.format
                    val resolvedEpisodeTitle = episodeTitle?.takeIf { it.isNotBlank() }
                        ?: local?.episodeTitle
                    val candidate = PlaybackHistory(
                        id = canonicalHistoryId,
                        animeId = canonicalId,
                        episodeId = episodeId,
                        seasonNumber = seasonNumber,
                        episodeNumber = episodeNumber,
                        episodeTitle = resolvedEpisodeTitle,
                        positionMs = positionMs,
                        durationMs = durationMs,
                        completed = completed || (local?.completed == true),
                        animeTitle = resolvedTitle,
                        posterUrl = resolvedPoster,
                        mediaType = resolvedMediaType,
                        lastPlayedAt = now
                    )
                    if (local == null || candidate.lastPlayedAt >= local.lastPlayedAt) {
                        dao.upsertHistory(candidate)
                        mergedHistory = candidate
                    } else {
                        mergedHistory = local
                    }
                }

                val saved = mergedHistory ?: return@launch

                // 2. Sync to Firestore if authenticated
                val currentUser = AuthManager.getInstance(context).currentUser ?: return@launch
                val ownerKey = currentUser.uid
                val docData = buildHistoryDocMap(saved, ownerKey)

                val historyRef = firestore.collection("watch_history")
                    .document(ownerKey)
                    .collection("episodes")
                    .document(saved.id)
                FirestoreRequestGuard.execute("sync playback history") {
                    firestore.runTransaction { transaction ->
                        val remote = transaction.get(historyRef)
                        val remoteTimestamp = remote.getLong("last_played_at") ?: Long.MIN_VALUE
                        if (!remote.exists() || saved.lastPlayedAt >= remoteTimestamp) {
                            val merged = docData.toMutableMap()
                            merged["completed"] = saved.completed || (remote.getBoolean("completed") == true)
                            if (saved.animeTitle.isNullOrBlank()) {
                                remote.getString("anime_title")?.takeIf { it.isNotBlank() }?.let { merged["anime_title"] = it }
                            }
                            if (saved.posterUrl.isNullOrBlank()) {
                                remote.getString("poster_url")?.takeIf { it.isNotBlank() }?.let { merged["poster_url"] = it }
                            }
                            if (saved.mediaType.isNullOrBlank()) {
                                remote.getString("media_type")?.takeIf { it.isNotBlank() }?.let { merged["media_type"] = it }
                            }
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
                val canonicalId = animeRepository.resolveCanonicalRoute(animeId).animeId
                val existing = database.watchlistDao().getWatchlistEntry(canonicalId)
                val entry = WatchlistEntry(
                    animeId = canonicalId,
                    status = status,
                    isFavorite = isFavorite,
                    userScore = existing?.userScore,
                    addedAt = existing?.addedAt ?: System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
                database.watchlistDao().upsertWatchlistEntry(entry)

                val currentUser = AuthManager.getInstance(context).currentUser ?: return@launch
                val ownerKey = currentUser.uid
                val docData = buildWatchlistDocMap(entry, ownerKey)

                FirestoreRequestGuard.execute("sync watchlist") {
                    firestore.collection("watchlist")
                        .document(ownerKey)
                        .collection("items")
                        .document(canonicalId)
                        .set(docData, SetOptions.merge())
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.w("CloudHistoryRepository", "Failed to sync watchlist to cloud: ${e.message}")
            }
        }
    }

    fun deleteWatchlistStatus(animeId: String) {
        scope.launch {
            try {
                val canonicalId = animeRepository.resolveCanonicalRoute(animeId).animeId
                database.watchlistDao().deleteWatchlistEntry(canonicalId)

                val currentUser = AuthManager.getInstance(context).currentUser ?: return@launch
                val ownerKey = currentUser.uid
                FirestoreRequestGuard.execute("delete watchlist") {
                    firestore.collection("watchlist")
                        .document(ownerKey)
                        .collection("items")
                        .document(canonicalId)
                        .delete()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.w("CloudHistoryRepository", "Failed to delete watchlist from cloud: ${e.message}")
            }
        }
    }

    suspend fun pullCloudHistoryAndWatchlist(force: Boolean = false): Boolean {
        return pullMutex.withLock {
            try {
                val currentUser = AuthManager.getInstance(context).currentUser ?: return@withLock false
                val ownerKey = currentUser.uid
                val prefKey = "last_successful_pull_$ownerKey"
                val now = System.currentTimeMillis()
                if (!force && now - syncPreferences.getLong(prefKey, 0L) < MIN_PULL_INTERVAL_MS) {
                    return@withLock true
                }

                // 1. Pull cloud history
                val historyDocs = FirestoreRequestGuard.execute("pull playback history") {
                    firestore.collection("watch_history")
                        .document(ownerKey)
                        .collection("episodes")
                        .get()
                }

                val cloudHistories = buildList {
                    for (doc in historyDocs.documents) {
                        val rawAnimeId = doc.getString("anime_id") ?: continue
                        val animeId = animeRepository.resolveCanonicalRoute(rawAnimeId).animeId
                        val episodeId = doc.getString("episode_id") ?: continue
                        add(
                            PlaybackHistory(
                                id = "history_${animeId}_$episodeId",
                                animeId = animeId,
                                episodeId = episodeId,
                                seasonNumber = doc.getLong("season_number")?.toInt() ?: 1,
                                episodeNumber = doc.getLong("episode_number")?.toInt() ?: 1,
                                episodeTitle = doc.getString("episode_title"),
                                positionMs = doc.getLong("position_ms") ?: 0L,
                                durationMs = doc.getLong("duration_ms") ?: 0L,
                                completed = doc.getBoolean("completed") ?: false,
                                animeTitle = doc.getString("anime_title")?.takeIf { it.isNotBlank() },
                                posterUrl = doc.getString("poster_url")?.takeIf { it.isNotBlank() },
                                mediaType = doc.getString("media_type")?.takeIf { it.isNotBlank() },
                                lastPlayedAt = doc.getLong("last_played_at") ?: System.currentTimeMillis()
                            )
                        )
                    }
                }.distinctBy { it.id }

                val cloudHistoryById = cloudHistories.associateBy { it.id }
                val localHistoriesBefore = database.playbackHistoryDao().getAllHistoryOnce()

                if (cloudHistories.isNotEmpty()) {
                    database.withTransaction {
                        val dao = database.playbackHistoryDao()
                        val animeDao = database.animeDao()
                        cloudHistories.forEach { cloud ->
                            val local = dao.getHistoryForEpisode(cloud.animeId, cloud.episodeId)
                            val anime = animeDao.getAnimeById(cloud.animeId)
                            val merged = mergePlaybackHistories(local, cloud, anime)
                            if (local == null || cloud.lastPlayedAt >= local.lastPlayedAt) {
                                dao.upsertHistory(merged)
                            } else if (merged != local) {
                                // Backfill any missing metadata or completion flag onto newer local row
                                dao.upsertHistory(merged)
                            }
                        }
                    }
                }

                // Push local guest/newer history items up to Firestore so they are preserved on login/signup
                val historiesToPush = localHistoriesBefore.filter { local ->
                    val canonicalId = animeRepository.resolveCanonicalRoute(local.animeId).animeId
                    val normalizedId = "history_${canonicalId}_${local.episodeId}"
                    val remote = cloudHistoryById[normalizedId]
                    remote == null || local.lastPlayedAt > remote.lastPlayedAt || (local.completed && !remote.completed)
                }
                for (local in historiesToPush) {
                    val canonicalId = animeRepository.resolveCanonicalRoute(local.animeId).animeId
                    val anime = database.animeDao().getAnimeById(canonicalId)
                    val normalized = local.copy(
                        id = "history_${canonicalId}_${local.episodeId}",
                        animeId = canonicalId,
                        animeTitle = local.animeTitle?.takeIf { it.isNotBlank() } ?: anime?.preferredTitle ?: anime?.titleEnglish,
                        posterUrl = local.posterUrl?.takeIf { it.isNotBlank() } ?: anime?.posterUrl,
                        mediaType = local.mediaType?.takeIf { it.isNotBlank() } ?: anime?.format
                    )
                    runCatching {
                        FirestoreRequestGuard.execute("push local playback history") {
                            firestore.collection("watch_history")
                                .document(ownerKey)
                                .collection("episodes")
                                .document(normalized.id)
                                .set(buildHistoryDocMap(normalized, ownerKey), SetOptions.merge())
                        }
                    }
                }

                // 2. Pull cloud watchlist
                val watchlistDocs = FirestoreRequestGuard.execute("pull watchlist") {
                    firestore.collection("watchlist")
                        .document(ownerKey)
                        .collection("items")
                        .get()
                }

                val cloudWatchlist = buildList {
                    for (doc in watchlistDocs.documents) {
                        val rawAnimeId = doc.getString("anime_id") ?: continue
                        val animeId = animeRepository.resolveCanonicalRoute(rawAnimeId).animeId
                        add(
                            WatchlistEntry(
                                animeId = animeId,
                                status = doc.getString("status") ?: "WATCHING",
                                isFavorite = doc.getBoolean("is_favorite") ?: false,
                                updatedAt = doc.getLong("updated_at") ?: System.currentTimeMillis()
                            )
                        )
                    }
                }.distinctBy { it.animeId }

                val localWatchlistBefore = database.watchlistDao().getAllWatchlistOnce()
                val mergePlan = computeWatchlistMergePlan(localWatchlistBefore, cloudWatchlist)

                if (mergePlan.toUpsertLocal.isNotEmpty()) {
                    database.watchlistDao().upsertWatchlistEntries(mergePlan.toUpsertLocal)
                }
                for (local in mergePlan.toPushCloud) {
                    runCatching {
                        FirestoreRequestGuard.execute("push local watchlist") {
                            firestore.collection("watchlist")
                                .document(ownerKey)
                                .collection("items")
                                .document(local.animeId)
                                .set(buildWatchlistDocMap(local, ownerKey), SetOptions.merge())
                        }
                    }
                }

                syncPreferences.edit().putLong(prefKey, System.currentTimeMillis()).apply()
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.w("CloudHistoryRepository", "Pull cloud history error: ${e.message}")
                false
            }
        }
    }

    private fun buildHistoryDocMap(history: PlaybackHistory, ownerKey: String): HashMap<String, Any> {
        val docData = hashMapOf<String, Any>(
            "id" to history.id,
            "anime_id" to history.animeId,
            "episode_id" to history.episodeId,
            "season_number" to history.seasonNumber,
            "episode_number" to history.episodeNumber,
            "episode_title" to (history.episodeTitle ?: ""),
            "position_ms" to history.positionMs,
            "duration_ms" to history.durationMs,
            "completed" to history.completed,
            "last_played_at" to history.lastPlayedAt,
            "owner_key" to ownerKey
        )
        history.animeTitle?.takeIf { it.isNotBlank() }?.let { docData["anime_title"] = it }
        history.posterUrl?.takeIf { it.isNotBlank() }?.let { docData["poster_url"] = it }
        history.mediaType?.takeIf { it.isNotBlank() }?.let { docData["media_type"] = it }
        return docData
    }

    private fun buildWatchlistDocMap(entry: WatchlistEntry, ownerKey: String): HashMap<String, Any> =
        hashMapOf(
            "anime_id" to entry.animeId,
            "status" to entry.status,
            "is_favorite" to entry.isFavorite,
            "updated_at" to entry.updatedAt,
            "owner_key" to ownerKey
        )

    internal data class WatchlistMergePlan(
        val toUpsertLocal: List<WatchlistEntry>,
        val toPushCloud: List<WatchlistEntry>
    )

    companion object {
        private const val MIN_PULL_INTERVAL_MS = 5 * 60 * 1000L
        @Volatile
        private var instance: CloudHistoryRepository? = null

        fun getInstance(context: Context): CloudHistoryRepository {
            return instance ?: synchronized(this) {
                instance ?: CloudHistoryRepository(context.applicationContext).also { instance = it }
            }
        }

        internal fun mergePlaybackHistories(
            local: PlaybackHistory?,
            cloud: PlaybackHistory,
            anime: com.example.data.local.AnimeEntry? = null
        ): PlaybackHistory {
            val base = if (local == null || cloud.lastPlayedAt >= local.lastPlayedAt) cloud else local
            val resolvedTitle = base.animeTitle?.takeIf { it.isNotBlank() }
                ?: local?.animeTitle?.takeIf { it.isNotBlank() }
                ?: cloud.animeTitle?.takeIf { it.isNotBlank() }
                ?: anime?.preferredTitle
                ?: anime?.titleEnglish
            val resolvedPoster = base.posterUrl?.takeIf { it.isNotBlank() }
                ?: local?.posterUrl?.takeIf { it.isNotBlank() }
                ?: cloud.posterUrl?.takeIf { it.isNotBlank() }
                ?: anime?.posterUrl
            val resolvedType = base.mediaType?.takeIf { it.isNotBlank() }
                ?: local?.mediaType?.takeIf { it.isNotBlank() }
                ?: cloud.mediaType?.takeIf { it.isNotBlank() }
                ?: anime?.format
            val resolvedEpTitle = base.episodeTitle?.takeIf { it.isNotBlank() }
                ?: local?.episodeTitle?.takeIf { it.isNotBlank() }
                ?: cloud.episodeTitle?.takeIf { it.isNotBlank() }
            return base.copy(
                id = "history_${cloud.animeId}_${cloud.episodeId}",
                completed = cloud.completed || (local?.completed == true),
                episodeTitle = resolvedEpTitle,
                animeTitle = resolvedTitle,
                posterUrl = resolvedPoster,
                mediaType = resolvedType
            )
        }

        internal fun computeWatchlistMergePlan(
            localEntries: List<WatchlistEntry>,
            cloudEntries: List<WatchlistEntry>
        ): WatchlistMergePlan {
            val localById = localEntries.associateBy { it.animeId }
            val cloudById = cloudEntries.associateBy { it.animeId }

            val toUpsertLocal = cloudEntries.filter { cloud ->
                val local = localById[cloud.animeId]
                local == null || cloud.updatedAt >= local.updatedAt
            }
            val toPushCloud = localEntries.filter { local ->
                val cloud = cloudById[local.animeId]
                cloud == null || local.updatedAt > cloud.updatedAt
            }
            return WatchlistMergePlan(
                toUpsertLocal = toUpsertLocal,
                toPushCloud = toPushCloud
            )
        }
    }
}
