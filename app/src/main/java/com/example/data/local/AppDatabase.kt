package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        AnimeEntry::class,
        AnimeAlias::class,
        CatalogMapping::class,
        SeasonEntry::class,
        EpisodeEntry::class,
        SearchQuery::class,
        SearchResultRef::class,
        WatchlistEntry::class,
        PlaybackHistory::class,
        UserPreference::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun animeDao(): AnimeDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun searchDao(): SearchDao
    abstract fun watchlistDao(): WatchlistDao
    abstract fun playbackHistoryDao(): PlaybackHistoryDao
    abstract fun userPreferenceDao(): UserPreferenceDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "prophecyverse.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
