package io.github.rosyidputrar.duobackup.drive

import io.github.rosyidputrar.duobackup.core.transport.TransportException
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DriveApiTest {
    @TempDir
    lateinit var temp: File

    private val server = MockWebServer()
    private val tokens = FakeTokens()
    private lateinit var api: DriveApi

    @BeforeEach
    fun setUp() {
        server.start()
        api = DriveApi(OkHttpClient(), tokens, server.url("/"))
    }

    @AfterEach
    fun tearDown() = server.close()

    @Test
    fun `about reads the account and its quota from string numbers`() = runTest {
        server.enqueue(
            json(
                200,
                """{"user":{"emailAddress":"a@example.com"},"storageQuota":{"limit":"16106127360","usage":"1024","usageInDrive":"512","other":1}}"""
            )
        )
        val about = api.about()
        assertEquals("a@example.com", about.user?.emailAddress)
        assertEquals(DriveAbout.StorageQuota(16_106_127_360, 1024, 512), about.storageQuota)
        assertEquals(16_106_126_336, about.storageQuota.freeBytes)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/drive/v3/about", request.url.encodedPath)
        assertEquals("user(emailAddress),storageQuota", request.url.queryParameter("fields"))
        assertEquals("Bearer $SECRET_TOKEN-1", request.headers["Authorization"])
    }

    @Test
    fun `an account without a limit has unknown free space and usage never goes negative`() = runTest {
        server.enqueue(json(200, """{"storageQuota":{"usage":"5"}}"""))
        val about = api.about()
        assertNull(about.storageQuota.limit)
        assertNull(about.storageQuota.freeBytes)
        assertNull(about.user)
        assertEquals(0, DriveAbout.StorageQuota(limit = 10, usage = 30).freeBytes)
    }

    @Test
    fun `list follows page tokens`() = runTest {
        server.enqueue(
            json(200, """{"files":[{"id":"a","size":"3","appProperties":{"db":"part"}}],"nextPageToken":"next"}""")
        )
        server.enqueue(json(200, """{"files":[{"id":"b","trashed":true}]}"""))
        val files = api.listAllFiles("trashed = false", pageSize = 1)
        assertEquals(listOf("a", "b"), files.map { it.id })
        assertEquals(3L, files[0].size)
        assertEquals("part", files[0].appProperties["db"])
        assertTrue(files[1].trashed)
        val first = server.takeRequest()
        assertEquals("trashed = false", first.url.queryParameter("q"))
        assertEquals("1", first.url.queryParameter("pageSize"))
        assertNull(first.url.queryParameter("pageToken"))
        assertEquals("next", server.takeRequest().url.queryParameter("pageToken"))
    }

    @Test
    fun `create folder sends name parent and properties`() = runTest {
        server.enqueue(json(200, """{"id":"folder-1","name":"data"}"""))
        val created = api.createFolder("data", "root-1", mapOf("db" to "data"))
        assertEquals("folder-1", created.id)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals(
            """{"name":"data","mimeType":"application/vnd.google-apps.folder","parents":["root-1"],"appProperties":{"db":"data"}}""",
            request.body!!.utf8()
        )
        server.enqueue(json(200, """{"id":"root-1"}"""))
        api.createFolder("DuoBackup", null, mapOf("db" to "root"))
        assertFalse(server.takeRequest().body!!.utf8().contains("parents"))
    }

    @Test
    fun `get update and delete address the file by id`() = runTest {
        server.enqueue(json(200, """{"id":"f1","size":"10","md5Checksum":"abc","trashed":false}"""))
        server.enqueue(json(200, """{"id":"f1","appProperties":{"db":"part"}}"""))
        server.enqueue(MockResponse.Builder().code(204).build())
        assertEquals("abc", api.getFile("f1", "id,size,md5Checksum,trashed").md5Checksum)
        assertEquals(
            "part",
            api.updateFile("f1", DriveFileMetadata(appProperties = mapOf("db" to "part"))).appProperties["db"]
        )
        api.deleteFile("f1")
        val get = server.takeRequest()
        assertEquals("/drive/v3/files/f1", get.url.encodedPath)
        assertEquals("id,size,md5Checksum,trashed", get.url.queryParameter("fields"))
        val patch = server.takeRequest()
        assertEquals("PATCH", patch.method)
        assertEquals("""{"appProperties":{"db":"part"}}""", patch.body!!.utf8())
        assertEquals("DELETE", server.takeRequest().method)
    }

    @Test
    fun `download writes the content and reports progress`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("hello world").build())
        val target = File(temp, "out.bin")
        val seen = ArrayList<Long>()
        assertEquals(11, api.download("f1", target) { seen += it })
        assertEquals("hello world", target.readText())
        assertEquals(11L, seen.last())
        val request = server.takeRequest()
        assertEquals("media", request.url.queryParameter("alt"))
        assertNull(request.headers["Range"])
    }

    @Test
    fun `download resumes with a range and appends`() = runTest {
        val target = File(temp, "out.bin").apply { writeText("hello ") }
        server.enqueue(MockResponse.Builder().code(206).body("world").build())
        assertEquals(11, api.download("f1", target, rangeStart = 6))
        assertEquals("hello world", target.readText())
        assertEquals("bytes=6-", server.takeRequest().headers["Range"])
    }

    @Test
    fun `download starts over when the server ignores the range`() = runTest {
        val target = File(temp, "out.bin").apply { writeText("stale ") }
        server.enqueue(MockResponse.Builder().code(200).body("hello world").build())
        assertEquals(11, api.download("f1", target, rangeStart = 6))
        assertEquals("hello world", target.readText())
    }

    @Test
    fun `download that was already complete keeps the file`() = runTest {
        val target = File(temp, "out.bin").apply { writeText("hello world") }
        server.enqueue(MockResponse.Builder().code(416).build())
        assertEquals(11, api.download("f1", target, rangeStart = 11))
        assertEquals("hello world", target.readText())
    }

    @Test
    fun `download of a missing file is not found`() = runTest {
        server.enqueue(json(404, errorBody(404, "notFound")))
        assertFailsWith<TransportException.NotFound> { api.download("gone", File(temp, "out.bin")) }
    }

    @Test
    fun `a rejected token is refreshed once and the request repeated`() = runTest {
        server.enqueue(json(401, errorBody(401, "authError")))
        server.enqueue(json(200, """{"id":"f1"}"""))
        assertEquals("f1", api.getFile("f1").id)
        assertEquals(1, tokens.invalidated)
        assertEquals("Bearer $SECRET_TOKEN-1", server.takeRequest().headers["Authorization"])
        assertEquals("Bearer $SECRET_TOKEN-2", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `a second rejection means the user must authorize again`() = runTest {
        server.enqueue(json(401, errorBody(401, "authError")))
        server.enqueue(json(401, errorBody(401, "authError")))
        assertFailsWith<TransportException.AuthRequired> { api.getFile("f1") }
        assertEquals(1, tokens.invalidated)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `no request is sent when there is no token`() = runTest {
        tokens.authRequired = true
        assertFailsWith<TransportException.AuthRequired> { api.about() }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a broken connection and a malformed body are transient`() = runTest {
        server.enqueue(json(200, "this is not json"))
        assertFailsWith<TransportException.Transient> { api.getFile("f1") }
        server.close()
        val error = assertFailsWith<TransportException.Transient> { api.getFile("f1") }
        // Coroutines may wrap the failure in a copy of itself to keep both stack traces.
        assertTrue(generateSequence<Throwable>(error) { it.cause }.any { it is java.io.IOException })
    }

    @Test
    fun `the access token never appears in any failure`() = runTest {
        val responses = listOf(
            json(400, """{"error":{"errors":[{"reason":"invalid"}],"message":"Bearer $SECRET_TOKEN-1"}}"""),
            json(403, errorBody(403, "storageQuotaExceeded")),
            json(403, errorBody(403, "userRateLimitExceeded")),
            json(403, errorBody(403, "insufficientPermissions")),
            json(404, errorBody(404, "notFound")),
            json(500, "Bearer $SECRET_TOKEN-1"),
            json(200, "{broken $SECRET_TOKEN-1")
        )
        val failures = ArrayList<Throwable>()
        for (response in responses) {
            server.enqueue(response)
            failures += assertFailsWith<TransportException> { api.getFile("f1") }
        }
        server.enqueue(json(401, "$SECRET_TOKEN-1"))
        server.enqueue(json(401, "$SECRET_TOKEN-2"))
        failures += assertFailsWith<TransportException> { api.getFile("f1") }
        server.close()
        failures += assertFailsWith<TransportException> { api.about() }

        assertEquals(9, failures.size)
        for (failure in failures) {
            var current: Throwable? = failure
            while (current != null) {
                val text = current.toString() + current.stackTraceToString()
                assertFalse(text.contains("SECRET"), "token leaked through ${failure.javaClass.simpleName}")
                current = current.cause
            }
        }
    }

    @Test
    fun `resumable byte ranges are read from the Range header`() {
        assertContentEquals(
            longArrayOf(0, 0, 1, 262_144, 0),
            listOf(
                null,
                "",
                "bytes=0-0",
                "bytes=0-262143",
                "garbage"
            ).map(ResumableUploader::confirmedBytes).toLongArray()
        )
    }
}
