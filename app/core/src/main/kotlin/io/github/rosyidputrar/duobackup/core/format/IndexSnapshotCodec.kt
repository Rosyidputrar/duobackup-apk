package io.github.rosyidputrar.duobackup.core.format

import io.github.rosyidputrar.duobackup.core.crypto.Dbe1Cipher
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Index snapshots: gzip-compressed JSON, wrapped in a DBE1 stream when encrypted. */
object IndexSnapshotCodec {
    /** The uncompressed JSON document. */
    fun toJson(snapshot: IndexSnapshot): String = FormatJson.encodeToString(IndexSnapshot.serializer(), snapshot)

    /** Writes the snapshot and closes `out`. */
    fun write(snapshot: IndexSnapshot, out: OutputStream, cipher: Dbe1Cipher? = null) {
        val sink = cipher?.wrap(out) ?: out
        GZIPOutputStream(sink).use { it.write(toJson(snapshot).toByteArray(Charsets.UTF_8)) }
    }

    /**
     * Reads a snapshot and closes `input`. Unknown fields are ignored; an unknown version is
     * rejected. Pass `cipher` for `.dbe` files.
     */
    fun read(input: InputStream, cipher: Dbe1Cipher? = null): IndexSnapshot {
        val source = cipher?.unwrap(input) ?: input
        val json = source.use {
            val text = try {
                GZIPInputStream(it).readBytes().toString(Charsets.UTF_8)
            } catch (e: ZipException) {
                throw FormatException.Malformed("index is not valid gzip", e)
            } catch (e: java.io.EOFException) {
                throw FormatException.Malformed("index gzip data is cut short", e)
            }
            // Drain so an encrypted stream is verified to its final frame.
            val sink = ByteArray(8192)
            do {
                val drained = it.read(sink)
            } while (drained >= 0)
            text
        }
        try {
            val root = FormatJson.parseToJsonElement(json) as? JsonObject
                ?: throw FormatException.Malformed("index is not a JSON object")
            if ((root["format"] as? JsonPrimitive)?.contentOrNull != IndexSnapshot.FORMAT) {
                throw FormatException.Malformed("not a DuoBackup index")
            }
            val version = (root["v"] as? JsonPrimitive)?.intOrNull
                ?: throw FormatException.Malformed("index has no version")
            if (version != IndexSnapshot.VERSION) {
                throw FormatException.UnsupportedVersion(IndexSnapshot.FORMAT, version.toString())
            }
            return FormatJson.decodeFromJsonElement(IndexSnapshot.serializer(), root)
        } catch (e: SerializationException) {
            throw FormatException.Malformed("index JSON is malformed", e)
        }
    }
}
