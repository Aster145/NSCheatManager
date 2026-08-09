package com.nscheatmanager.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.nscheatmanager.app.R
import com.nscheatmanager.app.domain.DeviceProfile
import com.nscheatmanager.app.ui.about.AboutScreen
import com.nscheatmanager.app.ui.settings.DeviceEditorUiState
import com.nscheatmanager.app.ui.settings.SettingsScreen
import com.nscheatmanager.app.ui.settings.SettingsUiState
import com.nscheatmanager.app.ui.settings.SettingsMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

data class SettingsActions(
    val add: () -> Unit,
    val edit: (DeviceProfile) -> Unit,
    val delete: (String) -> Unit,
    val setDefault: (String) -> Unit,
    val selectLanguage: (String) -> Unit,
    val editorChanged: (DeviceEditorUiState) -> Unit,
    val saveEditor: () -> Unit,
    val dismissEditor: () -> Unit,
) {
    companion object {
        val None = SettingsActions({}, {}, {}, {}, {}, {}, {}, {})
    }
}

private data class MainDestination(val route: String, val label: Int, val tag: String)

private val mainDestinations = listOf(
    MainDestination("game", R.string.nav_game, "nav-game"),
    MainDestination("cheats", R.string.nav_cheats, "nav-cheats"),
    MainDestination("memory", R.string.nav_memory, "nav-memory"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NSCheatManagerApp(
    settingsState: SettingsUiState,
    settingsActions: SettingsActions,
    versionName: String,
    settingsMessages: Flow<SettingsMessage> = emptyFlow(),
) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: "cheats"
    val mainRoute = mainDestinations.any { it.route == route }
    var menuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            if (mainRoute) TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    val moreOptions = stringResource(R.string.more_options)
                    IconButton(
                        modifier = Modifier.testTag("overflow-menu").semantics { contentDescription = moreOptions },
                        onClick = { menuExpanded = true },
                    ) { Text("⋮") }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            modifier = Modifier.testTag("menu-settings"),
                            text = { Text(stringResource(R.string.settings_title)) },
                            onClick = { menuExpanded = false; navController.navigate("settings") },
                        )
                        DropdownMenuItem(
                            modifier = Modifier.testTag("menu-about"),
                            text = { Text(stringResource(R.string.about_title)) },
                            onClick = { menuExpanded = false; navController.navigate("about") },
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (mainRoute) NavigationBar {
                mainDestinations.forEach { destination ->
                    NavigationBarItem(
                        modifier = Modifier.testTag(destination.tag),
                        selected = route == destination.route,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo("cheats") { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {},
                        label = { Text(stringResource(destination.label)) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(navController, startDestination = "cheats", modifier = Modifier.padding(padding)) {
            composable("game") { Placeholder(R.string.game_placeholder, "game-screen") }
            composable("cheats") { Placeholder(R.string.cheats_placeholder, "cheats-screen") }
            composable("memory") { Placeholder(R.string.memory_placeholder, "memory-screen") }
            composable("settings") {
                SettingsScreen(
                    state = settingsState, onBack = navController::popBackStack,
                    onAddDevice = settingsActions.add, onEditDevice = settingsActions.edit,
                    onDeleteDevice = settingsActions.delete, onSetDefault = settingsActions.setDefault,
                    onLanguageSelected = settingsActions.selectLanguage,
                    onEditorChanged = settingsActions.editorChanged,
                    onSaveEditor = settingsActions.saveEditor,
                    onDismissEditor = settingsActions.dismissEditor,
                    messages = settingsMessages,
                    modifier = Modifier.testTag("settings-content"),
                )
            }
            composable("about") { AboutScreen(versionName, navController::popBackStack) }
        }
    }
}

@Composable
private fun Placeholder(label: Int, tag: String) {
    Box(Modifier.fillMaxSize().testTag(tag), contentAlignment = Alignment.Center) {
        Text(stringResource(label))
    }
}
