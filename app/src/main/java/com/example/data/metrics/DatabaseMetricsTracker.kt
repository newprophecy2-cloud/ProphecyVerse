package com.example.data.metrics

import android.util.Log
import com.example.data.streaming.MonotonicMetrics
import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe telemetry tracker for D1, Firestore, Memory LRU, Room, and HTTP API usage.
 * Never logs playback URLs, authorization headers, tokens, or PII.
 */
class DatabaseMetricsTracker {
    data class MetricsSnapshot(
        val d1Reads: Long,
        val d1RowsReturned: Long,
        val d1Writes: Long,
        val d1WriteSkipsUnchanged: Long,
        val d1Errors: Long,
        val d1AverageLatencyMs: Long,
        val firestoreReads: Long,
        val firestoreWrites: Long,
        val firestoreWriteSkipsUnchanged: Long,
        val firestoreErrors: Long,
        val memoryCacheHits: Long,
        val memoryCacheMisses: Long,
        val roomCacheHits: Long,
        val roomCacheMisses: Long,
        val apiRequests: Long,
        val apiFailures: Long,
        val duplicateRequestsPrevented: Long,
        val roomWrites: Long = 0L,
        val roomWriteSkipsUnchanged: Long = 0L,
        // Expanded D1 & Resolver telemetry
        val d1CacheHits: Long = 0L,
        val d1CacheMisses: Long = 0L,
        val d1FallbackLookups: Long = 0L,
        val d1PreloadCycles: Long = 0L,
        val d1PreloadCycleReads: Long = 0L,
        val resolverInvocations: Long = 0L,
        val resolverLatencyTotalMs: Long = 0L,
        // Expanded Firestore telemetry
        val firestoreQueryCount: Long = 0L,
        val firestoreDocumentsReturned: Long = 0L,
        val firestoreListenerEvents: Long = 0L,
        val firestoreActiveListeners: Long = 0L,
        val firestoreFullSyncCount: Long = 0L,
        val firestoreIncrementalSyncCount: Long = 0L,
        val firestoreCacheHits: Long = 0L,
        // Expanded Playback & Ready Queue telemetry
        val readyQueueHits: Long = 0L,
        val readyQueueMisses: Long = 0L,
        val episodeSwitchPrepCount: Long = 0L,
        val episodeSwitchPrepTotalMs: Long = 0L,
        val playbackStartLatencyCount: Long = 0L,
        val playbackStartLatencyTotalMs: Long = 0L,
        val bufferingEvents: Long = 0L,
        val bufferingDurationTotalMs: Long = 0L
    ) {
        val memoryHitRate: Double
            get() {
                val total = memoryCacheHits + memoryCacheMisses
                return if (total == 0L) 0.0 else memoryCacheHits.toDouble() / total.toDouble()
            }

        val roomHitRate: Double
            get() {
                val total = roomCacheHits + roomCacheMisses
                return if (total == 0L) 0.0 else roomCacheHits.toDouble() / total.toDouble()
            }

        val overallCacheHitRate: Double
            get() {
                val hits = memoryCacheHits + roomCacheHits
                val total = hits + roomCacheMisses
                return if (total == 0L) 0.0 else hits.toDouble() / total.toDouble()
            }

        val d1ReadsPerPreloadCycle: Double
            get() = if (d1PreloadCycles <= 0L) 0.0 else d1PreloadCycleReads.toDouble() / d1PreloadCycles.toDouble()

        val resolverAverageLatencyMs: Long
            get() = if (resolverInvocations <= 0L) 0L else resolverLatencyTotalMs / resolverInvocations

        val episodeSwitchPrepAverageMs: Long
            get() = if (episodeSwitchPrepCount <= 0L) 0L else episodeSwitchPrepTotalMs / episodeSwitchPrepCount

        val playbackStartLatencyAverageMs: Long
            get() = if (playbackStartLatencyCount <= 0L) 0L else playbackStartLatencyTotalMs / playbackStartLatencyCount

        val readyQueueHitRate: Double
            get() {
                val total = readyQueueHits + readyQueueMisses
                return if (total == 0L) 0.0 else readyQueueHits.toDouble() / total.toDouble()
            }
    }

    private val d1Reads = AtomicLong(0L)
    private val d1RowsReturned = AtomicLong(0L)
    private val d1Writes = AtomicLong(0L)
    private val d1WriteSkipsUnchanged = AtomicLong(0L)
    private val d1Errors = AtomicLong(0L)
    private val d1TotalLatencyMs = AtomicLong(0L)

    private val firestoreReads = AtomicLong(0L)
    private val firestoreWrites = AtomicLong(0L)
    private val firestoreWriteSkipsUnchanged = AtomicLong(0L)
    private val firestoreErrors = AtomicLong(0L)

