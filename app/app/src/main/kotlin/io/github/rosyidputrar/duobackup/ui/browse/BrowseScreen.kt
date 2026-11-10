package io.github.rosyidputrar.duobackup.ui.browse

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.rosyidputrar.duobackup.R
import io.github.rosyidputrar.duobackup.ui.formatBytes

/** The files the catalog knows, as a folder tree. Restore actions arrive with the restore engine. */
@Composable
fun BrowseScreen(viewModel: BrowseViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    BackHandler(enabled = state.folder.isNotEmpty()) { viewModel.up() }
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = state.folder.ifEmpty { stringResource(R.string.browse_root) },
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp)
        )
        HorizontalDivider()
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            if (state.folder.isNotEmpty()) {
                item(key = "..") {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.browse_up)) },
                        leadingContent = { Icon(painterResource(R.drawable.ic_arrow_up), contentDescription = null) },
                        modifier = Modifier.clickable { viewModel.up() }
                    )
                }
            }
            if (state.entries.isEmpty() && state.folder.isEmpty()) {
                item(key = "empty") {
                    Text(stringResource(R.string.browse_empty), modifier = Modifier.padding(16.dp))
                }
            }
            items(state.entries, key = { (if (it.isFolder) "d:" else "f:") + it.name }) { entry ->
                val size = formatBytes(context, entry.totalBytes)
                ListItem(
                    headlineContent = { Text(entry.name) },
                    supportingContent = {
                        Text(
                            if (entry.isFolder) {
                                pluralStringResource(R.plurals.browse_file_count, entry.fileCount, entry.fileCount) +
                                    " · " + size
                            } else {
                                size
                            }
                        )
                    },
                    leadingContent = {
                        Icon(
                            painterResource(if (entry.isFolder) R.drawable.ic_folder else R.drawable.ic_file),
                            contentDescription = null
                        )
                    },
                    modifier = if (entry.isFolder) Modifier.clickable { viewModel.open(entry) } else Modifier
                )
            }
        }
    }
}
