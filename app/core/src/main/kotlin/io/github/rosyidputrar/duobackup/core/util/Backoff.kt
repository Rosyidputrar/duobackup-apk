package io.github.rosyidputrar.duobackup.core.util

import kotlin.math.pow
import kotlin.random.Random

/** Exponential backoff. `attempt` is 0 for the first retry. */
class Backoff(
    private val baseMs: Long = 2_000,
    private val factor: Double = 2.0,
    private val capMs: Long = 3_600_000,
    private val jitter: Jitter = Jitter.FULL
) {
    enum class Jitter { NONE, FULL }

    init {
        require(baseMs > 0 && factor >= 1.0 && capMs >= baseMs) { "invalid backoff parameters" }
    }

    /** Upper bound of the delay for `attempt`, before jitter. Never above the cap. */
    fun ceiling(attempt: Int): Long {
        require(attempt >= 0) { "negative attempt" }
        val raw = baseMs * factor.pow(attempt)
        return if (raw >= capMs) capMs else raw.toLong()
    }

    fun nextDelay(attempt: Int, random: Random): Long {
        val ceiling = ceiling(attempt)
        return when (jitter) {
            Jitter.NONE -> ceiling
            Jitter.FULL -> random.nextLong(ceiling + 1)
        }
    }
}
