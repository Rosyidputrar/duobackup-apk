package io.github.rosyidputrar.duobackup.drive

import io.github.rosyidputrar.duobackup.core.transport.TransportException
import io.github.rosyidputrar.duobackup.core.util.Clock
import java.io.File
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCoroutinesApi::class)
class ResumableUploaderTest {
    @TempDir
    lateinit var temp: File

    private val server = MockWebServer()
    private val tokens = FakeTokens()
    private val store = MemorySessionStore()
    private val key = UploadKey("a".repeat(64), 0)
    private val metadata = DriveFileMetadata(name = "aaaaaaaaaaaaaaaa.0.dbp", parents = listOf("data-dir"))
    private val unit = ResumableUploader.CHUNK_UNIT.toInt()

    /** Every reading is 10 s after the last, so a 256 KiB chunk always "takes" the target time. */
    private var now = 1_000_000L
    private val clock = Clock {
        now += 10_000
        now
    }
    private lateinit var uploader: ResumableUploader
    private lateinit var file: File
    private lateinit var content: ByteArray

    @BeforeEach
    fun setUp() {
        server.start()
        val api = DriveApi(OkHttpClient(), tokens, server.url("/"))
        uploader = ResumableUploader(api, store, clock, random = Random(1), initialChunkBytes = unit.toLong())
        content = Random(7).nextBytes(2 * unit + 1000)
        file = File(temp, "part.scratch").apply { writeBytes(content) }
    }

    @AfterEach
    fun tearDown() = server.close()

    private val sessionUri get() = server.url("/upload/session/one").toString()

    private fun opened(uri: String = sessionUri) = MockResponse.Builder().code(200).addHeader("Location", uri).build()

    private fun incomplete(confirmed: Int?) = MockResponse.Builder().code(308)
        .apply { if (confirmed != null && confirmed > 0) addHeader("Range", "bytes=0-${confirmed - 1}") }
        .build()

    private fun done(id: String = "file-1", bytes: ByteArray = content) =
        json(200, """{"id":"$id","size":"${bytes.size}","md5Checksum":"${md5Hex(bytes)}"}""")

    private fun enqueue(vararg responses: MockResponse) = responses.forEach(server::enqueue)

    private fun requests(): List<RecordedRequest> = List(server.requestCount) { server.takeRequest() }

    private fun savedSession(createdAt: Long = now, total: Long = content.size.toLong()) =
        SavedSession(sessionUri, total, createdAt, failures = 0, scratchPath = file.path)

    @Test
    fun `uploads in chunks and verifies the result`() = runTest {
        enqueue(opened(), incomplete(unit), incomplete(2 * unit), done())
        val progress = ArrayList<Long>()
        val result = uploader.upload(key, metadata, file) { progress += it }

        assertEquals("file-1", result.id)
        assertEquals(listOf(unit.toLong(), 2L * unit), progress)
        assertNull(store.sessions[key])
        assertEquals(1, store.saves)

        val (open, first, second, last) = requests()
        assertEquals("POST", open.method)
        assertEquals("/upload/drive/v3/files", open.url.encodedPath)
        assertEquals("resumable", open.url.queryParameter("uploadType"))
        assertEquals("id,size,md5Checksum", open.url.queryParameter("fields"))
        assertEquals("application/octet-stream", open.headers["X-Upload-Content-Type"])
        assertEquals(content.size.toString(), open.headers["X-Upload-Content-Length"])
        assertEquals("""{"name":"aaaaaaaaaaaaaaaa.0.dbp","parents":["data-dir"]}""", open.body!!.utf8())
        assertEquals("Bearer $SECRET_TOKEN-1", open.headers["Authorization"])

        assertEquals("bytes 0-${unit - 1}/${content.size}", first.headers["Content-Range"])
        assertEquals("bytes $unit-${2 * unit - 1}/${content.size}", second.headers["Content-Range"])
        assertEquals("bytes ${2 * unit}-${content.size - 1}/${content.size}", last.headers["Content-Range"])
        assertEquals("/upload/session/one", first.url.encodedPath)
        assertContentEquals(
            content,
            first.body!!.toByteArray() + second.body!!.toByteArray() + last.body!!.toByteArray()
        )
        for (chunk in listOf(first, second)) assertEquals(0, chunk.body!!.size % unit)
        assertEquals(1000, last.body!!.size)
    }

