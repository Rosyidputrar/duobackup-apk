package io.github.rosyidputrar.duobackup

import org.junit.Assert.assertTrue
import org.junit.Test

class AppIdentityTest {
    @Test
    fun applicationIdUsesTheLockedPackage() {
        assertTrue(BuildConfig.APPLICATION_ID.startsWith("io.github.rosyidputrar.duobackup"))
    }
}
