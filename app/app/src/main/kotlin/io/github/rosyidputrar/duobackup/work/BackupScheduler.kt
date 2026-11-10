package io.github.rosyidputrar.duobackup.work

import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.github.rosyidputrar.duobackup.core.catalog.RunTrigger
import io.github.rosyidputrar.duobackup.data.settings.Schedule
import io.github.rosyidputrar.duobackup.data.settings.Settings
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** @property nextRunAt epoch milliseconds of the next scheduled run, or null when none is planned */
data class BackupStatus(val running: Boolean, val nextRunAt: Long?)

/** Turns settings into WorkManager work and reports what is running. */
class BackupScheduler(private val workManager: WorkManager) {
    /** Creates, updates or cancels the periodic backup to match the settings. */
    fun applySchedule(settings: Settings) {
        val hours = intervalHours(settings.schedule)
        if (hours == null) {
            workManager.cancelUniqueWork(PERIODIC)
            return
        }
        val request = PeriodicWorkRequestBuilder<BackupWorker>(hours, TimeUnit.HOURS)
            .setConstraints(constraintsFor(settings, manual = false))
            .setInputData(workDataOf(BackupWorker.KEY_TRIGGER to RunTrigger.SCHEDULED.name))
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** Starts a backup now. A run that is already queued or running is kept. */
    fun runNow(settings: Settings) {
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(constraintsFor(settings, manual = true))
            .setInputData(workDataOf(BackupWorker.KEY_TRIGGER to RunTrigger.MANUAL.name))
            .build()
        workManager.enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
    }

    /** Queues another run for work a session left behind when its time budget ran out. */
    fun enqueueFollowUp(settings: Settings) {
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(constraintsFor(settings, manual = false))
            .setInitialDelay(FOLLOW_UP_DELAY_MINUTES, TimeUnit.MINUTES)
            .setInputData(workDataOf(BackupWorker.KEY_TRIGGER to RunTrigger.SCHEDULED.name))
            .build()
        workManager.enqueueUniqueWork(FOLLOW_UP, ExistingWorkPolicy.REPLACE, request)
    }

    /** Stops a running backup and drops queued one-time runs. The periodic schedule stays. */
    fun stop() {
        workManager.cancelUniqueWork(NOW)
        workManager.cancelUniqueWork(FOLLOW_UP)
    }

    fun status(): Flow<BackupStatus> = combine(
        workManager.getWorkInfosForUniqueWorkFlow(PERIODIC),
        workManager.getWorkInfosForUniqueWorkFlow(NOW),
        workManager.getWorkInfosForUniqueWorkFlow(FOLLOW_UP)
    ) { periodic, now, followUp ->
        val all = periodic + now + followUp
        val next = (periodic + followUp)
            .filter { it.state == WorkInfo.State.ENQUEUED }
            .map { it.nextScheduleTimeMillis }
            .filter { it != Long.MAX_VALUE }
            .minOrNull()
        BackupStatus(running = all.any { it.state == WorkInfo.State.RUNNING }, nextRunAt = next)
    }

    companion object {
        const val PERIODIC: String = "duobackup.backup.periodic"
        const val NOW: String = "duobackup.backup.now"
        const val FOLLOW_UP: String = "duobackup.backup.followup"
        const val FOLLOW_UP_DELAY_MINUTES: Long = 15

        fun intervalHours(schedule: Schedule): Long? = when (schedule) {
            Schedule.OFF -> null
            Schedule.DAILY -> 24
            Schedule.EVERY_6H -> 6
        }

        /**
         * Wi-Fi only maps to an unmetered network for every run, so a manual run never spends
         * mobile data against the user's setting. Charging and battery rules apply to
         * unattended runs only: someone who taps "Back up now" has decided to run now.
         */
        fun constraintsFor(settings: Settings, manual: Boolean): Constraints = Constraints.Builder()
            .setRequiredNetworkType(if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(!manual && settings.chargingOnly)
            .setRequiresBatteryNotLow(!manual)
            .build()
    }
}
