package io.github.rosyidputrar.duobackup.drive

import io.github.rosyidputrar.duobackup.core.transport.TransportException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/** A thin client for the Drive v3 REST operations DuoBackup needs. */
class DriveApi(
    client: OkHttpClient,
    tokens: AccessTokenProvider,
    private val baseUrl: HttpUrl = DEFAULT_BASE_URL.toHttpUrl(),
    private val io: CoroutineDispatcher = Dispatchers.IO
) {
    internal val http = DriveHttp(client, tokens)

    suspend fun about(fields: String = "user(emailAddress),storageQuota"): DriveAbout = getJson(
        url("drive/v3/about") { addQueryParameter("fields", fields) },
        DriveAbout.serializer()
    )

    suspend fun listFiles(
        q: String,
        fields: String = DEFAULT_LIST_FIELDS,
        pageToken: String? = null,
        pageSize: Int = 1000
    ): DriveFileList = getJson(
        url("drive/v3/files") {
            addQueryParameter("q", q)
            addQueryParameter("fields", fields)
            addQueryParameter("pageSize", pageSize.toString())
            addQueryParameter("spaces", "drive")
            if (pageToken != null) addQueryParameter("pageToken", pageToken)
        },
        DriveFileList.serializer()
    )

    /** Follows `nextPageToken` until every match has been read. */
    suspend fun listAllFiles(q: String, fields: String = DEFAULT_LIST_FIELDS, pageSize: Int = 1000): List<DriveFile> {
        val all = ArrayList<DriveFile>()
        var token: String? = null
        do {
            val page = listFiles(q, fields, token, pageSize)
            all += page.files
            token = page.nextPageToken
        } while (token != null)
        return all
    }

    suspend fun createFolder(name: String, parentId: String?, appProperties: Map<String, String>): DriveFile {
        val metadata = DriveFileMetadata(name, FOLDER_MIME_TYPE, parentId?.let(::listOf), appProperties)
        val target = url("drive/v3/files") { addQueryParameter("fields", DEFAULT_FILE_FIELDS) }
        return http.executeOk { Request.Builder().url(target).post(json(metadata)) }.use { response ->
            decode(response.body.string(), DriveFile.serializer())
        }
    }

    suspend fun getFile(id: String, fields: String = DEFAULT_FILE_FIELDS): DriveFile =
        getJson(url("drive/v3/files/$id") { addQueryParameter("fields", fields) }, DriveFile.serializer())

    suspend fun updateFile(id: String, metadata: DriveFileMetadata): DriveFile {
        val target = url("drive/v3/files/$id") { addQueryParameter("fields", DEFAULT_FILE_FIELDS) }
        return http.executeOk { Request.Builder().url(target).patch(json(metadata)) }.use { response ->
            decode(response.body.string(), DriveFile.serializer())
        }
    }

    suspend fun deleteFile(id: String) {
        http.executeOk { Request.Builder().url(url("drive/v3/files/$id")).delete() }.close()
    }

    /**
     * Downloads a file's content into `target`. With `rangeStart` above zero the bytes are
     * appended, resuming an earlier download; if the server ignores the range, the file is
     * rewritten from the start.
     *
     * @return the size of `target` afterwards
     */
    suspend fun download(id: String, target: File, rangeStart: Long = 0, progress: (Long) -> Unit = {}): Long {
        val source = url("drive/v3/files/$id") { addQueryParameter("alt", "media") }
        val response = http.execute {
            Request.Builder().url(source).get().apply {
                if (rangeStart > 0) header("Range", "bytes=$rangeStart-")
            }
        }
        return response.use {
            // 416 means there is nothing after rangeStart: the earlier download was complete.
            if (it.code == 416 && rangeStart > 0) return@use target.length()
            if (!it.isSuccessful) throw http.failure(it)
            val append = it.code == 206 && rangeStart > 0
            withContext(io) {
                try {
                    FileOutputStream(target, append).use { out ->
                        var written = if (append) rangeStart else 0L
                        val buffer = ByteArray(COPY_BUFFER)
                        val input = it.body.byteStream()
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            written += read
                            progress(written)
                        }
                        written
                    }
                } catch (e: IOException) {
                    throw TransportException.Transient(e)
                }
            }
        }
    }

    internal fun url(path: String, configure: HttpUrl.Builder.() -> Unit = {}): HttpUrl =
        baseUrl.newBuilder().addPathSegments(path).apply(configure).build()

    /**
     * Parses a response body. The parser's own error is not kept as the cause, because its
     * message quotes the text it failed on, and response text does not belong in logs.
     */
    internal fun <T> decode(text: String, serializer: kotlinx.serialization.KSerializer<T>): T = try {
        DriveJson.decodeFromString(serializer, text)
    } catch (e: kotlinx.serialization.SerializationException) {
        throw TransportException.Transient(IOException("malformed Drive response"))
    } catch (e: IllegalArgumentException) {
        throw TransportException.Transient(IOException("malformed Drive response"))
    }

    internal fun json(metadata: DriveFileMetadata): RequestBody =
        DriveJson.encodeToString(DriveFileMetadata.serializer(), metadata).toRequestBody(JSON_TYPE)

    private suspend fun <T> getJson(target: HttpUrl, serializer: kotlinx.serialization.KSerializer<T>): T =
        http.executeOk { Request.Builder().url(target).get() }.use { decode(it.body.string(), serializer) }

    companion object {
        const val DEFAULT_BASE_URL: String = "https://www.googleapis.com/"
        const val DEFAULT_FILE_FIELDS: String = "id,name,size,md5Checksum,trashed,appProperties,parents,createdTime"
        const val DEFAULT_LIST_FIELDS: String = "nextPageToken,files($DEFAULT_FILE_FIELDS)"
        private const val COPY_BUFFER = 64 * 1024
        internal val JSON_TYPE = "application/json; charset=UTF-8".toMediaType()
    }
}
