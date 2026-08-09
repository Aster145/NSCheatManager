package com.nscheatmanager.app.ui.game

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nscheatmanager.app.cheats.parser.CheatFile
import com.nscheatmanager.app.cheats.parser.CheatGroup
import com.nscheatmanager.app.cheats.vm.CheatValidationError
import com.nscheatmanager.app.cheats.vm.CheatValidator
import com.nscheatmanager.app.cheats.vm.ExecutionReport
import com.nscheatmanager.app.cheats.vm.ExecutionStatus
import com.nscheatmanager.app.cheats.vm.ValidationResult
import com.nscheatmanager.app.data.files.ZipInspection
import com.nscheatmanager.app.domain.ConnectionState
import com.nscheatmanager.app.domain.DeviceProfile
import com.nscheatmanager.app.domain.DeviceSessionState
import com.nscheatmanager.app.domain.GameOperationKey
import com.nscheatmanager.app.domain.DirectOverwriteConfirmation
import com.nscheatmanager.app.domain.DownloadOverwriteConfirmation
import com.nscheatmanager.app.domain.TransferReport
import com.nscheatmanager.app.domain.UploadConfirmation
import com.nscheatmanager.app.domain.UploadPreview
import com.nscheatmanager.app.protocol.sysbot.GameIdentity
import com.nscheatmanager.app.protocol.ProtocolError
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

data class CheatGroupUiState(
    val name: String,
    val checked: Boolean = false,
    val executable: Boolean = true,
    val executing: Boolean = false,
    val unsupportedLine: Int? = null,
    val unsupportedOpcode: String? = null,
    val validationDetail: String? = null,
    val diagnostic: CheatDiagnosticUiState? = null,
    val lastExecutedAtEpochMillis: Long? = null,
)

enum class CheatDiagnosticKind {
    UnsupportedOpcode,
    UnsupportedForm,
    UnsupportedMemoryRegion,
    ArithmeticOverflow,
    InstructionLimitExceeded,
    IoLimitExceeded,
    Connection,
    Timeout,
    Disconnected,
    MalformedResponse,
    ResponseTooLarge,
    CommandTooLarge,
}

data class CheatDiagnosticUiState(
    val kind: CheatDiagnosticKind,
    val line: Int,
    val opcode: String? = null,
    val argument: String? = null,
)

data class GameUiState(
    val devices: List<DeviceProfile> = emptyList(),
    val selectedDeviceId: String? = null,
    val connection: ConnectionState = ConnectionState.Disconnected,
    val gameValidated: Boolean = false,
    val titleId: String? = null,
    val buildId: String? = null,
    val mainBase: String? = null,
    val heapBase: String? = null,
    val mirrorPath: String? = null,
    val groups: List<CheatGroupUiState> = emptyList(),
    val missingMirror: Boolean = false,
    val canImport: Boolean = false,
    val canDownload: Boolean = false,
    val busy: Boolean = false,
    val editMode: Boolean = false,
    val pendingConfirmation: GameConfirmation? = null,
)

sealed interface GameConfirmation {
    val id: Long
    val key: GameOperationKey

    data class ZipImport(
        override val id: Long,
        override val key: GameOperationKey,
        val inspection: ZipInspection,
    ) : GameConfirmation

    data class Download(
        override val id: Long,
        override val key: GameOperationKey,
        val report: TransferReport.RequiresLocalOverwriteConfirmation,
    ) : GameConfirmation

    data class Upload(
        override val id: Long,
        override val key: GameOperationKey,
        val preview: UploadPreview,
    ) : GameConfirmation

    data class DirectUpload(
        override val id: Long,
        override val key: GameOperationKey,
        val upload: UploadConfirmation,
        val direct: DirectOverwriteConfirmation,
    ) : GameConfirmation

    data class EmptyNotesShare(
        override val id: Long,
        override val key: GameOperationKey,
    ) : GameConfirmation
}

enum class GameMessage {
    SELECT_DEVICE,
    SESSION_NOT_READY,
    EXECUTION_FAILED,
    UNSUPPORTED_CHEAT,
    OPERATION_FAILED,
    REMOTE_CHEAT_MISSING,
    STALE_LOCAL_FILES,
    DOWNLOAD_COMPLETE,
    UPLOAD_COMPLETE,
    IMPORT_COMPLETE,
    DETACH_COMPLETE,
}

