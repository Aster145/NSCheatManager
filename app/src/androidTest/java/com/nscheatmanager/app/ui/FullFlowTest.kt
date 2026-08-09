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
                        editorState = com.nscheatmanager.app.ui.editor.CheatEditorUiState(isOpen = editorOpen.value),
                    )
                }
            }
        }
        compose.onNodeWithTag("cheat-editor").assertIsDisplayed()
        assertInside320("cheat-editor")
        assertInside320("editor-save")
        compose.runOnIdle { editorOpen.value = false }
        compose.onNodeWithTag("nav-game").performClick(); compose.onNodeWithTag("game-screen").assertIsDisplayed(); assertInside320("device-selector"); assertInside320("overflow-menu")
        compose.onNodeWithTag("nav-memory").performClick(); compose.onNodeWithTag("memory-screen").assertIsDisplayed(); assertInside320("memory-action-row")
        compose.onNodeWithTag("nav-cheats").performClick(); compose.onNodeWithTag("cheats-screen").assertIsDisplayed()
        compose.onNodeWithTag("overflow-menu").performClick(); compose.onNodeWithTag("menu-settings").performClick()
        compose.onNodeWithTag("settings-content").assertIsDisplayed()
        compose.onNodeWithText(if (languageTag == "en") "Back" else "返回").performClick()
        compose.onNodeWithTag("overflow-menu").performClick(); compose.onNodeWithTag("menu-about").performClick()
        compose.onNodeWithText(if (languageTag == "en") "About" else "关于").assertIsDisplayed()
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
        MainActivity.dependenciesForTest = object : com.nscheatmanager.app.MainActivityDependencies by application.dependencies {
            override fun createGameSession(scope: kotlinx.coroutines.CoroutineScope) = session
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
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                scenario.recreate()
                compose.onNodeWithTag("memory-screen").assertIsDisplayed()
            }
            compose.runOnIdle { check(session.writeCount == 1 && session.lockCount == 1 && session.closeCount == 1) }
        } finally {
            MainActivity.dependenciesForTest = null
        }
    }

    private class CountingSessionGateway : com.nscheatmanager.app.ui.game.GameSessionGateway {
        private val device = com.nscheatmanager.app.domain.DeviceProfile("activity-fake", "Fake Switch", "192.168.1.40")
        private val identity = com.nscheatmanager.app.protocol.sysbot.GameIdentity(
            com.nscheatmanager.app.core.model.TitleId.parse("0100F2C0115B6000"),
            com.nscheatmanager.app.core.model.BuildId.parse("A4A8D3E7F29C81A2"), 0x1000u, 0x8000u,
        )
        override val state = kotlinx.coroutines.flow.MutableStateFlow(com.nscheatmanager.app.domain.DeviceSessionState(
            device = device, connection = com.nscheatmanager.app.domain.ConnectionState.Ready,
            game = identity, gameValidated = true, generation = 1,
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

}
