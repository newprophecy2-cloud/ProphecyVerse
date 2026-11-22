package com.example.data.remote.session

import java.util.concurrent.atomic.AtomicReference

/**
 * Centralized in-memory session manager for the Prophecy Verse V3 backend.
 * Captures `session_id` from Search responses and attaches `X-PV-Session`
 * strictly to V3 backend requests. Never logs or persists raw session tokens.
 */
object PvSessionManager {
    const val HEADER_NAME: String = "X-PV-Session"

    private val activeSessionId = AtomicReference<String?>(null)

    fun currentSessionId(): String? = activeSessionId.get()

    fun updateSessionId(sessionId: String?) {
        val clean = sessionId?.trim()?.takeIf { it.isNotEmpty() } ?: return
        activeSessionId.set(clean)
    }

    fun clearSession() {
        activeSessionId.set(null)
    }

    fun isSessionError(httpCode: Int, errorCode: String?): Boolean {
        if (httpCode == 401) return true
        return errorCode in setOf("SESSION_REQUIRED", "SESSION_INVALID", "SESSION_EXPIRED")
    }
}
