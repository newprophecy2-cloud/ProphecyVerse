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
        val roomWriteSkipsUnchanged: Long = 0L
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

    fun recordFirestoreRead(count: Long = 1L) {
        firestoreReads.addAndGet(count.coerceAtLeast(1L))
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
            roomWriteSkipsUnchanged = roomWriteSkipsUnchanged.get()
        )
    }

    fun logSafeSummary(contextTag: String = "Metrics") {
        val snap = snapshot()
        runCatching {
            Log.i(
                "ProphecyDbMetrics",
                "$contextTag d1_r=${snap.d1Reads} d1_rows=${snap.d1RowsReturned} d1_w=${snap.d1Writes} " +
                    "d1_skip=${snap.d1WriteSkipsUnchanged} fs_r=${snap.firestoreReads} fs_w=${snap.firestoreWrites} " +
                    "room_w=${snap.roomWrites} room_skip=${snap.roomWriteSkipsUnchanged} " +
                    "mem_hit=${snap.memoryCacheHits}/${snap.memoryCacheHits + snap.memoryCacheMisses} " +
                    "room_hit=${snap.roomCacheHits}/${snap.roomCacheHits + snap.roomCacheMisses} " +
                    "dedup=${snap.duplicateRequestsPrevented}"
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
