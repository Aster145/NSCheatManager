package com.nscheatmanager.app.ui

import android.content.Intent
import android.content.res.Resources
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.nscheatmanager.app.R
import com.nscheatmanager.app.data.files.ZipInspection
import com.nscheatmanager.app.domain.DeviceProfile
import com.nscheatmanager.app.domain.DirectOverwriteConfirmation
import com.nscheatmanager.app.domain.TransferReport
import com.nscheatmanager.app.ui.about.AboutScreen
import com.nscheatmanager.app.ui.editor.CheatEditorActions
import com.nscheatmanager.app.ui.editor.CheatEditorUiState
import com.nscheatmanager.app.ui.editor.EditorEffect
import com.nscheatmanager.app.ui.game.GameEffect
import com.nscheatmanager.app.ui.game.GameMessage
import com.nscheatmanager.app.ui.game.GameScreen
import com.nscheatmanager.app.ui.game.GameScreenActions
import com.nscheatmanager.app.ui.game.GameUiState
import com.nscheatmanager.app.ui.share.ZipDocumentReader
import com.nscheatmanager.app.ui.share.ZipShareService
import com.nscheatmanager.app.ui.settings.DeviceEditorUiState
import com.nscheatmanager.app.ui.settings.SettingsMessage
import com.nscheatmanager.app.ui.settings.SettingsScreen
import com.nscheatmanager.app.ui.settings.SettingsUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

data class GameEffectActions(
    val zipDocument: (ByteArray) -> Unit,
    val confirmZipImport: (ZipInspection) -> Unit,
    val confirmDownload: (TransferReport.RequiresLocalOverwriteConfirmation) -> Unit,
    val discardDownload: (TransferReport.RequiresLocalOverwriteConfirmation) -> Unit,
    val confirmUpload: (com.nscheatmanager.app.domain.UploadPreview) -> Unit,
    val confirmDirectUpload: (com.nscheatmanager.app.domain.UploadConfirmation, DirectOverwriteConfirmation) -> Unit,
    val confirmEmptyNotesShare: () -> Unit,
    val externalFailure: (Throwable) -> Unit,
) {
    companion object {
        val None = GameEffectActions({}, {}, {}, {}, {}, { _, _ -> }, {}, {})
    }
}

data class EditorEffectActions(
    val confirmDiscard: () -> Unit,
    val saved: (EditorEffect.Saved) -> Unit,
) {
    companion object {
        val None = EditorEffectActions({}, {})
    }
}

private data class MainDestination(val route: String, val label: Int, val tag: String)

private val mainDestinations = listOf(
    MainDestination("game", R.string.nav_game, "nav-game"),
    MainDestination("cheats", R.string.nav_cheats, "nav-cheats"),
    MainDestination("memory", R.string.nav_memory, "nav-memory"),
)

