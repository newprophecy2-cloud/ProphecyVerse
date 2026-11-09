package com.example.data.firebase

import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ThreadLocalRandom
import javax.net.ssl.SSLException

internal enum class FirestoreFailureCategory {
    RESOURCE_EXHAUSTED,
    PERMISSION_DENIED,
    UNAUTHENTICATED,
    UNAVAILABLE,
    DEADLINE_EXCEEDED,
    NETWORK_ERROR,
    UNKNOWN_ERROR
}

internal class FirestoreOperationException(
    val category: FirestoreFailureCategory,
    val operation: String,
    val retryAfterMs: Long,
    cause: Throwable
) : Exception("Firestore $operation failed (${category.name}); automatic retry is disabled.", cause)

/** Deterministic cooldown policy with a production-only jitter source supplied by the caller. */
internal class FirestoreQuotaCooldown(
    private val baseDelayMs: Long = 30_000L,
    private val maxDelayMs: Long = 15 * 60_000L
) {
    private var consecutiveQuotaFailures = 0
    private var cooldownUntilMs = 0L

    @Synchronized
    fun remainingMs(nowMs: Long): Long = (cooldownUntilMs - nowMs).coerceAtLeast(0L)

    @Synchronized
    fun recordQuotaFailure(nowMs: Long, jitterMs: Long = 0L): Long {
        consecutiveQuotaFailures = (consecutiveQuotaFailures + 1).coerceAtMost(16)
        val multiplier = 1L shl (consecutiveQuotaFailures - 1).coerceAtMost(10)
        val delay = (baseDelayMs * multiplier).coerceAtMost(maxDelayMs)
        val boundedDelay = (delay + jitterMs.coerceIn(0L, delay / 5L)).coerceAtMost(maxDelayMs)
        cooldownUntilMs = nowMs + boundedDelay
        return delay
    }

    @Synchronized
    fun recordSuccess() {
        consecutiveQuotaFailures = 0
        cooldownUntilMs = 0L
    }
}

/**
 * Every guarded Firestore operation is single-attempt. Quota failures activate a project-wide
 * process cooldown with bounded exponential backoff and jitter. Permission/auth/network errors
 * are classified separately and are not retried or treated as empty data.
 */
internal object FirestoreRequestGuard {
    private val cooldown = FirestoreQuotaCooldown()

    suspend fun <T> execute(operation: String, taskFactory: () -> Task<T>): T {
        val now = System.currentTimeMillis()
        val remaining = cooldown.remainingMs(now)
        if (remaining > 0L) {
            throw FirestoreOperationException(
                category = FirestoreFailureCategory.RESOURCE_EXHAUSTED,
                operation = operation,
                retryAfterMs = remaining,
                cause = IllegalStateException("Firestore quota cooldown is active")
            )
        }
        try {
            val value = taskFactory().await()
            cooldown.recordSuccess()
            return value
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val category = classify(error)
            if (category == FirestoreFailureCategory.RESOURCE_EXHAUSTED) {
                val jitter = ThreadLocalRandom.current().nextLong(0L, 6_001L)
                cooldown.recordQuotaFailure(System.currentTimeMillis(), jitter)
            }
            throw FirestoreOperationException(
                category = category,
                operation = operation,
                retryAfterMs = cooldown.remainingMs(System.currentTimeMillis()),
                cause = error
            )
        }
    }

    fun recordListenerFailure(error: Throwable): FirestoreFailureCategory {
        val category = classify(error)
        if (category == FirestoreFailureCategory.RESOURCE_EXHAUSTED) {
            val jitter = ThreadLocalRandom.current().nextLong(0L, 6_001L)
            cooldown.recordQuotaFailure(System.currentTimeMillis(), jitter)
        }
        return category
    }

    fun cooldownRemainingMs(nowMs: Long = System.currentTimeMillis()): Long = cooldown.remainingMs(nowMs)

    internal fun classify(error: Throwable): FirestoreFailureCategory {
        val chain = generateSequence(error) { it.cause }.toList()
        val firestoreError = chain.filterIsInstance<FirebaseFirestoreException>().firstOrNull()
        return classifyFirestoreCodeName(firestoreError?.code?.name, chain)
    }

    internal fun classifyFirestoreCodeName(
        codeName: String?,
        causes: List<Throwable> = emptyList()
    ): FirestoreFailureCategory = when (codeName) {
            "RESOURCE_EXHAUSTED" -> FirestoreFailureCategory.RESOURCE_EXHAUSTED
            "PERMISSION_DENIED" -> FirestoreFailureCategory.PERMISSION_DENIED
            "UNAUTHENTICATED" -> FirestoreFailureCategory.UNAUTHENTICATED
            "UNAVAILABLE" -> FirestoreFailureCategory.UNAVAILABLE
            "DEADLINE_EXCEEDED" -> FirestoreFailureCategory.DEADLINE_EXCEEDED
            else -> when {
                causes.any { it is SocketTimeoutException } -> FirestoreFailureCategory.DEADLINE_EXCEEDED
                causes.any { it is UnknownHostException || it is ConnectException || it is SSLException || it is IOException } ->
                    FirestoreFailureCategory.NETWORK_ERROR
                else -> FirestoreFailureCategory.UNKNOWN_ERROR
            }
        }
}
