package io.github.rosyidputrar.duobackup.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.rosyidputrar.duobackup.core.catalog.Catalog
import io.github.rosyidputrar.duobackup.core.catalog.RunRecord
import io.github.rosyidputrar.duobackup.core.model.DestinationId
import io.github.rosyidputrar.duobackup.data.settings.SettingsRepository
import io.github.rosyidputrar.duobackup.work.BackupScheduler
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeState(
    val lastRun: RunRecord? = null,
    val nextRunAt: Long? = null,
    val running: Boolean = false,
    val driveEnabled: Boolean = false,
    val telegramEnabled: Boolean = false,
    val pendingDriveBytes: Long = 0,
    val pendingTelegramBytes: Long = 0
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    catalog: Catalog,
    private val settings: SettingsRepository,
    private val scheduler: BackupScheduler
) : ViewModel() {
    val state: StateFlow<HomeState> = combine(
        catalog.observeRuns(1),
        catalog.observePendingBytes(DestinationId.DRIVE),
        catalog.observePendingBytes(DestinationId.TELEGRAM),
        scheduler.status(),
        settings.settings
    ) { runs, pendingDrive, pendingTelegram, status, current ->
        HomeState(
            lastRun = runs.firstOrNull(),
            nextRunAt = status.nextRunAt,
            running = status.running,
            driveEnabled = current.driveEnabled,
            telegramEnabled = current.telegramEnabled,
            pendingDriveBytes = pendingDrive,
            pendingTelegramBytes = pendingTelegram
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())

    fun backUpNow() {
        viewModelScope.launch { scheduler.runNow(settings.current()) }
    }

    fun stop() = scheduler.stop()
}
