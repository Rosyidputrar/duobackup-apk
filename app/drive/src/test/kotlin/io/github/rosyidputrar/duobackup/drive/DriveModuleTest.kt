package io.github.rosyidputrar.duobackup.drive

import io.github.rosyidputrar.duobackup.core.model.DestinationId
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class DriveModuleTest {
    @Test
    fun `module targets the Drive destination`() {
        assertEquals(DestinationId.DRIVE, DriveModule.destination)
    }
}
