package io.github.rosyidputrar.duobackup.core.format

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Names of remote files. Original file names are never used remotely (plan §4.1). */
object RemoteNames {
    const val KEY_FILE: String = "duobackup-key.json"
    const val STORAGE_TITLE: String = "DuoBackup"

    private val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    /** `<first 16 hex of oid>.<part index>.dbp` */
    fun part(oid: String, partIndex: Int): String {
        require(oid.length >= 16 && partIndex >= 0) { "invalid oid or part index" }
        return "${oid.substring(0, 16)}.$partIndex.dbp"
    }

    /** `duobackup-index-<UTC timestamp>.json.gz`, with `.dbe` appended when encrypted. */
    fun index(generatedAt: Long, encrypted: Boolean): String {
        val name = "duobackup-index-${timestamp.format(Instant.ofEpochMilli(generatedAt))}.json.gz"
        return if (encrypted) "$name.dbe" else name
    }
}
