package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface StreamCacheDao {
    @Query("SELECT * FROM stream_containers WHERE scopeKey = :scopeKey LIMIT 1")
    suspend fun getContainer(scopeKey: String): StreamContainerEntity?

    @Query("SELECT * FROM stream_containers WHERE scopeKey = :scopeKey LIMIT 1")
    fun observeContainer(scopeKey: String): Flow<StreamContainerEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertContainer(container: StreamContainerEntity)

    @Query("SELECT * FROM stream_seasons WHERE seasonDocKey = :seasonDocKey LIMIT 1")
    suspend fun getSeason(seasonDocKey: String): StreamSeasonEntity?

    @Query("SELECT * FROM stream_seasons WHERE contentKey = :contentKey AND deletedAt IS NULL ORDER BY seasonNumber ASC")
    suspend fun getActiveSeasons(contentKey: String): List<StreamSeasonEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSeason(season: StreamSeasonEntity)

    @Query("SELECT * FROM stream_episodes WHERE episodeDocKey = :episodeDocKey LIMIT 1")
    suspend fun getEpisode(episodeDocKey: String): StreamEpisodeEntity?

    @Query("SELECT * FROM stream_episodes WHERE contentKey = :contentKey AND deletedAt IS NULL ORDER BY seasonNumber ASC, episodeNumber ASC")
    suspend fun getActiveEpisodes(contentKey: String): List<StreamEpisodeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEpisode(episode: StreamEpisodeEntity)

    @Query("SELECT * FROM stream_providers WHERE scopeKey = :scopeKey AND status != 'REMOVED' AND deletedAt IS NULL ORDER BY providerName ASC")
    suspend fun getActiveProviders(scopeKey: String): List<StreamProviderEntity>

    @Query("SELECT * FROM stream_providers WHERE scopeKey = :scopeKey")
    suspend fun getAllProviders(scopeKey: String): List<StreamProviderEntity>

    @Query("SELECT * FROM stream_providers WHERE scopeKey = :scopeKey AND status != 'REMOVED' AND deletedAt IS NULL ORDER BY providerName ASC")
    fun observeActiveProviders(scopeKey: String): Flow<List<StreamProviderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProviders(providers: List<StreamProviderEntity>)

    @Query("SELECT * FROM stream_sources WHERE scopeKey = :scopeKey AND status != 'REMOVED' AND deletedAt IS NULL ORDER BY providerId ASC, sourceId ASC")
    suspend fun getActiveSources(scopeKey: String): List<StreamSourceEntity>

    @Query("SELECT * FROM stream_sources WHERE scopeKey = :scopeKey")
    suspend fun getAllSources(scopeKey: String): List<StreamSourceEntity>

    @Query("SELECT * FROM stream_sources WHERE scopeKey = :scopeKey AND status != 'REMOVED' AND deletedAt IS NULL ORDER BY providerId ASC, sourceId ASC")
    fun observeActiveSources(scopeKey: String): Flow<List<StreamSourceEntity>>

    @Query("SELECT * FROM stream_sources WHERE sourceId = :sourceId AND status != 'REMOVED' AND deletedAt IS NULL ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getLatestActiveSourceBySourceId(sourceId: String): StreamSourceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSource(source: StreamSourceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSources(sources: List<StreamSourceEntity>)

    @Query("UPDATE stream_sources SET status = 'REMOVED', deletedAt = :deletedAt, updatedAt = :deletedAt, version = :version WHERE sourceDocKey = :sourceDocKey")
    suspend fun softDeleteSource(sourceDocKey: String, deletedAt: Long, version: Long)

    @Query("DELETE FROM stream_sources WHERE sourceDocKey IN (:docKeys)")
    suspend fun deleteSourcesByKeys(docKeys: List<String>)

    @Query("DELETE FROM stream_providers WHERE providerDocKey IN (:docKeys)")
    suspend fun deleteProvidersByKeys(docKeys: List<String>)

    @Query("UPDATE stream_providers SET status = 'REMOVED', deletedAt = :deletedAt, updatedAt = :deletedAt, version = :version WHERE providerDocKey = :providerDocKey")
    suspend fun softDeleteProvider(providerDocKey: String, deletedAt: Long, version: Long)

    @Query("UPDATE stream_episodes SET discoveryStatus = 'REMOVED', deletedAt = :deletedAt, updatedAt = :deletedAt, version = :version WHERE episodeDocKey = :episodeDocKey")
    suspend fun softDeleteEpisode(episodeDocKey: String, deletedAt: Long, version: Long)

    @Query("UPDATE stream_seasons SET discoveryStatus = 'REMOVED', deletedAt = :deletedAt, updatedAt = :deletedAt, version = :version WHERE seasonDocKey = :seasonDocKey")
    suspend fun softDeleteSeason(seasonDocKey: String, deletedAt: Long, version: Long)

    @Query("DELETE FROM stream_sources WHERE scopeKey = :scopeKey")
    suspend fun deleteSourcesForScope(scopeKey: String)

    @Query("DELETE FROM stream_providers WHERE scopeKey = :scopeKey")
    suspend fun deleteProvidersForScope(scopeKey: String)

    /**
     * Synchronizes remote/discovered streaming providers and sources for an episode/movie scope
     * transactionally, preserving existing valid stream_url values when remote/partial data
     * omits URLs or fails, and applying soft-deletions ("REMOVED") so removed records never
     * resurrect from local cache.
     */
    @Transaction
    suspend fun syncScopeRecords(
        scopeKey: String,
        incomingProviders: List<StreamProviderEntity>,
        incomingSources: List<StreamSourceEntity>,
        authoritativeComplete: Boolean
    ) {
        val existingProviders = getAllProviders(scopeKey).associateBy { it.providerDocKey }
        val existingSources = getAllSources(scopeKey).associateBy { it.sourceDocKey }
        val now = System.currentTimeMillis()

        val mergedProviders = incomingProviders.mapNotNull { incoming ->
            val prev = existingProviders[incoming.providerDocKey]
            if (prev != null && incoming.version < prev.version) return@mapNotNull prev
            if (incoming.status == "REMOVED" || incoming.deletedAt != null) {
                incoming.copy(status = "REMOVED", deletedAt = incoming.deletedAt ?: now)
            } else {
                incoming
            }
        }
        val incomingProviderKeys = mergedProviders.map { it.providerDocKey }.toSet()
        val extraProviders = if (authoritativeComplete) {
            existingProviders.values
                .filter { it.providerDocKey !in incomingProviderKeys }
                .map { it.copy(status = "REMOVED", deletedAt = it.deletedAt ?: now, updatedAt = now, version = it.version + 1) }
        } else {
            emptyList()
        }

        val mergedSources = incomingSources.mapNotNull { incoming ->
            val prev = existingSources[incoming.sourceDocKey]
            if (prev != null && incoming.version < prev.version) return@mapNotNull prev
            if (incoming.status == "REMOVED" || incoming.deletedAt != null) {
                incoming.copy(status = "REMOVED", deletedAt = incoming.deletedAt ?: now)
            } else if (incoming.streamUrl.isNullOrBlank() && prev?.streamUrl?.isNotBlank() == true && prev.status != "REMOVED") {
                // Never wipe a previously valid cached stream_url when incoming metadata lacks a URL
                incoming.copy(
                    streamUrl = prev.streamUrl,
                    urlType = prev.urlType,
                    contentType = incoming.contentType ?: prev.contentType,
                    safeHeadersJson = prev.safeHeadersJson,
                    expiresAt = incoming.expiresAt ?: prev.expiresAt,
                    lastVerifiedAt = prev.lastVerifiedAt,
                    lastPlaybackSuccessAt = prev.lastPlaybackSuccessAt
                )
            } else {
                incoming
            }
        }
        val incomingSourceKeys = mergedSources.map { it.sourceDocKey }.toSet()
        val extraSources = if (authoritativeComplete) {
            existingSources.values
                .filter { it.sourceDocKey !in incomingSourceKeys }
                .map { it.copy(status = "REMOVED", deletedAt = it.deletedAt ?: now, updatedAt = now, version = it.version + 1) }
        } else {
            emptyList()
        }

        val providersToPersist = (mergedProviders + extraProviders).distinctBy { it.providerDocKey }
        val sourcesToPersist = (mergedSources + extraSources).distinctBy { it.sourceDocKey }
        if (providersToPersist.isNotEmpty()) upsertProviders(providersToPersist)
        if (sourcesToPersist.isNotEmpty()) upsertSources(sourcesToPersist)
    }
}
