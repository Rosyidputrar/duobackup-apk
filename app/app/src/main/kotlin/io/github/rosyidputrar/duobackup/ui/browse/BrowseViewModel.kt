package io.github.rosyidputrar.duobackup.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.rosyidputrar.duobackup.core.catalog.Catalog
import io.github.rosyidputrar.duobackup.core.catalog.FolderEntry
import io.github.rosyidputrar.duobackup.storage.AndroidSourceFileSystem
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** @property folder path of the folder shown, "" for the volume root */
data class BrowseState(val folder: String = "", val entries: List<FolderEntry> = emptyList())

@HiltViewModel
class BrowseViewModel @Inject constructor(catalog: Catalog) : ViewModel() {
    private val folder = MutableStateFlow("")

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<BrowseState> = folder
        .flatMapLatest { current ->
            catalog.observeFolder(AndroidSourceFileSystem.PRIMARY, current).map { BrowseState(current, it) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BrowseState())

    fun open(entry: FolderEntry) {
        if (!entry.isFolder) return
        folder.value = if (folder.value.isEmpty()) entry.name else "${folder.value}/${entry.name}"
    }

    /** Goes to the parent folder. Returns false at the root, where there is nowhere to go. */
    fun up(): Boolean {
        val current = folder.value
        if (current.isEmpty()) return false
        folder.value = current.substringBeforeLast('/', missingDelimiterValue = "")
        return true
    }
}
