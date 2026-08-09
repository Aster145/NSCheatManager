package com.nscheatmanager.app.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import com.nscheatmanager.app.cheats.parser.CheatFile
import com.nscheatmanager.app.cheats.parser.CheatFileParser
import com.nscheatmanager.app.cheats.parser.CheatParseDiagnostic
import com.nscheatmanager.app.protocol.sysbot.GameIdentity
import com.nscheatmanager.app.domain.GameOperationKey
import com.nscheatmanager.app.data.files.EditorDraft
import com.nscheatmanager.app.data.files.EditorDraftStore
import com.nscheatmanager.app.data.files.InMemoryEditorDraftStore
import com.nscheatmanager.app.ui.game.EditableCheatParseException
import com.nscheatmanager.app.ui.game.GameFileGateway
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

enum class EditorTab { Cheat, Notes }

data class CheatEditorUiState(
    val isOpen: Boolean = false,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val identity: GameIdentity? = null,
    val operationKey: GameOperationKey? = null,
    val selectedTab: EditorTab = EditorTab.Cheat,
    val cheatTabLabel: String = "",
    val notesTabLabel: String = "",
    val cheatText: String = "",
    val notesText: String = "",
    val dirty: Boolean = false,
    val parseDiagnostic: CheatParseDiagnostic? = null,
    val pendingDiscard: PendingEditorDiscard? = null,
    val pendingNavigationRoute: String? = null,
)

data class PendingEditorDiscard(val id: Long, val route: String?)

sealed interface EditorEffect {
    data class Saved(val identity: GameIdentity, val file: CheatFile) : EditorEffect
    data class Error(val detail: String?) : EditorEffect
}

