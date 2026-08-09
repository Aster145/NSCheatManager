package com.nscheatmanager.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private val dependencies get() = (application as NSCheatManagerApplication).dependencies
    private val settingsViewModel by viewModels<SettingsViewModel> {
        SettingsViewModel.Factory(dependencies.devices, dependencies.preferences, ::applyLocale)
    }
    private val gameViewModel by viewModels<GameViewModel> {
        GameViewModel.Factory(dependencies.gameDevices, dependencies.gameFiles, dependencies::createGameSession)
    }
    private val editorViewModel by viewModels<CheatEditorViewModel> {
        CheatEditorViewModel.Factory(dependencies.gameFiles)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                dependencies.preferences.languageTag.collect(::applyLocale)
            }
        }
        setContent {
            NSCheatManagerTheme {
                val state by settingsViewModel.uiState.collectAsStateWithLifecycle()
                val gameState by gameViewModel.uiState.collectAsStateWithLifecycle()
                val editorState by editorViewModel.uiState.collectAsStateWithLifecycle()
                NSCheatManagerApp(
                    settingsState = state,
                    settingsActions = SettingsActions(
                        add = settingsViewModel::openAddDevice,
                        edit = settingsViewModel::openEditDevice,
                        delete = settingsViewModel::deleteDevice,
                        setDefault = settingsViewModel::setDefaultDevice,
                        selectLanguage = settingsViewModel::selectLanguage,
                        editorChanged = settingsViewModel::updateEditor,
                        saveEditor = settingsViewModel::saveEditor,
                        dismissEditor = settingsViewModel::dismissEditor,
                    ),
                    versionName = BuildConfig.VERSION_NAME,
                    settingsMessages = settingsViewModel.messages,
                    gameState = gameState,
                    gameActions = GameScreenActions(
                        selectDevice = gameViewModel::onDeviceSelected,
                        connectionToggle = gameViewModel::onConnectionToggle,
                        detachDmnt = gameViewModel::onDetachDmntRequested,
                        cheatChecked = gameViewModel::onCheatChecked,
                        editModeChanged = { enabled ->
                            if (enabled) {
                                gameViewModel.currentIdentityForEditor()?.let(editorViewModel::open)
                                    ?: gameViewModel.onEditorUnavailable()
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
                        confirmZipImport = gameViewModel::confirmZipImport,
                        confirmDownload = gameViewModel::confirmDownload,
                        discardDownload = gameViewModel::discardDownload,
                        confirmUpload = gameViewModel::confirmUpload,
                        confirmDirectUpload = gameViewModel::confirmDirectUpload,
                        confirmEmptyNotesShare = gameViewModel::confirmEmptyNotesShare,
                        externalFailure = gameViewModel::onExternalFailure,
                    ),
                    editorState = editorState,
                    editorActions = CheatEditorActions(
                        selectTab = editorViewModel::selectTab,
                        cheatChanged = editorViewModel::updateCheatText,
                        notesChanged = editorViewModel::updateNotesText,
                        save = editorViewModel::save,
                        cancel = editorViewModel::requestClose,
                    ),
                    editorEffects = editorViewModel.effects,
                    editorEffectActions = EditorEffectActions(
                        confirmDiscard = editorViewModel::confirmDiscard,
                        saved = { saved -> gameViewModel.onLocalFileSaved(saved.identity, saved.file) },
                    ),
                )
            }
        }
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
    MaterialTheme(content = content)
}
