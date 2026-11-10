package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "shared_catalog_cache", indices = [Index(value = ["contentType"]), Index(value = ["backendId"])])
data class SharedCatalogCacheEntry(
    @PrimaryKey val documentId: String,
    val contentType: String,
    val canonicalId: String?,
    val backendId: String?,
    val schemaVersion: Int,
    val metadataHash: String,
    val payloadJson: String,
    val lastSyncedAt: Long?
)

@Entity(tableName = "shared_catalog_pending", indices = [Index(value = ["state"]), Index(value = ["nextAttemptAt"])])
data class SharedCatalogPendingEntry(
    @PrimaryKey val documentId: String,
    val collectionName: String,
    val contentType: String,
    val metadataHash: String,
    val payloadJson: String,
    val queuedAt: Long,
    val retryCount: Int = 0,
    val nextAttemptAt: Long = 0,
    val lastErrorCategory: String? = null,
    val state: String = "PENDING"
)
