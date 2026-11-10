package io.github.rosyidputrar.duobackup.ui.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.rosyidputrar.duobackup.core.source.SourceFileSystem
import io.github.rosyidputrar.duobackup.core.source.SourceVolume
import io.github.rosyidputrar.duobackup.core.util.ExcludeMatcher
import io.github.rosyidputrar.duobackup.data.settings.Schedule
import io.github.rosyidputrar.duobackup.data.settings.Settings
import io.github.rosyidputrar.duobackup.data.settings.SettingsRepository
import io.github.rosyidputrar.duobackup.storage.AndroidSourceFileSystem
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the storage looks like right now: its volumes and the top-level folders of the primary one. */
data class StorageOverview(val volumes: List<SourceVolume> = emptyList(), val folders: List<String> = emptyList())

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val source: SourceFileSystem
) : ViewModel() {
    val settings: StateFlow<Settings> =
        repository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Settings())

    private val storageState = MutableStateFlow(StorageOverview())
    val storage: StateFlow<StorageOverview> = storageState.asStateFlow()

    /** Reads the volume and folder lists. They are empty until file access has been granted. */
    fun refreshStorage() {
        viewModelScope.launch {
            storageState.value = withContext(Dispatchers.IO) {
                try {
                    StorageOverview(source.volumes(), source.listFolders(AndroidSourceFileSystem.PRIMARY, ""))
                } catch (e: SecurityException) {
                    Log.w(TAG, "storage is not readable: ${e.javaClass.simpleName}")
                    StorageOverview()
                }
            }
        }
    }

    fun setSchedule(schedule: Schedule) = update { setSchedule(schedule) }

    fun setWifiOnly(enabled: Boolean) = update { setWifiOnly(enabled) }

    fun setChargingOnly(enabled: Boolean) = update { setChargingOnly(enabled) }

    fun setMinBatteryPct(percent: Int) = update { setMinBatteryPct(percent) }

    fun setIncludeRoots(roots: List<String>) = update { setIncludeRoots(roots) }

    fun addExclude(glob: String) = update { setExcludes(current().excludes + glob) }

    fun removeExclude(glob: String) = update { setExcludes(current().excludes - glob) }

    fun resetExcludes() = update { setExcludes(ExcludeMatcher.DEFAULT_GLOBS) }

    fun setRetentionDays(days: Int) = update { setRetentionDays(days) }

    fun setSnapshotKeep(count: Int) = update { setSnapshotKeep(count) }

    private fun update(change: suspend SettingsRepository.() -> Unit) {
        viewModelScope.launch { repository.change() }
    }

    private companion object {
        const val TAG = "SettingsViewModel"
    }
}
