package com.nscheatmanager.app.ui

import android.content.res.Configuration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nscheatmanager.app.MainActivity
import com.nscheatmanager.app.R
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun realCompositionRootStartsOnCheatsAndOpensSettings() {
        compose.onNodeWithTag("cheats-screen").assertIsDisplayed()
        compose.onNodeWithTag("overflow-menu").performClick()
        compose.onNodeWithTag("menu-settings").performClick()
        compose.onNodeWithTag("settings-content").assertIsDisplayed()
    }

    @Test
    fun appNamesAreLocalizedInEnglishAndSimplifiedChinese() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        fun appName(tag: String): String {
            val configuration = Configuration(context.resources.configuration)
            configuration.setLocale(Locale.forLanguageTag(tag))
            return context.createConfigurationContext(configuration).getString(R.string.app_name)
        }

        assertEquals("NSCheatManager", appName("en"))
        assertEquals("NS金手指管理", appName("zh-CN"))
    }
}