data class ShareArchive(val fileName: String, val bytes: ByteArray)

sealed interface GameEffect {
    data object OpenZipDocument : GameEffect
    data class Share(val archive: ShareArchive) : GameEffect
    data class Message(
        val message: GameMessage,
        val detail: String? = null,
        val sourceLine: Int? = null,
        val diagnostic: CheatDiagnosticUiState? = null,
    ) : GameEffect
}

interface GameDeviceStore {
    val devices: Flow<List<DeviceProfile>>
    val selectedDeviceId: Flow<String?>
    suspend fun selectDevice(deviceId: String)
}

interface GameSessionGateway {
    val state: StateFlow<DeviceSessionState>
    fun connectAndRecognize(device: DeviceProfile)
    fun switchDevice(device: DeviceProfile)
    fun disconnect()
    fun recognizeAgain()
    suspend fun detachDmnt()
    fun currentOperationKey(): GameOperationKey?
    fun requireCurrentOperationKey(expected: GameOperationKey)
    suspend fun executeGroup(expected: GameOperationKey, group: CheatGroup): ExecutionReport
    suspend fun uncheckGroup(expected: GameOperationKey, groupName: String)
    suspend fun close()
}

class GameViewModel private constructor(
    private val devices: GameDeviceStore,
    private val files: GameFileGateway,
    private val validator: CheatValidator,
    sessionFactory: (CoroutineScope) -> GameSessionGateway,
) : ViewModel() {
    private val session = sessionFactory(viewModelScope)
    private val mutableUiState = MutableStateFlow(GameUiState())
    val uiState = mutableUiState.asStateFlow()
    private val effectChannel = Channel<GameEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()
    private data class GroupClaim(val key: GameOperationKey, val groupName: String)
    private val claimedExecutions = linkedSetOf<GroupClaim>()
    private val locallyCompleted = linkedSetOf<GroupClaim>()
    private val pendingUnchecks = linkedSetOf<GroupClaim>()
    private val confirmationLock = Any()
    private var nextConfirmationId = 1L
    private var sessionState = DeviceSessionState()
    private var displayedCheatFile: CheatFile? = null
    private var displayedIdentity: GameIdentity? = null
    private var localCheatOverride: CheatFile? = null
    private var localOverrideIdentity: GameIdentity? = null

    constructor(
        devices: GameDeviceStore,
        session: GameSessionGateway,
        files: GameFileGateway,
        validator: CheatValidator = CheatValidator(),
    ) : this(devices, files, validator, { session })

    init {
        viewModelScope.launch {
            devices.devices.collect { profiles ->
                mutableUiState.update { current ->
                    val selected = current.selectedDeviceId
                        ?.takeIf { id -> profiles.any { it.id == id } }
                        ?: profiles.firstOrNull { it.isDefault }?.id
                        ?: profiles.firstOrNull()?.id
                    current.copy(
                        devices = Collections.unmodifiableList(profiles.toList()),
                        selectedDeviceId = selected,
                    )
                }
            }
        }
        viewModelScope.launch {
            devices.selectedDeviceId.collect { selected ->
                if (selected != null) mutableUiState.update { it.copy(selectedDeviceId = selected) }
            }
        }
        viewModelScope.launch {
            session.state.collect(::publishSessionState)
        }
    }

    fun onDeviceSelected(deviceId: String) {
        val profile = mutableUiState.value.devices.firstOrNull { it.id == deviceId } ?: return
        mutableUiState.update { it.copy(selectedDeviceId = deviceId) }
        viewModelScope.launch { runCatching { devices.selectDevice(deviceId) }.onFailure(::showFailure) }
        if (sessionState.device?.id != deviceId && sessionState.connection in setOf(
                ConnectionState.Connecting,
                ConnectionState.Recognizing,
                ConnectionState.Ready,
            )
        ) {
            session.switchDevice(profile)
        }
    }

    fun onConnectionToggle() {
        if (sessionState.connection in setOf(
                ConnectionState.Connecting,
                ConnectionState.Recognizing,
                ConnectionState.Ready,
            )
        ) {
            session.disconnect()
            return
        }
        selectedProfile()?.let(session::connectAndRecognize)
            ?: effectChannel.trySend(GameEffect.Message(GameMessage.SELECT_DEVICE))
    }

    fun onRecognizeRequested() {
        runCatching(session::recognizeAgain).onFailure(::showFailure)
    }

    fun onDetachDmntRequested() {
        viewModelScope.launch {
            runCatching { session.detachDmnt() }
                .onSuccess { effectChannel.trySend(GameEffect.Message(GameMessage.DETACH_COMPLETE)) }
                .onFailure { showFailure(it) }
        }
    }

    fun onCheatChecked(groupName: String, wasChecked: Boolean, isChecked: Boolean) {
        if (wasChecked == isChecked) return
        val group = currentGroup(groupName) ?: return
        val key = sessionState.operationKey
        if (isChecked) {
            val row = mutableUiState.value.groups.firstOrNull { it.name == groupName }
            if (row?.executable != true || key == null) {
                effectChannel.trySend(
                    GameEffect.Message(
                        if (row?.executable == false) GameMessage.UNSUPPORTED_CHEAT else GameMessage.SESSION_NOT_READY,
                        row?.validationDetail,
                        row?.unsupportedLine,
                        row?.diagnostic,
                    ),
                )
                return
            }
            synchronized(claimedExecutions) {
                if (!claimedExecutions.add(GroupClaim(key, groupName))) return
            }
            republishGroups()
            viewModelScope.launch {
                try {
                    val report = session.executeGroup(key, group)
                    if (report.status != ExecutionStatus.Complete) {
                        val diagnostic = report.validationError?.toUiDiagnostic()
                            ?: report.error?.toUiDiagnostic(report.failureLine ?: group.startLine)
                        effectChannel.trySend(
                            GameEffect.Message(
                                GameMessage.EXECUTION_FAILED,
                                sourceLine = report.failureLine.takeIf { diagnostic == null },
                                diagnostic = diagnostic,
                            ),
                        )
                    } else synchronized(claimedExecutions) {
                        locallyCompleted += GroupClaim(key, groupName)
                    }
                } catch (error: Throwable) {
                    showFailure(error)
                } finally {
                    synchronized(claimedExecutions) {
                        val claim = GroupClaim(key, groupName)
                        if (claim !in locallyCompleted) claimedExecutions.remove(claim)
                    }
                    republishGroups()
                }
            }
        } else {
            if (key == null) return
            synchronized(claimedExecutions) {
                val claim = GroupClaim(key, groupName)
                locallyCompleted.remove(claim)
                claimedExecutions.remove(claim)
                pendingUnchecks += claim
            }
            setCheckedLocally(groupName, false)
            viewModelScope.launch {
                runCatching { session.uncheckGroup(key, groupName) }
                    .onFailure {
                        synchronized(claimedExecutions) { pendingUnchecks.remove(GroupClaim(key, groupName)) }
                        setCheckedLocally(groupName, true)
                        showFailure(it)
                    }
            }
        }
    }

    fun onImportZipRequested() {
        if (readyIdentity() == null) {
            effectChannel.trySend(GameEffect.Message(GameMessage.SESSION_NOT_READY))
        } else {
            effectChannel.trySend(GameEffect.OpenZipDocument)
        }
    }

    fun onZipDocument(bytes: ByteArray) {
        val key = session.currentOperationKey()
        if (key == null) {
            effectChannel.trySend(GameEffect.Message(GameMessage.SESSION_NOT_READY))
            return
        }
        viewModelScope.launch {
            runCatching { files.inspectZip(bytes) }
                .mapCatching { inspection ->
                    session.requireCurrentOperationKey(key)
                    inspection
                }
                .onSuccess { inspection ->
                    if (key.titleId != inspection.titleId || key.buildId != inspection.buildId) {
                        showFailure(IllegalArgumentException("ZIP TID/BID does not match the current recognized game"))
                    } else {
                        setConfirmation { id -> GameConfirmation.ZipImport(id, key, inspection) }
                    }
                }
                .onFailure { showFailure(it) }
        }
    }

    private fun confirmZipImport(key: GameOperationKey, inspection: ZipInspection) {
        viewModelScope.launch {
            runCatching {
                session.requireCurrentOperationKey(key)
                val identity = requireNotNull(readyIdentity()) { "A validated game is required" }
                require(key.titleId == inspection.titleId && key.buildId == inspection.buildId) {
                    "ZIP TID/BID no longer matches the current recognized game"
                }
                files.importZip(inspection) { session.requireCurrentOperationKey(key) }
                session.requireCurrentOperationKey(key)
                displayedIdentity?.takeIf {
                    it.titleId == inspection.titleId && it.buildId == inspection.buildId
                }?.let { reloadLocal(key, it) }
            }.onSuccess {
                effectChannel.trySend(GameEffect.Message(GameMessage.IMPORT_COMPLETE))
            }.onFailure { showFailure(it) }
        }
    }

    fun onDownloadRequested() {
        val profile = selectedProfile()
        val identity = readyIdentity()
        val key = session.currentOperationKey()
        if (profile == null || identity == null || key == null) {
            effectChannel.trySend(GameEffect.Message(GameMessage.SESSION_NOT_READY))
            return
        }
        runDownload(key, profile, identity, null)
    }

    private fun confirmDownload(key: GameOperationKey, report: TransferReport.RequiresLocalOverwriteConfirmation) {
        val profile = selectedProfile()
        val identity = readyIdentity()
        if (profile == null || identity == null || profile.id != key.deviceId) return
        runDownload(key, profile, identity, report.confirmation)
    }

    private fun discardDownload(report: TransferReport.RequiresLocalOverwriteConfirmation) {
        viewModelScope.launch { files.discardDownload(report.confirmation) }
    }

    fun onUploadRequested() {
        val profile = selectedProfile()
        val identity = readyIdentity()
        val key = session.currentOperationKey()
        if (profile == null || identity == null || key == null) {
            effectChannel.trySend(GameEffect.Message(GameMessage.SESSION_NOT_READY))
            return
        }
        viewModelScope.launch {
            runCatching { files.previewUpload(profile, identity) { session.requireCurrentOperationKey(key) } }
                .mapCatching { preview -> session.requireCurrentOperationKey(key); preview }
                .onSuccess { preview -> setConfirmation { id -> GameConfirmation.Upload(id, key, preview) } }
                .onFailure { showFailure(it) }
        }
    }

    fun onShareZipRequested() {
        val identity = readyIdentity()
        val key = session.currentOperationKey()
        if (identity == null || key == null) {
            effectChannel.trySend(GameEffect.Message(GameMessage.SESSION_NOT_READY))
            return
        }
        viewModelScope.launch {
            runCatching { files.notesExist(identity) { session.requireCurrentOperationKey(key) } }
                .mapCatching { exists -> session.requireCurrentOperationKey(key); exists }
                .onSuccess { exists ->
                    if (exists) exportZip(key, identity, false)
                    else setConfirmation { id -> GameConfirmation.EmptyNotesShare(id, key) }
                }
                .onFailure { showFailure(it) }
        }
    }

    fun currentIdentityForEditor(): GameIdentity? = readyIdentity()
    fun currentOperationKeyForEditor(): GameOperationKey? = session.currentOperationKey()
    fun requireCurrentOperationKey(expected: GameOperationKey) = session.requireCurrentOperationKey(expected)

    fun onEditorUnavailable() {
        effectChannel.trySend(GameEffect.Message(GameMessage.SESSION_NOT_READY))
    }

    fun onExternalFailure(error: Throwable) {
        showFailure(error)
    }

    fun confirmPending(id: Long) {
        when (val confirmation = takeConfirmation(id)) {
            is GameConfirmation.ZipImport -> confirmZipImport(confirmation.key, confirmation.inspection)
            is GameConfirmation.Download -> confirmDownload(confirmation.key, confirmation.report)
            is GameConfirmation.Upload -> runUpload(confirmation.key, confirmation.preview.confirmation, null)
            is GameConfirmation.DirectUpload ->
                runUpload(confirmation.key, confirmation.upload, confirmation.direct)
            is GameConfirmation.EmptyNotesShare -> {
                val identity = readyIdentity()
                if (identity != null) viewModelScope.launch { exportZip(confirmation.key, identity, true) }
            }
            null -> Unit
        }
    }

    fun dismissPending(id: Long) {
        val confirmation = takeConfirmation(id)
        if (confirmation is GameConfirmation.Download) discardDownload(confirmation.report)
    }

    fun onLocalFileSaved(identity: GameIdentity, file: CheatFile) {
        if (displayedIdentity?.titleId != identity.titleId || displayedIdentity?.buildId != identity.buildId) return
        displayedCheatFile = file
        localCheatOverride = file
        localOverrideIdentity = identity
        republishGroups()
    }

    private fun runDownload(
        key: GameOperationKey,
        profile: DeviceProfile,
        identity: GameIdentity,
        confirmation: DownloadOverwriteConfirmation?,
    ) {
        viewModelScope.launch {
            runCatching {
                files.download(profile, identity, confirmation) { session.requireCurrentOperationKey(key) }
            }
                .mapCatching { report ->
                    session.requireCurrentOperationKey(key)
                    report
                }
                .onSuccess { report ->
                    when (report) {
                        TransferReport.RemoteCheatMissing ->
                            effectChannel.trySend(GameEffect.Message(GameMessage.REMOTE_CHEAT_MISSING))
                        is TransferReport.RequiresLocalOverwriteConfirmation ->
                            setConfirmation { id -> GameConfirmation.Download(id, key, report) }
                        is TransferReport.Downloaded -> {
                            reloadLocal(key, identity)
                            effectChannel.trySend(GameEffect.Message(GameMessage.DOWNLOAD_COMPLETE))
                        }
                        TransferReport.StaleLocalSnapshot ->
                            effectChannel.trySend(GameEffect.Message(GameMessage.STALE_LOCAL_FILES))
                        is TransferReport.RequiresDirectOverwriteConfirmation,
                        is TransferReport.Uploaded,
                        -> error("Unexpected download report: $report")
                    }
                }
                .onFailure { showFailure(it) }
        }
    }

    private fun runUpload(
        key: GameOperationKey,
        upload: UploadConfirmation,
        direct: DirectOverwriteConfirmation?,
    ) {
        viewModelScope.launch {
            runCatching {
                session.requireCurrentOperationKey(key)
                files.upload(upload, direct) { session.requireCurrentOperationKey(key) }
            }.mapCatching { report -> session.requireCurrentOperationKey(key); report }
                .onSuccess { report ->
                    when (report) {
                        is TransferReport.RequiresDirectOverwriteConfirmation ->
                            setConfirmation { id ->
                                GameConfirmation.DirectUpload(id, key, upload, report.confirmation)
                            }
                        is TransferReport.Uploaded ->
                            effectChannel.trySend(GameEffect.Message(GameMessage.UPLOAD_COMPLETE))
                        TransferReport.StaleLocalSnapshot ->
                            effectChannel.trySend(GameEffect.Message(GameMessage.STALE_LOCAL_FILES))
                        else -> error("Unexpected upload report: $report")
                    }
                }
                .onFailure { showFailure(it) }
        }
    }

    private suspend fun exportZip(
        key: GameOperationKey,
        identity: GameIdentity,
        includeEmptyNotes: Boolean,
    ) {
        runCatching {
            session.requireCurrentOperationKey(key)
            files.exportZip(identity, includeEmptyNotes) { session.requireCurrentOperationKey(key) }
        }.mapCatching { bytes -> session.requireCurrentOperationKey(key); bytes }
            .onSuccess { bytes ->
                effectChannel.trySend(
                    GameEffect.Share(ShareArchive("${identity.titleId.hex}_${identity.buildId.hex}.zip", bytes)),
                )
            }
            .onFailure { showFailure(it) }
    }

    private suspend fun reloadLocal(key: GameOperationKey, identity: GameIdentity) {
        session.requireCurrentOperationKey(key)
        val editable = files.loadEditable(identity) { session.requireCurrentOperationKey(key) }
        session.requireCurrentOperationKey(key)
        displayedCheatFile = com.nscheatmanager.app.cheats.parser.CheatFileParser().parse(editable.cheatText)
        localCheatOverride = displayedCheatFile
        localOverrideIdentity = identity
        displayedIdentity = identity
        republishGroups()
    }

    private fun publishSessionState(next: DeviceSessionState) {
        if (localOverrideIdentity?.titleId != next.game?.titleId || localOverrideIdentity?.buildId != next.game?.buildId) {
            localCheatOverride = null
            localOverrideIdentity = null
        }
        sessionState = next
        val currentKey = next.operationKey
        synchronized(claimedExecutions) {
            claimedExecutions.removeAll { it.key != currentKey }
            locallyCompleted.removeAll { it.key != currentKey }
            pendingUnchecks.removeAll { it.key != currentKey }
            currentKey?.let { key ->
                val acknowledged = next.checkedGroups.mapTo(linkedSetOf()) { GroupClaim(key, it) }
                locallyCompleted.removeAll(acknowledged)
                claimedExecutions.removeAll(acknowledged)
                pendingUnchecks.removeAll { it.key == key && it.groupName !in next.checkedGroups }
            }
        }
        val staleConfirmation = synchronized(confirmationLock) {
            mutableUiState.value.pendingConfirmation?.takeIf { it.key != currentKey }?.also {
                mutableUiState.update { state -> state.copy(pendingConfirmation = null) }
            }
        }
        if (staleConfirmation is GameConfirmation.Download) discardDownload(staleConfirmation.report)
        displayedIdentity = next.game
        displayedCheatFile = localCheatOverride ?: next.cheatFile
        val identity = next.game
        mutableUiState.update { current ->
            current.copy(
                connection = next.connection,
                gameValidated = next.gameValidated,
                titleId = identity?.titleId?.hex,
                buildId = identity?.buildId?.hex,
                mainBase = identity?.mainBase?.let(::hexAddress),
                heapBase = identity?.heapBase?.let(::hexAddress),
                mirrorPath = next.cheatRelativePath,
                missingMirror = next.gameValidated && displayedCheatFile == null,
                canImport = next.gameValidated,
                canDownload = next.gameValidated,
                busy = next.connection in setOf(ConnectionState.Connecting, ConnectionState.Recognizing),
                groups = groupRows(displayedCheatFile, identity, next),
            )
        }
    }

    private fun republishGroups() {
        mutableUiState.update { current ->
            current.copy(groups = groupRows(displayedCheatFile, displayedIdentity, sessionState))
        }
    }

    private fun groupRows(
        file: CheatFile?,
        identity: GameIdentity?,
        state: DeviceSessionState,
    ): List<CheatGroupUiState> = file?.groups.orEmpty().map { group ->
        val validation = validator.validate(group, identity)
        val invalid = (validation as? ValidationResult.Invalid)?.error
        val unsupported = invalid as? CheatValidationError.UnsupportedOpcode
        val diagnostic = invalid?.toUiDiagnostic()
        val key = state.operationKey
        val claim = key?.let { GroupClaim(it, group.name) }
        CheatGroupUiState(
            name = group.name,
            checked = claim != null && synchronized(claimedExecutions) {
                claim !in pendingUnchecks && (group.name in state.checkedGroups || claim in locallyCompleted)
            },
            executable = invalid == null && state.gameValidated,
            executing = group.name in state.executingGroups || synchronized(claimedExecutions) {
                claim in claimedExecutions
            },
            unsupportedLine = invalid?.line,
            unsupportedOpcode = unsupported?.opcode?.let { "0x${it.toString(16).uppercase()}" },
            validationDetail = invalid?.toDisplayText(),
            diagnostic = diagnostic,
            lastExecutedAtEpochMillis = state.lastExecutedAtEpochMillis[group.name],
        )
    }

    private fun setCheckedLocally(groupName: String, checked: Boolean) {
        mutableUiState.update { current ->
            current.copy(groups = current.groups.map { if (it.name == groupName) it.copy(checked = checked) else it })
        }
    }

    private fun currentGroup(name: String): CheatGroup? = displayedCheatFile?.groups?.firstOrNull { it.name == name }
    private fun selectedProfile(): DeviceProfile? = mutableUiState.value.devices.firstOrNull {
        it.id == mutableUiState.value.selectedDeviceId
    }
    private fun readyIdentity(): GameIdentity? = sessionState.game?.takeIf { sessionState.gameValidated }

    private fun showFailure(error: Throwable) {
        effectChannel.trySend(GameEffect.Message(GameMessage.OPERATION_FAILED, error.message))
    }

    private fun setConfirmation(create: (Long) -> GameConfirmation) {
        synchronized(confirmationLock) {
            if (mutableUiState.value.pendingConfirmation != null) return
            mutableUiState.update { it.copy(pendingConfirmation = create(nextConfirmationId++)) }
        }
    }

    private fun takeConfirmation(id: Long): GameConfirmation? = synchronized(confirmationLock) {
        val pending = mutableUiState.value.pendingConfirmation?.takeIf { it.id == id } ?: return@synchronized null
        mutableUiState.update { it.copy(pendingConfirmation = null) }
        pending
    }

    override fun onCleared() {
        runBlocking { runCatching { session.close() } }
        effectChannel.close()
    }

    class Factory(
        private val devices: GameDeviceStore,
        private val files: GameFileGateway,
        private val sessionFactory: (CoroutineScope) -> GameSessionGateway,
        private val validator: CheatValidator = CheatValidator(),
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(GameViewModel::class.java))
            return GameViewModel(devices, files, validator, sessionFactory) as T
        }
    }
}

