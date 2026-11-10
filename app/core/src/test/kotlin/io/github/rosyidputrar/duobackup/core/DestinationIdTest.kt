package io.github.rosyidputrar.duobackup.core

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class DestinationIdTest {
    @Test
    fun `there are exactly two destinations`() {
        assertEquals(listOf(DestinationId.DRIVE, DestinationId.TELEGRAM), DestinationId.entries.toList())
    }
}
