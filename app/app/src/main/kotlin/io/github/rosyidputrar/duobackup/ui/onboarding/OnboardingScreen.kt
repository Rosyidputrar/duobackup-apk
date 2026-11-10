package io.github.rosyidputrar.duobackup.ui.onboarding

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.rosyidputrar.duobackup.R
import io.github.rosyidputrar.duobackup.storage.StorageAccess

private fun notificationsAllowed(context: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
    PackageManager.PERMISSION_GRANTED

/** Opens the All Files Access screen, falling back to the general list when the app page is missing. */
private fun openAllFilesAccess(context: Context) {
    for (intent in StorageAccess.allFilesAccessIntents(context)) {
        try {
            context.startActivity(intent)
            return
        } catch (e: ActivityNotFoundException) {
            continue
        }
    }
}

/**
 * Explains what the app does and asks for file access and notifications. `storageGranted` is
 * owned by the caller, which re-checks it every time the app comes back to the foreground.
 */
@Composable
fun OnboardingScreen(storageGranted: Boolean, onStorageChanged: () -> Unit, onContinue: () -> Unit) {
    val context = LocalContext.current
    var notificationsGranted by remember { mutableStateOf(notificationsAllowed(context)) }
    LifecycleResumeEffect(Unit) {
        notificationsGranted = notificationsAllowed(context)
        onPauseOrDispose { }
    }
    val legacyStorage = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        onStorageChanged()
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsGranted = it
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(R.string.onboarding_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.onboarding_intro))

        Text(stringResource(R.string.onboarding_storage_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.onboarding_storage_text))
        Text(stringResource(R.string.onboarding_storage_limit), style = MaterialTheme.typography.bodySmall)
        if (storageGranted) {
            Text(stringResource(R.string.onboarding_storage_granted), color = MaterialTheme.colorScheme.primary)
        } else {
            Button(
                onClick = {
                    if (StorageAccess.usesAllFilesAccess) {
                        openAllFilesAccess(context)
                    } else {
                        legacyStorage.launch(StorageAccess.legacyPermissions)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.onboarding_storage_grant))
            }
        }

        Text(stringResource(R.string.onboarding_notifications_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.onboarding_notifications_text))
        if (notificationsGranted) {
            Text(stringResource(R.string.onboarding_notifications_granted), color = MaterialTheme.colorScheme.primary)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            OutlinedButton(
                onClick = { notifications.launch(Manifest.permission.POST_NOTIFICATIONS) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.onboarding_notifications_grant))
            }
        }

        Button(onClick = onContinue, enabled = storageGranted, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_continue))
        }
    }
}
