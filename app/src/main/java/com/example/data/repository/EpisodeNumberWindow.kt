package com.example.data.repository

/** Number-based episode window; bounds are inclusive at the start and exclusive at the end. */
data class EpisodeNumberWindow(val firstEpisode: Int, val episodeCount: Int) {
    init {
        require(firstEpisode >= 1) { "Episode numbers start at 1 or higher" }
        require(episodeCount >= 1) { "Episode window must contain at least one episode number" }
    }

    val episodeLimitExclusive: Int
        get() = firstEpisode + episodeCount

    val lastEpisodeInclusive: Int
        get() = episodeLimitExclusive - 1

    fun contains(originalEpisodeNumber: Int): Boolean =
        originalEpisodeNumber >= firstEpisode && originalEpisodeNumber < episodeLimitExclusive

    companion object {
        fun initial(): EpisodeNumberWindow = EpisodeNumberWindow(firstEpisode = 1, episodeCount = 30)

        /** Current episode plus the following 21 numbers (e.g. 99 through 120). */
        fun aroundSelected(episodeNumber: Int): EpisodeNumberWindow =
            EpisodeNumberWindow(firstEpisode = episodeNumber.coerceAtLeast(1), episodeCount = 22)
    }
}