    @Test
    fun `continues from where the server says when it accepts less than was sent`() = runTest {
        enqueue(opened(), incomplete(100_000), incomplete(100_000 + unit), done())
        uploader.upload(key, metadata, file)
        val sent = requests().drop(1)
        assertEquals("bytes 0-${unit - 1}/${content.size}", sent[0].headers["Content-Range"])
        assertEquals("bytes 100000-${100_000 + unit - 1}/${content.size}", sent[1].headers["Content-Range"])
        assertEquals("bytes ${100_000 + unit}-${content.size - 1}/${content.size}", sent[2].headers["Content-Range"])
        assertContentEquals(content.copyOfRange(100_000, 100_000 + unit), sent[1].body!!.toByteArray())
        assertEquals(0, sent[1].body!!.size % unit)
    }

    @Test
    fun `resumes a persisted session after a restart without opening a new one`() = runTest {
        store.sessions[key] = savedSession()
        enqueue(incomplete(2 * unit), done())
        val progress = ArrayList<Long>()
        assertEquals("file-1", uploader.upload(key, metadata, file) { progress += it }.id)

        val (query, rest) = requests()
        assertEquals("PUT", query.method)
        assertEquals("bytes */${content.size}", query.headers["Content-Range"])
        assertEquals(0, query.body?.size ?: 0)
        assertEquals("bytes ${2 * unit}-${content.size - 1}/${content.size}", rest.headers["Content-Range"])
        assertEquals(listOf(2L * unit), progress)
        assertNull(store.sessions[key])
    }

    @Test
    fun `a persisted session that already finished is returned as is`() = runTest {
        store.sessions[key] = savedSession()
        enqueue(done("already-there"))
        assertEquals("already-there", uploader.upload(key, metadata, file).id)
        assertEquals(1, server.requestCount)
        assertNull(store.sessions[key])
    }

    @Test
    fun `a session the server forgot is replaced and the upload starts from zero`() = runTest {
        store.sessions[key] = savedSession()
        val fresh = server.url("/upload/session/two").toString()
        enqueue(json(404, errorBody(404, "notFound")), opened(fresh), incomplete(unit), incomplete(2 * unit), done())
        uploader.upload(key, metadata, file)
        val all = requests()
        assertEquals(listOf("PUT", "POST", "PUT", "PUT", "PUT"), all.map { it.method })
        assertEquals("/upload/session/one", all[0].url.encodedPath)
        assertEquals("/upload/session/two", all[2].url.encodedPath)
        assertEquals("bytes 0-${unit - 1}/${content.size}", all[2].headers["Content-Range"])
    }

    @Test
    fun `stale and mismatched persisted sessions are discarded`() = runTest {
        store.sessions[key] = savedSession(createdAt = now - ResumableUploader.SESSION_MAX_AGE_MS - 60_000)
        enqueue(opened(), done())
        uploader = ResumableUploader(
            DriveApi(OkHttpClient(), tokens, server.url("/")),
            store,
            clock,
            initialChunkBytes = 64L * 1024 * 1024
        )
        uploader.upload(key, metadata, file)
        assertEquals("POST", server.takeRequest().method)
        server.takeRequest()

        store.sessions[key] = savedSession(total = 5)
        enqueue(opened(), done())
        uploader.upload(key, metadata, file)
        assertEquals("POST", server.takeRequest().method)
        server.takeRequest()

        store.sessions[key] = savedSession().copy(scratchPath = "/somewhere/else")
        enqueue(opened(), done())
        uploader.upload(key, metadata, file)
        assertEquals("POST", server.takeRequest().method)
    }

