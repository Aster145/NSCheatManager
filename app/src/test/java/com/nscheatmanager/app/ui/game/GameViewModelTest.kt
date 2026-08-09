package com.nscheatmanager.app.ui.game

import com.nscheatmanager.app.cheats.parser.CheatFile
import com.nscheatmanager.app.cheats.parser.CheatGroup
import com.nscheatmanager.app.cheats.parser.EncodedInstruction
import com.nscheatmanager.app.cheats.vm.ExecutionReport
import com.nscheatmanager.app.cheats.vm.ExecutionStatus
import com.nscheatmanager.app.cheats.vm.CheatValidationError
import com.nscheatmanager.app.core.model.BuildId
import com.nscheatmanager.app.core.model.TitleId
import com.nscheatmanager.app.data.files.OverwriteImpact
import com.nscheatmanager.app.data.files.ZipInspection
import com.nscheatmanager.app.data.files.ZipInspectionEntry
import com.nscheatmanager.app.domain.ConnectionState
import com.nscheatmanager.app.domain.DeviceProfile
import com.nscheatmanager.app.domain.DeviceSessionState
import com.nscheatmanager.app.domain.DownloadOverwriteConfirmation
import com.nscheatmanager.app.domain.DirectOverwriteConfirmation
import com.nscheatmanager.app.domain.GameOperationKey
import com.nscheatmanager.app.domain.StaleGameOperationException
import com.nscheatmanager.app.domain.TransferReport
import com.nscheatmanager.app.domain.UploadConfirmation
import com.nscheatmanager.app.domain.UploadPreview
import com.nscheatmanager.app.protocol.sysbot.GameIdentity
import com.nscheatmanager.app.protocol.ProtocolError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GameViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetMain() = Dispatchers.resetMain()

    @Test
    fun connectDelegatesOnceAndDoesNotRequestASecondRecognition() = runTest(dispatcher) {
        val session = FakeSessionGateway()
        val devices = FakeDeviceStore(listOf(DEVICE), DEVICE.id)
        val viewModel = GameViewModel(devices, session, FakeGameFileGateway())
        advanceUntilIdle()

        viewModel.onConnectionToggle()
        runCurrent()

        assertEquals(listOf(DEVICE), session.connects)
        assertEquals(0, session.manualRecognitions)
    }

    @Test
    fun readySessionLoadsGroupsAndMissingMirrorOffersImportAndDownload() = runTest(dispatcher) {
        val session = FakeSessionGateway()
        val viewModel = GameViewModel(FakeDeviceStore(listOf(DEVICE), DEVICE.id), session, FakeGameFileGateway())
        session.state.value = readyState(CheatFile(listOf(SUPPORTED), emptyList()))
        advanceUntilIdle()

        assertEquals(listOf("Write once"), viewModel.uiState.value.groups.map(CheatGroupUiState::name))
        assertFalse(viewModel.uiState.value.missingMirror)

        session.state.value = readyState(null)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.missingMirror)
        assertTrue(viewModel.uiState.value.canImport)
        assertTrue(viewModel.uiState.value.canDownload)
    }

    @Test
    fun falseToTrueExecutesOnceAndDuplicateTapCannotReplay() = runTest(dispatcher) {
        val release = CompletableDeferred<Unit>()
        val session = FakeSessionGateway(executeRelease = release)
        val viewModel = GameViewModel(FakeDeviceStore(listOf(DEVICE), DEVICE.id), session, FakeGameFileGateway())
        session.state.value = readyState(CheatFile(listOf(SUPPORTED), emptyList()))
        advanceUntilIdle()

        viewModel.onCheatChecked("Write once", wasChecked = false, isChecked = true)
        viewModel.onCheatChecked("Write once", wasChecked = false, isChecked = true)
        runCurrent()
        assertEquals(listOf("Write once"), session.executions)

        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, session.executions.size)
    }

    @Test
    fun completedExecutionRemainsLocallyCheckedAndClaimedUntilDelayedSessionAck() = runTest(dispatcher) {
        val session = FakeSessionGateway(acknowledgeExecution = false)
        val viewModel = GameViewModel(FakeDeviceStore(listOf(DEVICE), DEVICE.id), session, FakeGameFileGateway())
        session.state.value = readyState(CheatFile(listOf(SUPPORTED), emptyList()))
        advanceUntilIdle()

        viewModel.onCheatChecked("Write once", wasChecked = false, isChecked = true)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.groups.single().checked)

        viewModel.onCheatChecked("Write once", wasChecked = false, isChecked = true)
        advanceUntilIdle()

        assertEquals(listOf("Write once"), session.executions)
    }

    @Test
    fun switchingSessionDuringDownloadDiscardsOldCompletionBeforeReload() = runTest(dispatcher) {
        val release = CompletableDeferred<Unit>()
        val files = FakeGameFileGateway(downloadRelease = release)
        val session = FakeSessionGateway()
        val viewModel = GameViewModel(FakeDeviceStore(listOf(DEVICE), DEVICE.id), session, files)
        session.state.value = readyState(CheatFile(listOf(SUPPORTED), emptyList()), generation = 1L)
        advanceUntilIdle()

        viewModel.onDownloadRequested()
        runCurrent()
        session.state.value = readyState(
            file = CheatFile(emptyList(), emptyList()),
            game = GAME_B,
            generation = 2L,
        )
        runCurrent()
        release.complete(Unit)
        advanceUntilIdle()

        assertTrue(files.loaded.isEmpty())
        assertEquals(0, files.downloadPublishes)
        assertEquals(GAME_B.titleId.hex, viewModel.uiState.value.titleId)
    }

    @Test
    fun uncheckOnlyClearsPersistenceAndNeverExecutesMemory() = runTest(dispatcher) {
        val session = FakeSessionGateway()
        val viewModel = GameViewModel(FakeDeviceStore(listOf(DEVICE), DEVICE.id), session, FakeGameFileGateway())
        session.state.value = readyState(
            CheatFile(listOf(SUPPORTED), emptyList()),
            checked = setOf("Write once"),
        )
        advanceUntilIdle()

        viewModel.onCheatChecked("Write once", wasChecked = true, isChecked = false)
        advanceUntilIdle()

        assertEquals(listOf("Write once"), session.unchecks)
        assertTrue(session.executions.isEmpty())
    }

    @Test
    fun unsupportedOpcodeExposesExactSourceLineAndCannotExecute() = runTest(dispatcher) {
        val session = FakeSessionGateway()
        val viewModel = GameViewModel(FakeDeviceStore(listOf(DEVICE), DEVICE.id), session, FakeGameFileGateway())
        session.state.value = readyState(CheatFile(listOf(UNSUPPORTED), emptyList()))
        advanceUntilIdle()

        val row = viewModel.uiState.value.groups.single()
        assertFalse(row.executable)
        assertEquals(18, row.unsupportedLine)
        assertEquals("0x8", row.unsupportedOpcode)
        assertEquals(CheatDiagnosticKind.UnsupportedOpcode, row.diagnostic?.kind)

        viewModel.onCheatChecked("Key trigger", wasChecked = false, isChecked = true)
        advanceUntilIdle()
        assertTrue(session.executions.isEmpty())
    }

    @Test
    fun checkedGroupExposesPersistedLastSuccessfulExecutionTimestamp() = runTest(dispatcher) {
        val session = FakeSessionGateway()
        val viewModel = GameViewModel(FakeDeviceStore(listOf(DEVICE), DEVICE.id), session, FakeGameFileGateway())
        session.state.value = readyState(
            CheatFile(listOf(SUPPORTED), emptyList()),
            checked = setOf("Write once"),
            lastExecuted = mapOf("Write once" to 1_723_456_789_000L),
        )
        advanceUntilIdle()

        assertEquals(1_723_456_789_000L, viewModel.uiState.value.groups.single().lastExecutedAtEpochMillis)
    }

    @Test
    fun rejectedRuntimeValidationProducesStructuredDiagnosticWithoutRawToString() = runTest(dispatcher) {
        val session = FakeSessionGateway(
            executionReport = ExecutionReport(
                ExecutionStatus.Rejected,
                completedWrites = 0,
                failureLine = 18,
                validationError = CheatValidationError.UnsupportedOpcode(18, 0x8),
            ),
        )
        val viewModel = GameViewModel(FakeDeviceStore(listOf(DEVICE), DEVICE.id), session, FakeGameFileGateway())
        session.state.value = readyState(CheatFile(listOf(SUPPORTED), emptyList()))
        advanceUntilIdle()

        val effect = async { viewModel.effects.first() }
        viewModel.onCheatChecked("Write once", wasChecked = false, isChecked = true)
        advanceUntilIdle()
        val message = effect.await() as GameEffect.Message

        assertEquals(GameMessage.EXECUTION_FAILED, message.message)
        assertEquals(CheatDiagnosticKind.UnsupportedOpcode, message.diagnostic?.kind)
        assertEquals(18, message.diagnostic?.line)
        assertEquals("0x8", message.diagnostic?.opcode)
        assertNull(message.detail)
    }

    @Test
    fun zipInspectionConfirmationLivesInImmutableStateAndDuplicateConfirmImportsOnce() = runTest(dispatcher) {
        val files = FakeGameFileGateway()
        val session = FakeSessionGateway()
        val viewModel = GameViewModel(FakeDeviceStore(listOf(DEVICE), DEVICE.id), session, files)
        session.state.value = readyState(null)
        advanceUntilIdle()

        val picker = async { viewModel.effects.first() }
        viewModel.onImportZipRequested()
        assertTrue(picker.await() is GameEffect.OpenZipDocument)
        assertNull(withTimeoutOrNull(1) { viewModel.effects.first() })

        viewModel.onZipDocument(byteArrayOf(1, 2, 3))
        advanceUntilIdle()
        val confirmation = viewModel.uiState.value.pendingConfirmation as GameConfirmation.ZipImport
        assertEquals(0, files.imports)

        viewModel.confirmPending(confirmation.id)
        viewModel.confirmPending(confirmation.id)
        advanceUntilIdle()
        assertEquals(1, files.imports)
        assertNull(viewModel.uiState.value.pendingConfirmation)
    }

    @Test
    fun emptyNotesShareConfirmationSurvivesEffectCollectorReplacement() = runTest(dispatcher) {
        val files = FakeGameFileGateway(notesExist = false)
        val session = FakeSessionGateway()
        val viewModel = GameViewModel(FakeDeviceStore(listOf(DEVICE), DEVICE.id), session, files)
        session.state.value = readyState(CheatFile(listOf(SUPPORTED), emptyList()))
        advanceUntilIdle()

        viewModel.onShareZipRequested()
        advanceUntilIdle()

        val confirmation = viewModel.uiState.value.pendingConfirmation as GameConfirmation.EmptyNotesShare
        assertNull(withTimeoutOrNull(1) { viewModel.effects.first() })
        viewModel.dismissPending(confirmation.id)
        assertNull(viewModel.uiState.value.pendingConfirmation)
    }

    @Test
    fun downloadConfirmationSurvivesCollectorReplacementAndDuplicateConfirmPublishesOnce() = runTest(dispatcher) {
        val token = DownloadOverwriteConfirmation("download")
        val files = FakeGameFileGateway(
            downloadReports = ArrayDeque(
                listOf(
                    TransferReport.RequiresLocalOverwriteConfirmation(token, 10, 2),
                    TransferReport.Downloaded(
                        10,
                        2,
                        com.nscheatmanager.app.domain.NotesDownloadDisposition.DownloadedRemote,
                    ),
                ),
            ),
        )
        val session = FakeSessionGateway()
        val viewModel = GameViewModel(FakeDeviceStore(listOf(DEVICE), DEVICE.id), session, files)
        session.state.value = readyState(CheatFile(listOf(SUPPORTED), emptyList()))
        advanceUntilIdle()

        viewModel.onDownloadRequested()
        advanceUntilIdle()
        val pending = viewModel.uiState.value.pendingConfirmation as GameConfirmation.Download
        assertNull(withTimeoutOrNull(1) { viewModel.effects.first() })

        viewModel.confirmPending(pending.id)
        viewModel.confirmPending(pending.id)
        advanceUntilIdle()

        assertEquals(1, files.downloadPublishes)
        assertEquals(2, files.downloadCalls)
        assertNull(viewModel.uiState.value.pendingConfirmation)
    }

    @Test
    fun changingSessionDiscardsPendingDownloadConfirmationAndItsStagingToken() = runTest(dispatcher) {
        val files = FakeGameFileGateway(
            downloadReports = ArrayDeque(
                listOf(
                    TransferReport.RequiresLocalOverwriteConfirmation(
                        DownloadOverwriteConfirmation("download"),
                        10,
                        null,
                    ),
                ),
            ),
        )
        val session = FakeSessionGateway()
        val viewModel = GameViewModel(FakeDeviceStore(listOf(DEVICE), DEVICE.id), session, files)
        session.state.value = readyState(CheatFile(listOf(SUPPORTED), emptyList()), generation = 1L)
        advanceUntilIdle()
        viewModel.onDownloadRequested()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.pendingConfirmation is GameConfirmation.Download)

        session.state.value = readyState(CheatFile(emptyList(), emptyList()), game = GAME_B, generation = 2L)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.pendingConfirmation)
        assertEquals(1, files.discardDownloads)
    }

    @Test
    fun uploadAndDirectConfirmationsEachConsumeStableIdExactlyOnce() = runTest(dispatcher) {
        val files = FakeGameFileGateway(
            uploadReports = ArrayDeque(
                listOf(
                    TransferReport.RequiresDirectOverwriteConfirmation(DirectOverwriteConfirmation("direct")),
                    TransferReport.Uploaded(10, 2, false),
                ),
            ),
        )
        val session = FakeSessionGateway()
        val viewModel = GameViewModel(FakeDeviceStore(listOf(DEVICE), DEVICE.id), session, files)
        session.state.value = readyState(CheatFile(listOf(SUPPORTED), emptyList()))
        advanceUntilIdle()

        viewModel.onUploadRequested()
        advanceUntilIdle()
        val upload = viewModel.uiState.value.pendingConfirmation as GameConfirmation.Upload
        viewModel.confirmPending(upload.id)
        viewModel.confirmPending(upload.id)
        advanceUntilIdle()
        val direct = viewModel.uiState.value.pendingConfirmation as GameConfirmation.DirectUpload
        assertEquals(1, files.uploadCalls)

        viewModel.confirmPending(direct.id)
        viewModel.confirmPending(direct.id)
        advanceUntilIdle()
        assertEquals(2, files.uploadCalls)
        assertNull(viewModel.uiState.value.pendingConfirmation)
    }

    private class FakeDeviceStore(
        initialDevices: List<DeviceProfile>,
        initialSelected: String?,
    ) : GameDeviceStore {
        override val devices: Flow<List<DeviceProfile>> = MutableStateFlow(initialDevices)
        override val selectedDeviceId: Flow<String?> = MutableStateFlow(initialSelected)
        val selections = mutableListOf<String>()

        override suspend fun selectDevice(deviceId: String) {
            selections += deviceId
        }
    }

    private class FakeSessionGateway(
        private val executeRelease: CompletableDeferred<Unit> = CompletableDeferred(Unit),
        private val acknowledgeExecution: Boolean = true,
        private val executionReport: ExecutionReport = ExecutionReport(ExecutionStatus.Complete, completedWrites = 1),
    ) : GameSessionGateway {
        override val state = MutableStateFlow(DeviceSessionState())
        val connects = mutableListOf<DeviceProfile>()
        var manualRecognitions = 0
        val executions = mutableListOf<String>()
        val unchecks = mutableListOf<String>()

        override fun connectAndRecognize(device: DeviceProfile) {
            connects += device
        }

        override fun switchDevice(device: DeviceProfile) = Unit
        override fun disconnect() = Unit
        override fun recognizeAgain() { manualRecognitions++ }
        override suspend fun detachDmnt() = Unit

        override fun currentOperationKey(): GameOperationKey? = state.value.operationKey

        override fun requireCurrentOperationKey(expected: GameOperationKey) {
            if (state.value.operationKey != expected) throw StaleGameOperationException()
        }

        override suspend fun executeGroup(expected: GameOperationKey, group: CheatGroup): ExecutionReport {
            requireCurrentOperationKey(expected)
            executions += group.name
            executeRelease.await()
            if (acknowledgeExecution) {
                state.value = state.value.copy(checkedGroups = state.value.checkedGroups + group.name)
            }
            return executionReport
        }

        override suspend fun uncheckGroup(expected: GameOperationKey, groupName: String) {
            requireCurrentOperationKey(expected)
            unchecks += groupName
            state.value = state.value.copy(checkedGroups = state.value.checkedGroups - groupName)
        }

        override suspend fun close() = Unit
    }

    private class FakeGameFileGateway(
        private val downloadRelease: CompletableDeferred<Unit> = CompletableDeferred(Unit),
        private val notesExist: Boolean = true,
        private val downloadReports: ArrayDeque<TransferReport> = ArrayDeque(
            listOf(
                TransferReport.Downloaded(
                    1,
                    null,
                    com.nscheatmanager.app.domain.NotesDownloadDisposition.MissingRemoteNoLocalFile,
                ),
            ),
        ),
        private val uploadReports: ArrayDeque<TransferReport> = ArrayDeque(listOf(TransferReport.StaleLocalSnapshot)),
    ) : GameFileGateway {
        var imports = 0
        var downloadPublishes = 0
        var downloadCalls = 0
        var uploadCalls = 0
        var discardDownloads = 0
        val loaded = mutableListOf<GameIdentity>()
        private val inspection = ZipInspection(
            GAME.titleId,
            GAME.buildId,
            listOf(ZipInspectionEntry("atmosphere/contents/${GAME.titleId.hex}/cheats/${GAME.buildId.hex}.txt", 3)),
            1,
            OverwriteImpact(false, false),
            "test-token",
        )

        override suspend fun loadEditable(identity: GameIdentity, checkpoint: () -> Unit) = EditableGameFiles("", "", false).also {
            checkpoint()
            loaded += identity
        }
        override suspend fun saveEditable(identity: GameIdentity, cheatText: String, notesText: String, checkpoint: () -> Unit) =
            CheatFile(emptyList(), emptyList())

        override suspend fun inspectZip(bytes: ByteArray): ZipInspection = inspection
        override suspend fun importZip(inspection: ZipInspection, checkpoint: () -> Unit) { checkpoint(); imports++ }
        override suspend fun exportZip(identity: GameIdentity, includeEmptyNotes: Boolean, checkpoint: () -> Unit) = byteArrayOf().also { checkpoint() }
        override suspend fun notesExist(identity: GameIdentity, checkpoint: () -> Unit) = notesExist.also { checkpoint() }
        override suspend fun download(
            profile: DeviceProfile,
            identity: GameIdentity,
            confirmation: DownloadOverwriteConfirmation?,
            checkpoint: () -> Unit,
        ): TransferReport {
            downloadCalls++
            downloadRelease.await()
            val report = downloadReports.removeFirst()
            if (report is TransferReport.Downloaded) {
                checkpoint()
                downloadPublishes++
            }
            return report
        }

        override suspend fun discardDownload(confirmation: DownloadOverwriteConfirmation) {
            discardDownloads++
        }
        override suspend fun previewUpload(profile: DeviceProfile, identity: GameIdentity, checkpoint: () -> Unit) =
            UploadPreview(UploadConfirmation("upload"), 0, null).also { checkpoint() }

        override suspend fun upload(
            confirmation: UploadConfirmation,
            direct: com.nscheatmanager.app.domain.DirectOverwriteConfirmation?,
            checkpoint: () -> Unit,
        ): TransferReport {
            checkpoint()
            uploadCalls++
            return uploadReports.removeFirst()
        }
    }

    @Test
    fun transportFailureUsesCentralMessageAndNeverExposesExceptionText() = runTest(dispatcher) {
        val viewModel = GameViewModel(FakeDeviceStore(listOf(DEVICE), DEVICE.id), FakeSessionGateway(), FakeGameFileGateway())
        val effect = async(start = CoroutineStart.UNDISPATCHED) { viewModel.effects.first() }
        viewModel.onExternalFailure(ProtocolError.MalformedResponse("SECRET_PAYLOAD"))

        val message = effect.await() as GameEffect.UserError
        assertEquals(com.nscheatmanager.app.R.string.error_malformed_response, message.message.messageRes)
        assertFalse(message.message.detail.toString().contains("SECRET_PAYLOAD"))
    }

    private companion object {
        val DEVICE = DeviceProfile("switch", "Living room", "192.168.1.35")
        val GAME = GameIdentity(
            TitleId.parse("0100F2C0115B6000"),
            BuildId.parse("A4A8D3E7F29C81A2"),
            0x1000u,
            0x8000u,
        )
        val GAME_B = GameIdentity(
            TitleId.parse("0100000000000002"),
            BuildId.parse("BBBBBBBBBBBBBBBB"),
            0x2000u,
            0x9000u,
        )
        val SUPPORTED = CheatGroup(
            "Write once",
            listOf(EncodedInstruction(listOf(0x04000000u, 0x20u, 1u), 2, "04000000 00000020 00000001")),
            1,
        )
        val UNSUPPORTED = CheatGroup(
            "Key trigger",
            listOf(EncodedInstruction(listOf(0x80000001u), 18, "80000001")),
            17,
        )

        fun readyState(
            file: CheatFile?,
            checked: Set<String> = emptySet(),
            game: GameIdentity = GAME,
            generation: Long = 1L,
            lastExecuted: Map<String, Long> = emptyMap(),
        ) = DeviceSessionState(
            device = DEVICE,
            connection = ConnectionState.Ready,
            game = game,
            gameValidated = true,
            cheatFile = file,
            cheatRelativePath = "atmosphere/contents/${GAME.titleId.hex}/cheats/${GAME.buildId.hex}.txt",
            checkedGroups = checked,
            generation = generation,
            lastExecutedAtEpochMillis = lastExecuted,
        )
    }
}
