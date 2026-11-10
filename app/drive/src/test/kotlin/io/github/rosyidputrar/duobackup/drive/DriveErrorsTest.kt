package io.github.rosyidputrar.duobackup.drive

import io.github.rosyidputrar.duobackup.core.transport.TransportException
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class DriveErrorsTest {
    /** An error body as Drive v3 sends it. */
    private val quotaBody = """
        {
         "error": {
          "errors": [
           {
            "domain": "global",
            "reason": "storageQuotaExceeded",
            "message": "The user's Drive storage quota has been exceeded."
           }
          ],
          "code": 403,
          "message": "The user's Drive storage quota has been exceeded."
         }
        }
    """.trimIndent()

    @Test
    fun `rate limit reasons and 429 are rate limited`() {
        for (reason in listOf("userRateLimitExceeded", "rateLimitExceeded", "dailyLimitExceeded")) {
            assertIs<TransportException.RateLimited>(DriveErrors.map(403, errorBody(403, reason)), reason)
        }
        assertEquals(0, assertIs<TransportException.RateLimited>(DriveErrors.map(429, null)).retryAfterMs)
        assertEquals(7_000, assertIs<TransportException.RateLimited>(DriveErrors.map(429, "", "7")).retryAfterMs)
        assertEquals(0, assertIs<TransportException.RateLimited>(DriveErrors.map(429, "", "soon")).retryAfterMs)
        val limited = DriveErrors.map(403, errorBody(403, "userRateLimitExceeded"), " 30 ")
        assertEquals(30_000, assertIs<TransportException.RateLimited>(limited).retryAfterMs)
    }

    @Test
    fun `storage quota reasons are quota exceeded`() {
        assertIs<TransportException.QuotaExceeded>(DriveErrors.map(403, quotaBody))
        assertIs<TransportException.QuotaExceeded>(DriveErrors.map(403, errorBody(403, "quotaExceeded")))
    }

    @Test
    fun `permission problems need the user to authorize again`() {
        for (reason in listOf("insufficientPermissions", "appNotAuthorizedToFile", "insufficientFilePermissions")) {
            assertIs<TransportException.AuthRequired>(DriveErrors.map(403, errorBody(403, reason)), reason)
        }
        assertIs<TransportException.AuthRequired>(DriveErrors.map(401, errorBody(401, "authError")))
        assertIs<TransportException.AuthRequired>(DriveErrors.map(401, null))
    }

    @Test
    fun `missing files are not found`() {
        assertIs<TransportException.NotFound>(DriveErrors.map(404, errorBody(404, "notFound")))
        assertIs<TransportException.NotFound>(DriveErrors.map(410, null))
    }

    @Test
    fun `server errors and timeouts are transient`() {
        for (code in listOf(408, 500, 502, 503, 504)) {
            assertIs<TransportException.Transient>(DriveErrors.map(code, "<html>Bad Gateway</html>"), "code $code")
        }
    }

    @Test
    fun `other client errors are permanent and carry only the status and reason`() {
        val error = assertIs<TransportException.Permanent>(DriveErrors.map(400, errorBody(400, "invalidParameter")))
        assertEquals("Drive request failed: HTTP 400 (invalidParameter)", error.message)
        assertEquals("Drive request failed: HTTP 403", DriveErrors.map(403, "not json").message)
        assertIs<TransportException.Permanent>(DriveErrors.map(403, errorBody(403, "domainPolicy")))
        assertIs<TransportException.Permanent>(DriveErrors.map(409, null))
    }

    @Test
    fun `reason is read from the first error and tolerates odd bodies`() {
        assertEquals("storageQuotaExceeded", DriveErrors.reasonOf(quotaBody))
        for (body in listOf(
            null,
            "",
            "   ",
            "not json",
            "[]",
            "{}",
            """{"error":"x"}""",
            """{"error":{"errors":[]}}"""
        )) {
            assertNull(DriveErrors.reasonOf(body), body)
        }
        assertNull(DriveErrors.reasonOf("""{"error":{"errors":[{"message":"no reason"}]}}"""))
        assertNull(DriveErrors.reasonOf("""{"error":{"errors":"not a list"}}"""))
    }
}
