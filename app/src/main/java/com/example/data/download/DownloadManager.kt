package com.example.data.download

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.util.Log
import com.example.data.local.AppDatabase
import com.example.data.local.EpisodeEntry
import com.example.data.local.DownloadDao
import com.example.data.local.DownloadEntry
import com.example.data.remote.NetworkModule
import com.example.data.repository.AnimeRepository
import com.example.data.repository.UserFacingErrorMapper
import com.example.data.repository.resolveVerifiedPlaybackForEpisode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

class DownloadManager private constructor(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val database = AppDatabase.getInstance(context)
    private val downloadDao: DownloadDao = database.downloadDao()
    private val repository = AnimeRepository(database, applicationContext = context)
    private val okHttpClient = NetworkModule.okHttpClient

    // Track active jobs in memory
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val activeDownloadIds = ConcurrentHashMap.newKeySet<String>()

    // Ephemeral download progress state for live UI observation
    data class LiveProgress(
        val episodeId: String,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val speedBytesPerSec: Long = 0L,
        val etaSeconds: Long? = null,
        val isPaused: Boolean = false
    )

    private val _liveProgressMap = MutableStateFlow<Map<String, LiveProgress>>(emptyMap())
    val liveProgressMap: StateFlow<Map<String, LiveProgress>> = _liveProgressMap.asStateFlow()

    data class StorageStats(
        val usedBytes: Long,
        val freeBytes: Long,
        val totalDeviceBytes: Long
    )

    private val _storageStats = MutableStateFlow(StorageStats(0L, 0L, 0L))
    val storageStats: StateFlow<StorageStats> = _storageStats.asStateFlow()

    init {
        refreshStorageStats()
    }

    fun observeAllDownloads(): Flow<List<DownloadEntry>> = downloadDao.observeAllDownloads()
    fun observeCompletedDownloads(): Flow<List<DownloadEntry>> = downloadDao.observeCompletedDownloads()
    fun observeActiveDownloads(): Flow<List<DownloadEntry>> = downloadDao.observeActiveDownloads()
    fun observeDownloadsForAnime(animeId: String): Flow<List<DownloadEntry>> = downloadDao.observeDownloadsForAnime(animeId)
    fun observeDownload(episodeId: String): Flow<DownloadEntry?> = downloadDao.observeDownload(episodeId)
    suspend fun getDownload(episodeId: String): DownloadEntry? = downloadDao.getDownload(episodeId)

    /**
     * Resolves the media stream and begins an app-private background download.
     */
    fun startOrResumeDownload(
        animeId: String,
        episodeId: String,
        seasonNumber: Int,
        episodeNumber: Int,
        episodeTitle: String?,
        animeTitle: String,
        posterUrl: String?,
        mediaType: String? = "anime",
        preferredQuality: String? = null,
        preferredLanguage: String? = null,
        preferredSourceId: String? = null
    ) {
        if (!activeDownloadIds.add(episodeId)) {
            Log.d(TAG, "Download already active for episode: $episodeId")
            return
        }

        val job = scope.launch {
            try {
                val authManager = com.example.data.firebase.AuthManager.getInstance(context)
                val eligibility = authManager.checkDownloadEligibility()
                if (eligibility is com.example.data.firebase.DownloadEligibility.RequiresLogin) {
                    authManager.setPendingDownload(
                        com.example.data.firebase.PendingDownload(
                            animeId = animeId,
                            episodeId = episodeId,
                            seasonNumber = seasonNumber,
                            episodeNumber = episodeNumber,
                            episodeTitle = episodeTitle,
                            animeTitle = animeTitle,
                            posterUrl = posterUrl,
                            mediaType = mediaType,
                            preferredQuality = preferredQuality,
                            preferredLanguage = preferredLanguage,
                            preferredSourceId = preferredSourceId
                        )
                    )
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            context,
                            "Downloads require an account. Please log in or register.",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                    return@launch
                } else if (eligibility is com.example.data.firebase.DownloadEligibility.Suspended) {
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            context,
                            "Downloads disabled: ${eligibility.reason}",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                    return@launch
                }

                var existing = downloadDao.getDownload(episodeId)
                if (existing == null) {
                    existing = DownloadEntry(
                        id = episodeId,
                        animeId = animeId,
                        animeTitle = animeTitle,
                        posterUrl = posterUrl,
                        seasonNumber = seasonNumber,
                        episodeNumber = episodeNumber,
                        episodeTitle = episodeTitle,
                        downloadStatus = "PENDING_RESOLUTION",
                        downloadedBytes = 0L,
                        totalBytes = 0L,
                        mediaType = mediaType,
                        quality = preferredQuality,
                        audioLanguage = preferredLanguage,
                        createdAt = System.currentTimeMillis()
                    )
                    downloadDao.upsertDownload(existing)
                } else {
                    existing = existing.copy(
                        downloadStatus = "PENDING_RESOLUTION",
                        errorMessage = null,
                        quality = preferredQuality ?: existing.quality,
                        audioLanguage = preferredLanguage ?: existing.audioLanguage
                    )
                    downloadDao.upsertDownload(existing)
                }

                // Check device available storage
                val mediaDir = getAppPrivateMediaDir()
                val stat = StatFs(mediaDir.path)
                val availableBytes = stat.availableBytes
                if (availableBytes < 50 * 1024 * 1024) { // Less than 50MB
                    downloadDao.upsertDownload(
                        existing.copy(
                            downloadStatus = "FAILED",
                            errorMessage = "Insufficient storage space on device"
                        )
                    )
                    return@launch
                }

                // Cache-first stream URL resolution (Room -> Firestore -> lazy discovery/Resolve)
                val episode = database.episodeDao().getEpisodeById(episodeId)
                    ?: EpisodeEntry(episodeId, animeId, seasonNumber, episodeNumber, title = episodeTitle)
                var resolutionResult = repository.resolveVerifiedPlaybackForEpisode(
                    animeId,
                    episode,
                    preferredSourceId = preferredSourceId
                )
                if (resolutionResult.isFailure && isTransientResolutionFailure(resolutionResult.exceptionOrNull())) {
                    delay(1_500L)
                    resolutionResult = repository.resolveVerifiedPlaybackForEpisode(
                        animeId,
                        episode,
                        preferredSourceId = preferredSourceId
                    )
                }
                var verified = resolutionResult.getOrNull()
                if (verified != null &&
                    (!com.example.data.streaming.SafePlaybackHeaders.isValidStreamUrl(verified.directData.playbackUrl) ||
                        com.example.data.repository.SourceExpiryTime.isExpiredOrInvalid(verified.directData.expiresAt))
                ) {
                    Log.w(TAG, "Resolved download URL was invalid or expired; forcing a fresh resolution for $episodeId")
                    verified = repository.resolveVerifiedPlaybackForEpisode(
                        animeId,
                        episode,
                        preferredSourceId = preferredSourceId,
                        forceResolveFresh = true
                    ).getOrNull()
                }
                if (verified == null) {
                    downloadDao.upsertDownload(
                        existing.copy(
                            downloadStatus = "FAILED",
                            errorMessage = UserFacingErrorMapper.message(
                                resolutionResult.exceptionOrNull(),
                                "No verified downloadable source is ready yet. Please try again."
                            )
                        )
                    )
                    return@launch
                }

                val source = verified.source
                val playData = verified.directData
                val targetUrl = playData.playbackUrl
                val headers = playData.headers
                val protocolHint = playData.urlType
                existing = existing.copy(
                    downloadStatus = "DOWNLOADING",
                    downloadUrl = null,
                    quality = existing.quality ?: source.quality,
                    audioLanguage = existing.audioLanguage ?: source.language
                )
                downloadDao.upsertDownload(existing)

                if (!com.example.data.streaming.SafePlaybackHeaders.isValidStreamUrl(targetUrl) ||
                    com.example.data.repository.SourceExpiryTime.isExpiredOrInvalid(playData.expiresAt)
                ) {
                    downloadDao.upsertDownload(
                        existing.copy(
                            downloadStatus = "FAILED",
                            errorMessage = "No valid download URL available"
                        )
                    )
                    return@launch
                }

                // App-private target file (guaranteed not in Gallery)
                val isMovie = mediaType.equals("movie", ignoreCase = true) ||
                    seasonNumber == 0 || episodeId.startsWith("movie_")
                val sanitizedAnime = animeId.replace(Regex("[^a-zA-Z0-9_]"), "_")
                val baseName = if (isMovie) "${sanitizedAnime}_movie" else "${sanitizedAnime}_s${seasonNumber}_e${episodeNumber}"
                val finalFile = File(mediaDir, "$baseName.mp4")
                val tempFile = File(mediaDir, "$baseName.tmp")

                executeDownloadLoop(
                    episodeId = episodeId,
                    url = targetUrl,
                    headers = headers,
                    tempFile = tempFile,
                    finalFile = finalFile,
                    entry = existing,
                    protocolHint = protocolHint
                )
            } catch (e: Exception) {
                Log.e(TAG, "Download error for $episodeId", e)
                val current = downloadDao.getDownload(episodeId)
                if (current != null && current.downloadStatus != "PAUSED") {
                    downloadDao.upsertDownload(
                        current.copy(
                            downloadStatus = "FAILED",
                            errorMessage = UserFacingErrorMapper.message(e, "Download connection interrupted. Please try again.")
                        )
                    )
                }
        } finally {
            activeJobs.remove(episodeId)
            activeDownloadIds.remove(episodeId)
            removeLiveProgress(episodeId)
                refreshStorageStats()
            }
        }

        activeJobs[episodeId] = job
    }

    private suspend fun executeDownloadLoop(
        episodeId: String,
        url: String,
        headers: List<List<String>>,
        tempFile: File,
        finalFile: File,
        entry: DownloadEntry,
        protocolHint: String?
    ) {
        var attempt = 0
        while (true) {
            try {
                executeDownloadAttempt(episodeId, url, headers, tempFile, finalFile, entry, protocolHint)
                return
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (interrupted: IOException) {
                if (attempt >= MAX_INTERRUPTED_RETRIES) throw interrupted
                attempt++
                Log.w(
                    TAG,
                    "Download interrupted for $episodeId; resuming from temp file " +
                        "(attempt $attempt/$MAX_INTERRUPTED_RETRIES)",
                    interrupted
                )
                delay(RETRY_DELAY_MS * attempt)
            }
        }
    }

    private suspend fun executeDownloadAttempt(
        episodeId: String,
        url: String,
        headers: List<List<String>>,
        tempFile: File,
        finalFile: File,
        entry: DownloadEntry,
        protocolHint: String?
    ) = withContext(Dispatchers.IO) {
        var downloadedBytes = if (tempFile.exists()) tempFile.length() else 0L

        val reqBuilder = Request.Builder().url(url)
        // Inject authorized headers
        headers.forEach { pair ->
            if (pair.size >= 2) reqBuilder.header(pair[0], pair[1])
        }

        // Support HTTP Range requests for resume
        if (downloadedBytes > 0L) {
            reqBuilder.header("Range", "bytes=$downloadedBytes-")
        }

        var response = okHttpClient.newCall(reqBuilder.build()).execute()
        if (downloadedBytes > 0L && response.isSuccessful && response.code != 206) {
            // The server ignored Range (or returned a non-partial success). Restart from byte zero.
            response.close()
            tempFile.delete()
            downloadedBytes = 0L
            response = okHttpClient.newCall(
                Request.Builder().url(url).apply {
                    headers.forEach { if (it.size >= 2) header(it[0], it[1]) }
                }.build()
            ).execute()
        }
        if (downloadedBytes > 0L && response.code == 206) {
            val returnedOffset = response.header("Content-Range")
                ?.substringAfter("bytes ", "")
                ?.substringBefore('-')
                ?.toLongOrNull()
            if (returnedOffset != downloadedBytes) {
                // Never append bytes unless the server confirms the exact requested offset.
                response.close()
                tempFile.delete()
                downloadedBytes = 0L
                response = okHttpClient.newCall(
                    Request.Builder().url(url).apply {
                        headers.forEach { if (it.size >= 2) header(it[0], it[1]) }
                    }.build()
                ).execute()
            }
        }
        if (!response.isSuccessful && response.code != 206) {
            // If range not satisfiable, restart from 0
            if (response.code == 416) {
                response.close()
                tempFile.delete()
                downloadedBytes = 0L
                val restartResp = okHttpClient.newCall(
                    Request.Builder().url(url).apply {
                        headers.forEach { if (it.size >= 2) header(it[0], it[1]) }
                    }.build()
                ).execute()
                if (!restartResp.isSuccessful) {
                    downloadDao.upsertDownload(entry.copy(downloadStatus = "FAILED", errorMessage = UserFacingErrorMapper.message(IllegalStateException("HTTP ${restartResp.code}"))))
                    return@withContext
                }
                if (isManifestResponse(restartResp, url, protocolHint)) {
                    restartResp.close()
                    downloadDao.upsertDownload(entry.copy(downloadStatus = "FAILED", errorMessage = "HLS/DASH streams are not supported for complete downloads"))
                    return@withContext
                }
                streamResponseBody(episodeId, restartResp, tempFile, finalFile, 0L, entry)
                return@withContext
            } else {
                downloadDao.upsertDownload(entry.copy(downloadStatus = "FAILED", errorMessage = UserFacingErrorMapper.message(IllegalStateException("HTTP ${response.code}"))))
                return@withContext
            }
        }

        if (isManifestResponse(response, url, protocolHint)) {
            response.close()
            downloadDao.upsertDownload(entry.copy(downloadStatus = "FAILED", errorMessage = "HLS/DASH streams are not supported for complete downloads"))
            return@withContext
        }
        streamResponseBody(episodeId, response, tempFile, finalFile, downloadedBytes, entry)
    }

    private fun isManifestResponse(response: okhttp3.Response, url: String, protocolHint: String?): Boolean {
        val hint = protocolHint.orEmpty().lowercase()
        val path = url.substringBefore('?').lowercase()
        val contentType = response.header("Content-Type").orEmpty().lowercase()
        return hint in setOf("hls", "dash", "m3u8", "mpd") ||
            path.endsWith(".m3u8") || path.endsWith(".mpd") ||
            contentType.contains("mpegurl") || contentType.contains("m3u8") ||
            contentType.contains("dash+xml") || contentType.contains("application/mpd")
    }

    private suspend fun streamResponseBody(
        episodeId: String,
        response: okhttp3.Response,
        tempFile: File,
        finalFile: File,
        alreadyDownloaded: Long,
        entry: DownloadEntry
    ) = withContext(Dispatchers.IO) {
        val body = response.body ?: throw IllegalStateException("Empty download response body")
        val contentLength = body.contentLength()
        val totalBytes = if (contentLength > 0L) alreadyDownloaded + contentLength else alreadyDownloaded

        val output = if (alreadyDownloaded > 0L) {
            RandomAccessFile(tempFile, "rw").apply { seek(alreadyDownloaded) }
        } else {
            FileOutputStream(tempFile)
        }

        var currentDownloaded = alreadyDownloaded
        val buffer = ByteArray(32 * 1024)
        val inputStream = body.byteStream()

        var lastDbUpdateTime = System.currentTimeMillis()
        var lastSpeedTime = System.currentTimeMillis()
        var bytesSinceLastSpeed = 0L
        var currentSpeed = 0L

        try {
            while (true) {
                val currentJob = activeJobs[episodeId]
                if (currentJob == null || !currentJob.isActive) {
                    // Download paused or cancelled
                    output.close()
                    inputStream.close()
                    return@withContext
                }

                val read = inputStream.read(buffer)
                if (read == -1) break

                if (output is RandomAccessFile) {
                    output.write(buffer, 0, read)
                } else if (output is FileOutputStream) {
                    output.write(buffer, 0, read)
                }

                currentDownloaded += read
                bytesSinceLastSpeed += read

                val now = System.currentTimeMillis()
                if (now - lastSpeedTime >= 1000) {
                    currentSpeed = bytesSinceLastSpeed * 1000 / (now - lastSpeedTime)
                    bytesSinceLastSpeed = 0L
                    lastSpeedTime = now
                }

                // Update live in-memory progress for smooth UI
                updateLiveProgress(episodeId, currentDownloaded, totalBytes, currentSpeed)

                // Periodically update database every 3 seconds
                if (now - lastDbUpdateTime >= 3000) {
                    downloadDao.updateProgress(episodeId, "DOWNLOADING", currentDownloaded, totalBytes)
                    lastDbUpdateTime = now
                }
            }

            output.close()
            inputStream.close()

            downloadDao.updateProgress(episodeId, "DOWNLOADING", currentDownloaded, totalBytes)
            updateLiveProgress(episodeId, currentDownloaded, totalBytes, currentSpeed)

            // Completion: only publish a non-empty file. A successful HTTP response alone is
            // not sufficient evidence that a playable media file was downloaded.
            if (tempFile.exists() && tempFile.length() > 0L) {
                if (finalFile.exists()) finalFile.delete()
                val renamed = tempFile.renameTo(finalFile)
                if (renamed && finalFile.exists() && finalFile.length() > 0L) {
                    downloadDao.upsertDownload(
                        entry.copy(
                            downloadStatus = "COMPLETED",
                            downloadedBytes = finalFile.length(),
                            totalBytes = finalFile.length(),
                            localFilePath = finalFile.absolutePath,
                            errorMessage = null
                        )
                    )
                } else {
                    downloadDao.upsertDownload(
                        entry.copy(
                            downloadStatus = "FAILED",
                            errorMessage = "Failed to finalize downloaded file"
                        )
                    )
                }
            } else {
                downloadDao.upsertDownload(
                    entry.copy(
                        downloadStatus = "FAILED",
                        errorMessage = "Downloaded file is empty or unavailable"
                    )
                )
            }
        } finally {
            try { output.close() } catch (_: Exception) {}
            try { inputStream.close() } catch (_: Exception) {}
        }
    }

    fun pauseDownload(episodeId: String) {
        val job = activeJobs.remove(episodeId)
        job?.cancel()
        scope.launch {
            val current = downloadDao.getDownload(episodeId)
            if (current != null && current.downloadStatus == "DOWNLOADING") {
                downloadDao.upsertDownload(current.copy(downloadStatus = "PAUSED"))
            }
            removeLiveProgress(episodeId)
        }
    }

    fun cancelDownload(episodeId: String) {
        val job = activeJobs.remove(episodeId)
        job?.cancel()
        scope.launch {
            val current = downloadDao.getDownload(episodeId)
            if (current != null) {
                // Delete temp file
                val mediaDir = getAppPrivateMediaDir()
                val isMovie = current.mediaType.equals("movie", ignoreCase = true) ||
                    current.seasonNumber == 0 || current.id.startsWith("movie_")
                val sanitizedAnime = current.animeId.replace(Regex("[^a-zA-Z0-9_]"), "_")
                val baseName = if (isMovie) "${sanitizedAnime}_movie" else "${sanitizedAnime}_s${current.seasonNumber}_e${current.episodeNumber}"
                val tempFile = File(mediaDir, "$baseName.tmp")
                if (tempFile.exists()) tempFile.delete()
                downloadDao.deleteDownload(episodeId)
            }
            removeLiveProgress(episodeId)
            refreshStorageStats()
        }
    }

    fun deleteDownloadedFile(episodeId: String) {
        scope.launch {
            val current = downloadDao.getDownload(episodeId)
            if (current != null) {
                current.localFilePath?.let { path ->
                    val f = File(path)
                    if (f.exists()) f.delete()
                }
                downloadDao.deleteDownload(episodeId)
            }
            refreshStorageStats()
        }
    }

    fun getAppPrivateMediaDir(): File {
        val dir = context.getExternalFilesDir(null)?.resolve("app_downloads")
            ?: context.filesDir.resolve("app_downloads")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun refreshStorageStats() {
        scope.launch {
            val mediaDir = getAppPrivateMediaDir()
            val usedBytes = mediaDir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
            val stat = StatFs(mediaDir.path)
            val freeBytes = stat.availableBytes
            val totalDeviceBytes = stat.totalBytes
            _storageStats.value = StorageStats(
                usedBytes = usedBytes,
                freeBytes = freeBytes,
                totalDeviceBytes = totalDeviceBytes
            )
        }
    }

    private fun updateLiveProgress(episodeId: String, downloaded: Long, total: Long, speed: Long) {
        val map = _liveProgressMap.value.toMutableMap()
        val etaSeconds = if (total > 0L && speed > 0L) ((total - downloaded).coerceAtLeast(0L) / speed) else null
        map[episodeId] = LiveProgress(
            episodeId = episodeId,
            downloadedBytes = downloaded,
            totalBytes = total,
            speedBytesPerSec = speed,
            etaSeconds = etaSeconds
        )
        _liveProgressMap.value = map
    }

    private fun removeLiveProgress(episodeId: String) {
        val map = _liveProgressMap.value.toMutableMap()
        map.remove(episodeId)
        _liveProgressMap.value = map
    }

    private fun isTransientResolutionFailure(error: Throwable?): Boolean =
        generateSequence(error) { it.cause }.any { cause ->
            cause is java.io.IOException || cause is java.net.SocketTimeoutException ||
                Regex("HTTP (408|429|5\\d\\d)").containsMatchIn(cause.message.orEmpty()) ||
                cause.message.orEmpty().contains("temporarily", ignoreCase = true)
        }

    companion object {
        private const val TAG = "DownloadManager"
        private const val MAX_INTERRUPTED_RETRIES = 2
        private const val RETRY_DELAY_MS = 1_000L

        @Volatile
        private var INSTANCE: DownloadManager? = null

        fun getInstance(context: Context): DownloadManager {
            return INSTANCE ?: synchronized(this) {
                val instance = DownloadManager(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
}
