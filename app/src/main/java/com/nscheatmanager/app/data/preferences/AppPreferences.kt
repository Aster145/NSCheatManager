package com.nscheatmanager.app.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.appPreferencesDataStore by preferencesDataStore(name = "nscheatmanager_preferences")

class AppPreferences(private val dataStore: DataStore<Preferences>) {
    val selectedDeviceId: Flow<String?> = dataStore.data
        .map { it[SELECTED_DEVICE_ID] }
        .distinctUntilChanged()

    val languageTag: Flow<String> = dataStore.data
        .map { preferences ->
            preferences[LANGUAGE_TAG]
                ?.takeIf { it in SUPPORTED_LANGUAGE_TAGS }
                ?: CHINESE_LANGUAGE_TAG
        }
        .distinctUntilChanged()

    val showMemoryPage: Flow<Boolean> = dataStore.data
        .map { it[SHOW_MEMORY_PAGE] ?: false }
        .distinctUntilChanged()

    suspend fun setSelectedDeviceId(deviceId: String) {
        require(deviceId.isNotBlank()) { "Device ID must not be blank" }
        dataStore.edit { it[SELECTED_DEVICE_ID] = deviceId }
    }

    suspend fun clearSelectedDevice() {
        dataStore.edit { it.remove(SELECTED_DEVICE_ID) }
    }

    suspend fun setLanguageTag(languageTag: String) {
        require(languageTag in SUPPORTED_LANGUAGE_TAGS) { "Unsupported language: $languageTag" }
        dataStore.edit { it[LANGUAGE_TAG] = languageTag }
    }

    suspend fun setShowMemoryPage(show: Boolean) {
        dataStore.edit { it[SHOW_MEMORY_PAGE] = show }
    }

    companion object {
        const val CHINESE_LANGUAGE_TAG = "zh-CN"
        const val ENGLISH_LANGUAGE_TAG = "en"
        val SUPPORTED_LANGUAGE_TAGS = setOf(CHINESE_LANGUAGE_TAG, ENGLISH_LANGUAGE_TAG)

        fun create(context: Context): AppPreferences =
            AppPreferences(context.applicationContext.appPreferencesDataStore)

        private val SELECTED_DEVICE_ID = stringPreferencesKey("selected_device_id")
        private val LANGUAGE_TAG = stringPreferencesKey("language_tag")
        private val SHOW_MEMORY_PAGE = booleanPreferencesKey("show_memory_page")
    }
}
