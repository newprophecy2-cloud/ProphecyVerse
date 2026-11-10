package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface SharedCatalogDao {
    @Query("SELECT * FROM shared_catalog_cache WHERE documentId = :id LIMIT 1") suspend fun getCache(id: String): SharedCatalogCacheEntry?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putCache(entry: SharedCatalogCacheEntry)
    @Query("SELECT * FROM shared_catalog_pending WHERE documentId = :id LIMIT 1") suspend fun getPending(id: String): SharedCatalogPendingEntry?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putPending(entry: SharedCatalogPendingEntry)
    @Query("SELECT * FROM shared_catalog_pending WHERE state = 'PENDING' AND nextAttemptAt <= :now ORDER BY queuedAt LIMIT :limit") suspend fun getDue(now: Long, limit: Int = 20): List<SharedCatalogPendingEntry>
    @Query("DELETE FROM shared_catalog_pending WHERE documentId = :id") suspend fun deletePending(id: String)
    @Query("UPDATE shared_catalog_pending SET retryCount = :retryCount, nextAttemptAt = :retryAt, lastErrorCategory = :category, state = :state WHERE documentId = :id") suspend fun updatePending(id: String, retryCount: Int, retryAt: Long, category: String, state: String)
    @Query("DELETE FROM shared_catalog_pending WHERE state = 'PENDING' AND queuedAt < :cutoff") suspend fun prune(cutoff: Long)
    @Transaction suspend fun enqueueBounded(item: SharedCatalogPendingEntry, maxRecords: Int = 500) {
        val old = getPending(item.documentId)
        if (old != null && old.metadataHash == item.metadataHash) return
        if (old == null && countAllQueueRecords() >= maxRecords) {
            trimOldestTerminal((countAllQueueRecords() - maxRecords + 1).coerceAtLeast(1))
            if (countAllQueueRecords() >= maxRecords) return
        }
        putPending(item)
    }
    @Query("SELECT COUNT(*) FROM shared_catalog_pending WHERE state = 'PENDING'") suspend fun countPending(): Int
    @Query("SELECT COUNT(*) FROM shared_catalog_pending") suspend fun countAllQueueRecords(): Int
    @Query("DELETE FROM shared_catalog_pending WHERE documentId IN (SELECT documentId FROM shared_catalog_pending WHERE state != 'PENDING' ORDER BY queuedAt LIMIT :count)") suspend fun trimOldestTerminal(count: Int)
}
