package io.github.rosyidputrar.duobackup.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.github.rosyidputrar.duobackup.core.catalog.Catalog
import io.github.rosyidputrar.duobackup.core.catalog.RunResult
import io.github.rosyidputrar.duobackup.core.catalog.RunSummary
import io.github.rosyidputrar.duobackup.core.catalog.RunTrigger
import io.github.rosyidputrar.duobackup.core.engine.BackupSession
import io.github.rosyidputrar.duobackup.core.util.Clock
import io.github.rosyidputrar.duobackup.data.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Runs one backup session as a `dataSync` foreground service and logs it in the `runs` table.
 * Being stopped, by the user or by the system, is a normal end: the run is recorded as partial
 * and the next run resumes from the catalog.
 */
@HiltWorker
class BackupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val session: BackupSession,
    private val catalog: Catalog,
    private val clock: Clock,
    private val settings: SettingsRepository,
    private val scheduler: BackupScheduler,
    private val notifications: BackupNotifications
) : CoroutineWorker(context, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo = notifications.foregroundInfo(id)

    override suspend fun doWork(): Result {
        val trigger = inputData.getString(KEY_TRIGGER)
            ?.let { name -> RunTrigger.entries.firstOrNull { it.name == name } }
            ?: RunTrigger.SCHEDULED
        try {
            setForeground(getForegroundInfo())
        } catch (e: IllegalStateException) {
            // The system can refuse a foreground service started from the background. The run
            // still goes ahead as ordinary background work, within WorkManager's time limit.
            Log.w(TAG, "running without a foreground service: ${e.javaClass.simpleName}")
        }

        val runId = catalog.startRun(trigger, clock.now())
        val outcome = try {
            session.run(trigger, RUN_BUDGET_MS)
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                catalog.finishRun(runId, RunSummary(clock.now(), RunResult.PARTIAL, stopReason = STOP_REASON_STOPPED))
            }
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "backup run failed: ${e.javaClass.simpleName}")
            catalog.finishRun(
                runId,
                RunSummary(clock.now(), RunResult.FAILED, stopReason = e.javaClass.simpleName, errors = 1)
            )
            return Result.failure()
        }

        catalog.finishRun(
            runId,
            RunSummary(
                endedAt = clock.now(),
                result = outcome.result,
                stopReason = outcome.stopReason,
                bytesUploadedDrive = outcome.bytesUploadedDrive,
                bytesUploadedTg = outcome.bytesUploadedTg,
                filesHashed = outcome.filesHashed,
                errors = outcome.errors
            )
        )
        if (outcome.pendingParts > 0) scheduler.enqueueFollowUp(settings.current())
        return Result.success()
    }

    companion object {
        const val KEY_TRIGGER: String = "trigger"
        const val RUN_BUDGET_MS: Long = 45L * 60 * 1000
        const val STOP_REASON_STOPPED: String = "STOPPED"
        private const val TAG = "BackupWorker"
    }
}
