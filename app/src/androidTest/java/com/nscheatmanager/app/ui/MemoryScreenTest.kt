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
        compose.onNodeWithTag("memory-address").assertIsDisplayed()
        compose.onNodeWithTag("memory-read").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("memory-lock").performScrollTo().assertIsDisplayed().assertIsToggleable()
    }

    @Test fun confirmationShowsBoundBytes() {
        val pending = WriteConfirmation(1, MemoryViewModelTestData.key,
            com.nscheatmanager.app.core.model.MemoryTarget.Absolute(0x10u),
            com.nscheatmanager.app.core.model.ValueType.Int32, byteArrayOf(42,0,0,0))
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
