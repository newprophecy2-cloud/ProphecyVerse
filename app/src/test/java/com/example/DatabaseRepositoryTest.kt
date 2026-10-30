package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AnimeEntry
import com.example.data.local.AppDatabase
import com.example.data.local.EpisodeEntry
import com.example.data.local.PlaybackHistory
import com.example.data.local.SeasonEntry
import com.example.data.local.WatchlistEntry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DatabaseRepositoryTest {

    private lateinit var db: AppDatabase

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun databaseFirst_insertAndObserveAnime() = runBlocking {
        val entry = AnimeEntry(
            id = "test_backend_1",
            backendAnimeId = "test_backend_1",
            aniListId = 101,
            preferredTitle = "Jujutsu Kaisen",
            isPlayable = true,
            isTrending = true,
            score = 8.5
        )

        db.animeDao().upsertAnime(entry)

        val trending = db.animeDao().observeTrending().first()
        assertEquals(1, trending.size)
        assertEquals("Jujutsu Kaisen", trending[0].preferredTitle)
        assertTrue(trending[0].isPlayable)
    }

    @Test
    fun seasonsAndEpisodes_persistenceAndOrdering() = runBlocking {
        val animeId = "anime_test_1"
        db.episodeDao().upsertSeasons(
            listOf(
                SeasonEntry(id = "${animeId}_s1", animeId = animeId, seasonNumber = 1, title = "Season 1")
            )
        )

        db.episodeDao().upsertEpisodes(
            listOf(
                EpisodeEntry(id = "ep_1", animeId = animeId, seasonNumber = 1, episodeNumber = 1, title = "Episode 1"),
                EpisodeEntry(id = "ep_2", animeId = animeId, seasonNumber = 1, episodeNumber = 2, title = "Episode 2")
            )
        )

        val episodes = db.episodeDao().observeEpisodes(animeId, 1).first()
        assertEquals(2, episodes.size)
        assertEquals(1, episodes[0].episodeNumber)
        assertEquals(2, episodes[1].episodeNumber)
    }

    @Test
    fun watchlistAndHistory_persistence() = runBlocking {
        val animeId = "anime_999"
        val episodeId = "ep_999_1"

        db.watchlistDao().upsertWatchlistEntry(
            WatchlistEntry(animeId = animeId, status = "WATCHING", isFavorite = true)
        )
        db.playbackHistoryDao().upsertHistory(
            PlaybackHistory(
                id = "${animeId}_${episodeId}",
                animeId = animeId,
                episodeId = episodeId,
                seasonNumber = 1,
                episodeNumber = 1,
                positionMs = 150000,
                durationMs = 1440000
            )
        )

        val watchlist = db.watchlistDao().observeAllWatchlist().first()
        assertEquals(1, watchlist.size)
        assertTrue(watchlist[0].isFavorite)

        val continueWatching = db.playbackHistoryDao().observeContinueWatching().first()
        assertEquals(1, continueWatching.size)
        assertEquals(150000L, continueWatching[0].positionMs)
    }
}
