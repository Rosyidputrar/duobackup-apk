package io.github.rosyidputrar.duobackup.drive

import io.github.rosyidputrar.duobackup.core.transport.TransportException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Turns a failed Drive response into the failure class the retry policy acts on. */
object DriveErrors {
    private val rateLimitReasons = setOf("userRateLimitExceeded", "rateLimitExceeded", "dailyLimitExceeded")
    private val quotaReasons = setOf("storageQuotaExceeded", "quotaExceeded")
    private val permissionReasons =
        setOf("insufficientPermissions", "appNotAuthorizedToFile", "insufficientFilePermissions", "authError")

    /**
     * @param body the response body, used only to read Drive's machine-readable `reason`
     * @param retryAfter the `Retry-After` header in seconds, when the server sent one
     */
    fun map(code: Int, body: String?, retryAfter: String? = null): TransportException {
        val reason = reasonOf(body)
        val retryAfterMs = retryAfter?.trim()?.toLongOrNull()?.coerceAtLeast(0)?.times(1000) ?: 0
        return when {
            code == 429 -> TransportException.RateLimited(retryAfterMs)
            code == 403 && reason in rateLimitReasons -> TransportException.RateLimited(retryAfterMs)
            code == 403 && reason in quotaReasons -> TransportException.QuotaExceeded()
            code == 403 && reason in permissionReasons -> TransportException.AuthRequired()
            code == 401 -> TransportException.AuthRequired()
            code == 404 || code == 410 -> TransportException.NotFound()
            code == 408 || code >= 500 -> TransportException.Transient()
            // Only the status and Drive's reason code go in the message: no URLs, no body text.
            else -> TransportException.Permanent("Drive request failed: HTTP $code" + (reason?.let { " ($it)" } ?: ""))
        }
    }

    /** Drive's error reason, e.g. `storageQuotaExceeded`, or null when the body has none. */
    fun reasonOf(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return try {
            val error = (DriveJson.parseToJsonElement(body) as? JsonObject)?.get("error") as? JsonObject
            val first = error?.get("errors")?.jsonArray?.firstOrNull()?.jsonObject
            first?.get("reason")?.jsonPrimitive?.contentOrNull
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
