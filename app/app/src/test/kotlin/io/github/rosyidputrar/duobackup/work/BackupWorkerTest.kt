package io.github.rosyidputrar.duobackup.work

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import io.github.rosyidputrar.duobackup.core.catalog.RunRecord
import io.github.rosyidputrar.duobackup.core.catalog.RunResult
import io.github.rosyidputrar.duobackup.core.catalog.RunTrigger
import io.github.rosyidputrar.duobackup.core.engine.BackupSession
import io.github.rosyidputrar.duobackup.core.engine.SessionOutcome
import io.github.rosyidputrar.duobackup.data.db.CatalogDatabase
import io.github.rosyidputrar.duobackup.data.db.RoomCatalog
import io.github.rosyidputrar.duobackup.data.settings.SettingsRepository
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackupWorkerTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var db: CatalogDatabase
    private lateinit var catalog: RoomCatalog
    private lateinit var workManager: WorkManager
    private var now = 1_000L
    private var budgetSeen = 0L
    private var triggerSeen: RunTrigger? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        )
        workManager = WorkManager.getInstance(context)
        db = Room.inMemoryDatabaseBuilder(context, CatalogDatabase::class.java).allowMainThreadQueries().build()
        catalog = RoomCatalog(db)
    }

    @After
    fun tearDown() = db.close()

    private fun worker(trigger: RunTrigger?, session: BackupSession): BackupWorker {
        val settings = SettingsRepository(
            PreferenceDataStoreFactory.create { File(temp.root, "settings.preferences_pb") }
        )
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters
            ): ListenableWorker = BackupWorker(
                appContext,
                workerParameters,
                session,
                catalog,
                { now.also { now += 500 } },
                settings,
                BackupScheduler(workManager),
                BackupNotifications(appContext)
            )
        }
        val builder = TestListenableWorkerBuilder<BackupWorker>(context).setWorkerFactory(factory)
        if (trigger != null) builder.setInputData(workDataOf(BackupWorker.KEY_TRIGGER to trigger.name))
        return builder.build()
    }

    private fun session(outcome: SessionOutcome) = BackupSession { trigger, budgetMs ->
        triggerSeen = trigger
        budgetSeen = budgetMs
        outcome
    }

    private fun onlyRun(): RunRecord = runBlocking { catalog.observeRuns(10).first().single() }

    private fun followUps() = workManager.getWorkInfosForUniqueWork(BackupScheduler.FOLLOW_UP).get()

    @Test
    fun `a finished session is logged with its numbers`() = runBlocking {
        val outcome = SessionOutcome(
            result = RunResult.OK,
            stopReason = "DONE",
            bytesUploadedDrive = 11,
            bytesUploadedTg = 22,
            filesHashed = 3,
            errors = 0
        )
        val result = worker(RunTrigger.MANUAL, session(outcome)).doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        val run = onlyRun()
        assertEquals(RunTrigger.MANUAL, run.trigger)
        assertEquals(RunResult.OK, run.result)
        assertEquals("DONE", run.stopReason)
        assertEquals(listOf(1_000L, 1_500L), listOf(run.startedAt, run.endedAt))
        assertEquals(listOf(11L, 22L), listOf(run.bytesUploadedDrive, run.bytesUploadedTg))
        assertEquals(listOf(3, 0), listOf(run.filesHashed, run.errors))
        assertEquals(RunTrigger.MANUAL, triggerSeen)
        assertEquals(45L * 60 * 1000, budgetSeen)
        assertEquals(0, followUps().size)
    }

    @Test
    fun `a run without a trigger counts as scheduled`() = runBlocking {
        worker(null, session(SessionOutcome(RunResult.OK))).doWork()
        assertEquals(RunTrigger.SCHEDULED, onlyRun().trigger)
        assertEquals(RunTrigger.SCHEDULED, triggerSeen)
    }

    @Test
    fun `the placeholder session records an empty successful run`() = runBlocking {
        assertEquals(ListenableWorker.Result.success(), worker(RunTrigger.MANUAL, PlaceholderBackupSession()).doWork())
        val run = onlyRun()
        assertEquals(RunResult.OK, run.result)
        assertEquals(PlaceholderBackupSession.STOP_REASON, run.stopReason)
        assertEquals(0L, run.bytesUploadedDrive + run.bytesUploadedTg)
    }

    @Test
    fun `work left over queues a follow up run`() = runBlocking {
        val outcome = SessionOutcome(RunResult.PARTIAL, stopReason = "BUDGET", pendingParts = 4)
        assertEquals(ListenableWorker.Result.success(), worker(RunTrigger.SCHEDULED, session(outcome)).doWork())
        assertEquals(RunResult.PARTIAL, onlyRun().result)
        assertEquals(1, followUps().size)
    }

    @Test
    fun `a failing session is logged as failed without its message`() = runBlocking {
        val failing = BackupSession { _, _ -> throw IOException("/storage/emulated/0/secret/path.jpg") }
        assertEquals(ListenableWorker.Result.failure(), worker(RunTrigger.MANUAL, failing).doWork())
        val run = onlyRun()
        assertEquals(RunResult.FAILED, run.result)
        assertEquals("IOException", run.stopReason)
        assertEquals(1, run.errors)
        assertEquals(1_500L, run.endedAt)
    }

    @Test
    fun `a stopped run is logged as partial and the cancellation is passed on`() {
        val stopped = BackupSession { _, _ -> throw CancellationException("stopped") }
        assertThrows(CancellationException::class.java) {
            runBlocking { worker(RunTrigger.MANUAL, stopped).doWork() }
        }
        val run = onlyRun()
        assertEquals(RunResult.PARTIAL, run.result)
        assertEquals(BackupWorker.STOP_REASON_STOPPED, run.stopReason)
        assertEquals(1_500L, run.endedAt)
    }

    @Test
    fun `the worker shows the backup notification as a data sync service`() = runBlocking {
        val info = worker(RunTrigger.MANUAL, session(SessionOutcome(RunResult.OK))).getForegroundInfo()
        assertEquals(BackupNotifications.NOTIFICATION_ID, info.notificationId)
        assertEquals(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, info.foregroundServiceType)
        assertEquals(BackupNotifications.CHANNEL_ID, info.notification.channelId)
        assertEquals(1, info.notification.actions.size)
    }
}