@Composable
fun NSCheatManagerApp(
    settingsState: SettingsUiState,
    settingsActions: SettingsActions,
    versionName: String,
    settingsMessages: Flow<SettingsMessage> = emptyFlow(),
    gameState: GameUiState = GameUiState(),
    gameActions: GameScreenActions = GameScreenActions.None,
    gameEffects: Flow<GameEffect> = emptyFlow(),
    gameEffectActions: GameEffectActions = GameEffectActions.None,
    editorState: CheatEditorUiState = CheatEditorUiState(),
    editorActions: CheatEditorActions = CheatEditorActions.None,
    editorEffects: Flow<EditorEffect> = emptyFlow(),
    editorEffectActions: EditorEffectActions = EditorEffectActions.None,
) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: "cheats"
    val mainRoute = mainDestinations.any { it.route == route }
    val context = LocalContext.current
    val resources = LocalResources.current
    val shareChooserTitle = stringResource(R.string.package_share_zip)
    val operationFailedLabel = stringResource(R.string.operation_failed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val confirmations = remember { mutableStateListOf<GameEffect>() }
    var confirmDiscard by remember { mutableStateOf(false) }
    var pendingRoute by remember { mutableStateOf<String?>(null) }

    val documentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { ZipDocumentReader(context).read(uri) } }
                .onSuccess(gameEffectActions.zipDocument)
                .onFailure(gameEffectActions.externalFailure)
        }
    }

    LaunchedEffect(gameEffects) {
        gameEffects.collect { effect ->
            when (effect) {
                GameEffect.OpenZipDocument -> documentLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                is GameEffect.Share -> runCatching {
                    val intent = ZipShareService(context).createIntent(effect.archive)
                    context.startActivity(Intent.createChooser(intent, shareChooserTitle))
                }.onFailure(gameEffectActions.externalFailure)
                is GameEffect.Message -> snackbar.showSnackbar(resources.gameMessage(effect))
                else -> confirmations += effect
            }
        }
    }
    LaunchedEffect(editorEffects) {
        editorEffects.collect { effect ->
            when (effect) {
                EditorEffect.ConfirmDiscard -> confirmDiscard = true
                is EditorEffect.Saved -> editorEffectActions.saved(effect)
                is EditorEffect.Error -> snackbar.showSnackbar(
                    listOfNotNull(operationFailedLabel, effect.detail).joinToString(": "),
                )
            }
        }
    }

    fun navigateMain(target: String) {
        navController.navigate(target) {
            popUpTo("cheats") { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    fun requestNavigation(target: String) {
        if (editorState.dirty) {
            pendingRoute = target
            editorActions.cancel()
        } else {
            if (editorState.isOpen) editorActions.cancel()
            navigateMain(target)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (mainRoute && !editorState.isOpen) NavigationBar {
                mainDestinations.forEach { destination ->
                    NavigationBarItem(
                        modifier = Modifier.testTag(destination.tag),
                        selected = route == destination.route,
                        onClick = { requestNavigation(destination.route) },
                        icon = {},
                        label = { Text(stringResource(destination.label)) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(navController, startDestination = "cheats", modifier = Modifier.padding(padding)) {
            composable("game") {
                GameScreen(
                    gameState.copy(editMode = editorState.isOpen),
                    gameActions.copy(
                        settings = { requestNavigation("settings") },
                        about = { requestNavigation("about") },
                    ),
                    Modifier.testTag("game-screen"),
                    editorState,
                    editorActions,
                )
            }
            composable("cheats") {
                GameScreen(
                    gameState.copy(editMode = editorState.isOpen),
                    gameActions.copy(
                        settings = { requestNavigation("settings") },
                        about = { requestNavigation("about") },
                    ),
                    Modifier.testTag("cheats-screen"),
                    editorState,
                    editorActions,
                )
            }
            composable("memory") { Placeholder(R.string.memory_placeholder, "memory-screen") }
            composable("settings") {
                SettingsScreen(
                    state = settingsState,
                    onBack = navController::popBackStack,
                    onAddDevice = settingsActions.add,
                    onEditDevice = settingsActions.edit,
                    onDeleteDevice = settingsActions.delete,
                    onSetDefault = settingsActions.setDefault,
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

    confirmations.firstOrNull()?.let { effect ->
        GameConfirmationDialog(
            effect = effect,
            onDismiss = {
                if (effect is GameEffect.ConfirmDownload) gameEffectActions.discardDownload(effect.report)
                confirmations.remove(effect)
            },
            onConfirm = {
                when (effect) {
                    is GameEffect.ConfirmZipImport -> gameEffectActions.confirmZipImport(effect.inspection)
                    is GameEffect.ConfirmDownload -> gameEffectActions.confirmDownload(effect.report)
                    is GameEffect.ConfirmUpload -> gameEffectActions.confirmUpload(effect.preview)
                    is GameEffect.ConfirmDirectUpload ->
                        gameEffectActions.confirmDirectUpload(effect.upload, effect.direct)
                    GameEffect.ConfirmEmptyNotesShare -> gameEffectActions.confirmEmptyNotesShare()
                    else -> Unit
                }
                confirmations.remove(effect)
            },
        )
    }
    if (confirmDiscard) {
        AlertDialog(
            modifier = Modifier.testTag("confirm-discard-editor"),
            onDismissRequest = { confirmDiscard = false; pendingRoute = null },
            title = { Text(stringResource(R.string.discard_changes_title)) },
            text = { Text(stringResource(R.string.discard_changes_message)) },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false; pendingRoute = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    editorEffectActions.confirmDiscard()
                    pendingRoute?.let(::navigateMain)
                    pendingRoute = null
                }) { Text(stringResource(R.string.discard)) }
            },
        )
    }
}

@Composable
private fun GameConfirmationDialog(effect: GameEffect, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val description = when (effect) {
        is GameEffect.ConfirmZipImport -> buildString {
            append(effect.inspection.titleId.hex).append(" / ").append(effect.inspection.buildId.hex)
            append("\n").append(stringResource(R.string.groups_count, effect.inspection.groupCount))
            append("\n").append(effect.inspection.entries.joinToString("\n") { "${it.relativePath} (${it.expandedSize} B)" })
            if (effect.inspection.overwriteImpact.cheat || effect.inspection.overwriteImpact.notes) {
                append("\n").append(stringResource(R.string.files_will_be_overwritten))
            }
        }
        is GameEffect.ConfirmDownload ->
            stringResource(R.string.download_overwrite_detail, effect.report.cheatBytes, effect.report.notesBytes ?: 0)
        is GameEffect.ConfirmUpload ->
            stringResource(R.string.upload_detail, effect.preview.cheatBytes, effect.preview.notesBytes ?: 0)
        is GameEffect.ConfirmDirectUpload -> stringResource(R.string.direct_upload_warning)
        GameEffect.ConfirmEmptyNotesShare -> stringResource(R.string.empty_notes_share_warning)
        else -> ""
    }
    AlertDialog(
        modifier = Modifier.testTag("game-confirmation"),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.confirm_operation)) },
        text = { Text(description) },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.confirm)) } },
    )
}

private fun Resources.gameMessage(effect: GameEffect.Message): String {
    val label = getString(
        when (effect.message) {
            GameMessage.SELECT_DEVICE -> R.string.select_device
            GameMessage.SESSION_NOT_READY -> R.string.session_not_ready
            GameMessage.EXECUTION_FAILED -> R.string.execution_failed
            GameMessage.UNSUPPORTED_CHEAT -> R.string.unsupported_cheat
            GameMessage.OPERATION_FAILED -> R.string.operation_failed
            GameMessage.REMOTE_CHEAT_MISSING -> R.string.remote_cheat_missing
            GameMessage.STALE_LOCAL_FILES -> R.string.stale_local_files
            GameMessage.DOWNLOAD_COMPLETE -> R.string.download_complete
            GameMessage.UPLOAD_COMPLETE -> R.string.upload_complete
            GameMessage.IMPORT_COMPLETE -> R.string.import_complete
            GameMessage.DETACH_COMPLETE -> R.string.detach_complete
        },
    )
    return listOfNotNull(label, effect.sourceLine?.let { "Line $it" }, effect.detail).joinToString(" · ")
}

@Composable
private fun Placeholder(label: Int, tag: String) {
    Box(Modifier.fillMaxSize().testTag(tag), contentAlignment = Alignment.Center) {
        Text(stringResource(label))
    }
}
