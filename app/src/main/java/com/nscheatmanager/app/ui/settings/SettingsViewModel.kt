package com.nscheatmanager.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nscheatmanager.app.data.preferences.AppPreferences
import com.nscheatmanager.app.domain.DeviceProfile
import com.nscheatmanager.app.domain.DeviceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class DeviceEditorError {
    NAME_REQUIRED,
    INVALID_IPV4,
    INVALID_PORT,
    DUPLICATE_NAME,
    DUPLICATE_HOST,
    SAVE_FAILED,
}

enum class SettingsMessage { DELETE_FAILED, DEFAULT_FAILED, LANGUAGE_FAILED, MEMORY_VISIBILITY_FAILED, AUTO_DETACH_FAILED }

interface DeviceSettingsRepository {
    val devices: Flow<List<DeviceProfile>>

    suspend fun addDevice(
        name: String,
        host: String,
        sysBotPort: Int,
        ftpPort: Int,
        noexsPort: Int,
    ): DeviceProfile

    suspend fun saveDevice(profile: DeviceProfile): DeviceProfile
    suspend fun deleteDevice(deviceId: String)
    suspend fun setDefaultDevice(deviceId: String)
}

interface LanguagePreferenceStore {
    val languageTag: Flow<String>
    val showMemoryPage: Flow<Boolean>
    val detachDmntBeforeConnect: Flow<Boolean>
    suspend fun setLanguageTag(languageTag: String)
    suspend fun setShowMemoryPage(show: Boolean)
    suspend fun setDetachDmntBeforeConnect(enabled: Boolean)
}

class SettingsViewModel(
    private val repository: DeviceSettingsRepository,
    private val preferences: LanguagePreferenceStore,
    private val applyLocale: (String) -> Unit = {},
) : ViewModel() {
    private val mutableUiState = kotlinx.coroutines.flow.MutableStateFlow(SettingsUiState())
    val uiState: kotlinx.coroutines.flow.StateFlow<SettingsUiState> = mutableUiState
    private val mutableMessages = MutableSharedFlow<SettingsMessage>(extraBufferCapacity = 1)
    val messages = mutableMessages.asSharedFlow()

    init {
        viewModelScope.launch {
            repository.devices.collect { devices ->
                mutableUiState.update { it.copy(devices = devices) }
            }
        }
        viewModelScope.launch {
            preferences.languageTag.collect { languageTag ->
                mutableUiState.update { it.copy(languageTag = languageTag) }
            }
        }
        viewModelScope.launch {
            preferences.showMemoryPage.collect { show ->
                mutableUiState.update { it.copy(showMemoryPage = show) }
            }
        }
        viewModelScope.launch {
            preferences.detachDmntBeforeConnect.collect { enabled ->
                mutableUiState.update { it.copy(detachDmntBeforeConnect = enabled) }
            }
        }
    }

    fun openAddDevice() {
        mutableUiState.update { it.copy(editor = DeviceEditorUiState()) }
    }

    fun openEditDevice(profile: DeviceProfile) {
        mutableUiState.update {
            it.copy(
                editor = DeviceEditorUiState(
                    id = profile.id,
                    name = profile.name,
                    host = profile.host,
                    sysBotPort = profile.sysBotPort.toString(),
                    ftpPort = profile.ftpPort.toString(),
                    noexsPort = profile.noexsPort.toString(),
                ),
            )
        }
    }

    fun updateEditor(editor: DeviceEditorUiState) {
        mutableUiState.update { it.copy(editor = editor.copy(error = null)) }
    }

    fun dismissEditor() {
        if (!mutableUiState.value.isSaving) mutableUiState.update { it.copy(editor = null) }
    }

    fun saveEditor() {
        val editor = mutableUiState.value.editor ?: return
        val validated = validateEditor(editor, mutableUiState.value.devices)
        if (validated is EditorValidation.Failure) {
            mutableUiState.update { it.copy(editor = editor.copy(error = validated.error)) }
            return
        }
        validated as EditorValidation.Success
        mutableUiState.update { it.copy(isSaving = true, editor = editor.copy(error = null)) }
        viewModelScope.launch {
            try {
                if (editor.id == null) {
                    repository.addDevice(
                        name = validated.name,
                        host = validated.host,
                        sysBotPort = validated.sysBotPort,
                        ftpPort = validated.ftpPort,
                        noexsPort = validated.noexsPort,
                    )
                } else {
                    val existing = mutableUiState.value.devices.firstOrNull { it.id == editor.id }
                        ?: throw IllegalArgumentException("Unknown device")
                    repository.saveDevice(
                        existing.copy(
                            name = validated.name,
                            host = validated.host,
                            sysBotPort = validated.sysBotPort,
                            ftpPort = validated.ftpPort,
                            noexsPort = validated.noexsPort,
                        ),
                    )
                }
                mutableUiState.update { it.copy(editor = null, isSaving = false) }
            } catch (error: IllegalArgumentException) {
                val mapped = when {
                    error.message.orEmpty().contains("name", ignoreCase = true) -> DeviceEditorError.DUPLICATE_NAME
                    error.message.orEmpty().contains("host", ignoreCase = true) -> DeviceEditorError.DUPLICATE_HOST
                    else -> DeviceEditorError.SAVE_FAILED
                }
                mutableUiState.update {
                    it.copy(isSaving = false, editor = it.editor?.copy(error = mapped))
                }
            } catch (_: Exception) {
                mutableUiState.update {
                    it.copy(isSaving = false, editor = it.editor?.copy(error = DeviceEditorError.SAVE_FAILED))
                }
            }
        }
    }

    fun deleteDevice(deviceId: String) {
        viewModelScope.launch {
            runCatching { repository.deleteDevice(deviceId) }
                .onFailure { mutableMessages.emit(SettingsMessage.DELETE_FAILED) }
        }
    }

    fun setDefaultDevice(deviceId: String) {
        viewModelScope.launch {
            runCatching { repository.setDefaultDevice(deviceId) }
                .onFailure { mutableMessages.emit(SettingsMessage.DEFAULT_FAILED) }
        }
    }

    fun selectLanguage(languageTag: String) {
        require(languageTag in AppPreferences.SUPPORTED_LANGUAGE_TAGS) { "Unsupported language: $languageTag" }
        val previous = mutableUiState.value.languageTag
        if (previous == languageTag) return
        mutableUiState.update { it.copy(languageTag = languageTag) }
        viewModelScope.launch {
            try {
                preferences.setLanguageTag(languageTag)
                applyLocale(languageTag)
            } catch (_: Exception) {
                mutableUiState.update { it.copy(languageTag = previous) }
                mutableMessages.emit(SettingsMessage.LANGUAGE_FAILED)
            }
        }
    }

    fun setShowMemoryPage(show: Boolean) {
        val previous = mutableUiState.value.showMemoryPage
        if (previous == show) return
        mutableUiState.update { it.copy(showMemoryPage = show) }
        viewModelScope.launch {
            runCatching { preferences.setShowMemoryPage(show) }
                .onFailure {
                    mutableUiState.update { state -> state.copy(showMemoryPage = previous) }
                    mutableMessages.emit(SettingsMessage.MEMORY_VISIBILITY_FAILED)
                }
        }
    }

    fun setDetachDmntBeforeConnect(enabled: Boolean) {
        val previous = mutableUiState.value.detachDmntBeforeConnect
        if (previous == enabled) return
        mutableUiState.update { it.copy(detachDmntBeforeConnect = enabled) }
        viewModelScope.launch {
            runCatching { preferences.setDetachDmntBeforeConnect(enabled) }.onFailure {
                mutableUiState.update { state -> state.copy(detachDmntBeforeConnect = previous) }
                mutableMessages.emit(SettingsMessage.AUTO_DETACH_FAILED)
            }
        }
    }

    class Factory(
        private val devices: DeviceSettingsRepository,
        private val preferences: LanguagePreferenceStore,
        applyLocale: (String) -> Unit,
    ) : ViewModelProvider.Factory {
        private val localeApplier = applyLocale

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(SettingsViewModel::class.java))
            return SettingsViewModel(devices, preferences, localeApplier) as T
        }
    }
}

