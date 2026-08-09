package com.nscheatmanager.app.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import androidx.test.platform.app.InstrumentationRegistry
import com.nscheatmanager.app.ui.memory.*
import org.junit.Rule
import org.junit.Test
import java.util.Locale

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

    @Test fun explicit320DpEnglishHostIsLocalizedAndUnclipped() = verifyLocale("en", "Absolute 0x10")
    @Test fun explicit320DpChineseHostIsLocalizedAndUnclipped() = verifyLocale("zh-CN", "绝对地址 0x10")

    @Test fun copyPlacesExactRawAndTypedTextOnClipboardAndTimeIsReadable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.clearPrimaryClip()
        val state = MemoryUiState(ready = true, result = MemoryResultUi(1u, "2A 00 00 00", "42", com.nscheatmanager.app.core.model.ValueType.Int32, 0))
        localizedContent("en") { MemoryScreen(state, MemoryActions.None, Modifier.width(320.dp)) }
        compose.onNodeWithTag("memory-copy").performScrollTo().performClick()
        compose.waitForIdle()
        assert(clipboard.primaryClip?.getItemAt(0)?.coerceToText(context).toString() == "2A 00 00 00\n42")
        val time = compose.onNodeWithTag("memory-result-time").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("")
        assert(time != "0" && time.any(Char::isDigit))
        clipboard.clearPrimaryClip()
    }

    private fun localizedContent(tag: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)); screenWidthDp = 320 }
        val localized = base.createConfigurationContext(configuration)
        compose.setContent { CompositionLocalProvider(LocalContext provides localized, LocalResources provides localized.resources, content = content) }
    }

    private fun verifyLocale(tag: String, targetText: String) {
        localizedContent(tag) { MemoryScreen(MemoryUiState(ready = true, confirmation = confirmation()), MemoryActions.None, Modifier.width(320.dp).testTag("memory-320")) }
        compose.onNodeWithTag("memory-address").assertIsEnabled()
        compose.onNodeWithTag("memory-read").performScrollTo().assertIsEnabled().assertIsDisplayed()
        compose.onNodeWithTag("memory-lock").performScrollTo().assertIsEnabled().assertIsOff()
        compose.onNodeWithText(targetText, substring = true).assertExists()
        val root = compose.onNodeWithTag("memory-320").fetchSemanticsNode().boundsInRoot
        val control = compose.onNodeWithTag("memory-lock").fetchSemanticsNode().boundsInRoot
        assert(control.left >= root.left && control.right <= root.right)
    }

    private fun confirmation() = WriteConfirmation(1, MemoryViewModelTestData.key,
        com.nscheatmanager.app.core.model.MemoryTarget.Absolute(0x10u), MemoryTargetDisplay(AddressMode.Absolute, "10", 0x10u),
        com.nscheatmanager.app.core.model.ValueType.Int32, "42", com.nscheatmanager.app.domain.ImmutableBytes.copyOf(byteArrayOf(42,0,0,0)))
}

private object MemoryViewModelTestData {
    private val title = com.nscheatmanager.app.core.model.TitleId.parse("0100000000000001")
    private val build = com.nscheatmanager.app.core.model.BuildId.parse("0123456789ABCDEF")
    val key = com.nscheatmanager.app.domain.GameOperationKey("device", title, build, 1)
}
