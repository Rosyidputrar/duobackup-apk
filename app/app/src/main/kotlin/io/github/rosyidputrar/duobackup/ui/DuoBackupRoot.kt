package io.github.rosyidputrar.duobackup.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.rosyidputrar.duobackup.R
import io.github.rosyidputrar.duobackup.storage.StorageAccess
import io.github.rosyidputrar.duobackup.ui.activity.ActivityLogScreen
import io.github.rosyidputrar.duobackup.ui.browse.BrowseScreen
import io.github.rosyidputrar.duobackup.ui.destinations.DestinationsScreen
import io.github.rosyidputrar.duobackup.ui.home.HomeScreen
import io.github.rosyidputrar.duobackup.ui.onboarding.OnboardingScreen
import io.github.rosyidputrar.duobackup.ui.settings.SettingsScreen

private enum class Tab(val route: String, @StringRes val label: Int, @DrawableRes val icon: Int) {
    HOME("home", R.string.nav_home, R.drawable.ic_home),
    DESTINATIONS("destinations", R.string.nav_destinations, R.drawable.ic_cloud),
    BROWSE("browse", R.string.nav_browse, R.drawable.ic_folder),
    ACTIVITY("activity", R.string.nav_activity, R.drawable.ic_list),
    SETTINGS("settings", R.string.nav_settings, R.drawable.ic_tune)
}

/**
 * Shows onboarding until file access is granted and the user has continued, then the main tabs.
 * Access is checked again on every resume, because it is granted, and can be revoked, outside
 * the app.
 */
@Composable
fun DuoBackupRoot() {
    val context = LocalContext.current
    var storageGranted by rememberSaveable { mutableStateOf(StorageAccess.isGranted(context)) }
    var onboardingDone by rememberSaveable { mutableStateOf(storageGranted) }
    LifecycleResumeEffect(Unit) {
        storageGranted = StorageAccess.isGranted(context)
        onPauseOrDispose { }
    }

    if (!storageGranted || !onboardingDone) {
        Scaffold { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                OnboardingScreen(
                    storageGranted = storageGranted,
                    onStorageChanged = { storageGranted = StorageAccess.isGranted(context) },
                    onContinue = { onboardingDone = true }
                )
            }
        }
        return
    }

    val navController = rememberNavController()
    val current = navController.currentBackStackEntryAsState().value?.destination?.route
    Scaffold(
        bottomBar = {
            NavigationBar {
                for (tab in Tab.entries) {
                    NavigationBarItem(
                        selected = current == tab.route,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(painterResource(tab.icon), contentDescription = null) },
                        label = { Text(stringResource(tab.label), maxLines = 1) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(navController, startDestination = Tab.HOME.route, modifier = Modifier.padding(padding)) {
            composable(Tab.HOME.route) { HomeScreen() }
            composable(Tab.DESTINATIONS.route) { DestinationsScreen() }
            composable(Tab.BROWSE.route) { BrowseScreen() }
            composable(Tab.ACTIVITY.route) { ActivityLogScreen() }
            composable(Tab.SETTINGS.route) { SettingsScreen() }
        }
    }
}