    private val memoryCacheHits = AtomicLong(0L)
    private val memoryCacheMisses = AtomicLong(0L)
    private val roomCacheHits = AtomicLong(0L)
    private val roomCacheMisses = AtomicLong(0L)

    private val apiRequests = AtomicLong(0L)
    private val apiFailures = AtomicLong(0L)
    private val duplicateRequestsPrevented = AtomicLong(0L)
    private val roomWrites = AtomicLong(0L)
    private val roomWriteSkipsUnchanged = AtomicLong(0L)

    private val d1CacheHits = AtomicLong(0L)
    private val d1CacheMisses = AtomicLong(0L)
    private val d1FallbackLookups = AtomicLong(0L)
    private val d1PreloadCycles = AtomicLong(0L)
    private val d1PreloadCycleReads = AtomicLong(0L)
    private val resolverInvocations = AtomicLong(0L)
    private val resolverLatencyTotalMs = AtomicLong(0L)

    private val firestoreQueryCount = AtomicLong(0L)
    private val firestoreDocumentsReturned = AtomicLong(0L)
    private val firestoreListenerEvents = AtomicLong(0L)
    private val firestoreActiveListeners = AtomicLong(0L)
    private val firestoreFullSyncCount = AtomicLong(0L)
    private val firestoreIncrementalSyncCount = AtomicLong(0L)
    private val firestoreCacheHits = AtomicLong(0L)

    private val readyQueueHits = AtomicLong(0L)
    private val readyQueueMisses = AtomicLong(0L)
    private val episodeSwitchPrepCount = AtomicLong(0L)
    private val episodeSwitchPrepTotalMs = AtomicLong(0L)
    private val playbackStartLatencyCount = AtomicLong(0L)
    private val playbackStartLatencyTotalMs = AtomicLong(0L)
    private val bufferingEvents = AtomicLong(0L)
    private val bufferingDurationTotalMs = AtomicLong(0L)

    fun recordD1Read(rowsReturned: Int = 0, durationMs: Long = 0L) {
        d1Reads.incrementAndGet()
        if (rowsReturned > 0) d1RowsReturned.addAndGet(rowsReturned.toLong())
        if (durationMs >= 0L) {
            d1TotalLatencyMs.addAndGet(durationMs)
            MonotonicMetrics.recordValue("d1_read_ms", durationMs)
        }
    }

    fun recordD1Write(durationMs: Long = 0L) {
        d1Writes.incrementAndGet()
        if (durationMs >= 0L) {
            d1TotalLatencyMs.addAndGet(durationMs)
            MonotonicMetrics.recordValue("d1_write_ms", durationMs)
        }
    }

    fun recordD1WriteSkipped() {
        d1WriteSkipsUnchanged.incrementAndGet()
    }

    fun recordD1Error(durationMs: Long = 0L) {
        d1Errors.incrementAndGet()
        if (durationMs >= 0L) d1TotalLatencyMs.addAndGet(durationMs)
    }

    fun recordD1CacheHit() {
        d1CacheHits.incrementAndGet()
    }

    fun recordD1CacheMiss() {
        d1CacheMisses.incrementAndGet()
    }

    fun recordD1FallbackLookup() {
        d1FallbackLookups.incrementAndGet()
    }

    fun recordPreloadCycle(d1ReadsDelta: Long = 0L) {
        d1PreloadCycles.incrementAndGet()
        if (d1ReadsDelta > 0L) d1PreloadCycleReads.addAndGet(d1ReadsDelta)
    }

    fun recordResolverInvocation(durationMs: Long = 0L) {
        resolverInvocations.incrementAndGet()
        if (durationMs >= 0L) {
            resolverLatencyTotalMs.addAndGet(durationMs)
            MonotonicMetrics.recordValue("resolver_invocation_ms", durationMs)
        }
    }

    fun recordFirestoreRead(count: Long = 1L) {
        firestoreReads.addAndGet(count.coerceAtLeast(1L))
    }

    fun recordFirestoreQuery(documentsReturned: Long = 0L) {
        firestoreQueryCount.incrementAndGet()
        if (documentsReturned > 0L) {
            firestoreDocumentsReturned.addAndGet(documentsReturned)
        }
    }

    fun recordFirestoreDocumentsReturned(count: Long = 1L) {
        if (count > 0L) firestoreDocumentsReturned.addAndGet(count)
    }

    fun recordFirestoreListenerAttached() {
        firestoreActiveListeners.incrementAndGet()
    }

