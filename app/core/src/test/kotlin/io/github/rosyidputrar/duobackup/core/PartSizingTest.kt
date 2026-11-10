package io.github.rosyidputrar.duobackup.core

import io.github.rosyidputrar.duobackup.core.Limits.MIB
import io.github.rosyidputrar.duobackup.core.model.DestinationLimits
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class PartSizingTest {
    private fun limits(maxFileBytes: Long) = DestinationLimits(maxFileBytes = maxFileBytes, captionMax = 1024)

    @Test
    fun `fallback constants match the documented Telegram limits`() {
        assertEquals(2_097_152_000L, Limits.TG_FALLBACK_MAX_FILE_BYTES)
        assertEquals(536_870_912L, Limits.TARGET_PART_SIZE)
        assertEquals(1_048_576, Limits.FRAME)
    }

    @Test
    fun `empty file has no parts`() {
        assertEquals(emptyList(), PartSizing.layout(0, 512 * MIB))
    }

    @Test
    fun `one byte is one part`() {
        assertEquals(listOf(PartSpan(0, 0, 1)), PartSizing.layout(1, 512 * MIB))
    }

    @Test
    fun `exactly one part`() {
        assertEquals(listOf(PartSpan(0, 0, 512 * MIB)), PartSizing.layout(512 * MIB, 512 * MIB))
    }

    @Test
    fun `one part plus one byte spills into a second part`() {
        assertEquals(
            listOf(PartSpan(0, 0, 512 * MIB), PartSpan(1, 512 * MIB, 1)),
            PartSizing.layout(512 * MIB + 1, 512 * MIB)
        )
    }

    @Test
    fun `20 GiB in 512 MiB parts is 40 parts covering the file`() {
        val size = 20L * 1024 * MIB
        val parts = PartSizing.layout(size, 512 * MIB)
        assertEquals(40, parts.size)
        assertEquals(size, parts.sumOf { it.length })
        parts.forEachIndexed { i, part ->
            assertEquals(i, part.index)
            assertEquals(i * 512 * MIB, part.offset)
        }
    }

    @Test
    fun `layout rejects invalid arguments`() {
        assertFailsWith<IllegalArgumentException> { PartSizing.layout(-1, MIB) }
        assertFailsWith<IllegalArgumentException> { PartSizing.layout(1, 0) }
    }

    @Test
    fun `stored size without encryption is the plain size`() {
        for (size in listOf(0L, 1L, MIB, MIB + 1, 512 * MIB)) {
            assertEquals(size, PartSizing.storedSize(size, encrypted = false))
        }
    }

    @Test
    fun `stored size with encryption adds the header and one overhead per frame`() {
        assertEquals(41L, PartSizing.storedSize(0, encrypted = true))
        assertEquals(42L, PartSizing.storedSize(1, encrypted = true))
        assertEquals(MIB + 41, PartSizing.storedSize(MIB, encrypted = true))
        assertEquals(MIB + 1 + 61, PartSizing.storedSize(MIB + 1, encrypted = true))
        assertEquals(512 * MIB + 21 + 20 * 512, PartSizing.storedSize(512 * MIB, encrypted = true))
    }

    @Test
    fun `free tier limit still allows the 512 MiB target`() {
        val free = limits(Limits.TG_FALLBACK_MAX_FILE_BYTES)
        assertEquals(512 * MIB, PartSizing.choose(listOf(free), encrypted = false))
        assertEquals(512 * MIB, PartSizing.choose(listOf(free, limits(Limits.DRIVE_MAX_FILE_BYTES)), encrypted = true))
    }

    @Test
    fun `a 100 MiB limit gives 99 MiB plain and 98 MiB encrypted`() {
        val small = listOf(limits(100 * MIB), limits(Limits.DRIVE_MAX_FILE_BYTES))
        assertEquals(99 * MIB, PartSizing.choose(small, encrypted = false))
        assertEquals(98 * MIB, PartSizing.choose(small, encrypted = true))
    }

    @Test
    fun `limits too small for any part are rejected`() {
        assertFailsWith<IllegalArgumentException> { PartSizing.maxPlainPartSize(MIB, encrypted = false) }
        assertFailsWith<IllegalArgumentException> { PartSizing.maxPlainPartSize(2 * MIB, encrypted = true) }
        assertFailsWith<IllegalArgumentException> { PartSizing.choose(emptyList(), encrypted = false) }
    }

    @Test
    fun `the stored size of the chosen part never exceeds the limit`() {
        val seed = System.nanoTime()
        val random = Random(seed)
        repeat(2_000) {
            val limit = random.nextLong(3 * MIB, 4096 * MIB)
            for (encrypted in listOf(false, true)) {
                val chosen = PartSizing.choose(listOf(limits(limit)), encrypted)
                val stored = PartSizing.storedSize(chosen, encrypted)
                assertTrue(stored <= limit - MIB, "seed=$seed limit=$limit encrypted=$encrypted stored=$stored")
                assertEquals(0L, chosen % MIB, "seed=$seed limit=$limit")
                assertTrue(chosen <= Limits.TARGET_PART_SIZE, "seed=$seed limit=$limit")
            }
        }
    }
}
