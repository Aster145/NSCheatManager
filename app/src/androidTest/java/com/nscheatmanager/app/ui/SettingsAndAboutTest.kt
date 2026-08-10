package com.nscheatmanager.app.ui

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nscheatmanager.app.domain.DeviceProfile
import com.nscheatmanager.app.ui.about.AboutScreen
import com.nscheatmanager.app.ui.about.QQ_GROUP_URL
import com.nscheatmanager.app.ui.settings.SettingsScreen
import com.nscheatmanager.app.ui.settings.DeviceEditorError
import com.nscheatmanager.app.ui.settings.DeviceSettingsRepository
import com.nscheatmanager.app.ui.settings.LanguagePreferenceStore
import com.nscheatmanager.app.ui.settings.SettingsUiState
import com.nscheatmanager.app.ui.settings.SettingsViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsAndAboutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun memoryDestinationIsHiddenByDefaultAndAppearsWhenEnabled() {
        var showMemory by mutableStateOf(false)
        compose.setContent {
            NSCheatManagerApp(
                settingsState = SettingsUiState(languageTag = "en", showMemoryPage = showMemory),
                settingsActions = SettingsActions.None,
                versionName = "1",
            )
        }
        compose.onNodeWithTag("nav-memory").assertDoesNotExist()
        compose.onNodeWithTag("game-screen").assertIsDisplayed()
        compose.onNodeWithTag("main-swipe-area").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("cheats-screen").assertIsDisplayed()
        compose.onNodeWithTag("main-swipe-area").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("cheats-screen").assertIsDisplayed()
        compose.runOnIdle { showMemory = true }
        compose.onNodeWithTag("nav-memory").assertIsDisplayed()
    }

    @Test
    fun settingsShowsMultipleDevicesDefaultAndPortsAt320Dp() {
        val devices = listOf(
            DeviceProfile("living", "Living room Switch with a deliberately very long accessible name", "192.168.1.35", isDefault = true),
            DeviceProfile("bedroom", "Bedroom Switch", "192.168.1.52"),
        )

        compose.setContent {
            Box(Modifier.width(320.dp)) {
                SettingsScreen(
                    state = SettingsUiState(devices = devices, languageTag = "en"),
                    onBack = {},
                    onAddDevice = {},
                    onEditDevice = {},
                    onDeleteDevice = {},
                    onSetDefault = {},
                    onLanguageSelected = {},
                    onEditorChanged = {},
                    onSaveEditor = {},
                    onDismissEditor = {},
                    modifier = Modifier.testTag("settings-content"),
                )
            }
        }

        compose.onNodeWithTag("settings-content").assertWidthIsEqualTo(320.dp)
        compose.onNodeWithText("Living room Switch", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("edit-living").assertIsDisplayed()
        compose.onNodeWithTag("delete-living").assertIsDisplayed()
        compose.onNodeWithText("Bedroom Switch").assertIsDisplayed()
        compose.onAllNodesWithText("6000").assertCountEquals(2)
        compose.onNodeWithTag("auto-detach-setting").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("auto-detach-switch").assertIsOn()
        compose.onAllNodesWithText("21").assertCountEquals(2)
        compose.onAllNodesWithText("7331").assertCountEquals(2)
    }

    @Test
    fun aboutShowsBuildVersionCreditsRiskAndCreatesExactQqIntent() {
        var launched: Intent? = null
        compose.setContent {
            AboutScreen(
                versionName = "9.8.7-test",
                onBack = {},
                launchIntent = { intent ->
                    launched = intent
                    true
                },
            )
        }

        compose.onNodeWithText("9.8.7-test", substring = true).assertIsDisplayed()
        compose.onNodeWithText("sys-botbase").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Atmosphère").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Noexs").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("GPL-3.0", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("457965140", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("457965140", substring = true).performClick()

        compose.runOnIdle {
            requireNotNull(launched)
            assertEquals(Intent.ACTION_VIEW, launched?.action)
            assertEquals(QQ_GROUP_URL, launched?.dataString)
        }
    }

    @Test
    fun aboutShowsFallbackWhenNoApplicationCanOpenQqLink() {
        compose.setContent {
            AboutScreen(
                versionName = "1.0.0",
                onBack = {},
                launchIntent = { false },
            )
        }

        compose.onNodeWithText("457965140", substring = true).performScrollTo().performClick()
        compose.onNodeWithTag("qq-link-error").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun settingsViewModelRejectsInvalidIpv4PortAndDuplicatesBeforeSaving() = runBlocking {
        val existing = DeviceProfile("living", "Living room", "192.168.1.35", isDefault = true)
        val repository = FakeDeviceRepository(listOf(existing))
        val viewModel = SettingsViewModel(repository, FakeLanguagePreferences("en"))
        awaitState(viewModel) { it.devices.size == 1 }

        viewModel.openAddDevice()
        viewModel.updateEditor(
            requireNotNull(viewModel.uiState.value.editor).copy(
                name = "New Switch",
                host = "192.168.1.999",
            ),
        )
        viewModel.saveEditor()
        awaitState(viewModel) { it.editor?.error == DeviceEditorError.INVALID_IPV4 }

        viewModel.updateEditor(
            requireNotNull(viewModel.uiState.value.editor).copy(
                host = "192.168.1.36",
                ftpPort = "65536",
            ),
        )
        viewModel.saveEditor()
        awaitState(viewModel) { it.editor?.error == DeviceEditorError.INVALID_PORT }

        viewModel.updateEditor(
            requireNotNull(viewModel.uiState.value.editor).copy(
                name = "Living room",
                ftpPort = "21",
            ),
        )
        viewModel.saveEditor()
        awaitState(viewModel) { it.editor?.error == DeviceEditorError.DUPLICATE_NAME }

        viewModel.updateEditor(
            requireNotNull(viewModel.uiState.value.editor).copy(
                name = "New Switch",
                host = "192.168.1.35",
            ),
        )
        viewModel.saveEditor()
        awaitState(viewModel) { it.editor?.error == DeviceEditorError.DUPLICATE_HOST }
        assertEquals(0, repository.saved.size)
    }

    @Test
    fun settingsViewModelPersistsValidCrudDefaultAndLocaleOnce() = runBlocking {
        val repository = FakeDeviceRepository()
        val preferences = FakeLanguagePreferences("zh-CN")
        val appliedLocales = mutableListOf<String>()
        val viewModel = SettingsViewModel(repository, preferences, appliedLocales::add)

        viewModel.openAddDevice()
        viewModel.updateEditor(
            requireNotNull(viewModel.uiState.value.editor).copy(
                name = "Test Switch",
                host = "192.168.001.088",
            ),
        )
        viewModel.saveEditor()
        awaitState(viewModel) { it.editor == null && it.devices.size == 1 }
        assertEquals("192.168.1.88", repository.saved.single().host)
        assertEquals(6000, repository.saved.single().sysBotPort)
        assertEquals(21, repository.saved.single().ftpPort)
        assertEquals(7331, repository.saved.single().noexsPort)

        viewModel.setDefaultDevice("new-id")
        viewModel.deleteDevice("new-id")
        awaitState(viewModel) { repository.defaults == listOf("new-id") && repository.deleted == listOf("new-id") }

        viewModel.selectLanguage("en")
        awaitCondition { preferences.writes == listOf("en") && appliedLocales == listOf("en") }
        viewModel.selectLanguage("en")
        assertEquals(listOf("en"), preferences.writes)
        assertEquals(listOf("en"), appliedLocales)
    }

    @Test
    fun settingsViewModelEmitsTypedOneShotFailures() = runBlocking {
        val repository = FakeDeviceRepository().apply { failMutations = true }
        val preferences = FakeLanguagePreferences("zh-CN").apply { failWrites = true }
        val viewModel = SettingsViewModel(repository, preferences)

        val delete = async(start = CoroutineStart.UNDISPATCHED) { viewModel.messages.first() }
        viewModel.deleteDevice("id")
        assertEquals(com.nscheatmanager.app.ui.settings.SettingsMessage.DELETE_FAILED, delete.await())
        val default = async(start = CoroutineStart.UNDISPATCHED) { viewModel.messages.first() }
        viewModel.setDefaultDevice("id")
        assertEquals(com.nscheatmanager.app.ui.settings.SettingsMessage.DEFAULT_FAILED, default.await())
        val language = async(start = CoroutineStart.UNDISPATCHED) { viewModel.messages.first() }
        viewModel.selectLanguage("en")
        assertEquals(com.nscheatmanager.app.ui.settings.SettingsMessage.LANGUAGE_FAILED, language.await())
    }

    @Test
    fun settingsEditorExposesAllFieldsAndForwardsEditedValuesToSave() {
        var saved: com.nscheatmanager.app.ui.settings.DeviceEditorUiState? = null
        compose.setContent {
            var state by remember {
                mutableStateOf(
                    SettingsUiState(
                        editor = com.nscheatmanager.app.ui.settings.DeviceEditorUiState(
                            error = DeviceEditorError.INVALID_IPV4,
                        ),
                        languageTag = "en",
                    ),
                )
            }
            SettingsScreen(
                state = state,
                onBack = {},
                onAddDevice = {},
                onEditDevice = {},
                onDeleteDevice = {},
                onSetDefault = {},
                onLanguageSelected = {},
                onEditorChanged = { state = state.copy(editor = it) },
                onSaveEditor = { saved = state.editor },
                onDismissEditor = {},
            )
        }

        compose.onNodeWithTag("device-editor-error").assertIsDisplayed()
        compose.onNodeWithTag("device-name").performTextInput("Test Switch")
        compose.onNodeWithTag("device-host").performTextInput("192.168.1.88")
        compose.onNodeWithTag("sysbot-port").assertTextContains("6000")
        compose.onNodeWithTag("ftp-port").assertTextContains("21")
        compose.onNodeWithTag("noexs-port").assertTextContains("7331")
        compose.onNodeWithTag("ftp-port").performTextClearance()
        compose.onNodeWithTag("ftp-port").performTextInput("2121")
        compose.onNodeWithTag("save-device").performClick()

        compose.runOnIdle {
            assertEquals("Test Switch", saved?.name)
            assertEquals("192.168.1.88", saved?.host)
            assertEquals("2121", saved?.ftpPort)
        }
    }

    @Test
    fun appStartsOnGameAndNavigatesThroughBottomBarAndOverflow() {
        compose.setContent {
            NSCheatManagerApp(
                settingsState = SettingsUiState(languageTag = "en"),
                settingsActions = SettingsActions.None,
                versionName = "1.2.3",
            )
        }

        compose.onNodeWithTag("game-screen").assertIsDisplayed()
        compose.onNodeWithTag("nav-cheats").performClick()
        compose.onNodeWithTag("cheats-screen").assertIsDisplayed()
        compose.onNodeWithTag("nav-game").performClick()
        compose.onNodeWithTag("overflow-menu").performClick()
        compose.onNodeWithTag("menu-settings").performClick()
        compose.onNodeWithTag("settings-content").assertIsDisplayed()
    }

    @Test
    fun overflowAndDefaultSelectionExposeAccessibleActions() {
        compose.setContent {
            NSCheatManagerApp(SettingsUiState(languageTag = "en"), SettingsActions.None, "1")
        }
        compose.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription) and
                SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.TestTag, "overflow-menu"),
        ).assertHasClickAction().performClick()
        compose.onNodeWithTag("menu-settings").performClick()
        compose.onNodeWithTag("settings-content").assertIsDisplayed()
    }

    @Test fun defaultRadioExposesLocalizedLabelRoleAndSelectedStateOnSameNode() {
        val device = DeviceProfile("living", "Living room", "192.168.1.35", isDefault = true)
        compose.setContent {
            SettingsScreen(
                state = SettingsUiState(devices = listOf(device), languageTag = "en"),
                onBack = {}, onAddDevice = {}, onEditDevice = {}, onDeleteDevice = {}, onSetDefault = {},
                onLanguageSelected = {}, onEditorChanged = {}, onSaveEditor = {}, onDismissEditor = {},
            )
        }
        compose.onNodeWithTag("default-living")
            .assertContentDescriptionContains("Living room", substring = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assertIsSelected()
            .assertHasClickAction()
    }

    private suspend fun awaitState(
        viewModel: SettingsViewModel,
        predicate: (SettingsUiState) -> Boolean,
    ): SettingsUiState = withTimeout(5_000) { viewModel.uiState.first(predicate) }

    private suspend fun awaitCondition(predicate: () -> Boolean) {
        withTimeout(5_000) {
            while (!predicate()) delay(10)
        }
    }

    private class FakeDeviceRepository(initial: List<DeviceProfile> = emptyList()) : DeviceSettingsRepository {
        override val devices = MutableStateFlow(initial)
        val saved = mutableListOf<DeviceProfile>()
        val deleted = mutableListOf<String>()
        val defaults = mutableListOf<String>()
        var failMutations = false

        override suspend fun addDevice(
            name: String,
            host: String,
            sysBotPort: Int,
            ftpPort: Int,
            noexsPort: Int,
        ): DeviceProfile = DeviceProfile(
            id = "new-id",
            name = name,
            host = host,
            sysBotPort = sysBotPort,
            ftpPort = ftpPort,
            noexsPort = noexsPort,
            isDefault = devices.value.isEmpty(),
        ).also {
            saved += it
            devices.value += it
        }

        override suspend fun saveDevice(profile: DeviceProfile): DeviceProfile = profile.also { updated ->
            saved += updated
            devices.value = devices.value.map { if (it.id == updated.id) updated else it }
        }

        override suspend fun deleteDevice(deviceId: String) {
            if (failMutations) error("delete")
            deleted += deviceId
            devices.value = devices.value.filterNot { it.id == deviceId }
        }

        override suspend fun setDefaultDevice(deviceId: String) {
            if (failMutations) error("default")
            defaults += deviceId
            devices.value = devices.value.map { it.copy(isDefault = it.id == deviceId) }
        }
    }

    private class FakeLanguagePreferences(initial: String) : LanguagePreferenceStore {
        override val languageTag = MutableStateFlow(initial)
        override val showMemoryPage = MutableStateFlow(false)
        override val detachDmntBeforeConnect = MutableStateFlow(true)
        val writes = mutableListOf<String>()
        var failWrites = false

        override suspend fun setLanguageTag(languageTag: String) {
            if (failWrites) error("language")
            writes += languageTag
            this.languageTag.value = languageTag
        }


        override suspend fun setShowMemoryPage(show: Boolean) {
            showMemoryPage.value = show
        }
        override suspend fun setDetachDmntBeforeConnect(enabled: Boolean) {
            detachDmntBeforeConnect.value = enabled
        }
    }
}