    @Test
    fun `server errors back off and then ask the server where it is`() = runTest {
        enqueue(
            opened(),
            json(503, "unavailable"),
            json(503, "unavailable"),
            incomplete(null),
            incomplete(unit),
            incomplete(
                2 * unit
            ),
            done()
        )
        uploader.upload(key, metadata, file)
        assertTrue(currentTime > 0, "backoff should have waited")
        val all = requests()
        assertEquals("bytes 0-${unit - 1}/${content.size}", all[1].headers["Content-Range"])
        assertEquals("bytes */${content.size}", all[2].headers["Content-Range"])
        assertEquals("bytes */${content.size}", all[3].headers["Content-Range"])
        assertEquals("bytes 0-${unit - 1}/${content.size}", all[4].headers["Content-Range"])
        assertEquals(7, all.size)
    }

    @Test
    fun `a rate limit waits at least as long as the server asks and keeps the session`() = runTest {
        val limited = MockResponse.Builder().code(403).addHeader("Retry-After", "120")
            .body(errorBody(403, "userRateLimitExceeded")).build()
        enqueue(opened(), limited, incomplete(unit), incomplete(2 * unit), done())
        uploader.upload(key, metadata, file)
        assertTrue(currentTime >= 120_000, "waited only $currentTime ms")
        val all = requests()
        assertEquals(1, all.count { it.method == "POST" })
        assertEquals("bytes */${content.size}", all[2].headers["Content-Range"])
        assertEquals("bytes $unit-${2 * unit - 1}/${content.size}", all[3].headers["Content-Range"])
    }

    @Test
    fun `a full Drive stops the upload at once and keeps the session for later`() = runTest {
        enqueue(opened(), incomplete(unit), json(403, errorBody(403, "storageQuotaExceeded")))
        assertFailsWith<TransportException.QuotaExceeded> { uploader.upload(key, metadata, file) }
        assertEquals(sessionUri, store.sessions[key]?.uri)
        assertEquals(3, server.requestCount)
        assertEquals(0L, currentTime)
    }

    @Test
    fun `a rejected token that cannot be refreshed stops the upload`() = runTest {
        enqueue(opened(), json(401, ""), json(401, ""))
        assertFailsWith<TransportException.AuthRequired> { uploader.upload(key, metadata, file) }
        assertNotNull(store.sessions[key])
    }

    @Test
    fun `a checksum mismatch deletes the remote file and uploads again`() = runTest {
        val wrong = json(200, """{"id":"bad","size":"${content.size}","md5Checksum":"${"0".repeat(32)}"}""")
        enqueue(opened(), wrong, MockResponse.Builder().code(204).build(), opened(), done("good"))
        uploader =
            ResumableUploader(
                DriveApi(OkHttpClient(), tokens, server.url("/")),
                store,
                clock,
                initialChunkBytes =
                64L * 1024 * 1024
            )
        assertEquals("good", uploader.upload(key, metadata, file).id)
        val all = requests()
        assertEquals(listOf("POST", "PUT", "DELETE", "POST", "PUT"), all.map { it.method })
        assertEquals("/drive/v3/files/bad", all[2].url.encodedPath)
        assertNull(store.sessions[key])
    }

    @Test
    fun `a wrong size is a mismatch too and a missing remote file is fine`() = runTest {
        val short = json(200, """{"id":"short","size":"5","md5Checksum":"${md5Hex(content)}"}""")
        enqueue(opened(), short, json(404, errorBody(404, "notFound")), opened(), done("good"))
        uploader =
            ResumableUploader(
                DriveApi(OkHttpClient(), tokens, server.url("/")),
                store,
                clock,
                initialChunkBytes =
                64L * 1024 * 1024
            )
        assertEquals("good", uploader.upload(null, metadata, file).id)
        assertEquals(0, store.saves)
    }

    @Test
    fun `a session rejected in the middle is replaced`() = runTest {
        val fresh = server.url("/upload/session/two").toString()
        enqueue(
            opened(),
            incomplete(unit),
            json(400, errorBody(400, "badRequest")),
            opened(fresh),
            incomplete(unit),
            incomplete(
                2 * unit
            ),
            done()
        )
        uploader.upload(key, metadata, file)
        val all = requests()
        assertEquals(2, all.count { it.method == "POST" })
        assertEquals("/upload/session/two", all.last().url.encodedPath)
        assertEquals("bytes 0-${unit - 1}/${content.size}", all[4].headers["Content-Range"])
    }

