package com.nscheatmanager.app.ui

import android.content.Intent
import android.content.ContentValues
import android.content.res.Resources
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.nscheatmanager.app.R
import com.nscheatmanager.app.domain.DeviceProfile
import com.nscheatmanager.app.ui.about.AboutScreen
import com.nscheatmanager.app.ui.editor.CheatEditorActions
import com.nscheatmanager.app.ui.editor.CheatEditorUiState
import com.nscheatmanager.app.ui.editor.EditorEffect
import com.nscheatmanager.app.ui.game.GameEffect
import com.nscheatmanager.app.ui.game.GameConfirmation
import com.nscheatmanager.app.ui.game.CheatDiagnosticKind
import com.nscheatmanager.app.ui.game.CheatDiagnosticUiState
import com.nscheatmanager.app.ui.game.GameMessage
import com.nscheatmanager.app.ui.game.GameScreen
import com.nscheatmanager.app.ui.game.GameScreenActions
import com.nscheatmanager.app.ui.game.GameScreenContent
import com.nscheatmanager.app.ui.game.GameUiState
import com.nscheatmanager.app.ui.share.ZipDocumentReader
import com.nscheatmanager.app.ui.share.ZipShareService
import com.nscheatmanager.app.ui.settings.DeviceEditorUiState
import com.nscheatmanager.app.ui.settings.SettingsMessage
import com.nscheatmanager.app.ui.settings.SettingsScreen
import com.nscheatmanager.app.ui.settings.SettingsUiState
import com.nscheatmanager.app.ui.memory.MemoryActions
import com.nscheatmanager.app.ui.memory.MemoryScreen
import com.nscheatmanager.app.ui.memory.MemoryUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SettingsActions(
    val add: () -> Unit,
    val edit: (DeviceProfile) -> Unit,
    val delete: (String) -> Unit,
    val setDefault: (String) -> Unit,
    val selectLanguage: (String) -> Unit,
    val showMemoryPage: (Boolean) -> Unit,
    val editorChanged: (DeviceEditorUiState) -> Unit,
    val saveEditor: () -> Unit,
    val dismissEditor: () -> Unit,
) {
    companion object {
        val None = SettingsActions({}, {}, {}, {}, {}, {}, {}, {}, {})
    }
}

data class GameEffectActions(
    val zipDocument: (ByteArray) -> Unit,
    val confirmPending: (Long) -> Unit,
    val dismissPending: (Long) -> Unit,
    val zipFailure: (Throwable) -> Unit,
    val externalFailure: (Throwable) -> Unit,
) {
    companion object {
        val None = GameEffectActions({}, {}, {}, {}, {})
    }
}

data class EditorEffectActions(
    val saved: (EditorEffect.Saved) -> Unit,
) {
    companion object {
        val None = EditorEffectActions({})
    }
}

interface GameExternalActions {
    fun openZip(fallback: () -> Unit, deliver: (ByteArray) -> Unit, failure: (Throwable) -> Unit) = fallback()
    fun share(fallback: () -> Unit, archive: com.nscheatmanager.app.ui.game.ShareArchive, failure: (Throwable) -> Unit) = fallback()

    data object Platform : GameExternalActions
}

private data class MainDestination(val route: String, val label: Int, val tag: String, val icon: ImageVector)

