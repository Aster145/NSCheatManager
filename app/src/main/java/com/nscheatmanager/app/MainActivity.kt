package com.nscheatmanager.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import com.nscheatmanager.app.ui.NSCheatManagerApp
import com.nscheatmanager.app.ui.EditorEffectActions
import com.nscheatmanager.app.ui.GameEffectActions
import com.nscheatmanager.app.ui.SettingsActions
import com.nscheatmanager.app.ui.editor.CheatEditorActions
import com.nscheatmanager.app.ui.editor.CheatEditorViewModel
import com.nscheatmanager.app.ui.game.GameScreenActions
import com.nscheatmanager.app.ui.game.GameViewModel
import com.nscheatmanager.app.ui.settings.SettingsViewModel
import com.nscheatmanager.app.ui.memory.MemoryActions
import com.nscheatmanager.app.ui.memory.MemoryViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : AppCompatActivity() {
    companion object {
        @Volatile var dependenciesForTest: MainActivityDependencies? = null
    }
    private val dependencies get() = dependenciesForTest ?: (application as NSCheatManagerApplication).dependencies
    private var pendingBookmarkExport: String? = null
    private val exportBookmarks = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val text = pendingBookmarkExport.also { pendingBookmarkExport = null } ?: return@registerForActivityResult
        if (uri != null) lifecycleScope.launch(Dispatchers.IO) { contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(text) } }
    }
    private val importBookmarks = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch(Dispatchers.IO) {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText().take(2_000_001) }
            if (text != null && text.length <= 2_000_000) withContext(Dispatchers.Main) { memoryViewModel.beginBookmarkImport(text) }
        }
    }
    private val settingsViewModel by viewModels<SettingsViewModel> {
        SettingsViewModel.Factory(dependencies.devices, dependencies.preferences, ::applyLocale)
    }
    private val gameViewModel by viewModels<GameViewModel> {
        GameViewModel.Factory(dependencies.gameDevices, dependencies.gameFiles, dependencies::createGameSession)
    }
    private val editorViewModel by viewModels<CheatEditorViewModel> {
        CheatEditorViewModel.Factory(
            dependencies.gameFiles,
            gameViewModel::requireCurrentOperationKey,
            dependencies.editorDrafts,
        )
    }
    private val memoryViewModel by viewModels<MemoryViewModel> { MemoryViewModel.Factory(gameViewModel, dependencies.memoryBookmarks) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                dependencies.preferences.languageTag.collect(::applyLocale)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { editorViewModel.flushLatestDraft() }
                }
            }
        }
        setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale.coerceAtMost(1f))) {
            NSCheatManagerTheme {
                val state by settingsViewModel.uiState.collectAsStateWithLifecycle()
                val gameState by gameViewModel.uiState.collectAsStateWithLifecycle()
                val editorState by editorViewModel.uiState.collectAsStateWithLifecycle()
                val memoryState by memoryViewModel.uiState.collectAsStateWithLifecycle()
                NSCheatManagerApp(
                    settingsState = state,
                    settingsActions = SettingsActions(
                        add = settingsViewModel::openAddDevice,
                        edit = settingsViewModel::openEditDevice,
                        delete = settingsViewModel::deleteDevice,
                        setDefault = settingsViewModel::setDefaultDevice,
                        selectLanguage = settingsViewModel::selectLanguage,
                        showMemoryPage = settingsViewModel::setShowMemoryPage,
                        detachDmntBeforeConnect = settingsViewModel::setDetachDmntBeforeConnect,
                        editorChanged = settingsViewModel::updateEditor,
                        saveEditor = settingsViewModel::saveEditor,
                        dismissEditor = settingsViewModel::dismissEditor,
                    ),
                    versionName = BuildConfig.VERSION_NAME,
                    settingsMessages = settingsViewModel.messages,
                    gameState = gameState,
                    gameActions = GameScreenActions(
                        selectDevice = gameViewModel::onDeviceSelected,
                        connectionToggle = { gameViewModel.onConnectionToggle(state.detachDmntBeforeConnect) },
                        detachDmnt = gameViewModel::onDetachDmntRequested,
                        cheatChecked = gameViewModel::onCheatChecked,
                        editModeChanged = { enabled ->
                            if (enabled) {
                                val identity = gameViewModel.currentIdentityForEditor()
                                val key = gameViewModel.currentOperationKeyForEditor()
                                if (identity != null && key != null && !gameState.missingMirror) editorViewModel.open(identity, key)
                                else gameViewModel.onEditorUnavailable()
                            } else {
                                editorViewModel.requestClose()
                            }
                        },
                        recognize = gameViewModel::onRecognizeRequested,
                        download = gameViewModel::onDownloadRequested,
                        upload = gameViewModel::onUploadRequested,
                        shareZip = gameViewModel::onShareZipRequested,
                        importZip = gameViewModel::onImportZipRequested,
                        settings = {},
                        about = {},
                    ),
                    gameEffects = gameViewModel.effects,
                    gameEffectActions = GameEffectActions(
                        zipDocument = gameViewModel::onZipDocument,
                        confirmPending = gameViewModel::confirmPending,
                        dismissPending = gameViewModel::dismissPending,
                        zipFailure = gameViewModel::onZipExternalFailure,
                        externalFailure = gameViewModel::onExternalFailure,
                    ),
                    externalActions = dependencies.externalActions,
                    editorState = editorState,
                    editorActions = CheatEditorActions(
                        selectTab = editorViewModel::selectTab,
                        cheatChanged = editorViewModel::updateCheatText,
                        notesChanged = editorViewModel::updateNotesText,
                        save = editorViewModel::save,
                        cancel = editorViewModel::requestClose,
                        requestNavigation = editorViewModel::requestClose,
                        confirmDiscard = editorViewModel::confirmDiscard,
                        dismissDiscard = editorViewModel::dismissDiscard,
                        acknowledgeNavigation = editorViewModel::acknowledgeNavigation,
                    ),
                    editorEffects = editorViewModel.effects,
                    editorEffectActions = EditorEffectActions(
                        saved = { saved -> gameViewModel.onLocalFileSaved(saved.identity, saved.file) },
                    ),
                    memoryState = memoryState,
                    memoryActions = MemoryActions(
                        mode = memoryViewModel::selectMode, address = memoryViewModel::updateAddress,
                        type = memoryViewModel::selectType, value = memoryViewModel::updateValue,
                        length = memoryViewModel::updateLength, read = memoryViewModel::read,
                        write = memoryViewModel::requestWrite, lock = memoryViewModel::toggleLock,
                        confirm = memoryViewModel::confirmWrite, dismiss = memoryViewModel::dismissWrite,
                        applyBookmark = memoryViewModel::applyBookmark, saveBookmark = memoryViewModel::saveBookmark,
                        deleteBookmark = memoryViewModel::deleteBookmark,
                        importBookmarks = { importBookmarks.launch(arrayOf("application/json", "text/plain")) },
                        exportBookmarks = { noexes -> memoryViewModel.exportBookmarks(noexes)?.let {
                            pendingBookmarkExport = it; exportBookmarks.launch(if (noexes) "NSCheatManager-noexes-bookmarks.json" else "NSCheatManager-bookmarks.json")
                        } },
                        confirmBookmarkImport = memoryViewModel::confirmBookmarkImport,
                        dismissBookmarkImport = memoryViewModel::dismissBookmarkImport,
                    ),
                )
            } }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun applyLocale(languageTag: String) {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.toLanguageTags() != languageTag) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(languageTag))
        }
    }
}

