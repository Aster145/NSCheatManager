package com.nscheatmanager.app.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nscheatmanager.app.cheats.parser.CheatFile
import com.nscheatmanager.app.cheats.parser.CheatFileParser
import com.nscheatmanager.app.protocol.sysbot.GameIdentity
import com.nscheatmanager.app.ui.game.EditableCheatParseException
import com.nscheatmanager.app.ui.game.GameFileGateway
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class EditorTab { Cheat, Notes }

data class CheatEditorUiState(
    val isOpen: Boolean = false,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val identity: GameIdentity? = null,
    val selectedTab: EditorTab = EditorTab.Cheat,
    val cheatTabLabel: String = "",
    val notesTabLabel: String = "",
    val cheatText: String = "",
    val notesText: String = "",
    val dirty: Boolean = false,
    val validationLine: Int? = null,
    val validationMessage: String? = null,
)

sealed interface EditorEffect {
    data object ConfirmDiscard : EditorEffect
    data class Saved(val identity: GameIdentity, val file: CheatFile) : EditorEffect
    data class Error(val detail: String?) : EditorEffect
}

class CheatEditorViewModel(
    private val files: GameFileGateway,
    private val parser: CheatFileParser = CheatFileParser(),
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(CheatEditorUiState())
    val uiState = mutableUiState.asStateFlow()
    private val effectChannel = Channel<EditorEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()
    private var originalCheat = ""
    private var originalNotes = ""

    fun open(identity: GameIdentity) {
        mutableUiState.value = CheatEditorUiState(
            isOpen = true,
            isLoading = true,
            identity = identity,
            cheatTabLabel = "${identity.buildId.hex}.txt",
            notesTabLabel = "${identity.buildId.hex}/notes.txt",
        )
        viewModelScope.launch {
            runCatching { files.loadEditable(identity) }
                .onSuccess { loaded ->
                    originalCheat = loaded.cheatText
                    originalNotes = loaded.notesText
                    mutableUiState.update {
                        it.copy(
                            isLoading = false,
                            cheatText = loaded.cheatText,
                            notesText = loaded.notesText,
                            dirty = false,
                        )
                    }
                }
                .onFailure {
                    mutableUiState.value = CheatEditorUiState()
                    effectChannel.trySend(EditorEffect.Error(it.message))
                }
        }
    }

    fun selectTab(tab: EditorTab) {
        mutableUiState.update { it.copy(selectedTab = tab) }
    }

    fun updateCheatText(text: String) {
        mutableUiState.update {
            it.copy(
                cheatText = text,
                dirty = text != originalCheat || it.notesText != originalNotes,
                validationLine = null,
                validationMessage = null,
            )
        }
    }

    fun updateNotesText(text: String) {
        mutableUiState.update {
            it.copy(
                notesText = text,
                dirty = it.cheatText != originalCheat || text != originalNotes,
            )
        }
    }

    fun save() {
        val state = mutableUiState.value
        val identity = state.identity ?: return
        val parsed = parser.parse(state.cheatText)
        parsed.diagnostics.firstOrNull()?.let { diagnostic ->
            mutableUiState.update {
                it.copy(validationLine = diagnostic.line, validationMessage = diagnostic.message)
            }
            return
        }
        if (state.isSaving) return
        mutableUiState.update { it.copy(isSaving = true, validationLine = null, validationMessage = null) }
        viewModelScope.launch {
            runCatching { files.saveEditable(identity, state.cheatText, state.notesText) }
                .onSuccess { saved ->
                    originalCheat = state.cheatText
                    originalNotes = state.notesText
                    mutableUiState.value = CheatEditorUiState()
                    effectChannel.trySend(EditorEffect.Saved(identity, saved))
                }
                .onFailure { error ->
                    if (error is EditableCheatParseException) {
                        mutableUiState.update {
                            it.copy(
                                isSaving = false,
                                validationLine = error.line,
                                validationMessage = error.message,
                            )
                        }
                    } else {
                        mutableUiState.update { it.copy(isSaving = false) }
                        effectChannel.trySend(EditorEffect.Error(error.message))
                    }
                }
        }
    }

    fun requestClose() {
        val state = mutableUiState.value
        if (!state.isOpen || state.isSaving) return
        if (state.dirty) effectChannel.trySend(EditorEffect.ConfirmDiscard) else closeNow()
    }

    fun confirmDiscard() = closeNow()

    private fun closeNow() {
        originalCheat = ""
        originalNotes = ""
        mutableUiState.value = CheatEditorUiState()
    }

    override fun onCleared() {
        effectChannel.close()
    }

    class Factory(private val files: GameFileGateway) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(CheatEditorViewModel::class.java))
            return CheatEditorViewModel(files) as T
        }
    }
}
