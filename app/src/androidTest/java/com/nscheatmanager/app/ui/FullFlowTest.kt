package com.nscheatmanager.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import androidx.test.core.app.ActivityScenario
import androidx.lifecycle.Lifecycle
import com.nscheatmanager.app.MainActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nscheatmanager.app.domain.ConnectionState
import com.nscheatmanager.app.ui.game.GameScreen
import com.nscheatmanager.app.ui.game.GameScreenActions
import com.nscheatmanager.app.ui.game.GameUiState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FullFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun editModeMenuExposesOneLabeledToggleState() {
        compose.setContent {
            GameScreen(
                GameUiState(connection = ConnectionState.Ready, gameValidated = true),
                GameScreenActions.None,
            )
        }
        compose.onNodeWithTag("overflow-menu").performClick()
        compose.onNodeWithTag("menu-edit")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
            .assertIsDisplayed()
    }

    @Test fun interactiveControlSemanticsInventoryIsComplete() {
        val group = com.nscheatmanager.app.ui.game.CheatGroupUiState("Inventory cheat")
        val device = com.nscheatmanager.app.domain.DeviceProfile("inventory", "Inventory Switch", "192.168.1.41", isDefault = true)
        var phase by mutableIntStateOf(0)
        compose.setContent {
            when (phase) {
                0 -> GameScreen(GameUiState(connection = ConnectionState.Ready, gameValidated = true, groups = listOf(group)), GameScreenActions.None)
                1 -> com.nscheatmanager.app.ui.memory.MemoryScreen(com.nscheatmanager.app.ui.memory.MemoryUiState(ready = true), com.nscheatmanager.app.ui.memory.MemoryActions.None)
                else -> com.nscheatmanager.app.ui.settings.SettingsScreen(
                    com.nscheatmanager.app.ui.settings.SettingsUiState(devices = listOf(device), languageTag = "en"),
                    {}, {}, {}, {}, {}, {}, {}, {}, {},
                )
            }
        }
        compose.onNodeWithTag("overflow-menu")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription)).assertHasClickAction()
        compose.onNodeWithTag("cheat-Inventory cheat")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription)).assertIsOff().assertIsEnabled()
        compose.onNodeWithTag("overflow-menu").performClick()
        compose.onNodeWithTag("menu-edit")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))

        compose.runOnIdle { phase = 1 }
        compose.onNodeWithTag("memory-lock")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
            .assertIsOff().assertIsEnabled()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text))

        compose.runOnIdle { phase = 2 }
        compose.onNodeWithTag("default-inventory")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription))
            .assertIsSelected().assertHasClickAction()
    }

    @Test fun consumedOneShotMessageIsNotReplayedAfterCompositionRecreation() {
        val effects = Channel<com.nscheatmanager.app.ui.game.GameEffect>(Channel.BUFFERED)
        val generation = mutableIntStateOf(0)
        compose.setContent {
            key(generation.intValue) {
                NSCheatManagerApp(
                    settingsState = com.nscheatmanager.app.ui.settings.SettingsUiState(languageTag = "en"),
                    settingsActions = SettingsActions.None,
                    versionName = "1",
                    gameEffects = effects.receiveAsFlow(),
                )
            }
        }
        compose.runOnIdle { effects.trySend(com.nscheatmanager.app.ui.game.GameEffect.Message(com.nscheatmanager.app.ui.game.GameMessage.DOWNLOAD_COMPLETE)) }
        val downloadComplete = hasText("Download complete.") or hasText("下载完成。")
        compose.onNode(downloadComplete).assertIsDisplayed()
        compose.runOnIdle { generation.intValue++ }
        compose.onNode(downloadComplete).assertDoesNotExist()
    }

    @Test fun gameShellStaysInside320DpAtLargeFontScale() {
        compose.setContent {
            Box(Modifier.width(320.dp).testTag("full-flow-320")) {
                GameScreen(GameUiState(), GameScreenActions.None)
            }
        }
        compose.onNodeWithTag("full-flow-320").assertIsDisplayed()
        compose.onNodeWithTag("device-selector").assertIsDisplayed()
    }

    @Test fun englishAllDestinationsRemainReachableAt320DpAndLargeFont() = verifyAllDestinations("en")
    @Test fun chineseAllDestinationsRemainReachableAt320DpAndLargeFont() = verifyAllDestinations("zh-CN")

    private fun verifyAllDestinations(languageTag: String) {
        val base = ApplicationProvider.getApplicationContext<android.content.Context>()
        val configuration = Configuration(base.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(languageTag)); screenWidthDp = 320; fontScale = 1.5f
        }
        val localized = base.createConfigurationContext(configuration)
        val density = localized.resources.displayMetrics.density
        val editorOpen = mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides configuration,
                LocalDensity provides Density(density, 1.5f),
                LocalActivityResultRegistryOwner provides compose.activity,
            ) {
                Box(Modifier.width(320.dp).testTag("localized-320")) {
                    NSCheatManagerApp(
                        settingsState = com.nscheatmanager.app.ui.settings.SettingsUiState(languageTag = languageTag),
                        settingsActions = SettingsActions.None,
                        versionName = "1",
                        gameState = com.nscheatmanager.app.ui.game.GameUiState(
                            connection = ConnectionState.Ready, gameValidated = true,
                            groups = listOf(com.nscheatmanager.app.ui.game.CheatGroupUiState("Accessible cheat")),
                        ),
                        editorState = com.nscheatmanager.app.ui.editor.CheatEditorUiState(
                            isOpen = editorOpen.value, dirty = true,
                            selectedTab = com.nscheatmanager.app.ui.editor.EditorTab.Notes,
                            cheatText = "04000000", notesText = "note",
                        ),
                    )
                }
            }
        }
        compose.onNodeWithTag("cheat-editor").assertIsDisplayed()
        assertInside320("cheat-editor")
        assertInside320("editor-save")
        compose.onNodeWithTag("editor-notes-tab").performClick()
        compose.onNodeWithTag("editor-notes-text").assertIsDisplayed(); assertInside320("editor-notes-text")
        compose.runOnIdle { editorOpen.value = false }
        compose.onNodeWithTag("nav-game").performClick(); compose.onNodeWithTag("game-screen").assertIsDisplayed(); assertInside320("device-selector"); assertInside320("overflow-menu")
        compose.onNodeWithTag("nav-memory").performClick(); compose.onNodeWithTag("memory-screen").assertIsDisplayed(); assertInside320("memory-action-row")
        compose.onNodeWithTag("nav-cheats").performClick(); compose.onNodeWithTag("cheats-screen").assertIsDisplayed(); compose.onNodeWithTag("cheat-Accessible cheat").performScrollTo().assertIsDisplayed(); assertInside320("cheat-Accessible cheat")
        compose.onNodeWithTag("overflow-menu").performClick(); compose.onNodeWithTag("menu-settings").performClick()
        compose.onNodeWithTag("settings-content").assertIsDisplayed()
        compose.onNodeWithTag("language-actions").performScrollTo(); assertInside320("language-actions")
        compose.onNodeWithText(if (languageTag == "en") "Back" else "返回").performClick()
        compose.onNodeWithTag("overflow-menu").performClick(); compose.onNodeWithTag("menu-about").performClick()
        compose.onNodeWithText(if (languageTag == "en") "About" else "关于").assertIsDisplayed()
        compose.onNodeWithTag("qq-link").performScrollTo(); assertInside320("qq-link")
        compose.onNodeWithTag("localized-320").assertIsDisplayed()
    }

    private fun assertInside320(tag: String) {
        val root = compose.onNodeWithTag("localized-320").fetchSemanticsNode().boundsInRoot
        val child = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        check(child.left >= root.left && child.right <= root.right) { "$tag exceeds 320dp root: $child vs $root" }
    }

    @Test fun injectedDependenciesStillExerciseNormalProductionCompositionAndLifecycle() {
        val application = ApplicationProvider.getApplicationContext<com.nscheatmanager.app.NSCheatManagerApplication>()
        val session = CountingSessionGateway()
        val files = CountingGameFiles(session.identity)
        val external = CountingExternalActions()
        MainActivity.dependenciesForTest = object : com.nscheatmanager.app.MainActivityDependencies by application.dependencies {
            override fun createGameSession(scope: kotlinx.coroutines.CoroutineScope) = session
            override val gameFiles = files
            override val externalActions = external
            override val gameDevices = object : com.nscheatmanager.app.ui.game.GameDeviceStore {
                override val devices = kotlinx.coroutines.flow.MutableStateFlow(listOf(session.device))
                override val selectedDeviceId = kotlinx.coroutines.flow.MutableStateFlow<String?>(session.device.id)
                override suspend fun selectDevice(deviceId: String) = Unit
            }
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.onNodeWithTag("cheats-screen").assertIsDisplayed()
                compose.onNodeWithTag("nav-memory").performClick()
                compose.onNodeWithTag("memory-screen").assertIsDisplayed()
                compose.onNodeWithTag("memory-address").performTextInput("20")
                compose.onNodeWithTag("memory-value").performTextInput("7")
                compose.onNodeWithTag("memory-write").performClick()
                compose.onNodeWithTag("memory-confirm").performClick()
                compose.waitUntil(5_000) { session.writeCount == 1 }
                compose.onNodeWithTag("memory-lock").performClick()
                compose.waitUntil(5_000) { session.lockCount == 1 }
                compose.onNodeWithTag("nav-game").performClick()
                fun openMenu(tag: String) { compose.onNodeWithTag("overflow-menu").performClick(); compose.onNodeWithTag(tag).performClick() }
                openMenu("menu-upload")
                scenario.recreate()
                compose.onNodeWithTag("game-confirmation").assertIsDisplayed()
                compose.onNodeWithTag("game-confirm").performClick()
                compose.waitUntil(5_000) { files.stagedUploadCount == 1 }
                scenario.recreate()
                compose.onNodeWithTag("game-confirmation").assertIsDisplayed()
                compose.onNodeWithTag("game-confirm").performClick()
                compose.waitUntil(5_000) { files.directUploadCount == 1 }
                openMenu("menu-import-zip")
                scenario.recreate()
                compose.onNodeWithTag("game-confirm").performClick()
                compose.waitUntil(5_000) { files.importCount == 1 }
                openMenu("menu-share-zip")
                compose.waitUntil(5_000) { external.shareCount == 1 }
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                scenario.recreate()
                compose.onNodeWithTag("game-screen").assertIsDisplayed()
            }
            compose.runOnIdle {
                check(session.writeCount == 1 && session.lockCount == 1 && session.closeCount == 1)
                check(files.stagedUploadCount == 1 && files.directUploadCount == 1 && files.importCount == 1)
                check(external.openZipCount == 1 && external.shareCount == 1)
            }
        } finally {
            MainActivity.dependenciesForTest = null
        }
    }

    private class CountingSessionGateway : com.nscheatmanager.app.ui.game.GameSessionGateway {
        val device = com.nscheatmanager.app.domain.DeviceProfile("activity-fake", "Fake Switch", "192.168.1.40")
        val identity = com.nscheatmanager.app.protocol.sysbot.GameIdentity(
            com.nscheatmanager.app.core.model.TitleId.parse("0100F2C0115B6000"),
            com.nscheatmanager.app.core.model.BuildId.parse("A4A8D3E7F29C81A2"), 0x1000u, 0x8000u,
        )
        override val state = kotlinx.coroutines.flow.MutableStateFlow(com.nscheatmanager.app.domain.DeviceSessionState(
            device = device, connection = com.nscheatmanager.app.domain.ConnectionState.Ready,
            game = identity, gameValidated = true, generation = 1,
            cheatFile = com.nscheatmanager.app.cheats.parser.CheatFile(emptyList(), emptyList()),
        ))
        var writeCount = 0
        var lockCount = 0
        var closeCount = 0
        override fun connectAndRecognize(device: com.nscheatmanager.app.domain.DeviceProfile) = Unit
        override fun switchDevice(device: com.nscheatmanager.app.domain.DeviceProfile) = Unit
        override fun disconnect() = Unit
        override fun recognizeAgain() = Unit
        override suspend fun detachDmnt() = Unit
        override fun currentOperationKey() = state.value.operationKey
        override fun requireCurrentOperationKey(expected: com.nscheatmanager.app.domain.GameOperationKey) = Unit
        override suspend fun executeGroup(expected: com.nscheatmanager.app.domain.GameOperationKey, group: com.nscheatmanager.app.cheats.parser.CheatGroup) =
            com.nscheatmanager.app.cheats.vm.ExecutionReport(com.nscheatmanager.app.cheats.vm.ExecutionStatus.Complete, 1)
        override suspend fun uncheckGroup(expected: com.nscheatmanager.app.domain.GameOperationKey, groupName: String) = Unit
        override suspend fun writeMemory(expected: com.nscheatmanager.app.domain.GameOperationKey, target: com.nscheatmanager.app.core.model.MemoryTarget, type: com.nscheatmanager.app.core.model.ValueType, bytes: ByteArray) { writeCount++ }
        override suspend fun lockMemory(expected: com.nscheatmanager.app.domain.GameOperationKey, target: com.nscheatmanager.app.core.model.MemoryTarget, type: com.nscheatmanager.app.core.model.ValueType, bytes: ByteArray): com.nscheatmanager.app.domain.LockedValue {
            lockCount++
            val lock = com.nscheatmanager.app.domain.LockedValue(target, 0x20u, type, com.nscheatmanager.app.domain.ImmutableBytes.copyOf(bytes))
            state.value = state.value.copy(activeLocks = mapOf(lock.absoluteAddress to lock))
            return lock
        }
        override suspend fun close() { closeCount++ }
    }

    private class CountingExternalActions : GameExternalActions {
        var openZipCount = 0
        var shareCount = 0
        override fun openZip(fallback: () -> Unit, deliver: (ByteArray) -> Unit, failure: (Throwable) -> Unit) {
            openZipCount++; deliver(byteArrayOf(1))
        }
        override fun share(fallback: () -> Unit, archive: com.nscheatmanager.app.ui.game.ShareArchive, failure: (Throwable) -> Unit) { shareCount++ }
    }

    private class CountingGameFiles(private val identity: com.nscheatmanager.app.protocol.sysbot.GameIdentity) : com.nscheatmanager.app.ui.game.GameFileGateway {
        var stagedUploadCount = 0; var directUploadCount = 0; var importCount = 0
        override suspend fun loadEditable(identity: com.nscheatmanager.app.protocol.sysbot.GameIdentity, checkpoint: () -> Unit) = com.nscheatmanager.app.ui.game.EditableGameFiles("", "notes", true)
        override suspend fun saveEditable(identity: com.nscheatmanager.app.protocol.sysbot.GameIdentity, cheatText: String, notesText: String, checkpoint: () -> Unit) = com.nscheatmanager.app.cheats.parser.CheatFile(emptyList(), emptyList())
        override suspend fun inspectZip(bytes: ByteArray) = com.nscheatmanager.app.data.files.ZipInspection(identity.titleId, identity.buildId, emptyList(), 0, com.nscheatmanager.app.data.files.OverwriteImpact(false, false), "test")
        override suspend fun importZip(inspection: com.nscheatmanager.app.data.files.ZipInspection, checkpoint: () -> Unit) { checkpoint(); importCount++ }
        override suspend fun exportZip(identity: com.nscheatmanager.app.protocol.sysbot.GameIdentity, includeEmptyNotes: Boolean, checkpoint: () -> Unit) = byteArrayOf(1).also { checkpoint() }
        override suspend fun notesExist(identity: com.nscheatmanager.app.protocol.sysbot.GameIdentity, checkpoint: () -> Unit) = true.also { checkpoint() }
        override suspend fun download(profile: com.nscheatmanager.app.domain.DeviceProfile, identity: com.nscheatmanager.app.protocol.sysbot.GameIdentity, confirmation: com.nscheatmanager.app.domain.DownloadOverwriteConfirmation?, checkpoint: () -> Unit) = com.nscheatmanager.app.domain.TransferReport.RemoteCheatMissing
        override suspend fun discardDownload(confirmation: com.nscheatmanager.app.domain.DownloadOverwriteConfirmation) = Unit
        override suspend fun previewUpload(profile: com.nscheatmanager.app.domain.DeviceProfile, identity: com.nscheatmanager.app.protocol.sysbot.GameIdentity, checkpoint: () -> Unit) = com.nscheatmanager.app.domain.UploadPreview(com.nscheatmanager.app.domain.UploadConfirmation("test"), 4, null).also { checkpoint() }
        override suspend fun upload(confirmation: com.nscheatmanager.app.domain.UploadConfirmation, direct: com.nscheatmanager.app.domain.DirectOverwriteConfirmation?, checkpoint: () -> Unit): com.nscheatmanager.app.domain.TransferReport {
            checkpoint()
            return if (direct == null) { stagedUploadCount++; com.nscheatmanager.app.domain.TransferReport.RequiresDirectOverwriteConfirmation(com.nscheatmanager.app.domain.DirectOverwriteConfirmation("direct")) }
            else { directUploadCount++; com.nscheatmanager.app.domain.TransferReport.Uploaded(4, null, false) }
        }
    }

}
