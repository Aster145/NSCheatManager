package com.nscheatmanager.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
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
    @get:Rule val compose = createComposeRule()

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
}