private val mainDestinations = listOf(
    MainDestination("game", R.string.nav_game, "nav-game", Icons.Filled.Home),
    MainDestination("cheats", R.string.nav_cheats, "nav-cheats", Icons.Filled.Star),
    MainDestination("memory", R.string.nav_memory, "nav-memory", Icons.Filled.Build),
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
    externalActions: GameExternalActions = GameExternalActions.Platform,
    editorState: CheatEditorUiState = CheatEditorUiState(),
    editorActions: CheatEditorActions = CheatEditorActions.None,
    editorEffects: Flow<EditorEffect> = emptyFlow(),
    editorEffectActions: EditorEffectActions = EditorEffectActions.None,
    memoryState: MemoryUiState = MemoryUiState(),
    memoryActions: MemoryActions = MemoryActions.None,
) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: "cheats"
    val visibleDestinations = mainDestinations.filter { it.route != "memory" || settingsState.showMemoryPage }
    val mainRoute = mainDestinations.any { it.route == route }
    val context = LocalContext.current
    val resources = LocalResources.current
    val shareChooserTitle = stringResource(R.string.package_share_zip)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val documentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { ZipDocumentReader(context).read(uri) } }
                .onSuccess(gameEffectActions.zipDocument)
                .onFailure(gameEffectActions.zipFailure)
        }
    }

    LaunchedEffect(gameEffects) {
        gameEffects.collect { effect ->
            when (effect) {
                GameEffect.OpenZipDocument -> externalActions.openZip(
                    fallback = { documentLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                    deliver = gameEffectActions.zipDocument,
                    failure = gameEffectActions.zipFailure,
                )
                is GameEffect.Share -> runCatching {
                    externalActions.share(
                        fallback = {
                            val intent = ZipShareService(context).createIntent(effect.archive)
                            context.startActivity(Intent.createChooser(intent, shareChooserTitle))
                        },
                        archive = effect.archive,
                        failure = gameEffectActions.externalFailure,
                    )
                }.onFailure(gameEffectActions.externalFailure)
                is GameEffect.Screenshot -> runCatching {
                    withContext(Dispatchers.IO) { saveScreenshot(context, effect.jpeg) }
                }.onSuccess { name -> snackbar.showSnackbar("截图已保存：$name") }
                    .onFailure(gameEffectActions.externalFailure)
                is GameEffect.Message -> snackbar.showSnackbar(resources.localizedGameMessage(effect))
                is GameEffect.UserError -> snackbar.showSnackbar(resources.localizedUserMessage(effect.message))
            }
        }
    }
    LaunchedEffect(editorEffects) {
        editorEffects.collect { effect ->
            when (effect) {
                is EditorEffect.Saved -> editorEffectActions.saved(effect)
                is EditorEffect.Error -> snackbar.showSnackbar(
                    resources.localizedUserMessage(effect.message),
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
        if (editorState.isOpen) {
            editorActions.requestNavigation(target)
        } else {
            navigateMain(target)
        }
    }

    LaunchedEffect(editorState.pendingNavigationRoute) {
        editorState.pendingNavigationRoute?.let { target ->
            navigateMain(target)
            editorActions.acknowledgeNavigation(target)
        }
    }

    LaunchedEffect(settingsState.showMemoryPage, route) {
        if (!settingsState.showMemoryPage && route == "memory") {
            val pendingWrite = memoryState.confirmation
            if (pendingWrite != null) memoryActions.dismiss.invoke(pendingWrite.id)
            navigateMain("cheats")
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar, Modifier.testTag("app-snackbar")) },
        bottomBar = {
            if (mainRoute && !editorState.isOpen) NavigationBar {
                visibleDestinations.forEach { destination ->
                    NavigationBarItem(
                        modifier = Modifier.testTag(destination.tag),
                        selected = route == destination.route,
                        onClick = { requestNavigation(destination.route) },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        label = { Text(stringResource(destination.label)) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(navController, startDestination = "game", modifier = Modifier.padding(padding)) {
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
                    GameScreenContent.GameInfo,
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
                    GameScreenContent.Cheats,
                    onOpenGame = { requestNavigation("game") },
                )
            }
            composable("memory") { MemoryScreen(memoryState, memoryActions, Modifier.testTag("memory-screen")) }
            composable("settings") {
                SettingsScreen(
                    state = settingsState,
                    onBack = navController::popBackStack,
                    onAddDevice = settingsActions.add,
                    onEditDevice = settingsActions.edit,
                    onDeleteDevice = settingsActions.delete,
                    onSetDefault = settingsActions.setDefault,
                    onLanguageSelected = settingsActions.selectLanguage,
                    onShowMemoryPageChanged = settingsActions.showMemoryPage,
                    onEditorChanged = settingsActions.editorChanged,
                    onSaveEditor = settingsActions.saveEditor,
                    onDismissEditor = settingsActions.dismissEditor,
                    messages = settingsMessages,
                    modifier = Modifier.testTag("settings-content"),
                )
            }
            composable("about") { AboutScreen(versionName, navController::popBackStack, Modifier.testTag("about-content")) }
        }
    }

    gameState.pendingConfirmation?.let { confirmation ->
        GameConfirmationDialog(
            confirmation = confirmation,
            onDismiss = { gameEffectActions.dismissPending(confirmation.id) },
            onConfirm = { gameEffectActions.confirmPending(confirmation.id) },
        )
    }
    editorState.pendingDiscard?.let { pending ->
        AlertDialog(
            modifier = Modifier.testTag("confirm-discard-editor"),
            onDismissRequest = { editorActions.dismissDiscard(pending.id) },
            title = { Text(stringResource(R.string.discard_changes_title)) },
            text = { Text(stringResource(R.string.discard_changes_message)) },
            dismissButton = {
                TextButton(onClick = { editorActions.dismissDiscard(pending.id) }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    editorActions.confirmDiscard(pending.id)
                }) { Text(stringResource(R.string.discard)) }
            },
        )
    }
}

private fun saveScreenshot(context: android.content.Context, jpeg: ByteArray): String {
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
    val name = "NSCheatManager_${stamp}.jpg"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/NSCheatManager")
        }
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        context.contentResolver.openOutputStream(uri)?.use { it.write(jpeg) } ?: error("Could not open screenshot output")
    } else {
        val directory = File(requireNotNull(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)), "NSCheatManager")
        directory.mkdirs()
        File(directory, name).outputStream().use { it.write(jpeg) }
    }
    return name
}

private fun Resources.localizedUserMessage(message: com.nscheatmanager.app.ui.common.UserMessage): String {
    val detail = message.detail
    return listOfNotNull(
        getString(message.messageRes),
        detail.line?.let { getString(R.string.line_number, it) },
        detail.opcode,
    ).reduceOrNull { left, right -> getString(R.string.message_join, left, right) }
        ?: getString(message.messageRes)
}

@Composable
private fun GameConfirmationDialog(confirmation: GameConfirmation, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val description = when (confirmation) {
        is GameConfirmation.ZipImport -> buildString {
            append(confirmation.inspection.titleId.hex).append(" / ").append(confirmation.inspection.buildId.hex)
            append("\n").append(stringResource(R.string.groups_count, confirmation.inspection.groupCount))
            append("\n").append(confirmation.inspection.entries.joinToString("\n") { "${it.relativePath} (${it.expandedSize} B)" })
            if (confirmation.inspection.overwriteImpact.cheat || confirmation.inspection.overwriteImpact.notes) {
                append("\n").append(stringResource(R.string.files_will_be_overwritten))
            }
        }
        is GameConfirmation.Download ->
            stringResource(R.string.download_overwrite_detail, confirmation.report.cheatBytes, confirmation.report.notesBytes ?: 0)
        is GameConfirmation.Upload ->
            stringResource(R.string.upload_detail, confirmation.preview.cheatBytes, confirmation.preview.notesBytes ?: 0)
        is GameConfirmation.DirectUpload -> stringResource(R.string.direct_upload_warning)
        is GameConfirmation.EmptyNotesShare -> stringResource(R.string.empty_notes_share_warning)
    }
    AlertDialog(
        modifier = Modifier.testTag("game-confirmation"),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.confirm_operation)) },
        text = { Text(description) },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("game-dismiss")) { Text(stringResource(R.string.cancel)) } },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.testTag("game-confirm")) { Text(stringResource(R.string.confirm)) } },
    )
}

