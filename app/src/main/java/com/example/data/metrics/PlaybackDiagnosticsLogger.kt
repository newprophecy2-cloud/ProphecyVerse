package com.example.data.metrics

import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

enum class PlaybackDiagnosticEventType {
    SOURCE_DISCOVERY_STARTED,
    SOURCE_DISCOVERY_COMPLETED,
    SOURCE_DISCOVERY_EMPTY,
    SOURCE_CANDIDATE_REJECTED,
    D1_CACHE_HIT,
    D1_CACHE_MISS,
    D1_CACHE_WRITE,
    D1_CACHE_INVALIDATED,
    PLAYBACK_RESOLUTION_STARTED,
    PLAYBACK_RESOLUTION_SUCCEEDED,
    PLAYBACK_RESOLUTION_FAILED,
    PLAYBACK_BUFFERING_STARTED,
    PLAYBACK_BUFFERING_PROGRESSING,
    PLAYBACK_STALL_CONFIRMED,
    PLAYBACK_RECOVERY_STARTED,
    PLAYBACK_RECOVERY_SKIPPED_DUPLICATE,
    PLAYBACK_SESSION_FAILED
}

typealias PlaybackDiagnosticEvent = PlaybackDiagnosticEventType

data class PlaybackDiagnosticRecord(
    val timestampMs: Long,
    val type: PlaybackDiagnosticEventType,
    val scopeKey: String,
    val sourceId: String? = null,
    val providerLabel: String? = null,
    val detail: String = ""
)

/**
 * Privacy-safe structured logger for source discovery, D1 cache, resolution, buffering health,
 * and recovery lifecycle events. Never records full URLs, auth headers, tokens, or credentials.
 */
object PlaybackDiagnosticsLogger {
    private const val TAG = "ProphecyPlaybackDiag"
    private const val MAX_EVENTS = 160
    private val recentBuffer = CopyOnWriteArrayList<PlaybackDiagnosticRecord>()

    fun log(
        event: PlaybackDiagnosticEventType,
        animeId: String? = null,
        episodeId: String? = null,
        sourceId: String? = null,
        providerLabel: String? = null,
        reason: String? = null,
        detail: String = "",
        isWarning: Boolean = false
    ) {
        val scope = listOfNotNull(
            animeId?.trim()?.takeIf { it.isNotEmpty() },
            episodeId?.trim()?.takeIf { it.isNotEmpty() }
        ).joinToString(":").ifEmpty { "global" }
        val mergedDetail = buildString {
            if (!reason.isNullOrBlank()) append("reason=").append(reason.trim())
            if (detail.isNotBlank()) {
                if (isNotEmpty()) append(' ')
                append(detail.trim())
            }
        }
        log(
            type = event,
            scopeKey = scope,
            sourceId = sourceId,
            providerLabel = providerLabel,
            detail = mergedDetail,
            isWarning = isWarning
        )
    }

    fun log(
        type: PlaybackDiagnosticEventType,
        scopeKey: String,
        sourceId: String? = null,
        providerLabel: String? = null,
        detail: String = "",
        isWarning: Boolean = false
    ) {
        val safeScope = sanitize(scopeKey, maxLen = 96).ifEmpty { "unknown" }
        val safeSource = sourceId?.trim()?.takeIf { it.isNotEmpty() }?.let { sanitize(it, maxLen = 64) }
        val safeProvider = providerLabel?.trim()?.takeIf { it.isNotEmpty() }?.let { sanitize(it, maxLen = 48) }
        val safeDetail = sanitize(detail, maxLen = 200)

        val event = PlaybackDiagnosticRecord(
            timestampMs = System.currentTimeMillis(),
            type = type,
            scopeKey = safeScope,
            sourceId = safeSource,
            providerLabel = safeProvider,
            detail = safeDetail
        )
        recentBuffer.add(event)
        while (recentBuffer.size > MAX_EVENTS) {
            runCatching { recentBuffer.removeAt(0) }
        }

        val line = buildString {
            append(type.name)
            append(" scope=").append(safeScope)
            if (safeSource != null) append(" source=").append(safeSource)
            if (safeProvider != null) append(" provider=").append(safeProvider)
            if (safeDetail.isNotEmpty()) append(" detail=").append(safeDetail)
        }
        runCatching {
            if (isWarning) Log.w(TAG, line) else Log.i(TAG, line)
        }
    }

    fun recentEvents(type: PlaybackDiagnosticEventType? = null): List<PlaybackDiagnosticRecord> {
        val snapshot = recentBuffer.toList()
        return if (type == null) snapshot else snapshot.filter { it.type == type }
    }

    fun clearForTests() {
        recentBuffer.clear()
    }

    fun sanitize(raw: String, maxLen: Int = 200): String {
        return raw
            .replace(
                Regex("""(?i)([\"']?(?:authorization|access[_-]?token|refresh[_-]?token|token|signature|cookie|credential|password|secret|api[_-]?key|referer|origin)[\"']?\s*[:=]\s*(?:Bearer\s+|Basic\s+)?[\"']?)[^,}\s\"']+""")
            ) { match -> match.groupValues[1] + "[redacted]" }
            .replace(Regex("""https?://[^\s\"']+"""), "[redacted-url]")
            .replace(Regex("""[?&][a-zA-Z0-9_=-]{8,}"""), "[redacted-query]")
            .replace(Regex("""[\r\n\t]+"""), " ")
            .trim()
            .take(maxLen)
    }
}
