package com.nscheatmanager.app.ui.game

import com.nscheatmanager.app.cheats.parser.CheatFile
import com.nscheatmanager.app.cheats.parser.CheatGroup
import com.nscheatmanager.app.cheats.parser.EncodedInstruction
import com.nscheatmanager.app.cheats.vm.ExecutionReport
import com.nscheatmanager.app.cheats.vm.ExecutionStatus
import com.nscheatmanager.app.core.model.BuildId
import com.nscheatmanager.app.core.model.TitleId
import com.nscheatmanager.app.data.files.OverwriteImpact
import com.nscheatmanager.app.data.files.ZipInspection
import com.nscheatmanager.app.data.files.ZipInspectionEntry
import com.nscheatmanager.app.domain.ConnectionState
import com.nscheatmanager.app.domain.DeviceProfile
import com.nscheatmanager.app.domain.DeviceSessionState
import com.nscheatmanager.app.domain.DownloadOverwriteConfirmation
import com.nscheatmanager.app.domain.TransferReport
import com.nscheatmanager.app.domain.UploadConfirmation
import com.nscheatmanager.app.domain.UploadPreview
import com.nscheatmanager.app.protocol.sysbot.GameIdentity
import kotlinx.coroutines.CompletableDeferred
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

        viewModel.onCheatChecked("Key trigger", wasChecked = false, isChecked = true)
        advanceUntilIdle()
        assertTrue(session.executions.isEmpty())
    }

    @Test
    fun importPickerAndInspectionConfirmationAreOneShotAndNeverImportEarly() = runTest(dispatcher) {
        val files = FakeGameFileGateway()
        val session = FakeSessionGateway()
        val viewModel = GameViewModel(FakeDeviceStore(listOf(DEVICE), DEVICE.id), session, files)
        session.state.value = readyState(null)
        advanceUntilIdle()

        val picker = async { viewModel.effects.first() }
        viewModel.onImportZipRequested()
        assertTrue(picker.await() is GameEffect.OpenZipDocument)
        assertNull(withTimeoutOrNull(1) { viewModel.effects.first() })

        val confirmation = async { viewModel.effects.first() }
        viewModel.onZipDocument(byteArrayOf(1, 2, 3))
        advanceUntilIdle()
        val effect = confirmation.await() as GameEffect.ConfirmZipImport
        assertEquals(0, files.imports)

        viewModel.confirmZipImport(effect.inspection)
        advanceUntilIdle()
        assertEquals(1, files.imports)
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

        override suspend fun executeGroup(group: CheatGroup): ExecutionReport {
            executions += group.name
            executeRelease.await()
            state.value = state.value.copy(checkedGroups = state.value.checkedGroups + group.name)
            return ExecutionReport(ExecutionStatus.Complete, completedWrites = 1)
        }

        override suspend fun uncheckGroup(groupName: String) {
            unchecks += groupName
            state.value = state.value.copy(checkedGroups = state.value.checkedGroups - groupName)
        }

        override suspend fun close() = Unit
    }

    private class FakeGameFileGateway : GameFileGateway {
        var imports = 0
        private val inspection = ZipInspection(
            GAME.titleId,
            GAME.buildId,
            listOf(ZipInspectionEntry("atmosphere/contents/${GAME.titleId.hex}/cheats/${GAME.buildId.hex}.txt", 3)),
            1,
            OverwriteImpact(false, false),
            "test-token",
        )

        override suspend fun loadEditable(identity: GameIdentity) = EditableGameFiles("", "", false)
        override suspend fun saveEditable(identity: GameIdentity, cheatText: String, notesText: String) =
            CheatFile(emptyList(), emptyList())

        override suspend fun inspectZip(bytes: ByteArray): ZipInspection = inspection
        override suspend fun importZip(inspection: ZipInspection) { imports++ }
        override suspend fun exportZip(identity: GameIdentity, includeEmptyNotes: Boolean) = byteArrayOf()
        override suspend fun notesExist(identity: GameIdentity) = true
        override suspend fun download(
            profile: DeviceProfile,
            identity: GameIdentity,
            confirmation: DownloadOverwriteConfirmation?,
        ): TransferReport = TransferReport.RemoteCheatMissing

        override suspend fun discardDownload(confirmation: DownloadOverwriteConfirmation) = Unit
        override suspend fun previewUpload(profile: DeviceProfile, identity: GameIdentity) =
            UploadPreview(UploadConfirmation("upload"), 0, null)

        override suspend fun upload(
            confirmation: UploadConfirmation,
            direct: com.nscheatmanager.app.domain.DirectOverwriteConfirmation?,
        ): TransferReport = TransferReport.StaleLocalSnapshot
    }

    private companion object {
        val DEVICE = DeviceProfile("switch", "Living room", "192.168.1.35")
        val GAME = GameIdentity(
            TitleId.parse("0100F2C0115B6000"),
            BuildId.parse("A4A8D3E7F29C81A2"),
            0x1000u,
            0x8000u,
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

        fun readyState(file: CheatFile?, checked: Set<String> = emptySet()) = DeviceSessionState(
            device = DEVICE,
            connection = ConnectionState.Ready,
            game = GAME,
            gameValidated = true,
            cheatFile = file,
            cheatRelativePath = "atmosphere/contents/${GAME.titleId.hex}/cheats/${GAME.buildId.hex}.txt",
            checkedGroups = checked,
        )
    }
}
