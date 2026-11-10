package io.github.rosyidputrar.duobackup.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.rosyidputrar.duobackup.R
import io.github.rosyidputrar.duobackup.ui.formatBytes
import io.github.rosyidputrar.duobackup.ui.formatDateTime
import io.github.rosyidputrar.duobackup.ui.resultLabel

@Composable
fun HomeScreen(viewModel: HomeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        InfoCard(stringResource(R.string.home_last_run)) {
            val run = state.lastRun
            Text(
                when {
                    run == null -> stringResource(R.string.home_last_run_none)
                    run.endedAt == null ->
                        stringResource(R.string.home_last_run_running, formatDateTime(context, run.startedAt))
                    else -> stringResource(
                        R.string.home_last_run_detail,
                        formatDateTime(context, run.endedAt ?: run.startedAt),
                        stringResource(resultLabel(run.result))
                    )
                }
            )
        }
        InfoCard(stringResource(R.string.home_next_run)) {
            Text(state.nextRunAt?.let { formatDateTime(context, it) } ?: stringResource(R.string.home_next_run_none))
        }
        InfoCard(stringResource(R.string.home_pending)) {
            val off = stringResource(R.string.home_destination_off)
            Text(
                stringResource(
                    R.string.home_pending_drive,
                    if (state.driveEnabled) formatBytes(context, state.pendingDriveBytes) else off
                )
            )
            Text(
                stringResource(
                    R.string.home_pending_telegram,
                    if (state.telegramEnabled) formatBytes(context, state.pendingTelegramBytes) else off
                )
            )
        }
        InfoCard(stringResource(R.string.home_quota)) {
            Text(stringResource(R.string.home_quota_unknown))
        }
        if (!state.driveEnabled && !state.telegramEnabled) {
            Text(stringResource(R.string.home_no_destination), style = MaterialTheme.typography.bodySmall)
        }
        if (state.running) {
            Text(stringResource(R.string.home_running))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = viewModel::stop, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_stop))
            }
        } else {
            Button(onClick = viewModel::backUpNow, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.home_back_up_now))
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
