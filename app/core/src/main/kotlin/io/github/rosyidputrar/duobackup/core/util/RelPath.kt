package io.github.rosyidputrar.duobackup.core.util

class InvalidRelPathException(message: String) : IllegalArgumentException(message)

/** Relative paths inside a storage volume, always written with `/` between segments. */
object RelPath {
    /**
     * Validates a relative path and returns it unchanged. Rejects empty paths, absolute paths,
     * empty segments (which covers leading, trailing and doubled separators), `.` and `..`
     * segments and NUL characters.
     */
    fun normalize(path: String): String {
        if (path.isEmpty()) throw InvalidRelPathException("empty path")
        if (path.startsWith('/')) throw InvalidRelPathException("absolute path")
        if (path.indexOf('\u0000') >= 0) throw InvalidRelPathException("NUL in path")
        for (segment in path.split('/')) {
            when (segment) {
                "" -> throw InvalidRelPathException("empty segment")
                ".", ".." -> throw InvalidRelPathException("relative segment '$segment'")
            }
        }
        return path
    }

    /** The last segment of a path. */
    fun fileName(path: String): String = path.substringAfterLast('/')
}
