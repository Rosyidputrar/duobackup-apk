package io.github.rosyidputrar.duobackup.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.rosyidputrar.duobackup.R
import io.github.rosyidputrar.duobackup.data.settings.Schedule

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val storage by viewModel.storage.collectAsStateWithLifecycle()
    var choosingFolders by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.refreshStorage() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SectionTitle(stringResource(R.string.settings_section_when))
        Text(stringResource(R.string.settings_schedule), style = MaterialTheme.typography.titleSmall)
        ScheduleOption(R.string.settings_schedule_off, Schedule.OFF, settings.schedule, viewModel::setSchedule)
        ScheduleOption(R.string.settings_schedule_daily, Schedule.DAILY, settings.schedule, viewModel::setSchedule)
        ScheduleOption(R.string.settings_schedule_6h, Schedule.EVERY_6H, settings.schedule, viewModel::setSchedule)
        SwitchRow(
            stringResource(R.string.settings_wifi_only),
            stringResource(R.string.settings_wifi_only_text),
            settings.wifiOnly,
            onChange = viewModel::setWifiOnly
        )
        SwitchRow(
            stringResource(R.string.settings_charging_only),
            stringResource(R.string.settings_charging_only_text),
            settings.chargingOnly,
            onChange = viewModel::setChargingOnly
        )
        StepperRow(
            stringResource(R.string.settings_min_battery),
            stringResource(R.string.settings_percent, settings.minBatteryPct),
            onDecrease = { viewModel.setMinBatteryPct(settings.minBatteryPct - 5) },
            onIncrease = { viewModel.setMinBatteryPct(settings.minBatteryPct + 5) }
        )

        SectionTitle(stringResource(R.string.settings_section_what))
        Text(stringResource(R.string.settings_folders), style = MaterialTheme.typography.titleSmall)
        Text(
            if (settings.includeRoots == listOf("")) {
                stringResource(R.string.settings_folders_all)
            } else {
                settings.includeRoots.joinToString("\n")
            }
        )
        OutlinedButton(onClick = { choosingFolders = true }) {
            Text(stringResource(R.string.settings_folders_choose))
        }
        Text(stringResource(R.string.settings_excludes), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.settings_excludes_text), style = MaterialTheme.typography.bodySmall)
        ExcludeEditor(settings.excludes, viewModel::addExclude, viewModel::removeExclude, viewModel::resetExcludes)
        Text(stringResource(R.string.settings_fixed_excludes), style = MaterialTheme.typography.bodySmall)
        if (storage.volumes.isNotEmpty()) {
            Text(stringResource(R.string.settings_volumes), style = MaterialTheme.typography.titleSmall)
            for (volume in storage.volumes) {
                Text(
                    stringResource(
                        if (volume.removable) R.string.settings_volume_removable else R.string.settings_volume_primary,
                        volume.label
                    )
                )
            }
        }

        SectionTitle(stringResource(R.string.settings_section_keep))
        StepperRow(
            stringResource(R.string.settings_retention_days),
            pluralStringResource(R.plurals.settings_days, settings.retentionDays, settings.retentionDays),
            onDecrease = { viewModel.setRetentionDays(settings.retentionDays - 5) },
            onIncrease = { viewModel.setRetentionDays(settings.retentionDays + 5) }
        )
        StepperRow(
            stringResource(R.string.settings_snapshot_keep),
            settings.snapshotKeep.toString(),
            onDecrease = { viewModel.setSnapshotKeep(settings.snapshotKeep - 1) },
            onIncrease = { viewModel.setSnapshotKeep(settings.snapshotKeep + 1) }
        )

        SectionTitle(stringResource(R.string.settings_section_encryption))
        SwitchRow(
            stringResource(R.string.settings_encryption),
            stringResource(R.string.settings_encryption_text),
            checked = settings.encryptionEnabled,
            enabled = false,
            onChange = {}
        )
    }

    if (choosingFolders) {
        FolderPicker(
            folders = storage.folders,
            selected = settings.includeRoots,
            onDismiss = { choosingFolders = false },
            onSave = {
                viewModel.setIncludeRoots(it)
                choosingFolders = false
            }
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun ScheduleOption(label: Int, value: Schedule, current: Schedule, onSelect: (Schedule) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = value == current, role = Role.RadioButton, onClick = { onSelect(value) }),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = value == current, onClick = null, modifier = Modifier.padding(8.dp))
        Text(stringResource(label))
    }
}

@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun StepperRow(title: String, value: String, onDecrease: () -> Unit, onIncrease: () -> Unit) {
    val decrease = stringResource(R.string.action_decrease)
    val increase = stringResource(R.string.action_increase)
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title)
            Text(value, style = MaterialTheme.typography.bodySmall)
        }
        // The symbols are what is drawn; the content descriptions are what a screen reader says.
        TextButton(onClick = onDecrease, modifier = Modifier.semantics { contentDescription = decrease }) {
            Text("\u2212")
        }
        TextButton(onClick = onIncrease, modifier = Modifier.semantics { contentDescription = increase }) {
            Text("+")
        }
    }
}

@Composable
private fun ExcludeEditor(
    globs: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onReset: () -> Unit
) {
    var draft by rememberSaveable { mutableStateOf("") }
    for (glob in globs) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(glob, modifier = Modifier.weight(1f))
            TextButton(onClick = { onRemove(glob) }) { Text(stringResource(R.string.action_remove)) }
        }
    }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            singleLine = true,
            placeholder = { Text(stringResource(R.string.settings_excludes_add_hint)) },
            modifier = Modifier.weight(1f)
        )
        TextButton(
            onClick = {
                onAdd(draft)
                draft = ""
            },
            enabled = draft.isNotBlank()
        ) {
            Text(stringResource(R.string.action_add))
        }
    }
    TextButton(onClick = onReset) { Text(stringResource(R.string.settings_excludes_reset)) }
}

/** Picks top-level folders of shared storage. Choosing "all" stands for the whole volume. */
@Composable
private fun FolderPicker(
    folders: List<String>,
    selected: List<String>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit
) {
    var all by remember { mutableStateOf(selected == listOf("")) }
    var chosen by remember { mutableStateOf(selected.filter { it.isNotEmpty() }.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_folders_choose)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                CheckRow(stringResource(R.string.settings_folders_all), all) { all = it }
                if (folders.isEmpty()) Text(stringResource(R.string.settings_folders_none))
                // Folders chosen earlier stay listed even when they are missing right now.
                for (folder in (folders + chosen).distinct().sorted()) {
                    CheckRow(folder, checked = all || folder in chosen, enabled = !all) { checked ->
                        chosen = if (checked) chosen + folder else chosen - folder
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(if (all) listOf("") else chosen.sorted()) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

@Composable
private fun CheckRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.padding(8.dp))
        Text(label)
    }
}
