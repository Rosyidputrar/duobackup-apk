package io.github.rosyidputrar.duobackup.core

/**
 * Size constants. The Telegram values are conservative fallbacks used only when runtime
 * detection fails; real limits come from the destination at the start of every session.
 */
object Limits {
    const val MIB: Long = 1024L * 1024L

    const val TG_FALLBACK_MAX_PARTS: Int = 4000
    const val TG_UPLOAD_PART_BYTES: Int = 512 * 1024
    const val TG_FALLBACK_MAX_FILE_BYTES: Long = TG_FALLBACK_MAX_PARTS.toLong() * TG_UPLOAD_PART_BYTES
    const val TG_FALLBACK_CAPTION_MAX: Int = 1024

    const val DRIVE_MAX_FILE_BYTES: Long = 5L * 1024L * 1024L * 1024L * 1024L

    /** Plaintext size of one part unless a destination limit forces a smaller one. */
    const val TARGET_PART_SIZE: Long = 512L * MIB

    /** Plaintext size of one DBE1 frame. */
    const val FRAME: Int = 1024 * 1024
}