    @Test
    fun `a session that vanishes during a chunk is replaced`() = runTest {
        enqueue(opened(), json(410, ""), opened(), incomplete(unit), incomplete(2 * unit), done())
        uploader.upload(key, metadata, file)
        assertEquals(2, requests().count { it.method == "POST" })
    }

    @Test
    fun `a session that cannot be opened fails without retrying`() = runTest {
        enqueue(json(400, errorBody(400, "invalidParameter")))
        assertFailsWith<TransportException.Permanent> { uploader.upload(key, metadata, file) }
        assertEquals(1, server.requestCount)
        assertNull(store.sessions[key])

        enqueue(MockResponse.Builder().code(200).build(), opened(), incomplete(unit), incomplete(2 * unit), done())
        assertEquals("file-1", uploader.upload(key, metadata, file).id)
    }

    @Test
    fun `gives up after one hundred failures in a row`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.method == "POST") opened() else json(503, "unavailable")
        }
        assertFailsWith<TransportException.Transient> { uploader.upload(key, metadata, file) }
        assertEquals(ResumableUploader.MAX_FAILURES, server.requestCount - 1)
        assertEquals(ResumableUploader.MAX_FAILURES - 1, store.sessions[key]?.failures)
    }

    @Test
    fun `progress resets the failure count so a slow upload stays alive`() = runTest {
        var puts = 0
        val fake = FakeDrive()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                // Every other chunk request fails, far more than 100 times in total.
                if (request.method == "PUT" && request.headers["Content-Range"]?.startsWith("bytes */") == false) {
                    if (puts++ % 2 == 0) return json(503, "unavailable")
                }
                return fake.dispatch(request)
            }
        }
        val big = File(temp, "big.scratch").apply { writeBytes(Random(3).nextBytes(130 * unit)) }
        val result = uploader.upload(key, metadata, big)
        assertEquals(130L * unit, result.size)
        assertTrue(puts > 200, "only $puts chunk requests")
    }

    @Test
    fun `an empty file is uploaded with a single empty request`() = runTest {
        val empty = File(temp, "empty").apply { writeBytes(ByteArray(0)) }
        enqueue(opened(), done(bytes = ByteArray(0)))
        uploader.upload(null, metadata, empty)
        val put = requests()[1]
        assertEquals("bytes */0", put.headers["Content-Range"])
    }

    @Test
    fun `chunk size follows the measured speed in steps of 256 KiB`() {
        val unitBytes = ResumableUploader.CHUNK_UNIT
        assertEquals(unitBytes, ResumableUploader.nextChunkSize(unitBytes, 10_000))
        assertEquals(16 * unitBytes, ResumableUploader.nextChunkSize(1024 * 1024, 2_500))
        assertEquals(2 * unitBytes, ResumableUploader.nextChunkSize(700_000, 10_000))
        assertEquals(ResumableUploader.MIN_CHUNK_BYTES, ResumableUploader.nextChunkSize(1000, 60_000))
        assertEquals(ResumableUploader.MAX_CHUNK_BYTES, ResumableUploader.nextChunkSize(1024L * 1024 * 1024, 1_000))
        assertEquals(ResumableUploader.MAX_CHUNK_BYTES, ResumableUploader.nextChunkSize(1024, 0))
        for (elapsed in listOf(1L, 37, 999, 10_000, 123_456)) {
            assertEquals(0, ResumableUploader.nextChunkSize(5_000_000, elapsed) % unitBytes)
        }
    }

    @Test
    fun `the chunk size grows on a fast connection`() = runTest {
        var tick = 0L
        // One millisecond per reading: every chunk looks instant.
        val fast = Clock { ++tick }
        uploader =
            ResumableUploader(
                DriveApi(OkHttpClient(), tokens, server.url("/")),
                store,
                fast,
                initialChunkBytes = unit.toLong()
            )
        enqueue(opened(), incomplete(unit), done())
        uploader.upload(null, metadata, file)
        val all = requests()
        assertEquals(unit, all[1].body!!.size)
        assertEquals(content.size - unit, all[2].body!!.size)
    }
}
