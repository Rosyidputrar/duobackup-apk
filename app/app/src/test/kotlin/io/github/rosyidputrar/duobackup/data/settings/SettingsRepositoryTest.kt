package io.github.rosyidputrar.duobackup.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.rosyidputrar.duobackup.core.util.ExcludeMatcher
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun TestScope.openStore(file: File, job: Job): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + job)) {
            file
        }

    private fun withRepository(block: suspend (SettingsRepository, DataStore<Preferences>) -> Unit) = runTest {
        val job = Job()
        val store = openStore(File(temp.root, "settings.preferences_pb"), job)
        block(SettingsRepository(store), store)
        job.cancelAndJoin()
    }

    @Test
    fun `a fresh install has the documented defaults`() = withRepository { repository, _ ->
        val settings = repository.current()
        assertEquals(Settings(), settings)
        assertEquals(false, settings.driveEnabled)
        assertEquals(false, settings.telegramEnabled)
        assertEquals(true, settings.wifiOnly)
        assertEquals(false, settings.chargingOnly)
        assertEquals(20, settings.minBatteryPct)
        assertEquals(Schedule.DAILY, settings.schedule)
        assertEquals(listOf(""), settings.includeRoots)
        assertEquals(ExcludeMatcher.DEFAULT_GLOBS, settings.excludes)
        assertEquals(false, settings.encryptionEnabled)
        assertEquals(30, settings.retentionDays)
        assertEquals(10, settings.snapshotKeep)
        assertNull(settings.installUuid)
        assertNull(settings.driveFolderIds)
        assertNull(settings.tgStorageChatId)
    }

    @Test
    fun `every setting can be changed`() = withRepository { repository, _ ->
        repository.setDriveEnabled(true)
        repository.setTelegramEnabled(true)
        repository.setWifiOnly(false)
        repository.setChargingOnly(true)
        repository.setMinBatteryPct(55)
        repository.setSchedule(Schedule.EVERY_6H)
        repository.setIncludeRoots(listOf("DCIM", "Documents", "DCIM"))
        repository.setExcludes(listOf(" **/*.tmp ", "", "Android/data/**", "**/*.tmp"))
        repository.setEncryptionEnabled(true)
        repository.setRetentionDays(90)
        repository.setSnapshotKeep(5)
        repository.setDriveFolderIds("{\"root\":\"abc\"}")
        repository.setTgStorageChatId(-1_001_234_567_890)
        assertEquals(
            Settings(
                driveEnabled = true,
                telegramEnabled = true,
                wifiOnly = false,
                chargingOnly = true,
                minBatteryPct = 55,
                schedule = Schedule.EVERY_6H,
                includeRoots = listOf("DCIM", "Documents"),
                excludes = listOf("**/*.tmp", "Android/data/**"),
                encryptionEnabled = true,
                retentionDays = 90,
                snapshotKeep = 5,
                driveFolderIds = "{\"root\":\"abc\"}",
                tgStorageChatId = -1_001_234_567_890
            ),
            repository.current()
        )
        repository.setDriveFolderIds(null)
        repository.setTgStorageChatId(null)
        assertNull(repository.current().driveFolderIds)
        assertNull(repository.current().tgStorageChatId)
    }

    @Test
    fun `out of range numbers are clamped and an empty root selection means the whole volume`() =
        withRepository { repository, _ ->
            repository.setMinBatteryPct(150)
            repository.setRetentionDays(0)
            repository.setSnapshotKeep(-3)
            repository.setIncludeRoots(emptyList())
            val settings = repository.current()
            assertEquals(
                listOf(100, 1, 1),
                listOf(settings.minBatteryPct, settings.retentionDays, settings.snapshotKeep)
            )
            assertEquals(listOf(""), settings.includeRoots)
            repository.setIncludeRoots(listOf("DCIM", ""))
            assertEquals(listOf(""), repository.current().includeRoots)
        }

    @Test
    fun `install id is generated once and then stable`() = withRepository { repository, _ ->
        val first = repository.installUuid()
        assertEquals(36, first.length)
        assertEquals(first, repository.installUuid())
        assertEquals(first, repository.current().installUuid)
    }

    @Test
    fun `unreadable stored values fall back to defaults`() = withRepository { repository, store ->
        store.edit {
            it[stringPreferencesKey("schedule")] = "HOURLY"
            it[stringPreferencesKey("excludes")] = "not json"
        }
        assertEquals(Schedule.DAILY, repository.current().schedule)
        assertEquals(ExcludeMatcher.DEFAULT_GLOBS, repository.current().excludes)
    }

    @Test
    fun `settings survive reopening the store`() = runTest {
        val file = File(temp.root, "persist.preferences_pb")
        val firstJob = Job()
        val first = SettingsRepository(openStore(file, firstJob))
        first.setSchedule(Schedule.OFF)
        first.setIncludeRoots(listOf("Music"))
        val uuid = first.installUuid()
        firstJob.cancelAndJoin()

        val secondJob = Job()
        val reopened = SettingsRepository(openStore(file, secondJob)).settings.first()
        assertEquals(Schedule.OFF, reopened.schedule)
        assertEquals(listOf("Music"), reopened.includeRoots)
        assertEquals(uuid, reopened.installUuid)
        secondJob.cancelAndJoin()
    }
}
