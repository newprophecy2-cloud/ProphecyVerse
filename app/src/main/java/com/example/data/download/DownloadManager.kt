package com.example.data.download

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.util.Log
import com.example.data.local.AppDatabase
import com.example.data.local.DownloadDao
import com.example.data.local.DownloadEntry
import com.example.data.remote.NetworkModule
import com.example.data.repository.AnimeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

class DownloadManager private constructor(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val database = AppDatabase.getInstance(context)
    private val downloadDao: DownloadDao = database.downloadDao()
    private val repository = AnimeRepository(database)
    private val okHttpClient = NetworkModule.okHttpClient

    // Track active jobs in memory
    private val activeJobs = ConcurrentHashMap<String, Job>()

    // Ephemeral download progress state for live UI observation
    data class LiveProgress(
        val episodeId: String,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val speedBytesPerSec: Long = 0L,
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
        if (activeJobs.containsKey(episodeId)) {
            Log.d(TAG, "Download already active for episode: $episodeId")
            return
        }

        val job = scope.launch {
            try {
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
                        downloadStatus = "DOWNLOADING",
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
                        downloadStatus = "DOWNLOADING",
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

                // Resolve URL if missing or expired
                var directUrl = existing.downloadUrl
                var headers: List<List<String>> = emptyList()

                if (directUrl.isNullOrBlank()) {
                    val anime = database.animeDao().getAnimeById(animeId)
                    val backendId = anime?.backendAnimeId ?: if (animeId.startsWith("anilist_")) null else animeId
                    if (backendId == null) {
                        downloadDao.upsertDownload(
                            existing.copy(
                                downloadStatus = "FAILED",
                                errorMessage = "Catalog media source not found"
                            )
                        )
                        return@launch
                    }

                    val sourcesRes = repository.fetchEpisodeSources(backendId, episodeId)
                    val sources = sourcesRes.getOrNull()
                    val source = if (!preferredSourceId.isNullOrBlank()) {
                        sources?.firstOrNull { it.id == preferredSourceId }
                    } else if (!preferredQuality.isNullOrBlank() || !preferredLanguage.isNullOrBlank()) {
                        sources?.firstOrNull { s ->
                            (preferredQuality.isNullOrBlank() || s.quality?.equals(preferredQuality, ignoreCase = true) == true) &&
                            (preferredLanguage.isNullOrBlank() || s.language?.contains(preferredLanguage, ignoreCase = true) == true)
                        } ?: sources?.firstOrNull { s ->
                            preferredQuality.isNullOrBlank() || s.quality?.equals(preferredQuality, ignoreCase = true) == true
                        } ?: sources?.firstOrNull { it.downloadable == true } ?: sources?.firstOrNull()
                    } else {
                        sources?.firstOrNull { it.downloadable == true } ?: sources?.firstOrNull()
                    }

                    if (source == null) {
                        downloadDao.upsertDownload(
                            existing.copy(
                                downloadStatus = "FAILED",
                                errorMessage = "No downloadable source provided by backend"
                            )
                        )
                        return@launch
                    }

                    val playRes = repository.resolveDirectPlayback(source.id)
                    val playData = playRes.getOrNull()
                    if (playData == null || playData.playbackUrl.isBlank()) {
                        downloadDao.upsertDownload(
                            existing.copy(
                                downloadStatus = "FAILED",
                                errorMessage = "Direct URL resolution failed"
                            )
                        )
                        return@launch
                    }

                    directUrl = playData.playbackUrl
                    headers = playData.headers
                    existing = existing.copy(
                        downloadUrl = directUrl,
                        quality = existing.quality ?: source.quality,
                        audioLanguage = existing.audioLanguage ?: source.language
                    )
                    downloadDao.upsertDownload(existing)
                }

                val targetUrl = directUrl
                if (targetUrl.isNullOrBlank()) {
                    downloadDao.upsertDownload(
                        existing.copy(
                            downloadStatus = "FAILED",
                            errorMessage = "No valid download URL available"
                        )
                    )
                    return@launch
                }

                // App-private target file (guaranteed not in Gallery)
                val sanitizedAnime = animeId.replace(Regex("[^a-zA-Z0-9_]"), "_")
                val finalFile = File(mediaDir, "${sanitizedAnime}_s${seasonNumber}_e${episodeNumber}.mp4")
                val tempFile = File(mediaDir, "${sanitizedAnime}_s${seasonNumber}_e${episodeNumber}.tmp")

                executeDownloadLoop(
                    episodeId = episodeId,
                    url = targetUrl,
                    headers = headers,
                    tempFile = tempFile,
                    finalFile = finalFile,
                    entry = existing
                )
            } catch (e: Exception) {
                Log.e(TAG, "Download error for $episodeId", e)
                val current = downloadDao.getDownload(episodeId)
                if (current != null && current.downloadStatus != "PAUSED") {
                    downloadDao.upsertDownload(
                        current.copy(
                            downloadStatus = "FAILED",
                            errorMessage = e.message ?: "Download connection interrupted"
                        )
                    )
                }
            } finally {
                activeJobs.remove(episodeId)
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
        entry: DownloadEntry
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

        val response = okHttpClient.newCall(reqBuilder.build()).execute()
        if (!response.isSuccessful && response.code != 206) {
            // If range not satisfiable, restart from 0
            if (response.code == 416) {
                tempFile.delete()
                downloadedBytes = 0L
                val restartResp = okHttpClient.newCall(
                    Request.Builder().url(url).apply {
                        headers.forEach { if (it.size >= 2) header(it[0], it[1]) }
                    }.build()
                ).execute()
                if (!restartResp.isSuccessful) {
                    downloadDao.upsertDownload(entry.copy(downloadStatus = "FAILED", errorMessage = "Server returned ${restartResp.code}"))
                    return@withContext
                }
                streamResponseBody(episodeId, restartResp, tempFile, finalFile, 0L, entry)
                return@withContext
            } else {
                downloadDao.upsertDownload(entry.copy(downloadStatus = "FAILED", errorMessage = "Server error ${response.code}"))
                return@withContext
            }
        }

        streamResponseBody(episodeId, response, tempFile, finalFile, downloadedBytes, entry)
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

            // Completion: rename temp file to final file
            if (tempFile.exists()) {
                if (finalFile.exists()) finalFile.delete()
                val renamed = tempFile.renameTo(finalFile)
                if (renamed) {
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
                val sanitizedAnime = current.animeId.replace(Regex("[^a-zA-Z0-9_]"), "_")
                val tempFile = File(mediaDir, "${sanitizedAnime}_s${current.seasonNumber}_e${current.episodeNumber}.tmp")
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
        map[episodeId] = LiveProgress(
            episodeId = episodeId,
            downloadedBytes = downloaded,
            totalBytes = total,
            speedBytesPerSec = speed
        )
        _liveProgressMap.value = map
    }

    private fun removeLiveProgress(episodeId: String) {
        val map = _liveProgressMap.value.toMutableMap()
        map.remove(episodeId)
        _liveProgressMap.value = map
    }

    companion object {
        private const val TAG = "DownloadManager"

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
