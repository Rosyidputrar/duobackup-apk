package io.github.rosyidputrar.duobackup.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.rosyidputrar.duobackup.data.settings.Schedule
import io.github.rosyidputrar.duobackup.data.settings.Settings
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackupSchedulerTest {
    private lateinit var workManager: WorkManager
    private lateinit var scheduler: BackupScheduler

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)
        scheduler = BackupScheduler(workManager)
    }

    private fun infos(name: String): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(name).get()

    private fun live(name: String): List<WorkInfo> = infos(name).filter { !it.state.isFinished }

    @Test
    fun `wifi only maps to an unmetered network for every kind of run`() {
        for (manual in listOf(true, false)) {
            assertEquals(
                NetworkType.UNMETERED,
                BackupScheduler.constraintsFor(Settings(wifiOnly = true), manual).requiredNetworkType
            )
            assertEquals(
                NetworkType.CONNECTED,
                BackupScheduler.constraintsFor(Settings(wifiOnly = false), manual).requiredNetworkType
            )
        }
    }

    @Test
    fun `charging and battery rules apply to unattended runs only`() {
        val charging = Settings(chargingOnly = true)
        val unattended = BackupScheduler.constraintsFor(charging, manual = false)
        assertTrue(unattended.requiresCharging())
        assertTrue(unattended.requiresBatteryNotLow())
        assertFalse(BackupScheduler.constraintsFor(Settings(chargingOnly = false), manual = false).requiresCharging())

        val manual = BackupScheduler.constraintsFor(charging, manual = true)
        assertFalse(manual.requiresCharging())
        assertFalse(manual.requiresBatteryNotLow())
    }

    @Test
    fun `schedule maps to a repeat interval`() {
        assertNull(BackupScheduler.intervalHours(Schedule.OFF))
        assertEquals(24L, BackupScheduler.intervalHours(Schedule.DAILY))
        assertEquals(6L, BackupScheduler.intervalHours(Schedule.EVERY_6H))
    }

    @Test
    fun `daily schedule enqueues one periodic work with the settings constraints`() {
        scheduler.applySchedule(Settings(schedule = Schedule.DAILY, wifiOnly = true, chargingOnly = true))
        val info = live(BackupScheduler.PERIODIC).single()
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertEquals(TimeUnit.HOURS.toMillis(24), info.periodicityInfo!!.repeatIntervalMillis)
        assertEquals(NetworkType.UNMETERED, info.constraints.requiredNetworkType)
        assertTrue(info.constraints.requiresCharging())
    }

    @Test
    fun `changing the schedule updates the same periodic work`() {
        scheduler.applySchedule(Settings(schedule = Schedule.DAILY))
        val first = live(BackupScheduler.PERIODIC).single()
        scheduler.applySchedule(Settings(schedule = Schedule.EVERY_6H, wifiOnly = false))
        val updated = live(BackupScheduler.PERIODIC).single()
        assertEquals(first.id, updated.id)
        assertEquals(TimeUnit.HOURS.toMillis(6), updated.periodicityInfo!!.repeatIntervalMillis)
        assertEquals(NetworkType.CONNECTED, updated.constraints.requiredNetworkType)
    }

    @Test
    fun `schedule off cancels the periodic work`() {
        scheduler.applySchedule(Settings(schedule = Schedule.DAILY))
        scheduler.applySchedule(Settings(schedule = Schedule.OFF))
        assertEquals(emptyList<WorkInfo>(), live(BackupScheduler.PERIODIC))
        scheduler.applySchedule(Settings(schedule = Schedule.OFF))
    }

    @Test
    fun `back up now keeps a run that is already queued`() {
        scheduler.runNow(Settings())
        val first = live(BackupScheduler.NOW).single()
        scheduler.runNow(Settings())
        scheduler.runNow(Settings(wifiOnly = false))
        val kept = live(BackupScheduler.NOW).single()
        assertEquals(first.id, kept.id)
        assertEquals(NetworkType.UNMETERED, kept.constraints.requiredNetworkType)
        assertNull(kept.periodicityInfo)
    }

    @Test
    fun `follow up run waits fifteen minutes and replaces an older one`() {
        scheduler.enqueueFollowUp(Settings())
        val first = live(BackupScheduler.FOLLOW_UP).single()
        assertEquals(TimeUnit.MINUTES.toMillis(15), first.initialDelayMillis)
        assertTrue(first.constraints.requiresBatteryNotLow())
        scheduler.enqueueFollowUp(Settings())
        val second = live(BackupScheduler.FOLLOW_UP).single()
        assertFalse(first.id == second.id)
    }

    @Test
    fun `stop cancels one time runs and keeps the schedule`() {
        scheduler.applySchedule(Settings(schedule = Schedule.DAILY))
        scheduler.runNow(Settings())
        scheduler.enqueueFollowUp(Settings())
        scheduler.stop()
        assertEquals(emptyList<WorkInfo>(), live(BackupScheduler.NOW))
        assertEquals(emptyList<WorkInfo>(), live(BackupScheduler.FOLLOW_UP))
        assertEquals(1, live(BackupScheduler.PERIODIC).size)
    }

    @Test
    fun `status reports the next scheduled run and nothing running`() = runTest {
        assertEquals(BackupStatus(running = false, nextRunAt = null), scheduler.status().first())
        scheduler.applySchedule(Settings(schedule = Schedule.DAILY))
        val status = scheduler.status().first()
        assertFalse(status.running)
        assertNotNull(status.nextRunAt)
    }
}
