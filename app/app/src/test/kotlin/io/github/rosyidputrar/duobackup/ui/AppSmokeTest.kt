package io.github.rosyidputrar.duobackup.ui

import android.app.Application
import android.os.Environment
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.github.rosyidputrar.duobackup.DuoBackupApp
import io.github.rosyidputrar.duobackup.MainActivity
import io.github.rosyidputrar.duobackup.storage.StorageAccess
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowEnvironment

/**
 * Starts the real application and activity, with the real dependency graph, and lets the first
 * frames compose. It cannot check how screens look; it catches wiring that crashes on launch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = DuoBackupApp::class)
class AppSmokeTest {
    private fun launch() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(controller.get().isFinishing)
        controller.pause().stop().destroy()
    }

    @Test
    @Config(shadows = [AllFilesAccessShadow::class])
    fun `starts on onboarding when all files access is missing`() {
        AllFilesAccessShadow.granted = false
        launch()
    }

    @Test
    @Config(shadows = [AllFilesAccessShadow::class])
    fun `starts on the home tab when all files access is granted`() {
        AllFilesAccessShadow.granted = true
        launch()
    }

    /** Android 10 uses the classic storage permission, which a test can grant. */
    @Test
    @Config(sdk = [29])
    fun `starts on the home tab on Android 10 with the storage permission`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(*StorageAccess.legacyPermissions)
        assertTrue(StorageAccess.isGranted(app))
        launch()
    }
}

/** Robolectric does not simulate All Files Access, so this stands in for the system's answer. */
@Implements(Environment::class)
class AllFilesAccessShadow : ShadowEnvironment() {
    companion object {
        var granted: Boolean = false

        @JvmStatic
        @Implementation
        fun isExternalStorageManager(): Boolean = granted
    }
}