class DeviceRepositoryAdapter(private val delegate: DeviceRepository) : DeviceSettingsRepository {
    override val devices: Flow<List<DeviceProfile>> = delegate.observeDevices()

    override suspend fun addDevice(
        name: String,
        host: String,
        sysBotPort: Int,
        ftpPort: Int,
        noexsPort: Int,
    ): DeviceProfile = delegate.addDevice(name, host, sysBotPort, ftpPort, noexsPort)

    override suspend fun saveDevice(profile: DeviceProfile): DeviceProfile = delegate.saveDevice(profile)
    override suspend fun deleteDevice(deviceId: String) = delegate.deleteDevice(deviceId)
    override suspend fun setDefaultDevice(deviceId: String) = delegate.setDefaultDevice(deviceId)
}

class AppPreferencesAdapter(private val delegate: AppPreferences) : LanguagePreferenceStore {
    override val languageTag: Flow<String> = delegate.languageTag
    override val showMemoryPage: Flow<Boolean> = delegate.showMemoryPage
    override val detachDmntBeforeConnect: Flow<Boolean> = delegate.detachDmntBeforeConnect
    override suspend fun setLanguageTag(languageTag: String) = delegate.setLanguageTag(languageTag)
    override suspend fun setShowMemoryPage(show: Boolean) = delegate.setShowMemoryPage(show)
    override suspend fun setDetachDmntBeforeConnect(enabled: Boolean) = delegate.setDetachDmntBeforeConnect(enabled)
}

private sealed interface EditorValidation {
    data class Success(
        val name: String,
        val host: String,
        val sysBotPort: Int,
        val ftpPort: Int,
        val noexsPort: Int,
    ) : EditorValidation

    data class Failure(val error: DeviceEditorError) : EditorValidation
}

private fun validateEditor(
    editor: DeviceEditorUiState,
    devices: List<DeviceProfile>,
): EditorValidation {
    val name = editor.name.trim()
    if (name.isEmpty()) return EditorValidation.Failure(DeviceEditorError.NAME_REQUIRED)
    val host = canonicalIpv4(editor.host) ?: return EditorValidation.Failure(DeviceEditorError.INVALID_IPV4)
    val ports = listOf(editor.sysBotPort, editor.ftpPort, editor.noexsPort).map { value ->
        value.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: return EditorValidation.Failure(DeviceEditorError.INVALID_PORT)
    }
    val otherDevices = devices.filterNot { it.id == editor.id }
    if (otherDevices.any { it.name.equals(name, ignoreCase = true) }) {
        return EditorValidation.Failure(DeviceEditorError.DUPLICATE_NAME)
    }
    if (otherDevices.any { canonicalIpv4(it.host) == host }) {
        return EditorValidation.Failure(DeviceEditorError.DUPLICATE_HOST)
    }
    return EditorValidation.Success(name, host, ports[0], ports[1], ports[2])
}

private fun canonicalIpv4(raw: String): String? {
    val parts = raw.trim().split('.')
    if (parts.size != 4) return null
    val octets = parts.map { part ->
        if (part.isEmpty() || part.any { !it.isDigit() }) return null
        part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
    }
    return octets.joinToString(".")
}
