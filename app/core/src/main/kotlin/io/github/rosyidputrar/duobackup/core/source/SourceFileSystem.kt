package io.github.rosyidputrar.duobackup.core.source

import io.github.rosyidputrar.duobackup.core.util.ExcludeMatcher
import java.io.InputStream

/** A storage volume the app can read. `id` is `primary` or the volume's UUID. */
data class SourceVolume(val id: String, val label: String, val removable: Boolean)

/** One regular file found by a walk. `path` is relative to the volume root. */
data class SourceEntry(val volume: String, val path: String, val size: Long, val mtime: Long)

/** @property unreadable entries skipped because they could not be read */
data class WalkSummary(val files: Long, val unreadable: Long)

/** Read access to the files being backed up. */
interface SourceFileSystem {
    fun volumes(): List<SourceVolume>

    /**
     * Visits every regular file under `roots` ("" is the whole volume) that `excludes` does not
     * match. Symbolic links are not followed, and unreadable entries are counted, not fatal.
     */
    fun walk(volume: String, roots: List<String>, excludes: ExcludeMatcher, visitor: (SourceEntry) -> Unit): WalkSummary

    /** Sub-folder names directly under `folder`, sorted. */
    fun listFolders(volume: String, folder: String): List<String>

    /** Current size and mtime of a file, or null when it is gone or not a regular file. */
    fun stat(volume: String, path: String): SourceEntry?

    fun open(volume: String, path: String): InputStream
}
