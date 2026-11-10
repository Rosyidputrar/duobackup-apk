package io.github.rosyidputrar.duobackup.drive

/** Supplies OAuth access tokens for the `drive.file` scope. Implementations never persist them. */
interface AccessTokenProvider {
    /**
     * Returns a token that is believed to be valid.
     *
     * @throws io.github.rosyidputrar.duobackup.core.transport.TransportException.AuthRequired
     *   when a token cannot be had without the user
     */
    suspend fun token(): String

    /** Drops a token the server has rejected, so the next [token] call fetches a fresh one. */
    suspend fun invalidate(token: String)
}
