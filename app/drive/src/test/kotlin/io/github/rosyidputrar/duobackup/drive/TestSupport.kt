package io.github.rosyidputrar.duobackup.drive

import io.github.rosyidputrar.duobackup.core.transport.TransportException
import io.github.rosyidputrar.duobackup.core.util.Hex
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest

const val SECRET_TOKEN = "ya29.SECRET-token-value-that-must-never-leak"

/** Hands out `token-1`, `token-2`, ... after each invalidation, or fails when told to. */
class FakeTokens(private val prefix: String = SECRET_TOKEN) : AccessTokenProvider {
    var generation = 1
    var invalidated = 0
    var authRequired = false

    override suspend fun token(): String {
        if (authRequired) throw TransportException.AuthRequired()
        return "$prefix-$generation"
    }

    override suspend fun invalidate(token: String) {
        invalidated++
        generation++
    }
}

class MemorySessionStore : UploadSessionStore {
    val sessions = HashMap<UploadKey, SavedSession>()
    var saves = 0

    override suspend fun load(key: UploadKey): SavedSession? = sessions[key]

    override suspend fun save(key: UploadKey, session: SavedSession) {
        saves++
        sessions[key] = session
    }

    override suspend fun clear(key: UploadKey) {
        sessions.remove(key)
    }
}

class MemoryFolderCache(var folders: DriveFolders? = null) : DriveFolderCache {
    override suspend fun load(): DriveFolders? = folders

    override suspend fun save(folders: DriveFolders?) {
        this.folders = folders
    }
}

fun md5Hex(bytes: ByteArray): String = Hex.encode(MessageDigest.getInstance("MD5").digest(bytes))

fun errorBody(code: Int, reason: String): String =
    """{"error":{"errors":[{"domain":"usageLimits","reason":"$reason","message":"m"}],"code":$code,"message":"m"}}"""

fun json(code: Int, body: String): MockResponse =
    MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

/**
 * A small in-memory Drive: folders, files with `appProperties`, the query clauses DuoBackup
 * uses, ranged downloads and resumable uploads.
 */
class FakeDrive : Dispatcher() {
    class Entry(
        val id: String,
        var name: String?,
        val mimeType: String?,
        val parents: List<String>,
        var properties: Map<String, String>,
        var content: ByteArray = ByteArray(0),
        var trashed: Boolean = false
    )

    private class Session(val metadata: JsonObject, val total: Long, var received: ByteArray = ByteArray(0))

    val entries = ConcurrentHashMap<String, Entry>()
    val requests = ArrayList<String>()
    var quotaLimit: Long? = 1_000_000
    var quotaUsage: Long = 400_000
    var corruptNextUpload = false
    private val ids = AtomicInteger()
    private val sessions = ConcurrentHashMap<String, Session>()

    fun add(
        name: String,
        parents: List<String>,
        properties: Map<String, String>,
        folder: Boolean = true,
        content: ByteArray = ByteArray(0)
    ): Entry {
        val entry =
            Entry("id-${ids.incrementAndGet()}", name, FOLDER_MIME_TYPE.takeIf { folder }, parents, properties, content)
        entries[entry.id] = entry
        return entry
    }

    fun files(kind: String): List<Entry> = entries.values.filter { it.properties["db"] == kind && !it.trashed }

    override fun dispatch(request: RecordedRequest): MockResponse {
        val url = request.url
        val path = url.encodedPath
        synchronized(requests) { requests += "${request.method} $path" }
        return when {
            path == "/drive/v3/about" -> json(
                200,
                buildJsonObject {
                    putJsonObject("user") { put("emailAddress", "owner@example.com") }
                    putJsonObject("storageQuota") {
                        quotaLimit?.let { put("limit", it.toString()) }
                        put("usage", quotaUsage.toString())
                        put("usageInDrive", "1")
                    }
                }.toString()
            )
            path == "/drive/v3/files" && request.method == "GET" -> list(url.queryParameter("q").orEmpty())
            path == "/drive/v3/files" && request.method == "POST" -> create(request)
            path == "/upload/drive/v3/files" -> openSession(request)
            path.startsWith("/upload/session/") -> chunk(path.substringAfterLast('/'), request)
            path.startsWith("/drive/v3/files/") -> file(path.substringAfterLast('/'), request)
            else -> json(404, errorBody(404, "notFound"))
        }
    }

