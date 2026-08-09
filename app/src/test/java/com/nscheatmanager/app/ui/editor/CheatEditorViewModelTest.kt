package com.nscheatmanager.app.ui.editor

import com.nscheatmanager.app.cheats.parser.CheatFile
import com.nscheatmanager.app.cheats.parser.CheatParseDiagnosticKind
import com.nscheatmanager.app.core.model.BuildId
import com.nscheatmanager.app.core.model.TitleId
import com.nscheatmanager.app.data.files.ZipInspection
import com.nscheatmanager.app.data.files.EditorDraft
import com.nscheatmanager.app.data.files.FileEditorDraftStore
import com.nscheatmanager.app.domain.DeviceProfile
import com.nscheatmanager.app.domain.GameOperationKey
import com.nscheatmanager.app.domain.DownloadOverwriteConfirmation
import com.nscheatmanager.app.domain.TransferReport
import com.nscheatmanager.app.domain.UploadConfirmation
import com.nscheatmanager.app.domain.UploadPreview
import com.nscheatmanager.app.protocol.sysbot.GameIdentity
import com.nscheatmanager.app.ui.game.EditableGameFiles
import com.nscheatmanager.app.ui.game.GameFileGateway
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.LinkOption

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

        assertEquals(2, viewModel.uiState.value.parseDiagnostic?.line)
        assertEquals(CheatParseDiagnosticKind.InvalidInstructionWord, viewModel.uiState.value.parseDiagnostic?.kind)
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
    fun dirtyCloseAndPendingRouteRemainInStateUntilExplicitAck() = runTest(dispatcher) {
        val viewModel = CheatEditorViewModel(FakeEditorGateway("[Valid]\n04000000 00000020 00000001\n", ""))
        viewModel.open(GAME)
        advanceUntilIdle()
        viewModel.updateNotesText("dirty")
        viewModel.requestClose("settings")
        val confirmation = requireNotNull(viewModel.uiState.value.pendingDiscard)
        assertTrue(viewModel.uiState.value.isOpen)

        viewModel.confirmDiscard(confirmation.id)
        assertFalse(viewModel.uiState.value.isOpen)
        assertEquals("settings", viewModel.uiState.value.pendingNavigationRoute)
        viewModel.acknowledgeNavigation("settings")
        assertNull(viewModel.uiState.value.pendingNavigationRoute)
    }

    @Test
    fun openingAnotherGameCancelsAndDiscardsAnOutOfOrderOlderLoad() = runTest(dispatcher) {
        val firstRelease = CompletableDeferred<Unit>()
        val secondRelease = CompletableDeferred<Unit>()
        val gateway = OutOfOrderEditorGateway(firstRelease, secondRelease)
        val viewModel = CheatEditorViewModel(gateway)

        viewModel.open(GAME)
        runCurrent()
        viewModel.open(GAME_B)
        runCurrent()
        secondRelease.complete(Unit)
        runCurrent()
        firstRelease.complete(Unit)
        advanceUntilIdle()

        assertEquals(GAME_B, viewModel.uiState.value.identity)
        assertEquals("[Second]\n", viewModel.uiState.value.cheatText)
    }

    @Test
    fun dirtyTextAndDiscardRouteRestoreFromSavedStateHandle() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val draftStore = FileEditorDraftStore(Files.createTempDirectory("editor-draft-restore-"))
        val gateway = FakeEditorGateway("[Valid]\n04000000 00000020 00000001\n", "")
        val first = CheatEditorViewModel(gateway, savedStateHandle = handle, draftStore = draftStore)
        first.open(GAME)
        advanceUntilIdle()
        val nearMaximum = "n".repeat(FileEditorDraftStore.DEFAULT_MAX_TEXT_BYTES - 1)
        first.updateNotesText(nearMaximum)
        first.requestClose("about")
        val pendingId = requireNotNull(first.uiState.value.pendingDiscard).id
        val serializedFootprint = handle.keys().sumOf { key ->
            key.length + (handle.get<Any?>(key)?.toString()?.length ?: 0)
        }
        assertTrue("SavedState must remain Binder-safe, was $serializedFootprint chars", serializedFootprint < 1_024)
        assertTrue(handle.keys().none { key -> handle.get<Any?>(key) == nearMaximum })

        val restored = CheatEditorViewModel(gateway, savedStateHandle = handle, draftStore = draftStore)

        assertEquals(nearMaximum, restored.uiState.value.notesText)
        assertEquals(pendingId, restored.uiState.value.pendingDiscard?.id)
        assertEquals("about", restored.uiState.value.pendingDiscard?.route)
    }

    @Test
    fun draftStoreBoundsTokensContentExpiryAndMetadataIntegrity() {
        val root = Files.createTempDirectory("editor-draft-security-")
        var now = 1_000L
        val store = FileEditorDraftStore(root, clockMillis = { now }, expiryMillis = 100L)
        val key = GameOperationKey("switch", GAME.titleId, GAME.buildId, 7L)
        val token = store.save(
            null,
            EditorDraft(GAME, key, "cheat", "notes", "old cheat", "old notes"),
        )
        assertEquals("notes", store.load(token)?.notesText)
        assertThrows(IllegalArgumentException::class.java) { store.load("../outside") }
        assertThrows(IllegalArgumentException::class.java) {
            store.save(
                token,
                EditorDraft(
                    GAME,
                    key,
                    "x".repeat(FileEditorDraftStore.DEFAULT_MAX_TEXT_BYTES + 1),
                    "",
                    "",
                    "",
                ),
            )
        }

        val draftPath = Files.list(root).use { paths ->
            paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.findFirst().orElseThrow()
        }
        val corrupted = Files.readAllBytes(draftPath)
        corrupted[corrupted.lastIndex / 2] = (corrupted[corrupted.lastIndex / 2].toInt() xor 0x01).toByte()
        Files.write(draftPath, corrupted)
        assertThrows(IllegalArgumentException::class.java) { store.load(token) }

        val fresh = store.save(null, EditorDraft(GAME, key, "fresh", "", "fresh", ""))
        now += 101L
        store.cleanupExpired()
        assertNull(store.load(fresh))
    }

    @Test
    fun draftStoreRejectsSymlinkEntryWithoutTouchingReferent() {
        val root = Files.createTempDirectory("editor-draft-link-")
        val outside = Files.createTempFile("editor-draft-outside-", ".bin")
        Files.write(outside, "sentinel".toByteArray())
        val token = "11111111-1111-1111-1111-111111111111"
        try {
            Files.createSymbolicLink(root.resolve("$token.draft"), outside)
        } catch (error: Exception) {
            org.junit.Assume.assumeNoException("Symbolic links unavailable", error)
        }
        val store = FileEditorDraftStore(root)

        assertThrows(IllegalArgumentException::class.java) { store.load(token) }
        assertEquals("sentinel", String(Files.readAllBytes(outside)))
    }

    private class OutOfOrderEditorGateway(
        private val firstRelease: CompletableDeferred<Unit>,
        private val secondRelease: CompletableDeferred<Unit>,
    ) : FakeEditorGateway("", "") {
        override suspend fun loadEditable(identity: GameIdentity, checkpoint: () -> Unit): EditableGameFiles {
            checkpoint()
            withContext(NonCancellable) {
                if (identity == GAME) firstRelease.await() else secondRelease.await()
            }
            return EditableGameFiles(if (identity == GAME) "[First]\n" else "[Second]\n", "", false)
        }
    }

    private open class FakeEditorGateway(
        private val cheat: String,
        private val notes: String,
    ) : GameFileGateway {
        var saves = 0
        var savedNotes = ""
        override suspend fun loadEditable(identity: GameIdentity, checkpoint: () -> Unit) =
            EditableGameFiles(cheat, notes, true).also { checkpoint() }
        override suspend fun saveEditable(
            identity: GameIdentity,
            cheatText: String,
            notesText: String,
            checkpoint: () -> Unit,
        ): CheatFile {
            checkpoint()
            saves++
            savedNotes = notesText
            return com.nscheatmanager.app.cheats.parser.CheatFileParser().parse(cheatText)
        }
        override suspend fun inspectZip(bytes: ByteArray): ZipInspection = error("unused")
        override suspend fun importZip(inspection: ZipInspection, checkpoint: () -> Unit) = Unit
        override suspend fun exportZip(identity: GameIdentity, includeEmptyNotes: Boolean, checkpoint: () -> Unit) = error("unused")
        override suspend fun notesExist(identity: GameIdentity, checkpoint: () -> Unit) = true
        override suspend fun download(profile: DeviceProfile, identity: GameIdentity, confirmation: DownloadOverwriteConfirmation?, checkpoint: () -> Unit): TransferReport = error("unused")
        override suspend fun discardDownload(confirmation: DownloadOverwriteConfirmation) = Unit
        override suspend fun previewUpload(profile: DeviceProfile, identity: GameIdentity, checkpoint: () -> Unit): UploadPreview = error("unused")
        override suspend fun upload(confirmation: UploadConfirmation, direct: com.nscheatmanager.app.domain.DirectOverwriteConfirmation?, checkpoint: () -> Unit): TransferReport = error("unused")
    }

    private companion object {
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
    }
}