@Composable
fun NSCheatManagerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme(
            primary = Color(0xFF6EA8FF), onPrimary = Color(0xFF061A33),
            primaryContainer = Color(0xFF17345C), onPrimaryContainer = Color(0xFFD8E8FF),
            secondary = Color(0xFF9EC5FF), onSecondary = Color(0xFF082044),
            background = Color(0xFF111820), onBackground = Color(0xFFF3F7FC),
            surface = Color(0xFF19222D), onSurface = Color(0xFFF3F7FC),
            surfaceVariant = Color(0xFF202C39), onSurfaceVariant = Color(0xFFB5C2D1),
            outline = Color(0xFF4B5D70), outlineVariant = Color(0xFF303D4B),
        ) else lightColorScheme(
            primary = Color(0xFF2563EB), onPrimary = Color.White,
            primaryContainer = Color(0xFFE8F0FF), onPrimaryContainer = Color(0xFF153E91),
            secondary = Color(0xFF3B6FC4), onSecondary = Color.White,
            background = Color.White, onBackground = Color(0xFF172033),
            surface = Color.White, onSurface = Color(0xFF172033),
            surfaceVariant = Color(0xFFF3F6FA), onSurfaceVariant = Color(0xFF647085),
            outline = Color(0xFFD5DCE6), outlineVariant = Color(0xFFE6EBF2),
        ),
        shapes = Shapes(
            extraSmall = RoundedCornerShape(9.dp),
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(15.dp),
            large = RoundedCornerShape(18.dp),
            extraLarge = RoundedCornerShape(22.dp),
        ),
        typography = Typography(
            headlineSmall = androidx.compose.ui.text.TextStyle(fontSize = 20.sp, lineHeight = 26.sp),
            titleLarge = androidx.compose.ui.text.TextStyle(fontSize = 18.sp, lineHeight = 23.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
            titleMedium = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, lineHeight = 21.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
            titleSmall = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
            bodyLarge = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
            bodyMedium = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
            bodySmall = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, lineHeight = 16.sp),
            labelLarge = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
            labelMedium = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, lineHeight = 15.sp),
            labelSmall = androidx.compose.ui.text.TextStyle(fontSize = 10.sp, lineHeight = 14.sp),
        ),
        content = content
    )
}
