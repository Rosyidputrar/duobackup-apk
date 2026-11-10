package io.github.rosyidputrar.duobackup.ui.destinations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.rosyidputrar.duobackup.R

/** Placeholder cards. Connecting Drive arrives with the Drive transport, Telegram with TDLib. */
@Composable
fun DestinationsScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        DestinationCard(stringResource(R.string.destinations_drive), stringResource(R.string.destinations_drive_text))
        DestinationCard(
            stringResource(R.string.destinations_telegram),
            stringResource(R.string.destinations_telegram_text)
        )
    }
}

@Composable
private fun DestinationCard(title: String, description: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description)
            Text(stringResource(R.string.destinations_not_connected), color = MaterialTheme.colorScheme.error)
            Text(stringResource(R.string.destinations_coming), style = MaterialTheme.typography.bodySmall)
        }
    }
}
