package io.github.rosyidputrar.duobackup.core.util

import java.security.SecureRandom

/** Source of cryptographic randomness, injectable so tests and golden vectors are deterministic. */
fun interface SecureRandomSource {
    fun nextBytes(size: Int): ByteArray
}

object SystemSecureRandom : SecureRandomSource {
    private val random = SecureRandom()

    override fun nextBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)
}
