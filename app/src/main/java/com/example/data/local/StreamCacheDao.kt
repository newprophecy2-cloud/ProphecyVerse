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

    @Query("SELECT * FROM stream_sources WHERE scopeKey = :scopeKey AND TRIM(sourceId) = :sourceId AND status != 'REMOVED' AND deletedAt IS NULL ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getLatestActiveSourceInScope(scopeKey: String, sourceId: String): StreamSourceEntity?

    @Query("UPDATE stream_sources SET streamUrl = NULL, safeHeadersJson = NULL, status = 'MISSING_URL', updatedAt = :updatedAt WHERE scopeKey = :scopeKey AND TRIM(sourceId) = :sourceId AND status != 'REMOVED'")
    suspend fun invalidateCachedStreamUrl(scopeKey: String, sourceId: String, updatedAt: Long)

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
        val existingProvidersList = getAllProviders(scopeKey)
        val existingProviders = existingProvidersList.associateBy { it.providerDocKey }
        val existingSourcesList = getAllSources(scopeKey)
        val existingSources = existingSourcesList.associateBy { it.sourceDocKey }
        val existingByProviderAndTrimmedId = existingSourcesList.groupBy {
            it.providerId to it.sourceId.trim()
        }
        val now = System.currentTimeMillis()
        val metrics = com.example.data.metrics.DatabaseMetricsTracker.getInstance()

        val dedupedIncomingProviders = incomingProviders.distinctBy { it.providerDocKey }
        val mergedProviders = dedupedIncomingProviders.mapNotNull { incoming ->
            val prev = existingProviders[incoming.providerDocKey]
            if (prev != null && incoming.version < prev.version) return@mapNotNull prev
            if (incoming.status == "REMOVED" || incoming.deletedAt != null) {
                incoming.copy(status = "REMOVED", deletedAt = incoming.deletedAt ?: now)
            } else {
                incoming
            }
        }
        val incomingProviderKeys = mergedProviders.mapTo(HashSet(mergedProviders.size)) { it.providerDocKey }
        val extraProviders = if (authoritativeComplete) {
            existingProvidersList
                .filter { it.providerDocKey !in incomingProviderKeys && it.status != "REMOVED" }
                .map { it.copy(status = "REMOVED", deletedAt = it.deletedAt ?: now, updatedAt = now, version = it.version + 1) }
        } else {
            emptyList()
        }

        val dedupedIncomingSources = incomingSources
            .map { src ->
                val trimmedId = src.sourceId.trim()
                if (trimmedId == src.sourceId) src else src.copy(sourceId = trimmedId)
            }
            .distinctBy { it.providerId to it.sourceId }
            .distinctBy { it.sourceDocKey }

        val obsoleteSourceKeys = mutableListOf<String>()
        val mergedSources = dedupedIncomingSources.mapNotNull { incoming ->
            val aliasMatches = existingByProviderAndTrimmedId[incoming.providerId to incoming.sourceId].orEmpty()
            val prev = existingSources[incoming.sourceDocKey]
                ?: aliasMatches.firstOrNull { !it.streamUrl.isNullOrBlank() && it.status != "REMOVED" }
                ?: aliasMatches.firstOrNull()
            aliasMatches.forEach { alias ->
                if (alias.sourceDocKey != incoming.sourceDocKey) {
                    obsoleteSourceKeys += alias.sourceDocKey
                }
            }

            if (prev != null && prev.sourceDocKey == incoming.sourceDocKey && incoming.version < prev.version) {
                return@mapNotNull prev
            }
            if (incoming.status == "REMOVED" || incoming.deletedAt != null) {
                incoming.copy(status = "REMOVED", deletedAt = incoming.deletedAt ?: now)
            } else if (incoming.streamUrl.isNullOrBlank() && prev?.streamUrl?.isNotBlank() == true && prev.status != "REMOVED") {
                // Never wipe a previously valid cached stream_url or playable status when incoming metadata lacks a URL
                incoming.copy(
                    streamUrl = prev.streamUrl,
                    urlType = prev.urlType,
                    contentType = incoming.contentType ?: prev.contentType,
                    safeHeadersJson = prev.safeHeadersJson,
                    expiresAt = incoming.expiresAt ?: prev.expiresAt,
                    status = if (prev.status == "AVAILABLE" || prev.status == "EXPIRED") prev.status else incoming.status,
                    lastVerifiedAt = incoming.lastVerifiedAt ?: prev.lastVerifiedAt,
                    lastPlaybackSuccessAt = incoming.lastPlaybackSuccessAt ?: prev.lastPlaybackSuccessAt
                )
            } else if (prev != null && incoming.streamUrl.isNullOrBlank() && prev.status != "REMOVED") {
                incoming.copy(
                    backendEpisodeId = incoming.backendEpisodeId ?: prev.backendEpisodeId,
                    urlType = incoming.urlType.ifBlank { prev.urlType },
                    contentType = incoming.contentType ?: prev.contentType,
                    quality = incoming.quality ?: prev.quality,
                    codec = incoming.codec ?: prev.codec,
                    audioLanguage = incoming.audioLanguage ?: prev.audioLanguage,
                    downloadable = incoming.downloadable ?: prev.downloadable,
                    sizeBytes = incoming.sizeBytes ?: prev.sizeBytes,
                    expiresAt = incoming.expiresAt ?: prev.expiresAt,
                    lastVerifiedAt = incoming.lastVerifiedAt ?: prev.lastVerifiedAt,
                    lastPlaybackSuccessAt = incoming.lastPlaybackSuccessAt ?: prev.lastPlaybackSuccessAt
                )
            } else {
                incoming
            }
        }
        val incomingSourceKeys = mergedSources.mapTo(HashSet(mergedSources.size)) { it.sourceDocKey }
        val extraSources = if (authoritativeComplete) {
            existingSourcesList
                .filter { it.sourceDocKey !in incomingSourceKeys && it.sourceDocKey !in obsoleteSourceKeys && it.status != "REMOVED" }
                .map { it.copy(status = "REMOVED", deletedAt = it.deletedAt ?: now, updatedAt = now, version = it.version + 1) }
        } else {
            emptyList()
        }

        if (obsoleteSourceKeys.isNotEmpty()) {
            deleteSourcesByKeys(obsoleteSourceKeys.distinct())
            metrics.recordRoomWrite(obsoleteSourceKeys.size.toLong())
        }

        val changedProviders = (mergedProviders + extraProviders)
            .distinctBy { it.providerDocKey }
            .filter { candidate ->
                val prev = existingProviders[candidate.providerDocKey] ?: return@filter true
                prev.scopeKey != candidate.scopeKey ||
                    prev.providerId != candidate.providerId ||
                    prev.providerName != candidate.providerName ||
                    prev.status != candidate.status ||
                    prev.sourceCount != candidate.sourceCount ||
                    prev.deletedAt != candidate.deletedAt
            }

        val changedSources = (mergedSources + extraSources)
            .distinctBy { it.sourceDocKey }
            .filter { candidate ->
                val prev = existingSources[candidate.sourceDocKey] ?: return@filter true
                prev.scopeKey != candidate.scopeKey ||
                    prev.providerId != candidate.providerId ||
                    prev.sourceId != candidate.sourceId ||
                    prev.sourceName != candidate.sourceName ||
                    prev.backendEpisodeId != candidate.backendEpisodeId ||
                    prev.streamUrl != candidate.streamUrl ||
                    prev.urlType != candidate.urlType ||
                    prev.contentType != candidate.contentType ||
                    prev.quality != candidate.quality ||
                    prev.codec != candidate.codec ||
                    prev.audioLanguage != candidate.audioLanguage ||
                    prev.downloadable != candidate.downloadable ||
                    prev.sizeBytes != candidate.sizeBytes ||
                    prev.safeHeadersJson != candidate.safeHeadersJson ||
                    prev.expiresAt != candidate.expiresAt ||
                    prev.status != candidate.status ||
                    prev.deletedAt != candidate.deletedAt ||
                    (candidate.lastVerifiedAt != null && candidate.lastVerifiedAt != prev.lastVerifiedAt) ||
                    (candidate.lastPlaybackSuccessAt != null && candidate.lastPlaybackSuccessAt != prev.lastPlaybackSuccessAt)
            }

        if (changedProviders.isNotEmpty()) {
            upsertProviders(changedProviders)
            metrics.recordRoomWrite(changedProviders.size.toLong())
        } else if (dedupedIncomingProviders.isNotEmpty()) {
            metrics.recordRoomWriteSkipped(dedupedIncomingProviders.size.toLong())
        }

        if (changedSources.isNotEmpty()) {
            upsertSources(changedSources)
            metrics.recordRoomWrite(changedSources.size.toLong())
        } else if (dedupedIncomingSources.isNotEmpty()) {
            metrics.recordRoomWriteSkipped(dedupedIncomingSources.size.toLong())
        }
    }
}
