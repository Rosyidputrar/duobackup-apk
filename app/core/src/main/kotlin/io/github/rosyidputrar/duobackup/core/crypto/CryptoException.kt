package io.github.rosyidputrar.duobackup.core.crypto

import java.io.IOException

/** Every way DuoBackup's encrypted formats can be rejected. */
sealed class CryptoException(message: String) : IOException(message) {
    /** Wrong magic, unsupported version or a header cut short. */
    class BadHeader(message: String) : CryptoException(message)

    /** A frame announces more than 1 MiB of ciphertext. */
    class FrameTooLarge(length: Int) : CryptoException("frame length $length exceeds 1 MiB")

    /** A frame that is not the final one is shorter than 1 MiB. */
    class BadFrame(message: String) : CryptoException(message)

    /** The stream ended before its final frame was complete. */
    class Truncated(message: String) : CryptoException(message)

    /** There are bytes after the final frame. */
    class TrailingData : CryptoException("data after the final frame")

    /** An authentication tag did not verify: wrong key or modified data. */
    open class AuthFailed(message: String = "authentication failed") : CryptoException(message)

    /**
     * The key file could not be unwrapped. A wrong passphrase and a modified key file are
     * cryptographically indistinguishable, so this covers both.
     */
    class WrongPassphrase : AuthFailed("wrong passphrase or damaged key file")

    /** A key file that is not valid JSON, has the wrong format tag or bad field sizes. */
    class BadKeyFile(message: String) : CryptoException(message)

    /** A key file written by a newer, incompatible version. */
    class UnsupportedKeyFile(message: String) : CryptoException(message)
}
