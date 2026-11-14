package com.example.data.repository

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Converts internal/network failures to stable, non-technical user copy. */
object UserFacingErrorMapper {
    fun message(error: Throwable?, fallback: String = "Something went wrong. Please try again."): String {
        val chain = generateSequence(error) { it.cause }.toList()
        if (chain.any { it is UnknownHostException || (it is IOException && it !is SocketTimeoutException) }) return "No internet connection. Please check your network."
        if (chain.any { it is SocketTimeoutException || it.message.orEmpty().contains("timeout", true) }) return "Connection timed out. Please try again."
        val text = chain.joinToString(" ") { it.message.orEmpty() }
        if (text.contains("No streaming provider mapping", ignoreCase = true)) {
            return "This title could not be matched on streaming providers yet."
        }
        if (text.contains("Streaming episode record was not found", ignoreCase = true)) {
            return "This episode or movie is not yet available on streaming providers."
        }
        if (text.contains("backed off", ignoreCase = true)) {
            return "Provider is busy right now. Please tap Retry in a moment."
        }
        val code = Regex("\\b(400|401|403|404|408|409|429|500|502|503|504)\\b").find(text)?.value?.toIntOrNull()
        return when (code) {
            401, 403 -> "You are not authorized to access this content."
            404 -> "No playable source available right now."
            408, 429, 500, 502, 503, 504 -> "Service temporarily unavailable. Please try again later."
            400, 409 -> "This request could not be completed. Please try again."
            else -> fallback
        }
    }
}