    fun recordFirestoreListenerDetached() {
        firestoreActiveListeners.updateAndGet { (it - 1L).coerceAtLeast(0L) }
    }

    fun recordFirestoreListenerEvent(documentsReturned: Long = 0L) {
        firestoreListenerEvents.incrementAndGet()
        if (documentsReturned > 0L) {
            firestoreDocumentsReturned.addAndGet(documentsReturned)
        }
    }

    fun recordFirestoreFullSync(documentsReturned: Long = 0L) {
        firestoreFullSyncCount.incrementAndGet()
        if (documentsReturned > 0L) {
            firestoreDocumentsReturned.addAndGet(documentsReturned)
        }
    }

    fun recordFirestoreIncrementalSync(documentsReturned: Long = 0L) {
        firestoreIncrementalSyncCount.incrementAndGet()
        if (documentsReturned > 0L) {
            firestoreDocumentsReturned.addAndGet(documentsReturned)
        }
    }

    fun recordFirestoreCacheHit() {
        firestoreCacheHits.incrementAndGet()
    }

    fun recordFirestoreWrite(count: Long = 1L) {
        firestoreWrites.addAndGet(count.coerceAtLeast(1L))
    }

    fun recordFirestoreWriteSkipped() {
        firestoreWriteSkipsUnchanged.incrementAndGet()
    }

    fun recordFirestoreError() {
        firestoreErrors.incrementAndGet()
    }

    fun recordMemoryHit() {
        memoryCacheHits.incrementAndGet()
    }

    fun recordMemoryMiss() {
        memoryCacheMisses.incrementAndGet()
    }

    fun recordRoomHit() {
        roomCacheHits.incrementAndGet()
    }

    fun recordRoomMiss() {
        roomCacheMisses.incrementAndGet()
    }

    fun recordApiRequest(success: Boolean) {
        apiRequests.incrementAndGet()
        if (!success) apiFailures.incrementAndGet()
    }

    fun recordDuplicateRequestPrevented() {
        duplicateRequestsPrevented.incrementAndGet()
    }

    fun recordRoomWrite(count: Long = 1L) {
        if (count > 0L) roomWrites.addAndGet(count)
    }

    fun recordRoomWriteSkipped(count: Long = 1L) {
        if (count > 0L) roomWriteSkipsUnchanged.addAndGet(count)
    }

    fun recordReadyQueueHit() {
        readyQueueHits.incrementAndGet()
    }

    fun recordReadyQueueMiss() {
        readyQueueMisses.incrementAndGet()
    }

    fun recordEpisodeSwitchPrep(durationMs: Long) {
        episodeSwitchPrepCount.incrementAndGet()
        if (durationMs >= 0L) {
            episodeSwitchPrepTotalMs.addAndGet(durationMs)
            MonotonicMetrics.recordValue("episode_switch_prep_ms", durationMs)
        }
    }

    fun recordPlaybackStartLatency(durationMs: Long) {
        playbackStartLatencyCount.incrementAndGet()
        if (durationMs >= 0L) {
            playbackStartLatencyTotalMs.addAndGet(durationMs)
            MonotonicMetrics.recordValue("playback_start_latency_ms", durationMs)
        }
    }

    fun recordBufferingEvent(durationMs: Long = 0L) {
        bufferingEvents.incrementAndGet()
        if (durationMs > 0L) {
            bufferingDurationTotalMs.addAndGet(durationMs)
        }
    }

