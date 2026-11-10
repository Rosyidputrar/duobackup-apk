package io.github.rosyidputrar.duobackup

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import io.github.rosyidputrar.duobackup.data.settings.SettingsRepository
import io.github.rosyidputrar.duobackup.di.ApplicationScope
import io.github.rosyidputrar.duobackup.work.BackupScheduler
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@HiltAndroidApp
class DuoBackupApp :
    Application(),
    Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var settings: SettingsRepository

    @Inject
    lateinit var scheduler: BackupScheduler

    @Inject
    @ApplicationScope
    lateinit var scope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // Keep the periodic work in step with the settings that shape it.
        scope.launch {
            settings.settings
                .map { Triple(it.schedule, it.wifiOnly, it.chargingOnly) }
                .distinctUntilChanged()
                .collect { scheduler.applySchedule(settings.current()) }
        }
    }
}
