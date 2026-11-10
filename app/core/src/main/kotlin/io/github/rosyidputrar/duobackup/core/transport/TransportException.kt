package io.github.rosyidputrar.duobackup.core.transport

/** Failures a transport reports to the engine, already classified for the retry policy. */
sealed class TransportException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The destination asked to wait. Retry after `retryAfterMs`. */
    class RateLimited(val retryAfterMs: Long) : TransportException("rate limited")

    /** Storage quota is used up. Pause the destination and notify. */
    class QuotaExceeded : TransportException("quota exceeded")

    /** The session or token is no longer valid. Pause the destination and notify. */
    class AuthRequired : TransportException("auth required")

    class NotFound : TransportException("not found")

    /** Network trouble or a server error. Back off and retry. */
    class Transient(cause: Throwable? = null) : TransportException("transient", cause)

    /** Retrying cannot help. Fail this item. */
    class Permanent(message: String) : TransportException(message)
}