private fun hexAddress(value: ULong): String = "0x${value.toString(16).uppercase()}"

private fun CheatValidationError.toUiDiagnostic(): CheatDiagnosticUiState = when (this) {
    is CheatValidationError.UnsupportedOpcode -> CheatDiagnosticUiState(
        kind = CheatDiagnosticKind.UnsupportedOpcode,
        line = line,
        opcode = "0x${opcode.toString(16).uppercase()}",
    )
    is CheatValidationError.UnsupportedForm -> CheatDiagnosticUiState(
        kind = CheatDiagnosticKind.UnsupportedForm,
        line = line,
        argument = reason,
    )
    is CheatValidationError.UnsupportedMemoryRegion -> CheatDiagnosticUiState(
        kind = CheatDiagnosticKind.UnsupportedMemoryRegion,
        line = line,
        argument = region.toString(),
    )
    is CheatValidationError.ArithmeticOverflow -> CheatDiagnosticUiState(
        kind = CheatDiagnosticKind.ArithmeticOverflow,
        line = line,
    )
    is CheatValidationError.InstructionLimitExceeded -> CheatDiagnosticUiState(
        kind = CheatDiagnosticKind.InstructionLimitExceeded,
        line = line,
        argument = limit.toString(),
    )
    is CheatValidationError.IoLimitExceeded -> CheatDiagnosticUiState(
        kind = CheatDiagnosticKind.IoLimitExceeded,
        line = line,
        argument = limitBytes.toString(),
    )
}

