package com.nscheatmanager.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nscheatmanager.app.domain.ConnectionState
import com.nscheatmanager.app.domain.DeviceProfile
import com.nscheatmanager.app.ui.game.CheatGroupUiState
import com.nscheatmanager.app.ui.game.GameScreen
import com.nscheatmanager.app.ui.game.GameScreenActions
import com.nscheatmanager.app.ui.game.GameUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CheatFirstFlowTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun responsiveDeviceActionsAndOverflowRemainAccessibleAt320DpInExactOrder() {
        compose.setContent {
            Box(Modifier.width(320.dp)) {
                GameScreen(
                    state = populatedState(),
                    actions = GameScreenActions.None,
                )
            }
        }

        compose.onNodeWithTag("device-selector").assertIsDisplayed()
        compose.onNodeWithTag("connect-toggle").assertIsDisplayed()
        compose.onNodeWithTag("detach-dmnt").assertIsDisplayed()
        compose.onNodeWithTag("overflow-menu").performClick()
        listOf(
            "menu-edit", "menu-recognize", "menu-download", "menu-upload",
            "menu-share-zip", "menu-import-zip", "menu-settings", "menu-about",
        ).forEachIndexed { index, tag ->
            compose.onNodeWithTag("menu-order-$index").assertIsDisplayed()
            compose.onNodeWithTag(tag).assertIsDisplayed()
        }
    }

    @Test
    fun checkboxTransitionFiresOnceAndUnsupportedLineIsVisibleAndDisabled() {
        var state by mutableStateOf(populatedState())
        var transitions = 0
        compose.setContent {
            GameScreen(
                state = state,
                actions = GameScreenActions.None.copy(
                    cheatChecked = { name, previous, checked ->
                        transitions++
                        state = state.copy(
                            groups = state.groups.map { if (it.name == name) it.copy(checked = checked) else it },
                        )
                        assertEquals(false, previous)
                    },
                ),
            )
        }

        compose.onNodeWithTag("cheat-Write once").assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(1, transitions)
        compose.onNodeWithTag("cheat-Key trigger").assertIsNotEnabled()
        compose.onNodeWithText("Line 18 · opcode 0x8", substring = true).assertIsDisplayed()
    }

    @Test
    fun missingMirrorOffersImportAndDownload() {
        compose.setContent {
            GameScreen(populatedState().copy(groups = emptyList(), missingMirror = true), GameScreenActions.None)
        }

        compose.onNodeWithTag("missing-cheat-file").assertIsDisplayed()
        compose.onNodeWithTag("missing-import").assertIsDisplayed()
        compose.onNodeWithTag("missing-download").assertIsDisplayed()
    }

    private fun populatedState() = GameUiState(
        devices = listOf(DeviceProfile("switch", "Living room", "192.168.1.35")),
        selectedDeviceId = "switch",
        connection = ConnectionState.Ready,
        gameValidated = true,
        titleId = "0100F2C0115B6000",
        buildId = "A4A8D3E7F29C81A2",
        mirrorPath = "atmosphere/contents/0100F2C0115B6000/cheats/A4A8D3E7F29C81A2.txt",
        groups = listOf(
            CheatGroupUiState("Write once", checked = false, executable = true),
            CheatGroupUiState(
                "Key trigger", checked = false, executable = false,
                unsupportedLine = 18, unsupportedOpcode = "0x8",
            ),
        ),
    )
}
