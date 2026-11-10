package io.github.rosyidputrar.duobackup.core.transport

/** What the engine does after a transport failure. */
sealed interface RetryDecision {
    /** Wait exactly this long, then retry. */
    data class RetryAfter(val delayMs: Long) : RetryDecision

    /** Retry after the next backoff delay. */
    data object Backoff : RetryDecision

    /** Stop using this destination for the rest of the session and notify the user. */
    data object PauseDestination : RetryDecision

    /** Give up on this item; the rest of the session continues. */
    data object FailItem : RetryDecision
}

object RetryPolicy {
    fun classify(error: TransportException): RetryDecision = when (error) {
        is TransportException.RateLimited -> RetryDecision.RetryAfter(error.retryAfterMs)
        is TransportException.Transient -> RetryDecision.Backoff
        is TransportException.QuotaExceeded, is TransportException.AuthRequired -> RetryDecision.PauseDestination
        is TransportException.NotFound, is TransportException.Permanent -> RetryDecision.FailItem
    }
}
