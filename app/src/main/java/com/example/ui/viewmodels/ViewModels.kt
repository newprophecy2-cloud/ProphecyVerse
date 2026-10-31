package com.example.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AnimeEntry
import com.example.data.local.AppDatabase
import com.example.data.local.EpisodeEntry
import com.example.data.local.PlaybackHistory
import com.example.data.local.SeasonEntry
import com.example.data.local.WatchlistEntry
import com.example.data.remote.DirectPlayData
import com.example.data.remote.ProphecySourceDto
import com.example.data.repository.AnimeRepository
import com.example.player.PlayerManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AppViewModelFactory(private val application: Application) {
    val database by lazy { AppDatabase.getInstance(application) }
    val repository by lazy { AnimeRepository(database) }
}

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AnimeRepository(AppDatabase.getInstance(application))

    val trending: StateFlow<List<AnimeEntry>> = repository.observeTrending()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val seasonal: StateFlow<List<AnimeEntry>> = repository.observeSeasonal()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val popular: StateFlow<List<AnimeEntry>> = repository.observePopular()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val kdramas: StateFlow<List<AnimeEntry>> = repository.observeKDramas()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val movies: StateFlow<List<AnimeEntry>> = repository.observeMovies()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val webSeries: StateFlow<List<AnimeEntry>> = repository.observeWebSeries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val continueWatching: StateFlow<List<PlaybackHistory>> = repository.observeContinueWatching()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedCategory = MutableStateFlow("ALL")
    val selectedCategory: StateFlow<String> = _selectedCategory.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    init {
        refreshHome()
    }

    fun selectCategory(cat: String) {
        _selectedCategory.value = cat
    }

    fun refreshHome() {
        viewModelScope.launch {
            _isRefreshing.value = true
            _errorMessage.value = null
            val result = repository.refreshHomeDiscovery()
            if (result.isFailure) {
                _errorMessage.value = "Unable to fetch latest feeds. Showing cached catalog."
            }
            _isRefreshing.value = false
        }
    }
}

class SearchViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AnimeRepository(AppDatabase.getInstance(application))

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedGenre = MutableStateFlow<String?>(null)
    val selectedGenre: StateFlow<String?> = _selectedGenre.asStateFlow()

    private val _selectedFormat = MutableStateFlow<String?>(null)
    val selectedFormat: StateFlow<String?> = _selectedFormat.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var searchDebounceJob: Job? = null

    // Database-First: observe Room query results
    val searchResults: StateFlow<List<AnimeEntry>> = _searchQuery.flatMapLatest { q ->
        if (q.isBlank()) {
            repository.observePopular()
        } else {
            repository.observeSearchResults(q)
        }
    }.combine(_selectedGenre) { list, genre ->
        if (genre == null) list else list.filter { it.genres.contains(genre, ignoreCase = true) }
    }.combine(_selectedFormat) { list, format ->
        if (format == null) list else list.filter { it.format.equals(format, ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun onQueryChange(newQuery: String) {
        _searchQuery.value = newQuery
        searchDebounceJob?.cancel()

        if (newQuery.isBlank()) {
            _isSearching.value = false
            return
        }

        searchDebounceJob = viewModelScope.launch {
            delay(400) // 400ms debounce
            _isSearching.value = true
            _error.value = null
            val res = repository.searchAnime(newQuery)
            if (res.isFailure) {
                _error.value = "Network search failed. Showing local results."
            }
            _isSearching.value = false
        }
    }

    fun selectGenre(genre: String?) {
        _selectedGenre.value = if (_selectedGenre.value == genre) null else genre
    }

    fun selectFormat(format: String?) {
        _selectedFormat.value = if (_selectedFormat.value == format) null else format
    }
}

class DetailViewModel(
    application: Application,
    val animeId: String
) : AndroidViewModel(application) {
    private val repository = AnimeRepository(AppDatabase.getInstance(application))

    val animeEntry: StateFlow<AnimeEntry?> = repository.observeAnimeById(animeId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val seasons: StateFlow<List<SeasonEntry>> = repository.observeSeasons(animeId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedSeason = MutableStateFlow(1)
    val selectedSeason: StateFlow<Int> = _selectedSeason.asStateFlow()

    val episodes: StateFlow<List<EpisodeEntry>> = _selectedSeason.flatMapLatest { s ->
        repository.observeEpisodes(animeId, s)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val watchlistEntry: StateFlow<WatchlistEntry?> = repository.observeWatchlistEntry(animeId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val lastPlayed: StateFlow<PlaybackHistory?> = repository.observeLastPlayedForAnime(animeId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // In-memory episode source selection
    private val _episodeSources = MutableStateFlow<List<ProphecySourceDto>>(emptyList())
    val episodeSources: StateFlow<List<ProphecySourceDto>> = _episodeSources.asStateFlow()

    private val _selectedEpisodeForSources = MutableStateFlow<EpisodeEntry?>(null)
    val selectedEpisodeForSources: StateFlow<EpisodeEntry?> = _selectedEpisodeForSources.asStateFlow()

    private val _isLoadingSources = MutableStateFlow(false)
    val isLoadingSources: StateFlow<Boolean> = _isLoadingSources.asStateFlow()

    private val _sourceError = MutableStateFlow<String?>(null)
    val sourceError: StateFlow<String?> = _sourceError.asStateFlow()

    init {
        viewModelScope.launch {
            seasons.collect { sList ->
                if (sList.isNotEmpty() && sList.none { it.seasonNumber == _selectedSeason.value }) {
                    _selectedSeason.value = sList.first().seasonNumber
                }
            }
        }
        refreshDetails()
    }

    fun refreshDetails() {
        viewModelScope.launch {
            _isLoading.value = true
            com.example.data.firebase.AppAnalytics.getInstance(getApplication()).logEvent(
                "anime_view",
                mapOf("anime_id" to animeId)
            )
            repository.refreshAnimeDetails(animeId)
            _isLoading.value = false
        }
    }

    fun selectSeason(seasonNumber: Int) {
        _selectedSeason.value = seasonNumber
    }

    fun openSourceChooser(episode: EpisodeEntry) {
        _selectedEpisodeForSources.value = episode
        _sourceError.value = null
        val current = animeEntry.value
        val backendId = current?.backendAnimeId ?: if (animeId.startsWith("anilist_")) null else animeId

        if (backendId == null) {
            _sourceError.value = "This title is metadata-only and not currently playable in catalog."
            return
        }

        viewModelScope.launch {
            _isLoadingSources.value = true
            val res = repository.fetchEpisodeSources(backendId, episode.id)
            if (res.isSuccess) {
                _episodeSources.value = res.getOrNull() ?: emptyList()
                if (_episodeSources.value.isEmpty()) {
                    _sourceError.value = "No playable sources returned for Episode ${episode.episodeNumber}."
                }
            } else {
                _sourceError.value = "Failed to load sources: ${res.exceptionOrNull()?.message}"
            }
            _isLoadingSources.value = false
        }
    }

    fun closeSourceChooser() {
        _selectedEpisodeForSources.value = null
        _episodeSources.value = emptyList()
        _sourceError.value = null
    }

    fun toggleWatchlist(status: String = "WATCHING") {
        viewModelScope.launch {
            repository.toggleWatchlist(animeId, status)
            val entry = watchlistEntry.value
            com.example.data.firebase.CloudHistoryRepository.getInstance(getApplication())
                .syncWatchlistStatus(
                    animeId = animeId,
                    status = status,
                    isFavorite = entry?.isFavorite ?: false
                )
        }
    }

    fun toggleFavorite() {
        viewModelScope.launch {
            repository.toggleFavorite(animeId)
            val entry = watchlistEntry.value
            com.example.data.firebase.CloudHistoryRepository.getInstance(getApplication())
                .syncWatchlistStatus(
                    animeId = animeId,
                    status = entry?.status ?: "WATCHING",
                    isFavorite = !(entry?.isFavorite ?: false)
                )
        }
    }
}

class PlayerViewModel(
    application: Application,
    val animeId: String,
    val initialEpisodeId: String
) : AndroidViewModel(application) {
    private val repository = AnimeRepository(AppDatabase.getInstance(application))
    val playerManager = PlayerManager(application, viewModelScope)

    val animeEntry: StateFlow<AnimeEntry?> = repository.observeAnimeById(animeId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val allEpisodes: StateFlow<List<EpisodeEntry>> = repository.observeAllEpisodes(animeId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val seasons: StateFlow<List<SeasonEntry>> = repository.observeSeasons(animeId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedSeason = MutableStateFlow(1)
    val selectedSeason: StateFlow<Int> = _selectedSeason.asStateFlow()

    val seasonEpisodes: StateFlow<List<EpisodeEntry>> = _selectedSeason.flatMapLatest { s ->
        repository.observeEpisodes(animeId, s)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val suggestions: StateFlow<List<AnimeEntry>> = repository.observePopular()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _currentEpisode = MutableStateFlow<EpisodeEntry?>(null)
    val currentEpisode: StateFlow<EpisodeEntry?> = _currentEpisode.asStateFlow()

    val nextEpisode: StateFlow<EpisodeEntry?> = combine(_currentEpisode, allEpisodes) { current, list ->
        if (current == null || list.isEmpty()) null
        else {
            val currentIndex = list.indexOfFirst { it.id == current.id }
            if (currentIndex in 0 until list.size - 1) list[currentIndex + 1] else null
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _isResolving = MutableStateFlow(true)
    val isResolving: StateFlow<Boolean> = _isResolving.asStateFlow()

    private val _resolutionError = MutableStateFlow<String?>(null)
    val resolutionError: StateFlow<String?> = _resolutionError.asStateFlow()

    private val _availableSources = MutableStateFlow<List<ProphecySourceDto>>(emptyList())
    val availableSources: StateFlow<List<ProphecySourceDto>> = _availableSources.asStateFlow()

    private val _activeSource = MutableStateFlow<ProphecySourceDto?>(null)
    val activeSource: StateFlow<ProphecySourceDto?> = _activeSource.asStateFlow()

    // In-memory active direct playback data
    private var activeDirectData: DirectPlayData? = null
    private var activeSourceId: String? = null
    private var allCandidates: List<ProphecySourceDto> = emptyList()
    private var currentCandidateIndex: Int = 0

    init {
        viewModelScope.launch {
            // Find episode or wait for database
            repository.observeAllEpisodes(animeId).collect { epList ->
                if (_currentEpisode.value == null && epList.isNotEmpty()) {
                    val target = epList.firstOrNull { it.id == initialEpisodeId } ?: epList.first()
                    _currentEpisode.value = target
                    _selectedSeason.value = target.seasonNumber
                    startPlaybackForEpisode(target)
                }
            }
        }

        var lastWatchTimeTrackMs = System.currentTimeMillis()
        var lastCloudSyncMs = 0L

        // Auto-save playback position periodically and sync with Firebase Cloud
        playerManager.onPositionUpdate = { posMs, durMs ->
            val ep = _currentEpisode.value
            if (ep != null && durMs > 0) {
                val now = System.currentTimeMillis()
                val deltaSec = (now - lastWatchTimeTrackMs) / 1000
                if (deltaSec >= 10 && playerManager.isPlaying.value) {
                    com.example.data.firebase.AppAnalytics.getInstance(application).recordWatchTime(
                        deltaSec, animeId, ep.id
                    )
                    lastWatchTimeTrackMs = now
                }

                viewModelScope.launch {
                    val isCompleted = durMs > 0 && posMs >= (durMs * 0.9)
                    val anime = animeEntry.value
                    repository.updatePlaybackProgress(
                        animeId = animeId,
                        episodeId = ep.id,
                        seasonNumber = ep.seasonNumber,
                        episodeNumber = ep.episodeNumber,
                        episodeTitle = ep.title,
                        positionMs = posMs,
                        durationMs = durMs,
                        animeTitle = anime?.preferredTitle ?: anime?.titleEnglish,
                        posterUrl = anime?.posterUrl,
                        mediaType = anime?.format ?: "ANIME"
                    )

                    // Non-blocking cloud history synchronization
                    if (now - lastCloudSyncMs > 10000L || isCompleted) {
                        lastCloudSyncMs = now
                        com.example.data.firebase.CloudHistoryRepository.getInstance(application)
                            .syncPlaybackProgress(
                                animeId = animeId,
                                episodeId = ep.id,
                                seasonNumber = ep.seasonNumber,
                                episodeNumber = ep.episodeNumber,
                                episodeTitle = ep.title,
                                positionMs = posMs,
                                durationMs = durMs,
                                completed = isCompleted
                            )
                    }
                }
            }
        }
    }

    fun selectSeason(sNum: Int) {
        _selectedSeason.value = sNum
    }

    fun startPlaybackForEpisode(episode: EpisodeEntry, preferredSourceId: String? = null) {
        _currentEpisode.value = episode
        _selectedSeason.value = episode.seasonNumber
        _isResolving.value = true
        _resolutionError.value = null

        com.example.data.firebase.AppAnalytics.getInstance(getApplication()).logEvent(
            "episode_play",
            mapOf(
                "anime_id" to animeId,
                "episode_id" to episode.id,
                "episode_number" to episode.episodeNumber
            )
        )

        viewModelScope.launch {
            val db = AppDatabase.getInstance(getApplication())

            // 1. Check if episode is downloaded for offline playback
            val download = db.downloadDao().getDownload(episode.id)
            if (download != null && download.downloadStatus == "COMPLETED" && download.localFilePath != null) {
                val file = java.io.File(download.localFilePath)
                if (file.exists() && file.length() > 0) {
                    _isResolving.value = false
                    _activeSource.value = null
                    val lastSavedPos = try {
                        db.playbackHistoryDao().getHistoryForEpisode(animeId, episode.id)?.positionMs ?: 0L
                    } catch (_: Exception) { 0L }

                    val directData = DirectPlayData(
                        mode = "offline",
                        sourceId = "local_${episode.id}",
                        playbackUrl = "file://${file.absolutePath}",
                        urlType = "direct",
                        compatibility = "verified"
                    )
                    playerManager.initializePlayer(
                        directData = directData,
                        startPositionMs = lastSavedPos,
                        onPlaybackFailed = null,
                        onRetryNeeded = {}
                    )
                    return@launch
                }
            }

            // 2. Online playback flow using ProphecyVerse catalog
            val anime = db.animeDao().getAnimeById(animeId) ?: animeEntry.value
            val backendId = anime?.backendAnimeId ?: if (animeId.startsWith("anilist_")) null else animeId

            if (backendId == null) {
                _resolutionError.value = "Cannot play: Anime has no playable backend catalog ID."
                _isResolving.value = false
                return@launch
            }

            // Route D: Get sources for the selected episode
            val sourcesRes = repository.fetchEpisodeSources(backendId, episode.id)
            if (sourcesRes.isFailure || sourcesRes.getOrNull().isNullOrEmpty()) {
                _resolutionError.value = "No playable sources found for this episode."
                _isResolving.value = false
                return@launch
            }

            val sources = sourcesRes.getOrNull()!!
            val candidates = if (preferredSourceId != null) {
                listOfNotNull(sources.firstOrNull { it.id == preferredSourceId }) + sources.filter { it.id != preferredSourceId }
            } else {
                sources
            }

            resolveWithFallback(backendId, episode, candidates, 0)
        }
    }

    private suspend fun resolveWithFallback(
        backendId: String,
        episode: EpisodeEntry,
        candidates: List<ProphecySourceDto>,
        startIndex: Int = 0
    ) {
        allCandidates = candidates
        _availableSources.value = candidates
        var lastError: String? = null

        for (i in startIndex until candidates.size) {
            val source = candidates[i]
            currentCandidateIndex = i
            activeSourceId = source.id
            _activeSource.value = source

            // Route E: The ONE AND ONLY playback route: POST /api/v1/play/direct
            val playRes = repository.resolveDirectPlayback(source.id)
            if (playRes.isSuccess && playRes.getOrNull() != null) {
                val directData = playRes.getOrNull()!!
                activeDirectData = directData
                _isResolving.value = false

                // Fetch last saved position from database
                val lastSavedPos = try {
                    AppDatabase.getInstance(getApplication()).playbackHistoryDao()
                        .getHistoryForEpisode(animeId, episode.id)?.positionMs ?: 0L
                } catch (e: Exception) { 0L }

                // Initialize Media3 ExoPlayer with direct URL and headers
                playerManager.initializePlayer(
                    directData = directData,
                    startPositionMs = lastSavedPos,
                    onPlaybackFailed = {
                        // Automatically try next source candidate if playback fails
                        if (currentCandidateIndex + 1 < allCandidates.size) {
                            viewModelScope.launch {
                                _isResolving.value = true
                                resolveWithFallback(backendId, episode, allCandidates, currentCandidateIndex + 1)
                            }
                        }
                    },
                    onRetryNeeded = {
                        viewModelScope.launch {
                            val refreshedSources = repository.fetchEpisodeSources(backendId, episode.id).getOrNull() ?: emptyList()
                            val newCandidates = if (refreshedSources.isNotEmpty()) refreshedSources else listOf(source)
                            resolveWithFallback(backendId, episode, newCandidates, 0)
                        }
                    }
                )
                return
            } else {
                lastError = playRes.exceptionOrNull()?.message
            }
        }

        _resolutionError.value = lastError ?: "Direct stream resolution rejected or unavailable."
        _isResolving.value = false
    }

    fun switchSource(sourceId: String) {
        val ep = _currentEpisode.value ?: return
        startPlaybackForEpisode(ep, preferredSourceId = sourceId)
    }

    fun playNextEpisode() {
        val next = nextEpisode.value ?: return
        startPlaybackForEpisode(next)
    }

    override fun onCleared() {
        super.onCleared()
        playerManager.release()
    }
}

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AnimeRepository(AppDatabase.getInstance(application))

    val watchlist: StateFlow<List<WatchlistEntry>> = repository.observeWatchlist()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val continueWatching: StateFlow<List<PlaybackHistory>> = repository.observeContinueWatching()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val history: StateFlow<List<PlaybackHistory>> = repository.observeHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun clearHistory() {
        viewModelScope.launch {
            repository.clearHistory()
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            repository.clearSearchAndMetadataCache()
        }
    }
}

class DownloadsViewModel(application: Application) : AndroidViewModel(application) {
    private val downloadManager = com.example.data.download.DownloadManager.getInstance(application)

    val activeDownloads = downloadManager.observeActiveDownloads()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val completedDownloads = downloadManager.observeCompletedDownloads()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val storageStats = downloadManager.storageStats
    val liveProgressMap = downloadManager.liveProgressMap

    fun pauseDownload(episodeId: String) = downloadManager.pauseDownload(episodeId)
    fun cancelDownload(episodeId: String) = downloadManager.cancelDownload(episodeId)
    fun deleteDownload(episodeId: String) = downloadManager.deleteDownloadedFile(episodeId)
    fun resumeDownload(episodeId: String) {
        val entry = completedDownloads.value.firstOrNull { it.id == episodeId }
            ?: activeDownloads.value.firstOrNull { it.id == episodeId } ?: return
        downloadManager.startOrResumeDownload(
            animeId = entry.animeId,
            episodeId = entry.id,
            seasonNumber = entry.seasonNumber,
            episodeNumber = entry.episodeNumber,
            episodeTitle = entry.episodeTitle,
            animeTitle = entry.animeTitle,
            posterUrl = entry.posterUrl,
            mediaType = entry.mediaType
        )
    }
}

class ProfileViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AnimeRepository(AppDatabase.getInstance(application))
    private val downloadManager = com.example.data.download.DownloadManager.getInstance(application)

    val profile = repository.observeProfile()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val watchlist = repository.observeWatchlist()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val history = repository.observeHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val continueWatching = repository.observeContinueWatching()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val storageStats = downloadManager.storageStats

    fun updateProfile(name: String, avatarId: String, bio: String) {
        viewModelScope.launch {
            repository.updateProfile(name, avatarId, bio)
        }
    }
}
