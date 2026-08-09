package com.nscheatmanager.app.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.nscheatmanager.app.ui.memory.*
import org.junit.Rule
import org.junit.Test

class MemoryScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun controlsAndNativeLockAreAccessibleAtCompactWidth() {
        compose.setContent { MemoryScreen(MemoryUiState(), MemoryActions.None) }
        compose.onNodeWithTag("memory-address").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("memory-read").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("memory-lock").performScrollTo().assertIsDisplayed().assertIsToggleable().assertIsNotEnabled()
    }

    @Test fun lockedReadyStateIsCheckedAndDisablesEveryParameterWhileResultAndCopyRemainReachable() {
        val lock = com.nscheatmanager.app.domain.LockedValue(com.nscheatmanager.app.core.model.MemoryTarget.Absolute(1u), 1u,
            com.nscheatmanager.app.core.model.ValueType.Int8, com.nscheatmanager.app.domain.ImmutableBytes.copyOf(byteArrayOf(1)))
        val state = MemoryUiState(ready = true, locked = lock,
            result = MemoryResultUi(1u, "01", "1", com.nscheatmanager.app.core.model.ValueType.Int8, 0))
        compose.setContent { MemoryScreen(state, MemoryActions.None) }
        compose.onNodeWithTag("memory-address").assertIsNotEnabled()
        compose.onNodeWithTag("memory-value").assertIsNotEnabled()
        compose.onNodeWithTag("memory-lock").performScrollTo().assertIsOn()
        compose.onNodeWithTag("memory-result").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("memory-copy").assertIsDisplayed().assertHasClickAction()
    }

    @Test fun confirmationShowsBoundBytes() {
        val pending = WriteConfirmation(1, MemoryViewModelTestData.key,
            com.nscheatmanager.app.core.model.MemoryTarget.Absolute(0x10u),
            MemoryTargetDisplay(AddressMode.Absolute, "10", 0x10u),
            com.nscheatmanager.app.core.model.ValueType.Int32, "42",
            com.nscheatmanager.app.domain.ImmutableBytes.copyOf(byteArrayOf(42,0,0,0)))
        compose.setContent { MemoryScreen(MemoryUiState(confirmation = pending), MemoryActions.None) }
        compose.onNodeWithTag("memory-confirm").assertIsDisplayed()
        compose.onNodeWithText("2A 00 00 00", substring = true).assertExists()
    }
}

private object MemoryViewModelTestData {
    private val title = com.nscheatmanager.app.core.model.TitleId.parse("0100000000000001")
    private val build = com.nscheatmanager.app.core.model.BuildId.parse("0123456789ABCDEF")
    val key = com.nscheatmanager.app.domain.GameOperationKey("device", title, build, 1)
}
