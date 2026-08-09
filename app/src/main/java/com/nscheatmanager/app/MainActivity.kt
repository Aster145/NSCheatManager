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
import com.nscheatmanager.app.ui.SettingsActions
import com.nscheatmanager.app.ui.settings.SettingsViewModel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private val dependencies get() = (application as NSCheatManagerApplication).dependencies
    private val settingsViewModel by viewModels<SettingsViewModel> {
        SettingsViewModel.Factory(dependencies.devices, dependencies.preferences, ::applyLocale)
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