    fun snapshot(): MetricsSnapshot {
        val totalD1Ops = d1Reads.get() + d1Writes.get() + d1Errors.get()
        val avgD1Ms = if (totalD1Ops > 0L) d1TotalLatencyMs.get() / totalD1Ops else 0L
        return MetricsSnapshot(
            d1Reads = d1Reads.get(),
            d1RowsReturned = d1RowsReturned.get(),
            d1Writes = d1Writes.get(),
            d1WriteSkipsUnchanged = d1WriteSkipsUnchanged.get(),
            d1Errors = d1Errors.get(),
            d1AverageLatencyMs = avgD1Ms,
            firestoreReads = firestoreReads.get(),
            firestoreWrites = firestoreWrites.get(),
            firestoreWriteSkipsUnchanged = firestoreWriteSkipsUnchanged.get(),
            firestoreErrors = firestoreErrors.get(),
            memoryCacheHits = memoryCacheHits.get(),
            memoryCacheMisses = memoryCacheMisses.get(),
            roomCacheHits = roomCacheHits.get(),
            roomCacheMisses = roomCacheMisses.get(),
            apiRequests = apiRequests.get(),
            apiFailures = apiFailures.get(),
            duplicateRequestsPrevented = duplicateRequestsPrevented.get(),
            roomWrites = roomWrites.get(),
            roomWriteSkipsUnchanged = roomWriteSkipsUnchanged.get(),
            d1CacheHits = d1CacheHits.get(),
            d1CacheMisses = d1CacheMisses.get(),
            d1FallbackLookups = d1FallbackLookups.get(),
            d1PreloadCycles = d1PreloadCycles.get(),
            d1PreloadCycleReads = d1PreloadCycleReads.get(),
            resolverInvocations = resolverInvocations.get(),
            resolverLatencyTotalMs = resolverLatencyTotalMs.get(),
            firestoreQueryCount = firestoreQueryCount.get(),
            firestoreDocumentsReturned = firestoreDocumentsReturned.get(),
            firestoreListenerEvents = firestoreListenerEvents.get(),
            firestoreActiveListeners = firestoreActiveListeners.get(),
            firestoreFullSyncCount = firestoreFullSyncCount.get(),
            firestoreIncrementalSyncCount = firestoreIncrementalSyncCount.get(),
            firestoreCacheHits = firestoreCacheHits.get(),
            readyQueueHits = readyQueueHits.get(),
            readyQueueMisses = readyQueueMisses.get(),
            episodeSwitchPrepCount = episodeSwitchPrepCount.get(),
            episodeSwitchPrepTotalMs = episodeSwitchPrepTotalMs.get(),
            playbackStartLatencyCount = playbackStartLatencyCount.get(),
            playbackStartLatencyTotalMs = playbackStartLatencyTotalMs.get(),
            bufferingEvents = bufferingEvents.get(),
            bufferingDurationTotalMs = bufferingDurationTotalMs.get()
        )
    }

    fun logSafeSummary(contextTag: String = "Metrics") {
        val snap = snapshot()
        runCatching {
            Log.i(
                "ProphecyDbMetrics",
                "$contextTag d1_r=${snap.d1Reads} d1_hit=${snap.d1CacheHits} d1_miss=${snap.d1CacheMisses} " +
                    "d1_fb=${snap.d1FallbackLookups} preload_cycles=${snap.d1PreloadCycles} " +
                    "resolver=${snap.resolverInvocations} fs_r=${snap.firestoreReads} fs_q=${snap.firestoreQueryCount} " +
                    "fs_docs=${snap.firestoreDocumentsReturned} fs_cache=${snap.firestoreCacheHits} " +
                    "rq_hit=${snap.readyQueueHits}/${snap.readyQueueHits + snap.readyQueueMisses} " +
                    "buf_events=${snap.bufferingEvents} dedup=${snap.duplicateRequestsPrevented}"
            )
        }
    }

    fun reset() {
        d1Reads.set(0L)
        d1RowsReturned.set(0L)
        d1Writes.set(0L)
        d1WriteSkipsUnchanged.set(0L)
        d1Errors.set(0L)
        d1TotalLatencyMs.set(0L)
        firestoreReads.set(0L)
        firestoreWrites.set(0L)
        firestoreWriteSkipsUnchanged.set(0L)
        firestoreErrors.set(0L)
        memoryCacheHits.set(0L)
        memoryCacheMisses.set(0L)
        roomCacheHits.set(0L)
        roomCacheMisses.set(0L)
        apiRequests.set(0L)
        apiFailures.set(0L)
        duplicateRequestsPrevented.set(0L)
        roomWrites.set(0L)
        roomWriteSkipsUnchanged.set(0L)
        d1CacheHits.set(0L)
        d1CacheMisses.set(0L)
        d1FallbackLookups.set(0L)
        d1PreloadCycles.set(0L)
        d1PreloadCycleReads.set(0L)
        resolverInvocations.set(0L)
        resolverLatencyTotalMs.set(0L)
        firestoreQueryCount.set(0L)
        firestoreDocumentsReturned.set(0L)
        firestoreListenerEvents.set(0L)
        firestoreActiveListeners.set(0L)
        firestoreFullSyncCount.set(0L)
        firestoreIncrementalSyncCount.set(0L)
        firestoreCacheHits.set(0L)
        readyQueueHits.set(0L)
        readyQueueMisses.set(0L)
        episodeSwitchPrepCount.set(0L)
        episodeSwitchPrepTotalMs.set(0L)
        playbackStartLatencyCount.set(0L)
        playbackStartLatencyTotalMs.set(0L)
        bufferingEvents.set(0L)
        bufferingDurationTotalMs.set(0L)
    }

    companion object {
        @Volatile
        private var INSTANCE: DatabaseMetricsTracker? = null

        fun getInstance(): DatabaseMetricsTracker {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DatabaseMetricsTracker().also { INSTANCE = it }
            }
        }
    }
}
