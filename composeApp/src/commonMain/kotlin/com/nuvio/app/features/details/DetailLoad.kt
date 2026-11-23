package com.nuvio.app.features.details

internal sealed interface DetailLoadOutcome<out T> {
    data class Ready<T>(val value: T) : DetailLoadOutcome<T>
    data class Failed(val message: String) : DetailLoadOutcome<Nothing>
}

internal fun detailLoadKey(identity: String, attempt: Int): String = "$identity#$attempt"

internal fun nextDetailAttempt(attempt: Int): Int = attempt + 1

internal suspend fun <T> loadOrFail(
    attempt: Int,
    failureMessage: String,
    fetch: suspend (attempt: Int) -> T?,
): DetailLoadOutcome<T> {
    val value = fetch(attempt)
    return if (value != null) {
        DetailLoadOutcome.Ready(value)
    } else {
        DetailLoadOutcome.Failed(failureMessage)
    }
}