class CheatEditorViewModel(
    private val files: GameFileGateway,
    private val parser: CheatFileParser = CheatFileParser(),
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
    private val operationGuard: (GameOperationKey) -> Unit = {},
    private val draftStore: EditorDraftStore = InMemoryEditorDraftStore(),
) : ViewModel() {
    private var originalCheat = ""
    private var originalNotes = ""
    private var draftToken: String? = savedStateHandle[KEY_DRAFT_TOKEN]
    private val mutableUiState = MutableStateFlow(restoreState())
    val uiState = mutableUiState.asStateFlow()
    private val effectChannel = Channel<EditorEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()
    private var loadGeneration = 0L
    private var loadJob: Job? = null
    private var nextDiscardId: Long = savedStateHandle[KEY_NEXT_DISCARD_ID] ?: 1L

    fun open(identity: GameIdentity) = open(
        identity,
        GameOperationKey("test", identity.titleId, identity.buildId, 0L),
    )

    fun open(identity: GameIdentity, operationKey: GameOperationKey) {
        val generation = ++loadGeneration
        loadJob?.cancel()
        mutableUiState.value = CheatEditorUiState(
            isOpen = true,
            isLoading = true,
            identity = identity,
            operationKey = operationKey,
            cheatTabLabel = "${identity.buildId.hex}.txt",
            notesTabLabel = "${identity.buildId.hex}/notes.txt",
        )
        persistState()
        loadJob = viewModelScope.launch {
            runCatching { files.loadEditable(identity) { operationGuard(operationKey) } }
                .onSuccess { loaded ->
                    if (generation != loadGeneration || mutableUiState.value.identity != identity) return@onSuccess
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
                    persistState()
                }
                .onFailure {
                    if (generation != loadGeneration || mutableUiState.value.identity != identity) return@onFailure
                    mutableUiState.value = CheatEditorUiState()
                    persistState()
                    effectChannel.trySend(EditorEffect.Error(it.message))
                }
        }
    }

    fun selectTab(tab: EditorTab) {
        mutableUiState.update { it.copy(selectedTab = tab) }
        persistState()
    }

    fun updateCheatText(text: String) {
        mutableUiState.update {
            it.copy(
                cheatText = text,
                dirty = text != originalCheat || it.notesText != originalNotes,
                parseDiagnostic = null,
            )
        }
        persistState()
    }

    fun updateNotesText(text: String) {
        mutableUiState.update {
            it.copy(
                notesText = text,
                dirty = it.cheatText != originalCheat || text != originalNotes,
            )
        }
        persistState()
    }

    fun save() {
        val state = mutableUiState.value
        val identity = state.identity ?: return
        val operationKey = state.operationKey ?: return
        val parsed = parser.parse(state.cheatText)
        parsed.diagnostics.firstOrNull()?.let { diagnostic ->
            mutableUiState.update {
                it.copy(parseDiagnostic = diagnostic)
            }
            persistState()
            return
        }
        if (state.isSaving) return
        mutableUiState.update { it.copy(isSaving = true, parseDiagnostic = null) }
        persistState()
        viewModelScope.launch {
            runCatching {
                files.saveEditable(identity, state.cheatText, state.notesText) { operationGuard(operationKey) }
            }
                .onSuccess { saved ->
                    originalCheat = state.cheatText
                    originalNotes = state.notesText
                    mutableUiState.value = CheatEditorUiState()
                    persistState()
                    effectChannel.trySend(EditorEffect.Saved(identity, saved))
                }
                .onFailure { error ->
                    if (error is EditableCheatParseException) {
                        mutableUiState.update {
                            it.copy(
                                isSaving = false,
                                parseDiagnostic = error.diagnostic,
                            )
                        }
                        persistState()
                    } else {
                        mutableUiState.update { it.copy(isSaving = false) }
                        persistState()
                        effectChannel.trySend(EditorEffect.Error(error.message))
                    }
                }
        }
    }

    fun requestClose(route: String? = null) {
        val state = mutableUiState.value
        if (!state.isOpen || state.isSaving) return
        if (state.dirty) {
            if (state.pendingDiscard == null) {
                mutableUiState.update { it.copy(pendingDiscard = PendingEditorDiscard(nextDiscardId++, route)) }
                persistState()
            }
        } else {
            closeNow(route)
        }
    }

    fun confirmDiscard(id: Long) {
        val pending = mutableUiState.value.pendingDiscard?.takeIf { it.id == id } ?: return
        closeNow(pending.route)
    }

    fun dismissDiscard(id: Long) {
        mutableUiState.update { state ->
            if (state.pendingDiscard?.id == id) state.copy(pendingDiscard = null) else state
        }
        persistState()
    }

    fun acknowledgeNavigation(route: String) {
        mutableUiState.update { state ->
            if (state.pendingNavigationRoute == route) state.copy(pendingNavigationRoute = null) else state
        }
        persistState()
    }

    private fun closeNow(navigationRoute: String? = null) {
        originalCheat = ""
        originalNotes = ""
        mutableUiState.value = CheatEditorUiState(pendingNavigationRoute = navigationRoute)
        persistState()
    }

    override fun onCleared() {
        effectChannel.close()
    }

    private fun persistState() {
        val state = mutableUiState.value
        if (state.isOpen) {
            val identity = requireNotNull(state.identity)
            val key = requireNotNull(state.operationKey)
            draftToken = draftStore.save(
                draftToken,
                EditorDraft(identity, key, state.cheatText, state.notesText, originalCheat, originalNotes),
            )
        } else {
            draftToken?.let { runCatching { draftStore.delete(it) } }
            draftToken = null
        }
        savedStateHandle[KEY_DRAFT_TOKEN] = draftToken
        savedStateHandle[KEY_TITLE_ID] = state.operationKey?.titleId?.hex
        savedStateHandle[KEY_BUILD_ID] = state.operationKey?.buildId?.hex
        savedStateHandle[KEY_DEVICE_ID] = state.operationKey?.deviceId
        savedStateHandle[KEY_GENERATION] = state.operationKey?.generation
        savedStateHandle[KEY_SELECTED_TAB] = state.selectedTab.name
        savedStateHandle[KEY_DIRTY] = state.dirty
        savedStateHandle[KEY_DISCARD_ID] = state.pendingDiscard?.id
        savedStateHandle[KEY_DISCARD_ROUTE] = state.pendingDiscard?.route
        savedStateHandle[KEY_NAVIGATION_ROUTE] = state.pendingNavigationRoute
        savedStateHandle[KEY_NEXT_DISCARD_ID] = nextDiscardId
    }

    private fun restoreState(): CheatEditorUiState {
        runCatching { draftStore.cleanupExpired() }
        val token = draftToken ?: run {
            return CheatEditorUiState(pendingNavigationRoute = savedStateHandle[KEY_NAVIGATION_ROUTE])
        }
        val title = savedStateHandle.get<String>(KEY_TITLE_ID)?.let(com.nscheatmanager.app.core.model.TitleId::parse)
            ?: return CheatEditorUiState()
        val build = savedStateHandle.get<String>(KEY_BUILD_ID)?.let(com.nscheatmanager.app.core.model.BuildId::parse)
            ?: return CheatEditorUiState()
        val operationKey = GameOperationKey(
            deviceId = savedStateHandle.get<String>(KEY_DEVICE_ID) ?: return CheatEditorUiState(),
            titleId = title,
            buildId = build,
            generation = savedStateHandle.get<Long>(KEY_GENERATION) ?: return CheatEditorUiState(),
        )
        val draft = runCatching { draftStore.load(token) }.getOrNull()
            ?.takeIf { it.operationKey == operationKey }
            ?: return CheatEditorUiState()
        originalCheat = draft.originalCheat
        originalNotes = draft.originalNotes
        val discardId = savedStateHandle.get<Long>(KEY_DISCARD_ID)
        return CheatEditorUiState(
            isOpen = true,
            identity = draft.identity,
            operationKey = operationKey,
            selectedTab = savedStateHandle.get<String>(KEY_SELECTED_TAB)?.let(EditorTab::valueOf) ?: EditorTab.Cheat,
            cheatTabLabel = "${build.hex}.txt",
            notesTabLabel = "${build.hex}/notes.txt",
            cheatText = draft.cheatText,
            notesText = draft.notesText,
            dirty = savedStateHandle[KEY_DIRTY] ?: false,
            pendingDiscard = discardId?.let { PendingEditorDiscard(it, savedStateHandle[KEY_DISCARD_ROUTE]) },
            pendingNavigationRoute = savedStateHandle[KEY_NAVIGATION_ROUTE],
        )
    }

    class Factory(
        private val files: GameFileGateway,
        private val operationGuard: (GameOperationKey) -> Unit = {},
        private val draftStore: EditorDraftStore = InMemoryEditorDraftStore(),
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(CheatEditorViewModel::class.java))
            return CheatEditorViewModel(files, operationGuard = operationGuard, draftStore = draftStore) as T
        }

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: androidx.lifecycle.viewmodel.CreationExtras): T {
            require(modelClass.isAssignableFrom(CheatEditorViewModel::class.java))
            return CheatEditorViewModel(
                files,
                savedStateHandle = extras.createSavedStateHandle(),
                operationGuard = operationGuard,
                draftStore = draftStore,
            ) as T
        }
    }

    private companion object {
        const val KEY_DRAFT_TOKEN = "editor.draftToken"
        const val KEY_TITLE_ID = "editor.titleId"
        const val KEY_BUILD_ID = "editor.buildId"
        const val KEY_DEVICE_ID = "editor.deviceId"
        const val KEY_GENERATION = "editor.generation"
        const val KEY_SELECTED_TAB = "editor.tab"
        const val KEY_DIRTY = "editor.dirty"
        const val KEY_DISCARD_ID = "editor.discardId"
        const val KEY_DISCARD_ROUTE = "editor.discardRoute"
        const val KEY_NAVIGATION_ROUTE = "editor.navigationRoute"
        const val KEY_NEXT_DISCARD_ID = "editor.nextDiscardId"
    }
}
