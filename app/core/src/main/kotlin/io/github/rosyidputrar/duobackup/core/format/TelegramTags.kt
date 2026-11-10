package io.github.rosyidputrar.duobackup.core.format

/** The hashtag marker lines that identify DuoBackup's channel and its non-part messages (plan §4.3). */
object TelegramTags {
    const val STORAGE: String = "#duobackup-storage"
    const val INDEX: String = "#duobackup-index"
    const val KEY: String = "#duobackup-key"

    private const val VERSION = "v1"

    data class IndexTag(val generatedAt: Long, val keyId: String?)

    /** Marker line kept in the storage channel's description. */
    fun storage(install: String): String = "$STORAGE $VERSION $install"

    /** Returns the install id from a channel description, or null when no line is a v1 marker. */
    fun parseStorage(description: String): String? = description.lineSequence()
        .map { it.trim().split(' ') }
        .firstOrNull { it.size == 3 && it[0] == STORAGE && it[1] == VERSION }
        ?.get(2)

    fun index(generatedAt: Long, keyId: String?): String {
        val base = "$INDEX $VERSION $generatedAt"
        return if (keyId == null) base else "$base kid=$keyId"
    }

    /** Returns null for captions that are not a v1 index marker. */
    fun parseIndex(caption: String): IndexTag? {
        val tokens = caption.trim().split(' ')
        if (tokens.size !in 3..4 || tokens[0] != INDEX || tokens[1] != VERSION) return null
        val generatedAt = tokens[2].toLongOrNull() ?: return null
        if (tokens.size == 3) return IndexTag(generatedAt, null)
        if (!tokens[3].startsWith("kid=") || tokens[3].length == 4) return null
        return IndexTag(generatedAt, tokens[3].substring(4))
    }

    fun key(keyId: String): String = "$KEY $VERSION $keyId"

    /** Returns the key id, or null for captions that are not a v1 key marker. */
    fun parseKey(caption: String): String? {
        val tokens = caption.trim().split(' ')
        return if (tokens.size == 3 && tokens[0] == KEY && tokens[1] == VERSION) tokens[2] else null
    }
}
