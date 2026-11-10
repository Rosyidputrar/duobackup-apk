package io.github.rosyidputrar.duobackup.telegram

import io.github.rosyidputrar.duobackup.core.model.DestinationId
import org.junit.Assert.assertEquals
import org.junit.Test

class TelegramModuleTest {
    @Test
    fun moduleTargetsTheTelegramDestination() {
        assertEquals(DestinationId.TELEGRAM, TelegramModule.destination)
    }
}