    private fun describe(entry: Entry): JsonObject = buildJsonObject {
        put("id", entry.id)
        entry.name?.let { put("name", it) }
        put("trashed", entry.trashed)
        if (entry.mimeType == null) {
            put("size", entry.content.size.toString())
            put("md5Checksum", md5Hex(entry.content))
        }
        putJsonArray("parents") { entry.parents.forEach { add(JsonPrimitive(it)) } }
        putJsonObject("appProperties") { entry.properties.forEach { (key, value) -> put(key, value) } }
    }

    private fun list(q: String): MockResponse {
        val parents = Regex("'([^']*)' in parents").findAll(q).map { it.groupValues[1] }.toList()
        val properties = Regex("appProperties has \\{ key='([^']*)' and value='([^']*)' \\}").findAll(q)
            .map { it.groupValues[1] to it.groupValues[2] }.toList()
        val foldersOnly = q.contains("mimeType = '$FOLDER_MIME_TYPE'")
        val matches = entries.values.filter { entry ->
            !entry.trashed &&
                parents.all { it in entry.parents } &&
                properties.all { (key, value) -> entry.properties[key] == value } &&
                (!foldersOnly || entry.mimeType == FOLDER_MIME_TYPE)
        }.sortedBy { it.id.substringAfter('-').toInt() }
        return json(
            200,
            buildJsonObject {
                put("files", buildJsonArray { matches.forEach { add(describe(it)) } })
            }.toString()
        )
    }

    private fun create(request: RecordedRequest): MockResponse {
        val body = DriveJson.parseToJsonElement(request.body!!.utf8()).jsonObject
        val entry = add(
            body["name"]!!.jsonPrimitive.content,
            body["parents"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
            body["appProperties"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
        )
        return json(200, describe(entry).toString())
    }

    private fun file(id: String, request: RecordedRequest): MockResponse {
        val entry = entries[id] ?: return json(404, errorBody(404, "notFound"))
        return when (request.method) {
            "DELETE" -> {
                entries.remove(id)
                MockResponse.Builder().code(204).build()
            }
            "PATCH" -> {
                val body = DriveJson.parseToJsonElement(request.body!!.utf8()).jsonObject
                body["name"]?.let { entry.name = it.jsonPrimitive.content }
                body["appProperties"]?.let { props ->
                    entry.properties =
                        props.jsonObject.mapValues { it.value.jsonPrimitive.content }
                }
                json(200, describe(entry).toString())
            }
            else -> if (request.url.queryParameter("alt") ==
                "media"
            ) {
                download(entry, request)
            } else {
                json(200, describe(entry).toString())
            }
        }
    }

    private fun download(entry: Entry, request: RecordedRequest): MockResponse {
        val start = request.headers["Range"]?.removePrefix("bytes=")?.removeSuffix("-")?.toInt()
        return when {
            start == null -> MockResponse.Builder().code(200).body(okio.Buffer().write(entry.content)).build()
            start >= entry.content.size -> MockResponse.Builder().code(416).build()
            else -> MockResponse.Builder().code(206)
                .body(okio.Buffer().write(entry.content, start, entry.content.size - start)).build()
        }
    }

    private fun openSession(request: RecordedRequest): MockResponse {
        val id = "s${ids.incrementAndGet()}"
        sessions[id] = Session(
            DriveJson.parseToJsonElement(request.body!!.utf8()).jsonObject,
            request.headers["X-Upload-Content-Length"]!!.toLong()
        )
        return MockResponse.Builder().code(
            200
        ).addHeader("Location", request.url.resolve("/upload/session/$id").toString()).build()
    }

    private fun chunk(id: String, request: RecordedRequest): MockResponse {
        val session = sessions[id] ?: return json(404, errorBody(404, "notFound"))
        val range = request.headers["Content-Range"]!!
        if (!range.startsWith("bytes */")) {
            val start = range.removePrefix("bytes ").substringBefore('-').toLong()
            if (start == session.received.size.toLong()) session.received += request.body!!.toByteArray()
        }
        if (session.received.size.toLong() < session.total) {
            val builder = MockResponse.Builder().code(308)
            if (session.received.isNotEmpty()) builder.addHeader("Range", "bytes=0-${session.received.size - 1}")
            return builder.build()
        }
        sessions.remove(id)
        val entry = add(
            session.metadata["name"]!!.jsonPrimitive.content,
            session.metadata["parents"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
            session.metadata["appProperties"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty(),
            folder = false,
            content = session.received
        )
        if (corruptNextUpload) {
            corruptNextUpload = false
            entry.content = entry.content.clone().also { it[0] = (it[0].toInt() xor 1).toByte() }
        }
        return json(200, describe(entry).toString())
    }
}
