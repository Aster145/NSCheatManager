package com.nscheatmanager.app.ui

import android.content.res.Configuration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nscheatmanager.app.MainActivity
import com.nscheatmanager.app.R
import androidx.appcompat.app.AppCompatDelegate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.After
import kotlinx.coroutines.runBlocking
import androidx.core.os.LocaleListCompat
import com.nscheatmanager.app.NSCheatManagerApplication

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    private val createdDeviceIds = mutableListOf<String>()
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @After fun restoreGlobalState() = runBlocking {
        val dependencies = (compose.activity.application as NSCheatManagerApplication).dependencies
        createdDeviceIds.forEach { dependencies.deviceRepository.deleteDevice(it) }
        dependencies.appPreferences.setLanguageTag("zh-CN")
        compose.runOnUiThread {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("zh-CN"))
        }
        MainActivity.dependenciesForTest = null
    }

    @Test
    fun realCompositionRootStartsOnGameAndOpensSettings() {
        compose.onNodeWithTag("game-screen").assertIsDisplayed()
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

    @Test
    fun languageSelectionRecreatesAndRestoresFromPreferences() {
        openSettings()
        compose.onNodeWithText("English").performClick()
        compose.waitUntil(10_000) {
            AppCompatDelegate.getApplicationLocales().toLanguageTags() == "en"
        }
        compose.activityRule.scenario.recreate()
        openSettings()
        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNodeWithText("Interface language").assertIsDisplayed()

        compose.onNodeWithText("Simplified Chinese").performClick()
        compose.waitUntil(10_000) {
            AppCompatDelegate.getApplicationLocales().toLanguageTags() == "zh-CN"
        }
        compose.activityRule.scenario.recreate()
        openSettings()
        compose.onNodeWithText("设置").assertIsDisplayed()
        compose.onNodeWithText("界面语言").assertIsDisplayed()
    }

    @Test fun selectedDeviceAndLanguagePersistAcrossProductionActivityRecreation() {
        val dependencies = (compose.activity.application as NSCheatManagerApplication).dependencies
        val device = runBlocking {
            dependencies.deviceRepository.addDevice("Lifecycle Switch", "192.168.77.35").also {
                dependencies.deviceRepository.selectDevice(it.id)
                dependencies.appPreferences.setLanguageTag("en")
            }
        }
        createdDeviceIds += device.id
        compose.waitUntil(10_000) { AppCompatDelegate.getApplicationLocales().toLanguageTags() == "en" }
        compose.activityRule.scenario.recreate()
        openSettings()
        compose.onNodeWithText("Lifecycle Switch").assertIsDisplayed()
        compose.onNodeWithText("Interface language").assertIsDisplayed()
    }

    private fun openSettings() {
        if (compose.onAllNodesWithTag("settings-content").fetchSemanticsNodes().isNotEmpty()) return
        compose.onNodeWithTag("overflow-menu").performClick()
        compose.onNodeWithTag("menu-settings").performClick()
    }
}
