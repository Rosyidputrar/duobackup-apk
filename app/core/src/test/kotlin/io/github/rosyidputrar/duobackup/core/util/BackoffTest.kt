package io.github.rosyidputrar.duobackup.core.util

import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class BackoffTest {
    @Test
    fun `ceiling doubles from 2 s and stops at one hour`() {
        val backoff = Backoff()
        assertEquals(2_000, backoff.ceiling(0))
        assertEquals(4_000, backoff.ceiling(1))
        assertEquals(8_000, backoff.ceiling(2))
        assertEquals(2_048_000, backoff.ceiling(10))
        assertEquals(3_600_000, backoff.ceiling(11))
        assertEquals(3_600_000, backoff.ceiling(5_000))
        var previous = 0L
        for (attempt in 0..200) {
            val ceiling = backoff.ceiling(attempt)
            assertTrue(ceiling >= previous && ceiling <= 3_600_000, "attempt=$attempt")
            previous = ceiling
        }
    }

    @Test
    fun `full jitter stays within zero and the ceiling and reaches both ends of the range`() {
        val backoff = Backoff()
        val random = Random(11)
        for (attempt in listOf(0, 3, 11, 40)) {
            val delays = List(2_000) { backoff.nextDelay(attempt, random) }
            val ceiling = backoff.ceiling(attempt)
            assertTrue(delays.all { it in 0..ceiling }, "attempt=$attempt")
            assertTrue(delays.min() < ceiling / 10 && delays.max() > ceiling - ceiling / 10, "attempt=$attempt")
        }
    }

    @Test
    fun `without jitter the delay is the ceiling`() {
        val backoff = Backoff(baseMs = 100, factor = 3.0, capMs = 1_000, jitter = Backoff.Jitter.NONE)
        assertEquals(listOf(100L, 300L, 900L, 1_000L, 1_000L), (0..4).map { backoff.nextDelay(it, Random(1)) })
    }

    @Test
    fun `invalid parameters are rejected`() {
        assertFailsWith<IllegalArgumentException> { Backoff(baseMs = 0) }
        assertFailsWith<IllegalArgumentException> { Backoff(factor = 0.5) }
        assertFailsWith<IllegalArgumentException> { Backoff(baseMs = 10, capMs = 5) }
        assertFailsWith<IllegalArgumentException> { Backoff().ceiling(-1) }
    }
}
