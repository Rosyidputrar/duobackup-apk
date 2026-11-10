package io.github.rosyidputrar.duobackup.ui.activity

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.rosyidputrar.duobackup.R
import io.github.rosyidputrar.duobackup.core.catalog.Catalog
import io.github.rosyidputrar.duobackup.core.catalog.RunRecord
import io.github.rosyidputrar.duobackup.ui.formatBytes
import io.github.rosyidputrar.duobackup.ui.formatDateTime
import io.github.rosyidputrar.duobackup.ui.resultLabel
import io.github.rosyidputrar.duobackup.ui.triggerLabel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class ActivityLogViewModel @Inject constructor(catalog: Catalog) : ViewModel() {
    val runs: StateFlow<List<RunRecord>> = catalog.observeRuns(MAX_RUNS)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private companion object {
        const val MAX_RUNS = 200
    }
}

/** The log of backup runs, newest first. */
@Composable
fun ActivityLogScreen(viewModel: ActivityLogViewModel = hiltViewModel()) {
    val runs by viewModel.runs.collectAsStateWithLifecycle()
    val context = LocalContext.current
    if (runs.isEmpty()) {
        Text(stringResource(R.string.activity_empty), modifier = Modifier.padding(16.dp))
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(runs, key = { it.id }) { run ->
            val details = buildList {
                add(stringResource(triggerLabel(run.trigger)))
                add(
                    stringResource(
                        R.string.activity_uploaded,
                        formatBytes(context, run.bytesUploadedDrive),
                        formatBytes(context, run.bytesUploadedTg)
                    )
                )
                add(pluralStringResource(R.plurals.activity_files_hashed, run.filesHashed, run.filesHashed))
                if (run.errors > 0) add(pluralStringResource(R.plurals.activity_errors, run.errors, run.errors))
                run.stopReason?.let { add(stringResource(R.string.activity_stop_reason, it)) }
            }
            ListItem(
                headlineContent = {
                    Text(formatDateTime(context, run.startedAt) + " · " + stringResource(resultLabel(run.result)))
                },
                supportingContent = { Text(details.joinToString("\n")) }
            )
            HorizontalDivider()
        }
    }
}
