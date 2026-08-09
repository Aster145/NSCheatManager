package com.nscheatmanager.app.ui.editor

import com.nscheatmanager.app.cheats.parser.CheatFile
import com.nscheatmanager.app.core.model.BuildId
import com.nscheatmanager.app.core.model.TitleId
import com.nscheatmanager.app.data.files.ZipInspection
import com.nscheatmanager.app.domain.DeviceProfile
import com.nscheatmanager.app.domain.DownloadOverwriteConfirmation
import com.nscheatmanager.app.domain.TransferReport
import com.nscheatmanager.app.domain.UploadConfirmation
import com.nscheatmanager.app.domain.UploadPreview
import com.nscheatmanager.app.protocol.sysbot.GameIdentity
import com.nscheatmanager.app.ui.game.EditableGameFiles
import com.nscheatmanager.app.ui.game.GameFileGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CheatEditorViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setMain() = Dispatchers.setMain(dispatcher)
    @After fun resetMain() = Dispatchers.resetMain()

    @Test
    fun openPreservesRawTextsAndTabsUseExactBuildPaths() = runTest(dispatcher) {
        val gateway = FakeEditorGateway("[Raw]\r\n04000000 00000020 00000001\r\n", "note\r\n")
        val viewModel = CheatEditorViewModel(gateway)

        viewModel.open(GAME)
        advanceUntilIdle()

        assertEquals("[Raw]\r\n04000000 00000020 00000001\r\n", viewModel.uiState.value.cheatText)
        assertEquals("note\r\n", viewModel.uiState.value.notesText)
        assertEquals("${GAME.buildId.hex}.txt", viewModel.uiState.value.cheatTabLabel)
        assertEquals("${GAME.buildId.hex}/notes.txt", viewModel.uiState.value.notesTabLabel)
    }

    @Test
    fun invalidCheatReportsLineAndNeverReplacesMirror() = runTest(dispatcher) {
        val gateway = FakeEditorGateway("[Valid]\n04000000 00000020 00000001\n", "")
        val viewModel = CheatEditorViewModel(gateway)
        viewModel.open(GAME)
        advanceUntilIdle()
        viewModel.updateCheatText("[Broken]\nXYZ")

        viewModel.save()
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.validationLine)
        assertEquals(0, gateway.saves)
        assertTrue(viewModel.uiState.value.isOpen)
    }

    @Test
    fun validSaveUsesGatewayOnceAndClosesEditMode() = runTest(dispatcher) {
        val gateway = FakeEditorGateway("[Valid]\n04000000 00000020 00000001\n", "")
        val viewModel = CheatEditorViewModel(gateway)
        viewModel.open(GAME)
        advanceUntilIdle()
        viewModel.updateNotesText("preserved notes")

        viewModel.save()
        advanceUntilIdle()

        assertEquals(1, gateway.saves)
        assertFalse(viewModel.uiState.value.isOpen)
        assertEquals("preserved notes", gateway.savedNotes)
    }

    @Test
    fun dirtyCloseRequiresOneShotConfirmationBeforeDiscarding() = runTest(dispatcher) {
        val viewModel = CheatEditorViewModel(FakeEditorGateway("[Valid]\n04000000 00000020 00000001\n", ""))
        viewModel.open(GAME)
        advanceUntilIdle()
        viewModel.updateNotesText("dirty")
        val confirmation = async { viewModel.effects.first() }

        viewModel.requestClose()
        assertTrue(confirmation.await() is EditorEffect.ConfirmDiscard)
        assertTrue(viewModel.uiState.value.isOpen)

        viewModel.confirmDiscard()
        assertFalse(viewModel.uiState.value.isOpen)
    }

    private class FakeEditorGateway(
        private val cheat: String,
        private val notes: String,
    ) : GameFileGateway {
        var saves = 0
        var savedNotes = ""
        override suspend fun loadEditable(identity: GameIdentity) = EditableGameFiles(cheat, notes, true)
        override suspend fun saveEditable(identity: GameIdentity, cheatText: String, notesText: String): CheatFile {
            saves++
            savedNotes = notesText
            return com.nscheatmanager.app.cheats.parser.CheatFileParser().parse(cheatText)
        }
        override suspend fun inspectZip(bytes: ByteArray): ZipInspection = error("unused")
        override suspend fun importZip(inspection: ZipInspection) = Unit
        override suspend fun exportZip(identity: GameIdentity, includeEmptyNotes: Boolean) = error("unused")
        override suspend fun notesExist(identity: GameIdentity) = true
        override suspend fun download(profile: DeviceProfile, identity: GameIdentity, confirmation: DownloadOverwriteConfirmation?): TransferReport = error("unused")
        override suspend fun discardDownload(confirmation: DownloadOverwriteConfirmation) = Unit
        override suspend fun previewUpload(profile: DeviceProfile, identity: GameIdentity): UploadPreview = error("unused")
        override suspend fun upload(confirmation: UploadConfirmation, direct: com.nscheatmanager.app.domain.DirectOverwriteConfirmation?): TransferReport = error("unused")
    }

    private companion object {
        val GAME = GameIdentity(
            TitleId.parse("0100F2C0115B6000"),
            BuildId.parse("A4A8D3E7F29C81A2"),
            0x1000u,
            0x8000u,
        )
    }
}
