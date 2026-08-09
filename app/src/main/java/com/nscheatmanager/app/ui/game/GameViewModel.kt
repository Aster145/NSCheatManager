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
import com.nscheatmanager.app.domain.DirectOverwriteConfirmation
import com.nscheatmanager.app.domain.DownloadOverwriteConfirmation
import com.nscheatmanager.app.domain.TransferReport
import com.nscheatmanager.app.domain.UploadConfirmation
import com.nscheatmanager.app.domain.UploadPreview
import com.nscheatmanager.app.protocol.sysbot.GameIdentity
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
)

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
    data class ConfirmZipImport(val inspection: ZipInspection) : GameEffect
    data class ConfirmDownload(val report: TransferReport.RequiresLocalOverwriteConfirmation) : GameEffect
    data class ConfirmUpload(val preview: UploadPreview) : GameEffect
    data class ConfirmDirectUpload(
        val upload: UploadConfirmation,
        val direct: DirectOverwriteConfirmation,
    ) : GameEffect
    data object ConfirmEmptyNotesShare : GameEffect
    data class Share(val archive: ShareArchive) : GameEffect
    data class Message(
        val message: GameMessage,
        val detail: String? = null,
        val sourceLine: Int? = null,
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
    suspend fun executeGroup(group: CheatGroup): ExecutionReport
    suspend fun uncheckGroup(groupName: String)
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
    private val claimedExecutions = linkedSetOf<String>()
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
        if (isChecked) {
            val row = mutableUiState.value.groups.firstOrNull { it.name == groupName }
            if (row?.executable != true || !sessionState.gameValidated) {
                effectChannel.trySend(
                    GameEffect.Message(
                        if (row?.executable == false) GameMessage.UNSUPPORTED_CHEAT else GameMessage.SESSION_NOT_READY,
                        row?.validationDetail,
                        row?.unsupportedLine,
                    ),
                )
                return
            }
            synchronized(claimedExecutions) {
                if (!claimedExecutions.add(groupName)) return
            }
            republishGroups()
            viewModelScope.launch {
                try {
                    val report = session.executeGroup(group)
                    if (report.status != ExecutionStatus.Complete) {
                        effectChannel.trySend(
                            GameEffect.Message(
                                GameMessage.EXECUTION_FAILED,
                                report.error?.message ?: report.validationError?.toString(),
                                report.failureLine,
                            ),
                        )
                    }
                } catch (error: Throwable) {
                    showFailure(error)
                } finally {
                    synchronized(claimedExecutions) { claimedExecutions.remove(groupName) }
                    republishGroups()
                }
            }
        } else {
            setCheckedLocally(groupName, false)
            viewModelScope.launch {
                runCatching { session.uncheckGroup(groupName) }
                    .onFailure {
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
        viewModelScope.launch {
            runCatching { files.inspectZip(bytes) }
                .onSuccess { inspection ->
                    val identity = readyIdentity()
                    if (identity == null || identity.titleId != inspection.titleId || identity.buildId != inspection.buildId) {
                        showFailure(IllegalArgumentException("ZIP TID/BID does not match the current recognized game"))
                    } else {
                        effectChannel.trySend(GameEffect.ConfirmZipImport(inspection))
                    }
                }
                .onFailure { showFailure(it) }
        }
    }

    fun confirmZipImport(inspection: ZipInspection) {
        viewModelScope.launch {
            runCatching {
                val identity = requireNotNull(readyIdentity()) { "A validated game is required" }
                require(identity.titleId == inspection.titleId && identity.buildId == inspection.buildId) {
                    "ZIP TID/BID no longer matches the current recognized game"
                }
                files.importZip(inspection)
                displayedIdentity?.takeIf {
                    it.titleId == inspection.titleId && it.buildId == inspection.buildId
                }?.let { reloadLocal(it) }
            }.onSuccess {
                effectChannel.trySend(GameEffect.Message(GameMessage.IMPORT_COMPLETE))
            }.onFailure { showFailure(it) }
        }
    }

    fun onDownloadRequested() {
        val profile = selectedProfile()
        val identity = readyIdentity()
        if (profile == null || identity == null) {
            effectChannel.trySend(GameEffect.Message(GameMessage.SESSION_NOT_READY))
            return
        }
        runDownload(profile, identity, null)
    }

    fun confirmDownload(report: TransferReport.RequiresLocalOverwriteConfirmation) {
        val profile = selectedProfile()
        val identity = readyIdentity()
        if (profile == null || identity == null) return
        runDownload(profile, identity, report.confirmation)
    }

    fun discardDownload(report: TransferReport.RequiresLocalOverwriteConfirmation) {
        viewModelScope.launch { files.discardDownload(report.confirmation) }
    }

    fun onUploadRequested() {
        val profile = selectedProfile()
        val identity = readyIdentity()
        if (profile == null || identity == null) {
            effectChannel.trySend(GameEffect.Message(GameMessage.SESSION_NOT_READY))
            return
        }
        viewModelScope.launch {
            runCatching { files.previewUpload(profile, identity) }
                .onSuccess { effectChannel.trySend(GameEffect.ConfirmUpload(it)) }
                .onFailure { showFailure(it) }
        }
    }

    fun confirmUpload(preview: UploadPreview) = runUpload(preview.confirmation, null)

    fun confirmDirectUpload(upload: UploadConfirmation, direct: DirectOverwriteConfirmation) =
        runUpload(upload, direct)

    fun onShareZipRequested() {
        val identity = readyIdentity()
        if (identity == null) {
            effectChannel.trySend(GameEffect.Message(GameMessage.SESSION_NOT_READY))
            return
        }
        viewModelScope.launch {
            runCatching { files.notesExist(identity) }
                .onSuccess { exists ->
                    if (exists) exportZip(identity, false)
                    else effectChannel.trySend(GameEffect.ConfirmEmptyNotesShare)
                }
                .onFailure { showFailure(it) }
        }
    }

    fun currentIdentityForEditor(): GameIdentity? = readyIdentity()

    fun onEditorUnavailable() {
        effectChannel.trySend(GameEffect.Message(GameMessage.SESSION_NOT_READY))
    }

    fun onExternalFailure(error: Throwable) {
        showFailure(error)
    }

    fun confirmEmptyNotesShare() {
        readyIdentity()?.let { identity -> viewModelScope.launch { exportZip(identity, true) } }
    }

    fun onLocalFileSaved(identity: GameIdentity, file: CheatFile) {
        if (displayedIdentity?.titleId != identity.titleId || displayedIdentity?.buildId != identity.buildId) return
        displayedCheatFile = file
        localCheatOverride = file
        localOverrideIdentity = identity
        republishGroups()
    }

    private fun runDownload(
        profile: DeviceProfile,
        identity: GameIdentity,
        confirmation: DownloadOverwriteConfirmation?,
    ) {
        viewModelScope.launch {
            runCatching { files.download(profile, identity, confirmation) }
                .onSuccess { report ->
                    when (report) {
                        TransferReport.RemoteCheatMissing ->
                            effectChannel.trySend(GameEffect.Message(GameMessage.REMOTE_CHEAT_MISSING))
                        is TransferReport.RequiresLocalOverwriteConfirmation ->
                            effectChannel.trySend(GameEffect.ConfirmDownload(report))
                        is TransferReport.Downloaded -> {
                            reloadLocal(identity)
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

    private fun runUpload(upload: UploadConfirmation, direct: DirectOverwriteConfirmation?) {
        viewModelScope.launch {
            runCatching { files.upload(upload, direct) }
                .onSuccess { report ->
                    when (report) {
                        is TransferReport.RequiresDirectOverwriteConfirmation ->
                            effectChannel.trySend(GameEffect.ConfirmDirectUpload(upload, report.confirmation))
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

    private suspend fun exportZip(identity: GameIdentity, includeEmptyNotes: Boolean) {
        runCatching { files.exportZip(identity, includeEmptyNotes) }
            .onSuccess { bytes ->
                effectChannel.trySend(
                    GameEffect.Share(ShareArchive("${identity.titleId.hex}_${identity.buildId.hex}.zip", bytes)),
                )
            }
            .onFailure { showFailure(it) }
    }

    private suspend fun reloadLocal(identity: GameIdentity) {
        val editable = files.loadEditable(identity)
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
        CheatGroupUiState(
            name = group.name,
            checked = group.name in state.checkedGroups,
            executable = invalid == null && state.gameValidated,
            executing = group.name in state.executingGroups || synchronized(claimedExecutions) {
                group.name in claimedExecutions
            },
            unsupportedLine = invalid?.line,
            unsupportedOpcode = unsupported?.opcode?.let { "0x${it.toString(16).uppercase()}" },
            validationDetail = invalid?.toDisplayText(),
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

private fun CheatValidationError.toDisplayText(): String = when (this) {
    is CheatValidationError.UnsupportedOpcode -> "Line $line · opcode 0x${opcode.toString(16).uppercase()}"
    is CheatValidationError.UnsupportedForm -> "Line $line · $reason"
    is CheatValidationError.UnsupportedMemoryRegion -> "Line $line · memory region $region"
    is CheatValidationError.ArithmeticOverflow -> "Line $line · arithmetic overflow"
    is CheatValidationError.InstructionLimitExceeded -> "Line $line · instruction limit $limit"
    is CheatValidationError.IoLimitExceeded -> "Line $line · I/O limit $limitBytes"
}
