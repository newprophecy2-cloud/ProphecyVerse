package com.example.data.repository

/** Guarantees that a UI loading flag is cleared on success, failure, and cancellation. */
internal suspend fun <T> withLoadingFlag(
    setLoading: (Boolean) -> Unit,
    operation: suspend () -> T
): T {
    setLoading(true)
    try {
        return operation()
    } finally {
        setLoading(false)
    }
}
