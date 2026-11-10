package io.github.rosyidputrar.duobackup.core.testing

import io.github.rosyidputrar.duobackup.core.util.SecureRandomSource

/** Deterministic stand-in for randomness: bytes count up from `start`, wrapping at 256. */
class CountingRandom(start: Int = 0) : SecureRandomSource {
    private var next = start

    override fun nextBytes(size: Int): ByteArray = ByteArray(size) { (next++).toByte() }
}