internal fun Resources.localizedGameMessage(effect: GameEffect.Message): String {
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
            GameMessage.LOCAL_CHEAT_MISSING -> R.string.cheat_file_missing
        },
    )
    return listOfNotNull(
        label,
        effect.diagnostic?.let(::localizedDiagnostic)
            ?: effect.sourceLine?.let { getString(R.string.line_number, it) },
        effect.detail.takeIf { effect.diagnostic == null },
    ).reduceOrNull { left, right -> getString(R.string.message_join, left, right) } ?: label
}

private fun Resources.localizedDiagnostic(diagnostic: CheatDiagnosticUiState): String = when (diagnostic.kind) {
    CheatDiagnosticKind.UnsupportedOpcode -> getString(
        R.string.diagnostic_unsupported_opcode,
        diagnostic.line,
        diagnostic.opcode.orEmpty(),
    )
    CheatDiagnosticKind.UnsupportedForm -> getString(
        R.string.diagnostic_unsupported_form,
        diagnostic.line,
        diagnostic.argument.orEmpty(),
    )
    CheatDiagnosticKind.UnsupportedMemoryRegion -> getString(
        R.string.diagnostic_unsupported_memory_region,
        diagnostic.line,
        diagnostic.argument.orEmpty(),
    )
    CheatDiagnosticKind.ArithmeticOverflow -> getString(
        R.string.diagnostic_arithmetic_overflow,
        diagnostic.line,
    )
    CheatDiagnosticKind.InstructionLimitExceeded -> getString(
        R.string.diagnostic_instruction_limit,
        diagnostic.line,
        diagnostic.argument.orEmpty(),
    )
    CheatDiagnosticKind.IoLimitExceeded -> getString(
        R.string.diagnostic_io_limit,
        diagnostic.line,
        diagnostic.argument.orEmpty(),
    )
    CheatDiagnosticKind.Connection -> getString(R.string.diagnostic_connection, diagnostic.line)
    CheatDiagnosticKind.Timeout -> getString(
        R.string.diagnostic_timeout,
        diagnostic.line,
        diagnostic.argument.orEmpty(),
    )
    CheatDiagnosticKind.Disconnected -> getString(R.string.diagnostic_disconnected, diagnostic.line)
    CheatDiagnosticKind.MalformedResponse -> getString(R.string.diagnostic_malformed_response, diagnostic.line)
    CheatDiagnosticKind.ResponseTooLarge -> getString(
        R.string.diagnostic_response_too_large,
        diagnostic.line,
        diagnostic.argument.orEmpty(),
    )
    CheatDiagnosticKind.CommandTooLarge -> getString(
        R.string.diagnostic_command_too_large,
        diagnostic.line,
        diagnostic.argument.orEmpty(),
    )
}

@Composable
private fun Placeholder(label: Int, tag: String) {
    Box(Modifier.fillMaxSize().testTag(tag), contentAlignment = Alignment.Center) {
        Text(stringResource(label))
    }
}
