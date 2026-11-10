package com.example.data.repository

/** Guarantees that a UI loading flag is cleared on success, failure, and cancellation. */
internal suspend fun <T> withLoadingFlag(
    setLoading: (Boolean) -> Unit,
    operation: suspend () -> T
): T {
    setLoading(true)
    try {
        return operation()
    } finally {
        setLoading(false)
    }
}

enum class EpisodeCatalogState {
    UNKNOWN,
    PARTIAL,
    COMPLETE,
    CONFIRMED_EMPTY,
    FAILED
}

internal fun resolveEpisodeCatalogState(
    snapshotComplete: Boolean,
    cachedEpisodeCount: Int,
    backendIdKnown: Boolean,
    detailsEndpointSucceeded: Boolean,
    episodeListEndpointSucceeded: Boolean
): EpisodeCatalogState = when {
    snapshotComplete && cachedEpisodeCount == 0 -> EpisodeCatalogState.CONFIRMED_EMPTY
    snapshotComplete -> EpisodeCatalogState.COMPLETE
    backendIdKnown && !detailsEndpointSucceeded && !episodeListEndpointSucceeded -> EpisodeCatalogState.FAILED
    cachedEpisodeCount > 0 -> EpisodeCatalogState.PARTIAL
    detailsEndpointSucceeded || episodeListEndpointSucceeded -> EpisodeCatalogState.UNKNOWN
    backendIdKnown -> EpisodeCatalogState.FAILED
    else -> EpisodeCatalogState.UNKNOWN
}

data class EpisodeCatalogRefreshStatus(
    val state: EpisodeCatalogState,
    val detailsEndpointSucceeded: Boolean,
    val episodeListEndpointSucceeded: Boolean,
    val errorMessage: String? = null,
    val currentRefreshEpisodeIds: Set<String> = emptySet()
)
