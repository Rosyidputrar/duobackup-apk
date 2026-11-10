package io.github.rosyidputrar.duobackup.core.format

/** Every way a remote format can fail to encode or decode. */
sealed class FormatException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The caption does not fit the budget even with the path shortened as far as allowed. */
    class CaptionTooLong(val budget: Int) : FormatException("caption does not fit $budget code units")

    /** Written by a newer, incompatible version of the format. */
    class UnsupportedVersion(val format: String, val version: String) :
        FormatException("unsupported $format version $version")

    /** Carries a DuoBackup marker but cannot be parsed. */
    class Malformed(message: String, cause: Throwable? = null) : FormatException(message, cause)

    /** Encrypted data was met without a key to open it. */
    class KeyRequired(message: String) : FormatException(message)
}
