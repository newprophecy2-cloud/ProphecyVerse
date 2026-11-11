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
import com.example.data.repository.EpisodeCatalogRefreshStatus
import com.example.data.repository.EpisodeCatalogState
import com.example.data.repository.EpisodeRecordPagination
import com.example.data.repository.EpisodeSourceResponseState
import com.example.player.PlayerManager
import com.example.player.PreResolveQueueManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.example.data.download.DownloadManager
import com.example.data.local.DownloadEntry
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

class AppViewModelFactory(private val application: Application) {
    val database by lazy { AppDatabase.getInstance(application) }
    val repository by lazy { AnimeRepository(database, applicationContext = application) }
}

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AnimeRepository(AppDatabase.getInstance(application), applicationContext = application)

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

    private val connectivityManager = application.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            viewModelScope.launch {
                // When internet connectivity returns, automatically refresh feeds in background
                repository.processPendingSharedCatalogWrites()
                repository.refreshHomeDiscovery(force = false)
            }
        }
    }

    init {
        try {
            connectivityManager?.registerDefaultNetworkCallback(networkCallback)
        } catch (_: Exception) {}
        // Resume only the durable pending queue after process restart; no catalog scan is performed.
        viewModelScope.launch { repository.processPendingSharedCatalogWrites() }
        refreshHome()
    }

    fun selectCategory(cat: String) {
        _selectedCategory.value = cat
    }

    fun checkAndRefreshOnResume() {
        viewModelScope.launch {
            // Silently check for fresh content when returning to Home screen
            repository.refreshHomeDiscovery(force = false)
        }
    }

    fun refreshHome() {
        viewModelScope.launch {
            _isRefreshing.value = true
            _errorMessage.value = null
            val result = repository.refreshHomeDiscovery(force = true)
            if (result.isFailure) {
                _errorMessage.value = "Unable to fetch latest feeds. Showing cached catalog."
            }
            _isRefreshing.value = false
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {}
    }
}

class SearchViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AnimeRepository(AppDatabase.getInstance(application), applicationContext = application)

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

    private var searchJob: Job? = null
    private var searchPrefetchJob: Job? = null
    private var searchRequestSequence = 0L

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
        val requestId = ++searchRequestSequence
        searchJob?.cancel()
        searchPrefetchJob?.cancel()

        if (newQuery.isBlank()) {
            _isSearching.value = false
            return
        }

        searchJob = viewModelScope.launch {
            _isSearching.value = true
            _error.value = null
            val res = repository.searchAnime(newQuery)
            if (requestId == searchRequestSequence) {
                if (res.isFailure) {
                    _error.value = "Network search failed. Showing local results."
                }
                _isSearching.value = false
                if (res.isSuccess) {
                    viewModelScope.launch { repository.syncSearchResults(newQuery) }
                    searchPrefetchJob = viewModelScope.launch { repository.prefetchSearchResults(newQuery) }
                }
            }
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
    private val repository = AnimeRepository(AppDatabase.getInstance(application), applicationContext = application)

    val animeEntry: StateFlow<AnimeEntry?> = repository.observeAnimeById(animeId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val seasons: StateFlow<List<SeasonEntry>> = repository.observeSeasons(animeId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val firstEpisode: StateFlow<EpisodeEntry?> = repository.observeFirstEpisode(animeId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _selectedSeason = MutableStateFlow(1)
    val selectedSeason: StateFlow<Int> = _selectedSeason.asStateFlow()

    private val _episodeWindowStart = MutableStateFlow(0)
    val episodeWindowStart: StateFlow<Int> = _episodeWindowStart.asStateFlow()
    private val allCatalogEpisodes = repository.observeAllEpisodes(animeId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val _catalogRefreshStatus = MutableStateFlow(
        EpisodeCatalogRefreshStatus(EpisodeCatalogState.UNKNOWN, false, false)
    )
    val catalogRefreshStatus: StateFlow<EpisodeCatalogRefreshStatus> = _catalogRefreshStatus.asStateFlow()
    private val episodePage = combine(
        _selectedSeason, _episodeWindowStart, allCatalogEpisodes, _catalogRefreshStatus
    ) { season, offset, records, status ->
        val ordered = episodesForDisplay(records, season, status.currentRefreshEpisodeIds)
        EpisodeRecordPagination.page(
            orderedRecords = ordered,
            offset = offset,
            pageSize = 30,
            catalogComplete = status.state == EpisodeCatalogState.COMPLETE ||
                status.state == EpisodeCatalogState.CONFIRMED_EMPTY
        )
    }
    val episodes: StateFlow<List<EpisodeEntry>> = episodePage.map { it.items }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val canLoadNextEpisodePage: StateFlow<Boolean> = episodePage.map { it.hasNext }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val canLoadPreviousEpisodePage: StateFlow<Boolean> = episodePage.map { it.hasPrevious }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val selectedSeasonEpisodeCount: StateFlow<Int> = episodePage.map { it.totalKnownRecords }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val watchlistEntry: StateFlow<WatchlistEntry?> = repository.observeWatchlistEntry(animeId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val lastPlayed: StateFlow<PlaybackHistory?> = repository.observeLastPlayedForAnime(animeId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _syncError = MutableStateFlow<String?>(null)
    val syncError: StateFlow<String?> = _syncError.asStateFlow()
    private var refreshJob: Job? = null
    private var sourceDiscoveryJob: Job? = null
    private var sourceRequestGeneration: Long = 0L

    // In-memory episode source selection
    private val _episodeSources = MutableStateFlow<List<ProphecySourceDto>>(emptyList())
    val episodeSources: StateFlow<List<ProphecySourceDto>> = _episodeSources.asStateFlow()

    private val _selectedEpisodeForSources = MutableStateFlow<EpisodeEntry?>(null)
    val selectedEpisodeForSources: StateFlow<EpisodeEntry?> = _selectedEpisodeForSources.asStateFlow()

    private val _isLoadingSources = MutableStateFlow(false)
    val isLoadingSources: StateFlow<Boolean> = _isLoadingSources.asStateFlow()

    private val _sourceError = MutableStateFlow<String?>(null)
    val sourceError: StateFlow<String?> = _sourceError.asStateFlow()

    enum class EpisodeSourceState { NOT_LOADED, LOADING, AVAILABLE, PARTIAL, EMPTY, FAILED, TIMEOUT, UNKNOWN, STALE }
    private val _episodeSourceStates = MutableStateFlow<Map<String, EpisodeSourceState>>(emptyMap())
    val episodeSourceStates: StateFlow<Map<String, EpisodeSourceState>> = _episodeSourceStates.asStateFlow()
    private val automaticSourceSemaphore = Semaphore(2)
    private val automaticSourceJobs = ConcurrentHashMap<String, Job>()

    init {
        viewModelScope.launch {
            combine(seasons, firstEpisode) { sList, firstEp -> sList to firstEp }
                .collect { (sList, firstEp) ->
                    if (sList.isNotEmpty()) {
                        val firstSeasonWithEpisode = firstEp?.seasonNumber
                        if (sList.none { it.seasonNumber == _selectedSeason.value }) {
                            _selectedSeason.value = firstSeasonWithEpisode?.takeIf { candidate ->
                                sList.any { it.seasonNumber == candidate }
                            } ?: sList.first().seasonNumber
                        } else if (_selectedSeason.value == 1 && firstSeasonWithEpisode != null &&
                            firstSeasonWithEpisode != 1 && sList.any { it.seasonNumber == firstSeasonWithEpisode }) {
                            _selectedSeason.value = firstSeasonWithEpisode
                        }
                    }
                }
        }
        viewModelScope.launch {
            combine(lastPlayed, allCatalogEpisodes) { history, records -> history to records }
                .collect { (history, records) ->
                if (history != null) {
                    _selectedSeason.value = history.seasonNumber
                    val ordered = episodesForDisplay(
                        records, history.seasonNumber, _catalogRefreshStatus.value.currentRefreshEpisodeIds
                    )
                    val index = ordered.indexOfFirst { it.id == history.episodeId }
                        .takeIf { it >= 0 }
                        ?: ordered.indexOfFirst { it.episodeNumber == history.episodeNumber }
                    _episodeWindowStart.value = if (index >= 0) (index / 30) * 30 else 0
                }
            }
        }
        viewModelScope.launch {
            combine(animeEntry, episodes, lastPlayed) { anime, window, history -> Triple(anime, window, history) }
                .collect { (anime, window, history) -> queueAutomaticSourceDiscovery(window, anime, history) }
        }
        refreshDetails()
    }

    fun refreshDetails() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            _syncError.value = null
            com.example.data.repository.withLoadingFlag({ _isLoading.value = it }) {
                try {
                    try {
                        com.example.data.firebase.AppAnalytics.getInstance(getApplication()).logEvent(
                            "anime_view",
                            mapOf("anime_id" to animeId)
                        )
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Analytics is optional; it must not prevent catalog refresh.
                    }
                    val result = repository.refreshAnimeDetails(animeId)
                    val outcome = result.getOrNull()
                    if (outcome != null) {
                        _catalogRefreshStatus.value = outcome
                        if (!outcome.errorMessage.isNullOrBlank()) {
                            _syncError.value = outcome.errorMessage
                        }
                        viewModelScope.launch { repository.refreshOptionalAniListMetadata(animeId) }
                    } else {
                        _catalogRefreshStatus.value = EpisodeCatalogRefreshStatus(
                            EpisodeCatalogState.FAILED, false, false,
                            "Catalog refresh failed. Cached episodes remain available; tap retry to try again."
                        )
                        _syncError.value = "Catalog refresh failed. Cached episodes remain available; tap retry to try again."
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    _catalogRefreshStatus.value = EpisodeCatalogRefreshStatus(
                        EpisodeCatalogState.FAILED, false, false,
                        "Catalog refresh failed. Cached episodes remain available; tap retry to try again."
                    )
                    _syncError.value = "Catalog refresh failed. Cached episodes remain available; tap retry to try again."
                }
            }
        }
    }

    fun selectSeason(seasonNumber: Int) {
        _selectedSeason.value = seasonNumber
        _episodeWindowStart.value = 0
        viewModelScope.launch { repository.syncOpenedSeason(animeId, seasonNumber) }
    }

    fun loadNextEpisodeWindow() {
        if (canLoadNextEpisodePage.value) _episodeWindowStart.value += 30
    }

    fun loadPreviousEpisodeWindow() {
        if (canLoadPreviousEpisodePage.value) _episodeWindowStart.value = (_episodeWindowStart.value - 30).coerceAtLeast(0)
    }

    fun focusEpisodeWindow(episode: EpisodeEntry) {
        _selectedSeason.value = episode.seasonNumber
        val ordered = episodesForDisplay(
            allCatalogEpisodes.value, episode.seasonNumber, _catalogRefreshStatus.value.currentRefreshEpisodeIds
        )
        val index = ordered.indexOfFirst { it.id == episode.id }
        _episodeWindowStart.value = if (index >= 0) (index / 30) * 30 else 0
    }

    private fun episodesForDisplay(
        records: List<EpisodeEntry>,
        seasonNumber: Int,
        currentRefreshIds: Set<String>
    ): List<EpisodeEntry> = records.asSequence()
        .filter { it.seasonNumber == seasonNumber }
        .sortedWith(compareBy<EpisodeEntry> { it.episodeNumber }.thenBy { it.id })
        .groupBy { it.episodeNumber }
        .toSortedMap()
        .values
        .flatMap { sameNumber ->
            val refreshed = sameNumber.filter { it.id in currentRefreshIds }
            if (refreshed.size == 1) listOf(refreshed.single()) else sameNumber
        }

    private fun queueAutomaticSourceDiscovery(
        window: List<EpisodeEntry>,
        current: AnimeEntry?,
        history: PlaybackHistory?
    ) {
        current ?: return
        val backendId = current.backendAnimeId ?: if (animeId.startsWith("anilist_")) null else animeId
        if (backendId == null) {
            automaticSourceJobs.values.forEach { it.cancel() }
            automaticSourceJobs.clear()
            _episodeSourceStates.value = emptyMap()
            return
        }
        val visibleIds = window.map { it.id }.toSet()
        val selectedId = _selectedEpisodeForSources.value?.id
        automaticSourceJobs.toMap().forEach { (episodeId, job) ->
            if (episodeId !in visibleIds && episodeId != selectedId) {
                job.cancel()
                _episodeSourceStates.value = _episodeSourceStates.value - episodeId
            }
        }
        _episodeSourceStates.value = _episodeSourceStates.value.filterKeys { it in visibleIds || it == selectedId }
        if (window.isEmpty()) return
        val priorityEpisodeId = history?.episodeId
        val ordered = window.sortedWith(compareBy<EpisodeEntry> { if (it.id == priorityEpisodeId) 0 else 1 }
            .thenBy { it.episodeNumber })
        ordered.forEach { episode ->
            val existingState = _episodeSourceStates.value[episode.id]
            if (existingState in setOf(EpisodeSourceState.LOADING, EpisodeSourceState.AVAILABLE,
                    EpisodeSourceState.EMPTY, EpisodeSourceState.PARTIAL)) return@forEach
            if (automaticSourceJobs[episode.id]?.isActive == true) return@forEach
            _episodeSourceStates.value = _episodeSourceStates.value + (episode.id to EpisodeSourceState.LOADING)
            automaticSourceJobs[episode.id] = viewModelScope.launch {
                try {
                    automaticSourceSemaphore.withPermit {
                        val result = repository.fetchEpisodeSourceSnapshot(
                            backendId,
                            episode.id,
                            seasonNumber = episode.seasonNumber,
                            episodeNumber = episode.episodeNumber
                        )
                        val state = result.getOrNull()?.state?.let {
                            when (it) {
                                EpisodeSourceResponseState.AVAILABLE -> EpisodeSourceState.AVAILABLE
                                EpisodeSourceResponseState.EMPTY -> EpisodeSourceState.EMPTY
                                EpisodeSourceResponseState.PARTIAL -> EpisodeSourceState.PARTIAL
                                EpisodeSourceResponseState.FAILED -> EpisodeSourceState.FAILED
                                EpisodeSourceResponseState.TIMEOUT -> EpisodeSourceState.TIMEOUT
                                EpisodeSourceResponseState.UNKNOWN -> EpisodeSourceState.UNKNOWN
                                EpisodeSourceResponseState.STALE -> EpisodeSourceState.STALE
                            }
                        } ?: run {
                            val error = result.exceptionOrNull()
                            if (generateSequence(error) { it.cause }.any { it is java.net.SocketTimeoutException }) {
                                EpisodeSourceState.TIMEOUT
                            } else {
                                EpisodeSourceState.FAILED
                            }
                        }
                        _episodeSourceStates.value = _episodeSourceStates.value + (episode.id to state)
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    _episodeSourceStates.value = _episodeSourceStates.value - episode.id
                    throw cancelled
                } catch (_: Exception) {
                    _episodeSourceStates.value = _episodeSourceStates.value + (episode.id to EpisodeSourceState.FAILED)
                } finally {
                    automaticSourceJobs.remove(episode.id)
                }
            }
        }
    }

    fun openSourceChooser(episode: EpisodeEntry, forceRefresh: Boolean = false) {
        if (!forceRefresh && _selectedEpisodeForSources.value?.id == episode.id &&
            (_episodeSources.value.isNotEmpty() || sourceDiscoveryJob?.isActive == true)) return
        val sameEpisode = _selectedEpisodeForSources.value?.id == episode.id
        val requestGeneration = ++sourceRequestGeneration
        sourceDiscoveryJob?.cancel()
        _selectedEpisodeForSources.value = episode
        // Clear only when changing Episode; retry retains this Episode's candidates during refresh.
        if (!sameEpisode) _episodeSources.value = emptyList()
        _isLoadingSources.value = false
        _sourceError.value = null
        _episodeSourceStates.value = _episodeSourceStates.value + (episode.id to EpisodeSourceState.LOADING)
        automaticSourceJobs.toMap().forEach { (episodeId, job) ->
            if (episodeId != episode.id) {
                job.cancel()
                _episodeSourceStates.value = _episodeSourceStates.value - episodeId
            }
        }
        val current = animeEntry.value
        val backendId = current?.backendAnimeId ?: if (animeId.startsWith("anilist_")) null else animeId

        if (backendId == null) {
            _episodeSourceStates.value = _episodeSourceStates.value + (episode.id to EpisodeSourceState.FAILED)
            _sourceError.value = "This title is metadata-only and not currently playable in catalog."
            return
        }

        // Separate cancellable catalog sync; never make playback source resolution wait on Firestore.
        viewModelScope.launch { repository.syncOpenedEpisode(animeId, episode) }
        sourceDiscoveryJob = viewModelScope.launch {
            _isLoadingSources.value = true
            try {
                val cached = repository.getCachedEpisodeSourceSnapshot(
                    backendId,
                    episode.id,
                    seasonNumber = episode.seasonNumber,
                    episodeNumber = episode.episodeNumber
                )
                if (requestGeneration != sourceRequestGeneration) return@launch
                if (cached != null) {
                    _episodeSources.value = cached.sources
                    val cachedState = when (cached.state) {
                        EpisodeSourceResponseState.AVAILABLE -> EpisodeSourceState.AVAILABLE
                        EpisodeSourceResponseState.PARTIAL -> EpisodeSourceState.PARTIAL
                        EpisodeSourceResponseState.EMPTY -> EpisodeSourceState.EMPTY
                        EpisodeSourceResponseState.FAILED -> EpisodeSourceState.FAILED
                        EpisodeSourceResponseState.TIMEOUT -> EpisodeSourceState.TIMEOUT
                        EpisodeSourceResponseState.UNKNOWN -> EpisodeSourceState.UNKNOWN
                        EpisodeSourceResponseState.STALE -> EpisodeSourceState.STALE
                    }
                    _episodeSourceStates.value = _episodeSourceStates.value + (episode.id to cachedState)
                    if (cached.isStale) _sourceError.value = "Showing cached source candidates; they are stale and not verified current. Refreshing…"
                }
                val res = automaticSourceSemaphore.withPermit {
                    repository.fetchEpisodeSourceSnapshot(
                        backendId,
                        episode.id,
                        forceRefresh = forceRefresh,
                        seasonNumber = episode.seasonNumber,
                        episodeNumber = episode.episodeNumber
                    )
                }
                if (requestGeneration != sourceRequestGeneration) return@launch
                val snapshot = res.getOrNull()
                if (snapshot != null) {
                    _episodeSources.value = snapshot.sources
                    val state = when (snapshot.state) {
                        EpisodeSourceResponseState.AVAILABLE -> EpisodeSourceState.AVAILABLE
                        EpisodeSourceResponseState.EMPTY -> EpisodeSourceState.EMPTY
                        EpisodeSourceResponseState.PARTIAL -> EpisodeSourceState.PARTIAL
                        EpisodeSourceResponseState.FAILED -> EpisodeSourceState.FAILED
                        EpisodeSourceResponseState.TIMEOUT -> EpisodeSourceState.TIMEOUT
                        EpisodeSourceResponseState.UNKNOWN -> EpisodeSourceState.UNKNOWN
                        EpisodeSourceResponseState.STALE -> EpisodeSourceState.STALE
                    }
                    _episodeSourceStates.value = _episodeSourceStates.value + (episode.id to state)
                    if (!snapshot.errorMessage.isNullOrBlank()) {
                        _sourceError.value = snapshot.errorMessage
                    } else if (snapshot.sources.isEmpty()) {
                        _sourceError.value = when (snapshot.state) {
                            EpisodeSourceResponseState.EMPTY -> "The backend confirmed that no sources are currently available."
                            EpisodeSourceResponseState.PARTIAL -> "Source lookup is partial; retry to check again."
                            EpisodeSourceResponseState.FAILED -> snapshot.errorMessage ?: "Source lookup failed. Retry to check again."
                            EpisodeSourceResponseState.TIMEOUT -> snapshot.errorMessage ?: "Source lookup timed out. Retry to check again."
                            EpisodeSourceResponseState.STALE -> snapshot.errorMessage ?: "Showing stale, unverified source candidates."
                            else -> "The backend returned no source candidates, but did not confirm an empty result. Retry to check again."
                        }
                    } else if (snapshot.state == EpisodeSourceResponseState.PARTIAL) {
                        _sourceError.value = "Partial source response; available candidates are shown below."
                    } else if (snapshot.state == EpisodeSourceResponseState.FAILED ||
                        snapshot.state == EpisodeSourceResponseState.TIMEOUT ||
                        snapshot.state == EpisodeSourceResponseState.STALE) {
                        _sourceError.value = snapshot.errorMessage ?: "Some source information may be unavailable."
                    }
                } else {
                    val error = res.exceptionOrNull()
                    val timedOut = generateSequence(error) { it.cause }.any { it is java.net.SocketTimeoutException }
                    _episodeSourceStates.value = _episodeSourceStates.value + (
                        episode.id to if (timedOut) EpisodeSourceState.TIMEOUT else EpisodeSourceState.FAILED
                    )
                    _sourceError.value = "Failed to load sources: ${res.exceptionOrNull()?.message}"
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val timedOut = generateSequence<Throwable>(error) { it.cause }.any { it is java.net.SocketTimeoutException }
                _episodeSourceStates.value = _episodeSourceStates.value + (
                    episode.id to if (timedOut) EpisodeSourceState.TIMEOUT else EpisodeSourceState.FAILED
                )
                _sourceError.value = "Failed to load sources: ${error.message}"
            } finally {
                if (requestGeneration == sourceRequestGeneration) {
                    _isLoadingSources.value = false
                    queueAutomaticSourceDiscovery(episodes.value, animeEntry.value, lastPlayed.value)
                }
            }
        }
    }

    fun closeSourceChooser() {
        sourceRequestGeneration += 1L
        sourceDiscoveryJob?.cancel()
        sourceDiscoveryJob = null
        _isLoadingSources.value = false
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
    val initialEpisodeId: String,
    private val initialPreferredSourceId: String? = null
) : AndroidViewModel(application) {
    private val repository = AnimeRepository(AppDatabase.getInstance(application), applicationContext = application)
    val playerManager = PlayerManager(application, viewModelScope)
    val preResolveManager = PreResolveQueueManager(repository, viewModelScope)

    val animeEntry: StateFlow<AnimeEntry?> = repository.observeAnimeById(animeId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val allEpisodes: StateFlow<List<EpisodeEntry>> = repository.observeAllEpisodes(animeId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val seasons: StateFlow<List<SeasonEntry>> = repository.observeSeasons(animeId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val preResolveQueueStatus: StateFlow<Map<String, PreResolveQueueManager.ResolutionStatus>> =
        preResolveManager.queueStatus

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

    // Download integration
    val downloadManager = DownloadManager.getInstance(application)

    val currentDownload: StateFlow<DownloadEntry?> = _currentEpisode.flatMapLatest { ep ->
        if (ep == null) flowOf(null) else downloadManager.observeDownload(ep.id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val animeDownloads: StateFlow<List<DownloadEntry>> = downloadManager.observeDownloadsForAnime(animeId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val storageStats: StateFlow<DownloadManager.StorageStats> = downloadManager.storageStats

    val liveProgressMap: StateFlow<Map<String, DownloadManager.LiveProgress>> = downloadManager.liveProgressMap

    // Available qualities from actual sources (never fabricated)
    val availableDownloadQualities: StateFlow<List<String>> = _availableSources.combine(playerManager.availableQualities) { sources, qualities ->
        val fromSources = sources.mapNotNull { it.quality?.trim() }.filter { it.isNotBlank() }
        val fromPlayer = qualities.map { it.label.substringBefore('(').trim() }.filter { it.isNotBlank() && !it.contains("Auto", ignoreCase = true) }
        (fromSources + fromPlayer).distinct()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Available audio languages from actual sources (never fabricated)
    val availableDownloadLanguages: StateFlow<List<String>> = _availableSources.combine(playerManager.availableAudioTracks) { sources, audioTracks ->
        val fromSources = sources.mapNotNull { it.language?.trim() }.filter { it.isNotBlank() }
        val fromPlayer = audioTracks.map { it.label.substringBefore('(').trim() }.filter { it.isNotBlank() && !it.contains("Default", ignoreCase = true) }
        (fromSources + fromPlayer).distinct()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // In-memory active direct playback data
    private var activeDirectData: DirectPlayData? = null
    private var activeSourceId: String? = null
    private var playbackResolutionJob: Job? = null
    private var playbackGeneration: Long = 0L
    private var retriedSourceGeneration: Long = -1L
    private val retriedSourceIds = mutableSetOf<String>()

    val unifiedAudioTracks: StateFlow<List<PlayerManager.TrackOption>> = combine(
        _availableSources,
        _activeSource,
        playerManager.availableAudioTracks
    ) { sources, active, embeddedTracks ->
        val sourceTracks = sources.mapNotNull { source ->
            val language = source.language?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            PlayerManager.TrackOption(
                id = "source_audio:${source.id}",
                label = "$language • ${source.label ?: source.id}",
                isSelected = source.id == active?.id,
                isSupported = true
            )
        }.distinctBy { it.label.substringBefore(" • ").trim().lowercase() }
        val inStreamTracks = embeddedTracks.filterNot { it.id == "auto_audio" }
        (sourceTracks + inStreamTracks).distinctBy { it.label.substringBefore(" • ").trim().lowercase() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        playerManager.onPlaybackEndedCallback = {
            flushProgress()
        }

        viewModelScope.launch {
            // Find episode or wait for database
            repository.observeAllEpisodes(animeId).collect { epList ->
                if (epList.isNotEmpty()) {
                    if (_currentEpisode.value == null) {
                        val target = epList.firstOrNull { it.id == initialEpisodeId } ?: epList.first()
                        _currentEpisode.value = target
                        _selectedSeason.value = target.seasonNumber
                        startPlaybackForEpisode(
                            target,
                            preferredSourceId = if (target.id == initialEpisodeId) initialPreferredSourceId else null
                        )
                    } else {
                        val current = _currentEpisode.value
                        val anime = AppDatabase.getInstance(application).animeDao().getAnimeById(animeId) ?: animeEntry.value
                        val bId = anime?.backendAnimeId ?: if (animeId.startsWith("anilist_")) null else animeId
                        if (current != null && bId != null) {
                            preResolveManager.slideWindow(bId, current.id, epList)
                        }
                    }
                }
            }
        }

        var lastWatchTimeTrackMs = System.currentTimeMillis()
        var lastCloudSyncMs = 0L
        var lastCloudSyncEpisodeId: String? = null
        val completedCloudSyncEpisodeIds = mutableSetOf<String>()

        // Save playback locally at the player cadence, but bound cloud writes to one/minute.
        playerManager.onPositionUpdate = { posMs, durMs ->
            val ep = _currentEpisode.value
            if (ep != null && durMs > 0) {
                val now = System.currentTimeMillis()
                val deltaSec = (now - lastWatchTimeTrackMs) / 1000
                if (!playerManager.isPlaying.value) {
                    lastWatchTimeTrackMs = now
                } else if (deltaSec >= 60) {
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
                    val firstSyncForEpisode = lastCloudSyncEpisodeId != ep.id
                    val completionNeedsSync = isCompleted && completedCloudSyncEpisodeIds.add(ep.id)
                    if (firstSyncForEpisode || now - lastCloudSyncMs >= 60_000L || completionNeedsSync) {
                        lastCloudSyncMs = now
                        lastCloudSyncEpisodeId = ep.id
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

    fun startPlaybackForEpisode(
        episode: EpisodeEntry,
        preferredSourceId: String? = null,
        startPositionOverrideMs: Long? = null
    ) {
        if (_currentEpisode.value?.id == episode.id && _isResolving.value &&
            playbackResolutionJob?.isActive == true &&
            (preferredSourceId == null || preferredSourceId == activeSourceId)) return
        val requestGeneration = ++playbackGeneration
        retriedSourceGeneration = requestGeneration
        retriedSourceIds.clear()
        playbackResolutionJob?.cancel()
        _currentEpisode.value = episode
        _selectedSeason.value = episode.seasonNumber
        _availableSources.value = emptyList()
        _activeSource.value = null
        activeDirectData = null
        activeSourceId = null
        playerManager.clearTrackSelections()
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

        playbackResolutionJob = viewModelScope.launch {
            val db = AppDatabase.getInstance(getApplication())

            // 1. Check if episode is downloaded for offline playback
            val download = db.downloadDao().getDownload(episode.id)
            if (requestGeneration != playbackGeneration) return@launch
            if (download != null && download.downloadStatus == "COMPLETED" && download.localFilePath != null) {
                val file = java.io.File(download.localFilePath)
                if (file.exists() && file.length() > 0) {
                    _isResolving.value = false
                    _activeSource.value = null
                    val lastSavedPos = try {
                        startPositionOverrideMs ?: db.playbackHistoryDao().getHistoryForEpisode(animeId, episode.id)?.positionMs ?: 0L
                    } catch (_: Exception) { startPositionOverrideMs ?: 0L }

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
                        onRetryNeeded = {},
                        providerName = "Offline"
                    )
                    return@launch
                }
            }

            // 2. Online playback flow using ProphecyVerse catalog
            val anime = db.animeDao().getAnimeById(animeId) ?: animeEntry.value
            if (requestGeneration != playbackGeneration) return@launch
            val backendId = anime?.backendAnimeId ?: if (animeId.startsWith("anilist_")) null else animeId

            if (backendId == null) {
                _resolutionError.value = "Cannot play: Anime has no playable backend catalog ID."
                _isResolving.value = false
                return@launch
            }

            // 3. Fast-Path: Check 17-episode pre-resolve cache for instant start (< 25m TTL)
            if (preferredSourceId == null) {
                val prepared = preResolveManager.getPreparedSource(backendId, episode.id)
                if (requestGeneration != playbackGeneration) return@launch
                if (prepared != null) {
                    val lastSavedPos = try {
                        startPositionOverrideMs ?: db.playbackHistoryDao().getHistoryForEpisode(animeId, episode.id)?.positionMs ?: 0L
                    } catch (_: Exception) { startPositionOverrideMs ?: 0L }
                    if (requestGeneration != playbackGeneration) return@launch
                    _activeSource.value = prepared.source
                    _availableSources.value = listOf(prepared.source)
                    activeDirectData = prepared.directData
                    activeSourceId = prepared.source.id
                    _isResolving.value = false

                    playerManager.initializePlayer(
                        directData = prepared.directData,
                        startPositionMs = lastSavedPos,
                        onPlaybackFailed = {
                            if (requestGeneration == playbackGeneration) {
                                preResolveManager.discardPreparedSource(backendId, episode.id)
                                repository.invalidateDirectPlayback(prepared.source.id)
                                playbackResolutionJob = viewModelScope.launch {
                                _isResolving.value = true
                                val freshSources = repository.fetchEpisodeSources(
                                    backendId,
                                    episode.id,
                                    episode.seasonNumber,
                                    episode.episodeNumber,
                                    forceRefresh = true
                                ).getOrNull() ?: emptyList()
                                if (requestGeneration == playbackGeneration) {
                                    resolveWithFallback(backendId, episode, freshSources, 0, requestGeneration = requestGeneration)
                                }
                            }
                            }
                        },
                        onRetryNeeded = {
                            if (requestGeneration == playbackGeneration) {
                                preResolveManager.discardPreparedSource(backendId, episode.id)
                                repository.invalidateDirectPlayback(prepared.source.id)
                                playbackResolutionJob = viewModelScope.launch {
                                    val refreshedSources = repository.fetchEpisodeSources(
                                        backendId,
                                        episode.id,
                                        episode.seasonNumber,
                                        episode.episodeNumber,
                                        forceRefresh = true
                                    ).getOrNull() ?: emptyList()
                                    if (requestGeneration == playbackGeneration) {
                                        resolveWithFallback(backendId, episode, refreshedSources, 0, requestGeneration = requestGeneration)
                                    }
                                }
                            }
                        },
                        providerName = prepared.source.displayProviderName
                    )

                    // Slide the bounded pre-resolve queue forward.
                    preResolveManager.slideWindow(backendId, episode.id, allEpisodes.value)
                    return@launch
                }
            }

            // Route D: Get sources for the selected episode
            val sourcesRes = repository.fetchEpisodeSources(
                backendId, episode.id, episode.seasonNumber, episode.episodeNumber
            )
            if (requestGeneration != playbackGeneration) return@launch
            if (sourcesRes.isFailure || sourcesRes.getOrNull().isNullOrEmpty()) {
                _resolutionError.value = "No playable sources found for this episode."
                _isResolving.value = false
                return@launch
            }

            val sources = sourcesRes.getOrNull()!!
            val candidates = if (preferredSourceId != null) {
                val selected = sources.firstOrNull { it.id == preferredSourceId }
                if (selected == null) {
                    _resolutionError.value = "The selected source is not available for this episode."
                    _isResolving.value = false
                    return@launch
                }
                listOf(selected) + sources.filter { it.id != preferredSourceId }
            } else {
                sources
            }

            resolveWithFallback(
                backendId,
                episode,
                candidates,
                0,
                prioritize = preferredSourceId == null,
                startPositionOverrideMs = startPositionOverrideMs,
                requestGeneration = requestGeneration
            )
        }
    }

    private fun prioritizeSources(sources: List<ProphecySourceDto>): List<ProphecySourceDto> {
        val preferred = com.example.data.preferences.AudioPreferences.getInstance(getApplication())
            .getPreferredAudioLanguageCodes()
            .map { it.lowercase() }
        fun rank(source: ProphecySourceDto): Int {
            val rawLanguage = source.language?.lowercase()?.trim() ?: return Int.MAX_VALUE
            val language = when {
                rawLanguage.contains("hindi") -> "hi"
                rawLanguage.contains("english") -> "en"
                rawLanguage.contains("japanese") -> "ja"
                rawLanguage.contains("tamil") -> "ta"
                rawLanguage.contains("telugu") -> "te"
                rawLanguage.contains("spanish") -> "es"
                else -> rawLanguage
            }
            val match = preferred.indexOfFirst { code ->
                language == code || language.startsWith("$code-") || language.contains(code)
            }
            return if (match >= 0) match else Int.MAX_VALUE
        }
        return sources.withIndex()
            .sortedWith(compareBy<IndexedValue<ProphecySourceDto>> { rank(it.value) }.thenBy { it.index })
            .map { it.value }
    }

    private suspend fun resolveWithFallback(
        backendId: String,
        episode: EpisodeEntry,
        candidates: List<ProphecySourceDto>,
        startIndex: Int = 0,
        prioritize: Boolean = false,
        startPositionOverrideMs: Long? = null,
        requestGeneration: Long
    ) {
        if (requestGeneration != playbackGeneration) return
        val orderedCandidates = if (prioritize && startIndex == 0) prioritizeSources(candidates) else candidates
        _availableSources.value = orderedCandidates
        var lastError: String? = null

        for (i in startIndex until orderedCandidates.size) {
            if (requestGeneration != playbackGeneration) return
            val source = orderedCandidates[i]
            if (!source.hasUsableSourceId) {
                lastError = "Source candidate has no usable source ID."
                continue
            }
            activeSourceId = source.id
            _activeSource.value = source

            // Route E: The ONE AND ONLY playback route: POST /api/v1/play/direct
            val playRes = repository.resolveDirectPlayback(source.id)
            if (requestGeneration != playbackGeneration) return
            if (playRes.isSuccess && playRes.getOrNull() != null) {
                val directData = playRes.getOrNull()!!
                if (directData.sourceId != source.id) {
                    lastError = "Resolved source identity did not match the selected provider source."
                    continue
                }
                activeDirectData = directData

                // Fetch last saved position from database unless a source switch supplied it.
                val lastSavedPos = try {
                    startPositionOverrideMs
                        ?: AppDatabase.getInstance(getApplication()).playbackHistoryDao()
                            .getHistoryForEpisode(animeId, episode.id)?.positionMs
                        ?: 0L
                } catch (e: Exception) { startPositionOverrideMs ?: 0L }
                if (requestGeneration != playbackGeneration) return
                _isResolving.value = false

                // Initialize Media3 ExoPlayer with direct URL and headers
                playerManager.initializePlayer(
                    directData = directData,
                    startPositionMs = lastSavedPos,
                    onPlaybackFailed = {
                        // Try the next candidate, but always terminate in a user-visible error
                        // when the final candidate also fails.
                        if (requestGeneration == playbackGeneration) {
                            if (i + 1 < orderedCandidates.size) {
                                _isResolving.value = true
                                playbackResolutionJob?.cancel()
                                playbackResolutionJob = viewModelScope.launch {
                                    _isResolving.value = true
                                    resolveWithFallback(
                                        backendId, episode, orderedCandidates, i + 1,
                                        startPositionOverrideMs = lastSavedPos,
                                        requestGeneration = requestGeneration
                                    )
                                }
                            } else {
                                _resolutionError.value = "All available sources failed to start playback. Select Retry to try source discovery again."
                                _isResolving.value = false
                            }
                        }
                    },
                    onRetryNeeded = {
                        if (requestGeneration == playbackGeneration &&
                            (retriedSourceGeneration != requestGeneration || retriedSourceIds.add(source.id))) {
                            retriedSourceGeneration = requestGeneration
                            repository.invalidateDirectPlayback(source.id)
                            playbackResolutionJob?.cancel()
                            playbackResolutionJob = viewModelScope.launch {
                                // Refresh both source candidates and the source's resolved URL.
                                val refreshedSources = repository.fetchEpisodeSources(
                                    backendId,
                                    episode.id,
                                    episode.seasonNumber,
                                    episode.episodeNumber,
                                    forceRefresh = true
                                ).getOrNull() ?: emptyList()
                                if (requestGeneration != playbackGeneration) return@launch
                                val newCandidates = if (refreshedSources.isNotEmpty()) refreshedSources else listOf(source)
                                resolveWithFallback(
                                    backendId, episode, newCandidates, 0,
                                    startPositionOverrideMs = lastSavedPos,
                                    requestGeneration = requestGeneration
                                )
                            }
                        }
                    },
                    providerName = source.displayProviderName
                )

                // Trigger 17-episode sliding queue in background
                preResolveManager.slideWindow(backendId, episode.id, allEpisodes.value)
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
        startPlaybackForEpisode(
            ep,
            preferredSourceId = sourceId,
            startPositionOverrideMs = playerManager.currentPosition.value
        )
    }

    fun selectAudioTrack(option: PlayerManager.TrackOption) {
        if (option.id.startsWith("source_audio:")) {
            switchSource(option.id.removePrefix("source_audio:"))
        } else {
            playerManager.selectTrack(option)
        }
    }

    fun playNextEpisode() {
        val next = nextEpisode.value ?: return
        startPlaybackForEpisode(next)
    }

    fun startDownload(preferredQuality: String? = null, preferredLanguage: String? = null) {
        val ep = _currentEpisode.value ?: return
        val anime = animeEntry.value ?: return
        downloadManager.startOrResumeDownload(
            animeId = animeId,
            episodeId = ep.id,
            seasonNumber = ep.seasonNumber,
            episodeNumber = ep.episodeNumber,
            episodeTitle = ep.title,
            animeTitle = anime.preferredTitle,
            posterUrl = anime.posterUrl,
            mediaType = anime.format ?: "anime",
            preferredQuality = preferredQuality,
            preferredLanguage = preferredLanguage,
            preferredSourceId = activeSourceId
        )
    }

    fun pauseDownload() {
        _currentEpisode.value?.let { downloadManager.pauseDownload(it.id) }
    }

    fun resumeDownload() {
        startDownload()
    }

    fun retryDownload() {
        startDownload()
    }

    fun cancelDownload() {
        _currentEpisode.value?.let { downloadManager.cancelDownload(it.id) }
    }

    fun deleteDownload() {
        _currentEpisode.value?.let { downloadManager.deleteDownloadedFile(it.id) }
    }

    fun startBatchDownload(
        episodes: List<EpisodeEntry>,
        preferredQuality: String? = null,
        preferredLanguage: String? = null
    ) {
        val anime = animeEntry.value ?: return
        for (ep in episodes) {
            downloadManager.startOrResumeDownload(
                animeId = animeId,
                episodeId = ep.id,
                seasonNumber = ep.seasonNumber,
                episodeNumber = ep.episodeNumber,
                episodeTitle = ep.title,
                animeTitle = anime.preferredTitle,
                posterUrl = anime.posterUrl,
                mediaType = anime.format ?: "anime",
                preferredQuality = preferredQuality,
                preferredLanguage = preferredLanguage,
                preferredSourceId = if (ep.id == _currentEpisode.value?.id) activeSourceId else null
            )
        }
    }

    fun pauseDownloadForEpisode(episodeId: String) {
        downloadManager.pauseDownload(episodeId)
    }

    fun resumeDownloadForEpisode(episodeId: String, preferredQuality: String? = null, preferredLanguage: String? = null) {
        val ep = allEpisodes.value.firstOrNull { it.id == episodeId } ?: _currentEpisode.value ?: return
        val anime = animeEntry.value ?: return
        downloadManager.startOrResumeDownload(
            animeId = animeId,
            episodeId = ep.id,
            seasonNumber = ep.seasonNumber,
            episodeNumber = ep.episodeNumber,
            episodeTitle = ep.title,
            animeTitle = anime.preferredTitle,
            posterUrl = anime.posterUrl,
            mediaType = anime.format ?: "anime",
            preferredQuality = preferredQuality,
            preferredLanguage = preferredLanguage
        )
    }

    fun cancelDownloadForEpisode(episodeId: String) {
        downloadManager.cancelDownload(episodeId)
    }

    fun deleteDownloadForEpisode(episodeId: String) {
        downloadManager.deleteDownloadedFile(episodeId)
    }

    fun pauseAllDownloads() {
        val active = animeDownloads.value.filter { it.downloadStatus == "DOWNLOADING" }
        for (d in active) {
            downloadManager.pauseDownload(d.id)
        }
    }

    fun resumeAllDownloads() {
        val paused = animeDownloads.value.filter { it.downloadStatus == "PAUSED" }
        for (d in paused) {
            resumeDownloadForEpisode(d.id, d.quality, d.audioLanguage)
        }
    }

    fun flushProgress() {
        val ep = _currentEpisode.value ?: return
        val posMs = playerManager.currentPosition.value
        val durMs = playerManager.duration.value
        if (posMs > 0L && durMs > 0L) {
            viewModelScope.launch {
                val isCompleted = posMs >= (durMs * 0.9)
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
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        flushProgress()
        preResolveManager.clear()
        playerManager.release()
    }
}

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AnimeRepository(AppDatabase.getInstance(application), applicationContext = application)

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
    private val repository = AnimeRepository(AppDatabase.getInstance(application), applicationContext = application)
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
