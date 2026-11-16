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
import com.example.data.identity.EpisodeCanonicalizer
import com.example.data.remote.DirectPlayData
import com.example.data.remote.ProphecySourceDto
import com.example.data.repository.AnimeRepository
import com.example.data.repository.resolveVerifiedPlaybackForEpisode
import com.example.data.repository.UserFacingErrorMapper
import com.example.data.repository.EpisodeCatalogRefreshStatus
import com.example.data.repository.EpisodeCatalogState
import com.example.data.repository.EpisodeRecordPagination
import com.example.data.repository.EpisodeSourceResponseState
import com.example.player.PlaybackFailureReason
import com.example.player.PlaybackRecoveryCoordinator
import com.example.player.PlaybackRecoveryKey
import com.example.player.PlayerManager
import com.example.player.PreResolveQueueManager
import com.example.player.PreResolvePlan
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
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
    val repository by lazy { AnimeRepository.getInstance(application) }
}

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AnimeRepository.getInstance(application)

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
                repository.refreshHomeDiscovery(force = false)
            }
        }
    }

    init {
        try {
            connectivityManager?.registerDefaultNetworkCallback(networkCallback)
        } catch (_: Exception) {}
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
    private val repository = AnimeRepository.getInstance(application)

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedGenre = MutableStateFlow<String?>(null)
    val selectedGenre: StateFlow<String?> = _selectedGenre.asStateFlow()

    private val _selectedFormat = MutableStateFlow<String?>(null)
    val selectedFormat: StateFlow<String?> = _selectedFormat.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _hasNextPage = MutableStateFlow(false)
    val hasNextPage: StateFlow<Boolean> = _hasNextPage.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var searchJob: Job? = null
    private var loadMoreJob: Job? = null
    private var currentPage: Int = 1
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

    private var lastCompletedNormalizedQuery: String? = null

    fun onQueryChange(newQuery: String) {
        _searchQuery.value = newQuery
        val trimmed = newQuery.trim()
        val norm = com.example.data.repository.TitleMatcher.normalize(trimmed)
        if (norm.isBlank()) {
            searchRequestSequence++
            searchJob?.cancel()
            loadMoreJob?.cancel()
            currentPage = 1
            _hasNextPage.value = false
            _isLoadingMore.value = false
            _isSearching.value = false
            lastCompletedNormalizedQuery = null
            return
        }
        if (norm == lastCompletedNormalizedQuery && _error.value == null) {
            return
        }

        val requestId = ++searchRequestSequence
        searchJob?.cancel()
        loadMoreJob?.cancel()
        currentPage = 1
        _hasNextPage.value = false
        _isLoadingMore.value = false

        searchJob = viewModelScope.launch {
            _isSearching.value = true
            _error.value = null
            delay(250L)
            val res = repository.searchAnimePage(trimmed, page = 1)
            if (requestId == searchRequestSequence) {
                res.onSuccess { pageRes ->
                    currentPage = pageRes.currentPage
                    _hasNextPage.value = pageRes.hasNextPage
                    lastCompletedNormalizedQuery = norm
                }.onFailure {
                    lastCompletedNormalizedQuery = null
                    val savedState = repository.getSearchQueryState(trimmed)
                    _hasNextPage.value = savedState?.hasNextPage ?: false
                    _error.value = "Network search failed. Showing local results."
                }
                _isSearching.value = false
            }
        }
    }

    fun loadNextPage() {
        val query = _searchQuery.value.trim()
        if (query.isBlank() || _isSearching.value || _isLoadingMore.value || !_hasNextPage.value) return
        val requestId = searchRequestSequence
        val nextPage = currentPage + 1
        loadMoreJob = viewModelScope.launch {
            _isLoadingMore.value = true
            val res = repository.searchAnimePage(query, page = nextPage)
            if (requestId == searchRequestSequence) {
                res.onSuccess { pageRes ->
                    currentPage = pageRes.currentPage
                    _hasNextPage.value = pageRes.hasNextPage
                }.onFailure {
                    _error.value = "Could not load next search page."
                }
                _isLoadingMore.value = false
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
    val animeId: String,
    initialSeason: Int? = null
) : AndroidViewModel(application) {
    private val repository = AnimeRepository.getInstance(application)

    val animeEntry: StateFlow<AnimeEntry?> = repository.observeAnimeById(animeId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val seasons: StateFlow<List<SeasonEntry>> = repository.observeSeasons(animeId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val firstEpisode: StateFlow<EpisodeEntry?> = repository.observeFirstEpisode(animeId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _selectedSeason = MutableStateFlow(initialSeason?.takeIf { it > 0 } ?: 1)
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

    // In-memory episode or movie source selection
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
                .distinctUntilChangedBy { (sList, firstEp) ->
                    sList.map { it.seasonNumber } to firstEp?.let { it.id to it.seasonNumber }
                }
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
                .distinctUntilChangedBy { (history, records) ->
                    val historyIdentity = history?.let { Triple(it.seasonNumber, it.episodeId, it.episodeNumber) }
                    val episodeKeys = records.map { Triple(it.id, it.seasonNumber, it.episodeNumber) }
                    historyIdentity to episodeKeys
                }
                .collect { (history, records) ->
                if (history != null && history.seasonNumber > 0) {
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
                .distinctUntilChangedBy { (anime, window, history) ->
                    Triple(
                        anime?.let { listOf(it.id, it.canonicalAnimeId, it.mediaCategory, it.format) },
                        window.map { Triple(it.id, it.seasonNumber, it.episodeNumber) },
                        history?.let { Triple(it.episodeId, it.seasonNumber, it.episodeNumber) }
                    )
                }
                .collect { (anime, window, history) ->
                    checkCachedSourceStatesForWindow(window, anime, history)
                }
        }
        refreshDetails()
    }

    fun syntheticMovieTarget(entry: AnimeEntry): EpisodeEntry = EpisodeEntry(
        id = "movie_${entry.id}",
        animeId = entry.id,
        seasonNumber = 0,
        episodeNumber = 1,
        title = entry.preferredTitle
    )

    fun refreshDetails(forceRefresh: Boolean = false) {
        if (!forceRefresh && refreshJob?.isActive == true) return
        if (forceRefresh) refreshJob?.cancel()
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
                    } catch (_: Exception) {}

                    val result = repository.refreshAnimeDetails(animeId, forceRefresh = forceRefresh)
                    val outcome = result.getOrNull()
                    if (outcome != null) {
                        _catalogRefreshStatus.value = outcome
                        if (!outcome.errorMessage.isNullOrBlank()) {
                            _syncError.value = outcome.errorMessage
                        }
                    } else {
                        _catalogRefreshStatus.value = EpisodeCatalogRefreshStatus(
                            EpisodeCatalogState.FAILED, false, false,
                            "Catalog refresh failed. Cached metadata remains available; tap retry to try again."
                        )
                        _syncError.value = "Catalog refresh failed. Cached metadata remains available; tap retry to try again."
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    _catalogRefreshStatus.value = EpisodeCatalogRefreshStatus(
                        EpisodeCatalogState.FAILED, false, false,
                        "Catalog refresh failed. Cached metadata remains available; tap retry to try again."
                    )
                    _syncError.value = "Catalog refresh failed. Cached metadata remains available; tap retry to try again."
                }
            }
        }
    }

    fun selectSeason(seasonNumber: Int) {
        _selectedSeason.value = seasonNumber
        _episodeWindowStart.value = 0
        viewModelScope.launch {
            repository.loadEpisodePage(animeId, seasonNumber, offset = 0, limit = 30)
        }
    }

    fun loadNextEpisodeWindow() {
        if (canLoadNextEpisodePage.value) {
            val nextOffset = _episodeWindowStart.value + 30
            _episodeWindowStart.value = nextOffset
            val season = _selectedSeason.value
            viewModelScope.launch {
                repository.loadEpisodePage(animeId, season, offset = nextOffset, limit = 30)
            }
        }
    }

    fun loadPreviousEpisodeWindow() {
        if (canLoadPreviousEpisodePage.value) _episodeWindowStart.value = (_episodeWindowStart.value - 30).coerceAtLeast(0)
    }

    fun focusEpisodeWindow(episode: EpisodeEntry) {
        if (episode.seasonNumber > 0) {
            _selectedSeason.value = episode.seasonNumber
            val ordered = episodesForDisplay(
                allCatalogEpisodes.value, episode.seasonNumber, _catalogRefreshStatus.value.currentRefreshEpisodeIds
            )
            val index = ordered.indexOfFirst { it.id == episode.id }
            _episodeWindowStart.value = if (index >= 0) (index / 30) * 30 else 0
        }
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

    /**
     * Passive cache check for visible Episode rows (Room only; never triggers bulk Backend scans).
     */
    private fun checkCachedSourceStatesForWindow(
        window: List<EpisodeEntry>,
        current: AnimeEntry?,
        history: PlaybackHistory?
    ) {
        current ?: return
        val visibleIds = window.map { it.id }.toSet()
        val selectedId = _selectedEpisodeForSources.value?.id
        automaticSourceJobs.toMap().forEach { (episodeId, job) ->
            if (episodeId !in visibleIds && episodeId != selectedId) {
                job.cancel()
            }
        }
        _episodeSourceStates.value = _episodeSourceStates.value.filterKeys { it in visibleIds || it == selectedId }
        if (window.isEmpty()) return

        val ordered = window.sortedWith(compareBy<EpisodeEntry> { if (it.id == history?.episodeId) 0 else 1 }
            .thenBy { it.episodeNumber })
        ordered.forEach { episode ->
            if (automaticSourceJobs[episode.id]?.isActive == true) return@forEach
            val existingState = _episodeSourceStates.value[episode.id]
            if (existingState in setOf(EpisodeSourceState.AVAILABLE, EpisodeSourceState.EMPTY, EpisodeSourceState.PARTIAL)) return@forEach
            automaticSourceJobs[episode.id] = viewModelScope.launch {
                try {
                    val cached = repository.getCachedEpisodeSourceSnapshot(
                        backendAnimeId = animeId,
                        episodeId = episode.id,
                        seasonNumber = episode.seasonNumber,
                        episodeNumber = episode.episodeNumber
                    )
                    if (cached != null) {
                        _episodeSourceStates.value = _episodeSourceStates.value + (episode.id to mapResponseState(cached.state))
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                } finally {
                    automaticSourceJobs.remove(episode.id)
                }
            }
        }
    }

    fun openMovieSourceChooser(forceRefresh: Boolean = false) {
        val entry = animeEntry.value ?: return
        openSourceChooser(syntheticMovieTarget(entry), forceRefresh = forceRefresh)
    }

    fun openSourceChooser(episode: EpisodeEntry, forceRefresh: Boolean = false) {
        if (!forceRefresh && _selectedEpisodeForSources.value?.id == episode.id &&
            (_episodeSources.value.isNotEmpty() || sourceDiscoveryJob?.isActive == true)) return
        val sameEpisode = _selectedEpisodeForSources.value?.id == episode.id
        val requestGeneration = ++sourceRequestGeneration
        sourceDiscoveryJob?.cancel()
        _selectedEpisodeForSources.value = episode
        if (!sameEpisode) _episodeSources.value = emptyList()
        _isLoadingSources.value = false
        _sourceError.value = null
        _episodeSourceStates.value = _episodeSourceStates.value + (episode.id to EpisodeSourceState.LOADING)

        val entry = animeEntry.value
        val isMovie = com.example.data.streaming.ContentIdentityHelper.isMovie(entry) ||
            episode.seasonNumber == 0 || episode.id.startsWith("movie_")

        sourceDiscoveryJob = viewModelScope.launch {
            _isLoadingSources.value = true
            try {
                val res = automaticSourceSemaphore.withPermit {
                    repository.discoverStreamsForScope(
                        animeId = animeId,
                        seasonNumber = episode.seasonNumber,
                        episodeNumber = episode.episodeNumber,
                        catalogEpisodeId = episode.id,
                        forceRefresh = forceRefresh
                    )
                }
                if (requestGeneration != sourceRequestGeneration) return@launch
                val snapshot = res.getOrNull()
                if (snapshot != null) {
                    _episodeSources.value = snapshot.sources
                    snapshot.selectedEpisode?.let { updatedEp ->
                        val currentSelected = _selectedEpisodeForSources.value ?: episode
                        _selectedEpisodeForSources.value = currentSelected.copy(
                            seasonNumber = if (isMovie) 0 else updatedEp.seasonNumber,
                            episodeNumber = if (isMovie) 1 else updatedEp.episodeNumber,
                            title = updatedEp.title?.takeIf { it.isNotBlank() } ?: currentSelected.title,
                            overview = updatedEp.overview?.takeIf { it.isNotBlank() } ?: currentSelected.overview,
                            sourceEpisodeIds = updatedEp.sourceEpisodeIds ?: currentSelected.sourceEpisodeIds
                        )
                    }
                    val state = mapResponseState(snapshot.state)
                    _episodeSourceStates.value = _episodeSourceStates.value + (episode.id to state)
                    if (!snapshot.errorMessage.isNullOrBlank()) {
                        _sourceError.value = snapshot.errorMessage
                    } else if (snapshot.sources.isEmpty()) {
                        _sourceError.value = when (snapshot.state) {
                            EpisodeSourceResponseState.EMPTY -> "No streaming sources are currently available for this title."
                            EpisodeSourceResponseState.PARTIAL -> "Source discovery is partial; retry to check again."
                            else -> "No streaming sources returned yet. Tap Retry to check again."
                        }
                    } else if (snapshot.state == EpisodeSourceResponseState.PARTIAL) {
                        _sourceError.value = "Partial source discovery; available candidates are shown below."
                    }
                } else {
                    val error = res.exceptionOrNull()
                    val timedOut = generateSequence(error) { it.cause }.any { it is java.net.SocketTimeoutException }
                    _episodeSourceStates.value = _episodeSourceStates.value + (
                        episode.id to if (timedOut) EpisodeSourceState.TIMEOUT else EpisodeSourceState.FAILED
                    )
                    _sourceError.value = UserFacingErrorMapper.message(error, "No streaming sources discovered yet.")
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val timedOut = generateSequence<Throwable>(error) { it.cause }.any { it is java.net.SocketTimeoutException }
                _episodeSourceStates.value = _episodeSourceStates.value + (
                    episode.id to if (timedOut) EpisodeSourceState.TIMEOUT else EpisodeSourceState.FAILED
                )
                _sourceError.value = UserFacingErrorMapper.message(error)
            } finally {
                if (requestGeneration == sourceRequestGeneration) {
                    _isLoadingSources.value = false
                }
            }
        }
    }

    private fun mapResponseState(state: EpisodeSourceResponseState): EpisodeSourceState = when (state) {
        EpisodeSourceResponseState.AVAILABLE -> EpisodeSourceState.AVAILABLE
        EpisodeSourceResponseState.EMPTY -> EpisodeSourceState.EMPTY
        EpisodeSourceResponseState.PARTIAL -> EpisodeSourceState.PARTIAL
        EpisodeSourceResponseState.FAILED -> EpisodeSourceState.FAILED
        EpisodeSourceResponseState.TIMEOUT -> EpisodeSourceState.TIMEOUT
        EpisodeSourceResponseState.UNKNOWN -> EpisodeSourceState.UNKNOWN
        EpisodeSourceResponseState.STALE -> EpisodeSourceState.STALE
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

    override fun onCleared() {
        super.onCleared()
        sourceDiscoveryJob?.cancel()
        refreshJob?.cancel()
        automaticSourceJobs.values.forEach { it.cancel() }
    }

    fun toggleWatchlist(status: String = "WATCHING") {
        viewModelScope.launch {
            val updated = repository.toggleWatchlist(animeId, status)
            val cloud = com.example.data.firebase.CloudHistoryRepository.getInstance(getApplication())
            if (updated == null) {
                cloud.deleteWatchlistStatus(animeId)
            } else {
                cloud.syncWatchlistStatus(
                    animeId = updated.animeId,
                    status = updated.status,
                    isFavorite = updated.isFavorite
                )
            }
        }
    }

    fun toggleFavorite() {
        viewModelScope.launch {
            val updated = repository.toggleFavorite(animeId)
            com.example.data.firebase.CloudHistoryRepository.getInstance(getApplication())
                .syncWatchlistStatus(
                    animeId = updated.animeId,
                    status = updated.status,
                    isFavorite = updated.isFavorite
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
    private val repository = AnimeRepository.getInstance(application)
    val playerManager = PlayerManager(application, viewModelScope)
    val playbackDiagnostics: StateFlow<PlayerManager.PlaybackDiagnostics> = playerManager.playbackDiagnostics
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
        else PreResolvePlan.upcoming(current, list).firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    enum class ResolutionStage(val label: String) {
        IDLE(""),
        FINDING_SOURCES("Finding sources..."),
        VALIDATING_SOURCE("Validating source..."),
        RESOLVING_PLAYBACK("Resolving playback..."),
        STARTING_PLAYBACK("Starting playback...")
    }

    private val _isResolving = MutableStateFlow(true)
    val isResolving: StateFlow<Boolean> = _isResolving.asStateFlow()

    private val _resolutionStage = MutableStateFlow(ResolutionStage.IDLE)
    val resolutionStage: StateFlow<ResolutionStage> = _resolutionStage.asStateFlow()

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
    private val recoveryCoordinator = PlaybackRecoveryCoordinator(viewModelScope)
    private val sessionRecoveryMarkers = mutableSetOf<String>()

    val unifiedAudioTracks: StateFlow<List<PlayerManager.TrackOption>> = combine(
        _availableSources,
        _activeSource,
        playerManager.availableAudioTracks
    ) { sources, active, embeddedTracks ->
        val inStreamTracks = embeddedTracks.filterNot { it.id == "auto_audio" }
        val autoOption = embeddedTracks.firstOrNull { it.id == "auto_audio" }
        val inStreamLanguages = inStreamTracks.map {
            it.label.substringBefore(" (").substringBefore(" [").trim().lowercase()
        }.toSet()
        val sourceTracks = sources.mapNotNull { source ->
            val language = source.language?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (inStreamTracks.isNotEmpty() && language.lowercase() in inStreamLanguages && source.id == active?.id) {
                return@mapNotNull null
            }
            PlayerManager.TrackOption(
                id = "source_audio:${source.id}",
                label = "$language • ${source.label ?: source.id}",
                isSelected = source.id == active?.id && inStreamTracks.none { it.isSelected },
                isSupported = true
            )
        }.distinctBy { it.id }
        val combined = buildList {
            if (autoOption != null && inStreamTracks.isNotEmpty()) add(autoOption)
            addAll(inStreamTracks)
            addAll(sourceTracks)
        }
        combined.distinctBy { it.id }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        playerManager.onPlaybackEndedCallback = {
            flushProgress()
        }

        // Throttle background pre-resolution while active player is buffering/starting playback
        viewModelScope.launch {
            combine(
                playerManager.isBuffering,
                playerManager.exoPlayerInstance,
                playerManager.errorMessage
            ) { buffering, player, err ->
                buffering && player != null && err == null
            }.distinctUntilChanged().collect { shouldThrottle ->
                preResolveManager.setPlaybackBuffering(shouldThrottle)
            }
        }

        // Start either Flat Movie playback immediately or Series Episode playback once Room emits episodes
        viewModelScope.launch {
            combine(animeEntry, repository.observeAllEpisodes(animeId)) { anime, epList -> anime to epList }
                .distinctUntilChangedBy { (anime, epList) ->
                    val animeKey = anime?.let { Triple(it.id, it.canonicalAnimeId, it.mediaCategory to it.format) }
                    val epKeys = epList.map { Triple(it.id, it.seasonNumber, it.episodeNumber) }
                    animeKey to epKeys
                }
                .collect { (anime, epList) ->
                    val isMovieTarget = initialEpisodeId.startsWith("movie_") ||
                        com.example.data.streaming.ContentIdentityHelper.isMovie(anime)
                    if (_currentEpisode.value == null) {
                        if (isMovieTarget) {
                            val movieEp = EpisodeEntry(
                                id = if (initialEpisodeId.startsWith("movie_")) initialEpisodeId else "movie_$animeId",
                                animeId = animeId,
                                seasonNumber = 0,
                                episodeNumber = 1,
                                title = anime?.preferredTitle ?: "Full Movie"
                            )
                            _currentEpisode.value = movieEp
                            startPlaybackForEpisode(movieEp, preferredSourceId = initialPreferredSourceId)
                        } else if (epList.isNotEmpty()) {
                            val target = epList.firstOrNull {
                                it.id == initialEpisodeId ||
                                    initialEpisodeId in com.example.data.identity.EpisodeSourceEpisodeIds.read(it)
                            } ?: epList.first()
                            val matchesRequestedEpisode = target.id == initialEpisodeId ||
                                initialEpisodeId in com.example.data.identity.EpisodeSourceEpisodeIds.read(target)
                            _currentEpisode.value = target
                            _selectedSeason.value = target.seasonNumber
                            startPlaybackForEpisode(
                                target,
                                preferredSourceId = if (matchesRequestedEpisode) initialPreferredSourceId else null
                            )
                        }
                    } else if (!isMovieTarget && epList.isNotEmpty()) {
                        val current = _currentEpisode.value
                        if (current != null && current.seasonNumber > 0) {
                            val currentSeasonEpisodes = epList.filter { it.seasonNumber == current.seasonNumber }
                            preResolveManager.slideWindow(animeId, current, currentSeasonEpisodes, isMovie = false)
                        }
                    }
                }
        }

        var lastWatchTimeTrackMs = System.currentTimeMillis()
        var lastCloudSyncMs = 0L
        var lastCloudSyncEpisodeId: String? = null
        val completedCloudSyncEpisodeIds = mutableSetOf<String>()

        // Save playback locally at the player cadence, but bound cloud writes to one/minute.
        playerManager.onPositionUpdate = { session, posMs, durMs ->
            val ep = allEpisodes.value.firstOrNull {
                it.id == session.episodeId && it.seasonNumber == session.seasonNumber
            } ?: _currentEpisode.value?.takeIf { it.id == session.episodeId }
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
                    val resolvedType = if (com.example.data.streaming.ContentIdentityHelper.isMovie(anime) || ep.seasonNumber == 0) {
                        "MOVIE"
                    } else {
                        anime?.format ?: "ANIME"
                    }
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
                        mediaType = resolvedType
                    )

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
                                completed = isCompleted,
                                animeTitle = anime?.preferredTitle ?: anime?.titleEnglish,
                                posterUrl = anime?.posterUrl,
                                mediaType = resolvedType
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
        val normPreferredSource = com.example.data.repository.SourceIdentityValidator.normalizeId(preferredSourceId)
            .takeIf { it.isNotEmpty() }
        if (_currentEpisode.value?.id == episode.id &&
            (normPreferredSource == null || com.example.data.repository.SourceIdentityValidator.normalizedIdsEqual(normPreferredSource, activeSourceId)) &&
            playbackResolutionJob?.isActive == true) return
        if (_currentEpisode.value?.id == episode.id &&
            (normPreferredSource == null || com.example.data.repository.SourceIdentityValidator.normalizedIdsEqual(normPreferredSource, activeSourceId)) &&
            !_isResolving.value &&
            playerManager.errorMessage.value == null &&
            !playerManager.isPlaybackEnded.value &&
            playerManager.exoPlayerInstance.value != null) return
        val requestGeneration = ++playbackGeneration
        sessionRecoveryMarkers.clear()
        recoveryCoordinator.beginGeneration(requestGeneration)
        playbackResolutionJob?.cancel()
        _currentEpisode.value = episode
        _selectedSeason.value = episode.seasonNumber
        _availableSources.value = emptyList()
        _activeSource.value = null
        activeDirectData = null
        activeSourceId = null
        playerManager.clearTrackSelections()
        playerManager.clearErrorState()
        _isResolving.value = true
        _resolutionStage.value = ResolutionStage.FINDING_SOURCES
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
            com.example.data.repository.EpisodeResolutionCoordinator.beginForegroundResolution()
            try {
                val db = AppDatabase.getInstance(getApplication())

                // 1. Check if episode is downloaded for offline playback
                val download = db.downloadDao().getDownload(episode.id)
                if (requestGeneration != playbackGeneration) return@launch
                if (download != null && download.downloadStatus == "COMPLETED" && download.localFilePath != null) {
                    val file = java.io.File(download.localFilePath)
                    if (file.exists() && file.length() > 0) {
                        _resolutionStage.value = ResolutionStage.STARTING_PLAYBACK
                        _isResolving.value = false
                        _resolutionStage.value = ResolutionStage.IDLE
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
                            providerName = "Offline",
                            sessionIdentity = PlayerManager.PlaybackSessionIdentity(animeId, episode.seasonNumber, episode.id, requestGeneration)
                        )
                        return@launch
                    }
                }

                // 2. Online cache-first playback flow (Memory -> Room metadata -> Cloudflare D1 -> Backend Discovery & Direct Resolve)
                val anime = db.animeDao().getAnimeById(animeId) ?: animeEntry.value
                if (requestGeneration != playbackGeneration) return@launch
                val isMovieTarget = com.example.data.streaming.ContentIdentityHelper.isMovie(anime) ||
                    episode.seasonNumber == 0 || episode.id.startsWith("movie_")

                // 3. Fast-Path: Check pre-resolve cache BEFORE sliding window so prepared or in-flight work is reused
                if (normPreferredSource == null && !isMovieTarget) {
                    val prepared = preResolveManager.getPreparedSource(animeId, episode)
                    if (requestGeneration != playbackGeneration) return@launch
                    if (prepared != null) {
                        _resolutionStage.value = ResolutionStage.STARTING_PLAYBACK
                        val lastSavedPos = try {
                            startPositionOverrideMs ?: db.playbackHistoryDao().getHistoryForEpisode(animeId, episode.id)?.positionMs ?: 0L
                        } catch (_: Exception) { startPositionOverrideMs ?: 0L }
                        if (requestGeneration != playbackGeneration) return@launch
                        val preparedCandidates = prepared.candidates.ifEmpty { listOf(prepared.source) }
                        _activeSource.value = prepared.source
                        _availableSources.value = preparedCandidates
                        activeDirectData = prepared.directData
                        activeSourceId = prepared.source.id
                        _isResolving.value = false
                        _resolutionStage.value = ResolutionStage.IDLE

                        playerManager.initializePlayer(
                            directData = prepared.directData,
                            startPositionMs = lastSavedPos,
                            onPlaybackFailed = null,
                            onRetryNeeded = {},
                            onFailureSignal = { reason ->
                                handlePlaybackFailureSignal(
                                    targetAnimeId = animeId,
                                    episode = episode,
                                    source = prepared.source,
                                    candidates = preparedCandidates,
                                    candidateIndex = 0,
                                    currentDirectData = prepared.directData,
                                    resolvedFromCache = true,
                                    lastSavedPos = lastSavedPos,
                                    requestGeneration = requestGeneration,
                                    reason = reason,
                                    discardPrepared = true
                                )
                            },
                            providerName = prepared.source.displayProviderName,
                            sessionIdentity = PlayerManager.PlaybackSessionIdentity(animeId, episode.seasonNumber, episode.id, requestGeneration),
                            sourceQuality = prepared.source.quality,
                            resolveDurationMs = 0L
                        )
                        preResolveManager.slideWindow(animeId, episode, allEpisodes.value, isMovie = false)
                        return@launch
                    }
                }

                // Cache-first resolution: Memory -> Room -> D1/Firestore -> Bounded Concurrent Resolve
                // Note: Called with isForeground = true BEFORE slideWindow so any active background resolution
                // for this episode is promoted to foreground priority instead of cancelled.
                _resolutionStage.value = ResolutionStage.RESOLVING_PLAYBACK
                val sharedResult = repository.resolveVerifiedPlaybackForEpisode(
                    animeId, episode, normPreferredSource, isForeground = true
                )
                val sharedResolution = sharedResult.getOrNull()
                if (sharedResolution != null) {
                    _resolutionStage.value = ResolutionStage.VALIDATING_SOURCE
                    // Align resolved source at index 0 so valid preResolved stream is used directly without discarding
                    val alignedCandidates = listOf(sharedResolution.source) +
                        sharedResolution.candidates.filterNot {
                            com.example.data.repository.SourceIdentityValidator.normalizedIdsEqual(it.id, sharedResolution.source.id)
                        }
                    resolveWithFallback(
                        animeId,
                        episode,
                        alignedCandidates,
                        0,
                        prioritize = false,
                        startPositionOverrideMs = startPositionOverrideMs,
                        requestGeneration = requestGeneration,
                        preResolved = sharedResolution
                    )
                    if (requestGeneration == playbackGeneration && !isMovieTarget) {
                        preResolveManager.slideWindow(animeId, episode, allEpisodes.value, isMovie = false)
                    }
                    return@launch
                }

                _resolutionStage.value = ResolutionStage.FINDING_SOURCES
                val discoveryRes = repository.discoverStreamsForScope(
                    animeId, episode.seasonNumber, episode.episodeNumber, episode.id, forceRefresh = true, allowBypassBackoff = true
                )
                if (requestGeneration != playbackGeneration) return@launch
                val sources = discoveryRes.getOrNull()?.sources.orEmpty()
                if (sources.isEmpty()) {
                    _resolutionError.value = UserFacingErrorMapper.message(
                        sharedResult.exceptionOrNull() ?: discoveryRes.exceptionOrNull(),
                        "No playable sources found for this title."
                    )
                    _isResolving.value = false
                    _resolutionStage.value = ResolutionStage.IDLE
                    playerManager.clearErrorState()
                    return@launch
                }

                val candidates = if (normPreferredSource != null) {
                    val selected = sources.firstOrNull {
                        com.example.data.repository.SourceIdentityValidator.normalizedIdsEqual(it.id, normPreferredSource)
                    }
                    if (selected == null) {
                        _resolutionError.value = "The selected source is not available."
                        _isResolving.value = false
                        _resolutionStage.value = ResolutionStage.IDLE
                        playerManager.clearErrorState()
                        return@launch
                    }
                    listOf(selected) + sources.filterNot {
                        com.example.data.repository.SourceIdentityValidator.normalizedIdsEqual(it.id, normPreferredSource)
                    }
                } else {
                    sources
                }

                resolveWithFallback(
                    animeId,
                    episode,
                    candidates,
                    0,
                    prioritize = normPreferredSource == null,
                    startPositionOverrideMs = startPositionOverrideMs,
                    requestGeneration = requestGeneration
                )
                if (requestGeneration == playbackGeneration && !isMovieTarget && _resolutionError.value == null) {
                    preResolveManager.slideWindow(animeId, episode, allEpisodes.value, isMovie = false)
                }
            } finally {
                com.example.data.repository.EpisodeResolutionCoordinator.endForegroundResolution()
            }
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

    private fun isTransientNetworkFailure(error: Throwable?): Boolean =
        generateSequence(error) { it.cause }.any { cause ->
            cause is java.io.IOException || cause is java.net.SocketTimeoutException ||
                Regex("""HTTP (408|429|5\d\d)""").containsMatchIn(cause.message.orEmpty()) ||
                cause.message.orEmpty().contains("retry is backed off", ignoreCase = true)
        }

    private fun handlePlaybackFailureSignal(
        targetAnimeId: String,
        episode: EpisodeEntry,
        source: ProphecySourceDto,
        candidates: List<ProphecySourceDto>,
        candidateIndex: Int,
        currentDirectData: DirectPlayData,
        resolvedFromCache: Boolean,
        lastSavedPos: Long,
        requestGeneration: Long,
        reason: PlaybackFailureReason,
        discardPrepared: Boolean
    ) {
        if (requestGeneration != playbackGeneration) return
        val providerId = com.example.data.streaming.ContentIdentityHelper.deterministicProviderId(source.label)
        val recoveryKey = PlaybackRecoveryKey(
            animeId = targetAnimeId,
            episodeId = episode.id,
            sourceId = source.id,
            playbackGeneration = requestGeneration
        )
        val sameSourceRetryPendingEscalation =
            (sessionRecoveryMarkers.contains("stall:${providerId}:${source.id}") ||
                sessionRecoveryMarkers.contains("cache_refresh:${providerId}:${source.id}")) &&
                !sessionRecoveryMarkers.contains("switched:${providerId}:${source.id}")
        recoveryCoordinator.triggerRecovery(
            key = recoveryKey,
            reason = reason,
            allowRepeatForSameSource = sameSourceRetryPendingEscalation,
            onBudgetExhausted = {
                if (requestGeneration == playbackGeneration) {
                    _resolutionError.value =
                        "All available sources failed after bounded recovery attempts. Select Retry or another source."
                    _isResolving.value = false
                    _resolutionStage.value = ResolutionStage.IDLE
                    playerManager.clearErrorState()
                }
            }
        ) {
            if (requestGeneration != playbackGeneration) return@triggerRecovery
            if (discardPrepared) {
                preResolveManager.discardPreparedSource(targetAnimeId, episode)
            }

            // Never invalidate D1 URLs or force source rediscovery for decoder/codec errors.
            if (reason == PlaybackFailureReason.DECODER_OR_UNSUPPORTED) {
                if (candidateIndex + 1 < candidates.size) {
                    _isResolving.value = true
                    _resolutionStage.value = ResolutionStage.RESOLVING_PLAYBACK
                    resolveWithFallback(
                        targetAnimeId = targetAnimeId,
                        episode = episode,
                        candidates = candidates,
                        startIndex = candidateIndex + 1,
                        startPositionOverrideMs = lastSavedPos,
                        requestGeneration = requestGeneration,
                        isRecoveryAttempt = true
                    )
                } else {
                    val existingErr = playerManager.errorMessage.value
                    _resolutionError.value = existingErr ?: "The media format or codec is not supported on this device."
                    _isResolving.value = false
                    _resolutionStage.value = ResolutionStage.IDLE
                    playerManager.clearErrorState()
                }
                return@triggerRecovery
            }

            // Sustained zero-progress stall: first bounded attempt re-prepares the same URL
            // without wiping D1 cache or forcing discovery. Subsequent failure switches candidate.
            if (reason == PlaybackFailureReason.STALL_NO_PROGRESS &&
                sessionRecoveryMarkers.add("stall:${providerId}:${source.id}")
            ) {
                val resumePos = playerManager.currentPosition.value.takeIf { it > 0L } ?: lastSavedPos
                playerManager.initializePlayer(
                    directData = currentDirectData,
                    startPositionMs = resumePos,
                    onPlaybackFailed = null,
                    onRetryNeeded = {},
                    onFailureSignal = { nextReason ->
                        handlePlaybackFailureSignal(
                            targetAnimeId = targetAnimeId,
                            episode = episode,
                            source = source,
                            candidates = candidates,
                            candidateIndex = candidateIndex,
                            currentDirectData = currentDirectData,
                            resolvedFromCache = resolvedFromCache,
                            lastSavedPos = resumePos,
                            requestGeneration = requestGeneration,
                            reason = nextReason,
                            discardPrepared = false
                        )
                    },
                    providerName = source.displayProviderName,
                    sessionIdentity = PlayerManager.PlaybackSessionIdentity(
                        targetAnimeId, episode.seasonNumber, episode.id, requestGeneration
                    ),
                    sourceQuality = source.quality,
                    resolveDurationMs = 0L,
                    isRecoveryAttempt = true
                )
                return@triggerRecovery
            }

            // Recoverable HTTP/expired/network failure on a cached URL: invalidate only that source
            // and re-resolve once before falling back to alternative candidates.
            if (resolvedFromCache &&
                (reason == PlaybackFailureReason.HTTP_EXPIRED_OR_UNAUTHORIZED ||
                    reason == PlaybackFailureReason.TRANSIENT_NETWORK_ERROR) &&
                sessionRecoveryMarkers.add("cache_refresh:${providerId}:${source.id}")
            ) {
                repository.invalidateFailedCachedPlayback(
                    targetAnimeId, episode.seasonNumber, episode.episodeNumber, source.id, providerId
                )
                _isResolving.value = true
                _resolutionStage.value = ResolutionStage.RESOLVING_PLAYBACK
                resolveWithFallback(
                    targetAnimeId = targetAnimeId,
                    episode = episode,
                    candidates = candidates,
                    startIndex = candidateIndex,
                    startPositionOverrideMs = lastSavedPos,
                    requestGeneration = requestGeneration,
                    forceFresh = true,
                    isRecoveryAttempt = true
                )
                return@triggerRecovery
            }

            sessionRecoveryMarkers.add("switched:${providerId}:${source.id}")
            if (candidateIndex + 1 < candidates.size) {
                _isResolving.value = true
                _resolutionStage.value = ResolutionStage.RESOLVING_PLAYBACK
                resolveWithFallback(
                    targetAnimeId = targetAnimeId,
                    episode = episode,
                    candidates = candidates,
                    startIndex = candidateIndex + 1,
                    startPositionOverrideMs = lastSavedPos,
                    requestGeneration = requestGeneration,
                    isRecoveryAttempt = true
                )
                return@triggerRecovery
            }

            // Only force source rediscovery once per generation after existing candidates are exhausted.
            if (sessionRecoveryMarkers.add("rediscover:${requestGeneration}")) {
                _isResolving.value = true
                _resolutionStage.value = ResolutionStage.FINDING_SOURCES
                val refreshedSources = repository.discoverStreamsForScope(
                    targetAnimeId, episode.seasonNumber, episode.episodeNumber, episode.id,
                    forceRefresh = true,
                    allowBypassBackoff = true
                ).getOrNull()?.sources ?: emptyList()
                if (requestGeneration != playbackGeneration) return@triggerRecovery
                if (refreshedSources.isNotEmpty()) {
                    resolveWithFallback(
                        targetAnimeId = targetAnimeId,
                        episode = episode,
                        candidates = refreshedSources,
                        startIndex = 0,
                        startPositionOverrideMs = lastSavedPos,
                        requestGeneration = requestGeneration,
                        forceFresh = true,
                        isRecoveryAttempt = true
                    )
                    return@triggerRecovery
                }
            }

            _resolutionError.value = "All available sources failed to start playback. Select Retry to try again."
            _isResolving.value = false
            _resolutionStage.value = ResolutionStage.IDLE
            playerManager.clearErrorState()
        }
    }

    private suspend fun resolveWithFallback(
        targetAnimeId: String,
        episode: EpisodeEntry,
        candidates: List<ProphecySourceDto>,
        startIndex: Int = 0,
        prioritize: Boolean = false,
        startPositionOverrideMs: Long? = null,
        requestGeneration: Long,
        preResolved: com.example.data.repository.VerifiedEpisodePlayback? = null,
        forceFresh: Boolean = false,
        isRecoveryAttempt: Boolean = false
    ) {
        if (requestGeneration != playbackGeneration) return
        val orderedCandidates = if (prioritize && startIndex == 0) prioritizeSources(candidates) else candidates
        var lastError: String? = null

        for (i in startIndex until orderedCandidates.size) {
            if (requestGeneration != playbackGeneration) return
            val source = com.example.data.repository.SourceIdentityValidator.normalizeDto(orderedCandidates[i])
            if (!source.hasUsableSourceId) {
                lastError = "Source candidate has no usable source ID."
                continue
            }
            val providerId = com.example.data.streaming.ContentIdentityHelper.deterministicProviderId(source.label)
            activeSourceId = source.id
            _activeSource.value = source

            var resolvedDurationMs = 0L
            var resolvedFromCache = false
            val matchesPreResolved = i == 0 && !forceFresh && preResolved != null &&
                com.example.data.repository.SourceIdentityValidator.normalizedIdsEqual(preResolved.source.id, source.id) &&
                com.example.data.repository.SourceIdentityValidator.normalizedIdsEqual(
                    com.example.data.streaming.ContentIdentityHelper.deterministicProviderId(preResolved.source.label),
                    providerId
                )
            _resolutionStage.value = if (matchesPreResolved) ResolutionStage.VALIDATING_SOURCE else ResolutionStage.RESOLVING_PLAYBACK

            var playRes = if (matchesPreResolved && preResolved != null) {
                resolvedDurationMs = preResolved.resolveDurationMs
                resolvedFromCache = preResolved.cacheTier != "BACKEND_RESOLVE"
                Result.success(preResolved.directData)
            } else {
                repository.resolveVerifiedPlaybackForEpisode(
                    targetAnimeId,
                    episode,
                    preferredSourceId = source.id,
                    forceResolveFresh = forceFresh,
                    preferredProviderId = providerId
                ).map {
                    resolvedDurationMs = it.resolveDurationMs
                    resolvedFromCache = it.cacheTier != "BACKEND_RESOLVE"
                    it.directData
                }
            }

            // Only retry inline when this is the sole remaining candidate; otherwise fall through immediately
            val isLastCandidate = i >= orderedCandidates.lastIndex
            if (playRes.isFailure && isLastCandidate && isTransientNetworkFailure(playRes.exceptionOrNull())) {
                _isResolving.value = true
                _resolutionStage.value = ResolutionStage.RESOLVING_PLAYBACK
                delay(350L)
                if (requestGeneration != playbackGeneration) return
                playRes = repository.resolveVerifiedPlaybackForEpisode(
                    targetAnimeId,
                    episode,
                    preferredSourceId = source.id,
                    forceResolveFresh = true,
                    preferredProviderId = providerId
                ).map {
                    resolvedDurationMs = it.resolveDurationMs
                    resolvedFromCache = false
                    it.directData
                }
            }
            if (requestGeneration != playbackGeneration) return
            if (playRes.isSuccess && playRes.getOrNull() != null) {
                val directData = playRes.getOrNull()!!
                _resolutionStage.value = ResolutionStage.VALIDATING_SOURCE
                val validation = com.example.data.repository.SourceIdentityValidator.validateDirectPlayData(directData, source.id)
                if (validation is com.example.data.repository.SourceValidationResult.Invalid) {
                    com.example.data.repository.SourceIdentityValidator.recordDiagnostic(
                        code = validation.code,
                        scopeKey = "${targetAnimeId}:s${episode.seasonNumber}:e${episode.episodeNumber}",
                        sourceId = source.id,
                        providerLabel = source.displayProviderName,
                        detail = validation.reason
                    )
                    lastError = validation.reason
                    continue
                }
                val normalizedDirectData = directData.copy(
                    sourceId = com.example.data.repository.SourceIdentityValidator.normalizeId(directData.sourceId)
                )
                activeDirectData = normalizedDirectData

                val lastSavedPos = try {
                    startPositionOverrideMs
                        ?: AppDatabase.getInstance(getApplication()).playbackHistoryDao()
                            .getHistoryForEpisode(targetAnimeId, episode.id)?.positionMs
                        ?: 0L
                } catch (e: Exception) { startPositionOverrideMs ?: 0L }
                if (requestGeneration != playbackGeneration) return

                _availableSources.value = orderedCandidates
                _resolutionStage.value = ResolutionStage.STARTING_PLAYBACK
                _isResolving.value = false
                _resolutionStage.value = ResolutionStage.IDLE

                playerManager.initializePlayer(
                    directData = normalizedDirectData,
                    startPositionMs = lastSavedPos,
                    onPlaybackFailed = null,
                    onRetryNeeded = {},
                    onFailureSignal = { reason ->
                        handlePlaybackFailureSignal(
                            targetAnimeId = targetAnimeId,
                            episode = episode,
                            source = source,
                            candidates = orderedCandidates,
                            candidateIndex = i,
                            currentDirectData = normalizedDirectData,
                            resolvedFromCache = resolvedFromCache,
                            lastSavedPos = lastSavedPos,
                            requestGeneration = requestGeneration,
                            reason = reason,
                            discardPrepared = false
                        )
                    },
                    providerName = source.displayProviderName,
                    sessionIdentity = PlayerManager.PlaybackSessionIdentity(
                        targetAnimeId, episode.seasonNumber, episode.id, requestGeneration
                    ),
                    sourceQuality = source.quality,
                    resolveDurationMs = resolvedDurationMs,
                    isRecoveryAttempt = isRecoveryAttempt
                )

                return
            } else {
                lastError = UserFacingErrorMapper.message(playRes.exceptionOrNull(), "No playable source available right now.")
            }
        }

        _resolutionError.value = lastError ?: "No playable source available right now."
        _isResolving.value = false
        _resolutionStage.value = ResolutionStage.IDLE
        playerManager.clearErrorState()
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
        recoveryCoordinator.cancelAll()
        playbackResolutionJob?.cancel()
        preResolveManager.clear()
        playerManager.release()
    }
}

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AnimeRepository.getInstance(application)

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
    private val repository = AnimeRepository.getInstance(application)
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
