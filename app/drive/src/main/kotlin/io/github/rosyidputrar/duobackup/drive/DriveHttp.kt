package io.github.rosyidputrar.duobackup.drive

import io.github.rosyidputrar.duobackup.core.transport.TransportException
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Sends requests with the bearer token and handles the one-time refresh after a 401. */
internal class DriveHttp(private val client: OkHttpClient, private val tokens: AccessTokenProvider) {
    /**
     * Executes the request built by `build`, which is called again for the retry. The caller
     * owns the returned response and must close it.
     *
     * @throws TransportException.AuthRequired when a fresh token is rejected as well
     * @throws TransportException.Transient on network failure
     */
    suspend fun execute(build: () -> Request.Builder): Response {
        val first = tokens.token()
        val response = send(build, first)
        if (response.code != 401) return response
        response.close()
        tokens.invalidate(first)
        val retried = send(build, tokens.token())
        if (retried.code == 401) {
            retried.close()
            throw TransportException.AuthRequired()
        }
        return retried
    }

    /** Executes and returns the response only when it is 2xx; otherwise throws the mapped error. */
    suspend fun executeOk(build: () -> Request.Builder): Response {
        val response = execute(build)
        if (response.isSuccessful) return response
        throw response.use { failure(it) }
    }

    fun failure(response: Response): TransportException {
        val body = try {
            response.body.string()
        } catch (e: IOException) {
            null
        }
        return DriveErrors.map(response.code, body, response.header("Retry-After"))
    }

    private suspend fun send(build: () -> Request.Builder, token: String): Response =
        client.newCall(build().header("Authorization", "Bearer $token").build()).await()
}

internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, unused, _ -> unused.close() }
            }

            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(TransportException.Transient(e))
            }
        }
    )
}