private fun ProtocolError.toUiDiagnostic(line: Int): CheatDiagnosticUiState = when (this) {
    is ProtocolError.Connection -> CheatDiagnosticUiState(CheatDiagnosticKind.Connection, line)
    is ProtocolError.Timeout -> CheatDiagnosticUiState(CheatDiagnosticKind.Timeout, line, argument = operation)
    is ProtocolError.Disconnected -> CheatDiagnosticUiState(CheatDiagnosticKind.Disconnected, line)
    is ProtocolError.MalformedResponse -> CheatDiagnosticUiState(CheatDiagnosticKind.MalformedResponse, line)
    is ProtocolError.ResponseTooLarge -> CheatDiagnosticUiState(
        CheatDiagnosticKind.ResponseTooLarge,
        line,
        argument = limitBytes.toString(),
    )
    is ProtocolError.CommandTooLarge -> CheatDiagnosticUiState(
        CheatDiagnosticKind.CommandTooLarge,
        line,
        argument = "$actualBytes/$limitBytes",
    )
}

private fun CheatValidationError.toDisplayText(): String = when (this) {
    is CheatValidationError.UnsupportedOpcode -> "Line $line · opcode 0x${opcode.toString(16).uppercase()}"
    is CheatValidationError.UnsupportedForm -> "Line $line · $reason"
    is CheatValidationError.UnsupportedMemoryRegion -> "Line $line · memory region $region"
    is CheatValidationError.ArithmeticOverflow -> "Line $line · arithmetic overflow"
    is CheatValidationError.InstructionLimitExceeded -> "Line $line · instruction limit $limit"
    is CheatValidationError.IoLimitExceeded -> "Line $line · I/O limit $limitBytes"
}
