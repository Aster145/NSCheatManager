package com.nscheatmanager.app.ui.editor

import com.nscheatmanager.app.cheats.parser.CheatFile
import com.nscheatmanager.app.cheats.parser.CheatParseDiagnosticKind
import com.nscheatmanager.app.core.model.BuildId
import com.nscheatmanager.app.core.model.TitleId
import com.nscheatmanager.app.data.files.ZipInspection
import com.nscheatmanager.app.data.files.EditorDraft
import com.nscheatmanager.app.data.files.EditorDraftCleanupLimits
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
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
import java.nio.file.attribute.FileTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

@OptIn(ExperimentalCoroutinesApi::class)
class CheatEditorViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setMain() = Dispatchers.setMain(dispatcher)
    @After fun resetMain() = Dispatchers.resetMain()

    @Test
    fun openPreservesRawTextsAndTabsUseExactBuildPaths() = runTest(dispatcher) {
        val gateway = FakeEditorGateway("[Raw]\r\n04000000 00000020 00000001\r\n", "note\r\n")
        val viewModel = CheatEditorViewModel(gateway, ioDispatcher = dispatcher, injectedScope = backgroundScope)

        viewModel.open(GAME)
        runCurrent()

        assertEquals("[Raw]\r\n04000000 00000020 00000001\r\n", viewModel.uiState.value.cheatText)
        assertEquals("note\r\n", viewModel.uiState.value.notesText)
        assertEquals("${GAME.buildId.hex}.txt", viewModel.uiState.value.cheatTabLabel)
        assertEquals("${GAME.buildId.hex}/notes.txt", viewModel.uiState.value.notesTabLabel)
    }

    @Test
    fun invalidCheatReportsLineAndNeverReplacesMirror() = runTest(dispatcher) {
        val gateway = FakeEditorGateway("[Valid]\n04000000 00000020 00000001\n", "")
        val viewModel = CheatEditorViewModel(gateway, ioDispatcher = dispatcher, injectedScope = backgroundScope)
        viewModel.open(GAME)
        runCurrent()
        viewModel.updateCheatText("[Broken]\nXYZ")

        viewModel.save()
        runCurrent()

        assertEquals(2, viewModel.uiState.value.parseDiagnostic?.line)
        assertEquals(CheatParseDiagnosticKind.InvalidInstructionWord, viewModel.uiState.value.parseDiagnostic?.kind)
        assertEquals(0, gateway.saves)
        assertTrue(viewModel.uiState.value.isOpen)
    }

    @Test
    fun validSaveUsesGatewayOnceAndClosesEditMode() = runTest(dispatcher) {
        val gateway = FakeEditorGateway("[Valid]\n04000000 00000020 00000001\n", "")
        val viewModel = CheatEditorViewModel(gateway, ioDispatcher = dispatcher, injectedScope = backgroundScope)
        viewModel.open(GAME)
        runCurrent()
        viewModel.updateNotesText("preserved notes")

        viewModel.save()
        runCurrent()

        assertEquals(1, gateway.saves)
        assertFalse(viewModel.uiState.value.isOpen)
        assertEquals("preserved notes", gateway.savedNotes)
    }

    @Test
    fun dirtyCloseAndPendingRouteRemainInStateUntilExplicitAck() = runTest(dispatcher) {
        val viewModel = CheatEditorViewModel(
            FakeEditorGateway("[Valid]\n04000000 00000020 00000001\n", ""),
            ioDispatcher = dispatcher,
            injectedScope = backgroundScope,
        )
        viewModel.open(GAME)
        runCurrent()
        viewModel.updateNotesText("dirty")
        viewModel.requestClose("settings")
        val confirmation = requireNotNull(viewModel.uiState.value.pendingDiscard)
        assertTrue(viewModel.uiState.value.isOpen)

        viewModel.confirmDiscard(confirmation.id)
        runCurrent()
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
        val viewModel = CheatEditorViewModel(gateway, ioDispatcher = dispatcher, injectedScope = backgroundScope)

        viewModel.open(GAME)
        runCurrent()
        viewModel.open(GAME_B)
        runCurrent()
        secondRelease.complete(Unit)
        runCurrent()
        firstRelease.complete(Unit)
        runCurrent()

        assertEquals(GAME_B, viewModel.uiState.value.identity)
        assertEquals("[Second]\n", viewModel.uiState.value.cheatText)
    }

    @Test
    fun dirtyTextAndDiscardRouteRestoreFromSavedStateHandle() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val draftStore = FileEditorDraftStore(Files.createTempDirectory("editor-draft-restore-"))
        val gateway = FakeEditorGateway("[Valid]\n04000000 00000020 00000001\n", "")
        val first = CheatEditorViewModel(
            gateway,
            savedStateHandle = handle,
            draftStore = draftStore,
            ioDispatcher = dispatcher,
            injectedScope = backgroundScope,
        )
        first.open(GAME)
        runCurrent()
        val nearMaximum = "n".repeat(FileEditorDraftStore.DEFAULT_MAX_TEXT_BYTES - 1)
        first.updateNotesText(nearMaximum)
        first.requestClose("about")
        val pendingId = requireNotNull(first.uiState.value.pendingDiscard).id
        assertTrue(first.flushLatestDraft())
        val serializedFootprint = handle.keys().sumOf { key ->
            key.length + (handle.get<Any?>(key)?.toString()?.length ?: 0)
        }
        assertTrue("SavedState must remain Binder-safe, was $serializedFootprint chars", serializedFootprint < 1_024)
        assertTrue(handle.keys().none { key -> handle.get<Any?>(key) == nearMaximum })

        val restored = CheatEditorViewModel(
            gateway,
            savedStateHandle = handle,
            draftStore = draftStore,
            ioDispatcher = dispatcher,
            injectedScope = backgroundScope,
        )
        runCurrent()

        assertEquals(nearMaximum, restored.uiState.value.notesText)
        assertEquals(pendingId, restored.uiState.value.pendingDiscard?.id)
        assertEquals("about", restored.uiState.value.pendingDiscard?.route)
    }

    @Test
    fun restoreAndCleanupAreAsyncAndNeverUseTheMainDispatcher() = runTest(dispatcher) {
        val handle = restoredHandle()
        val mainThread = Thread.currentThread()
        val store = ThreadRecordingDraftStore(
            EditorDraft(
                GAME,
                GameOperationKey("switch", GAME.titleId, GAME.buildId, 7L),
                "[Restored]\n",
                "notes",
                "[Restored]\n",
                "notes",
            ),
        )
        val io = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val viewModel = CheatEditorViewModel(
                FakeEditorGateway("", ""),
                savedStateHandle = handle,
                draftStore = store,
                ioDispatcher = io,
                injectedScope = backgroundScope,
            )

            assertTrue(viewModel.uiState.value.isLoading)
            assertTrue(store.threads.isEmpty())
            runCurrent()
            store.loadFinished.await()
            withContext(io) { Unit }
            runCurrent()

            assertEquals("notes", viewModel.uiState.value.notesText)
            assertTrue(store.threads.isNotEmpty())
            assertTrue(store.threads.none { it === mainThread })
        } finally {
            io.close()
        }
    }

    @Test
    fun slowOldDraftWriteCannotBlockCallbacksOrOverwriteTheLatestEdit() = runTest(dispatcher) {
        val store = BlockingDraftStore()
        val io = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val viewModel = CheatEditorViewModel(
                FakeEditorGateway("[Valid]\n04000000 00000020 00000001\n", "old"),
                draftStore = store,
                ioDispatcher = io,
                draftDebounceMillis = 100L,
                injectedScope = backgroundScope,
            )
            viewModel.open(GAME)
            runCurrent()
            store.blockNextSave = true
            val oldFlush = async(Dispatchers.Default) { viewModel.flushLatestDraft() }
            store.saveStarted.await()

            viewModel.updateNotesText("latest")
            assertEquals("latest", viewModel.uiState.value.notesText)

            store.releaseSave.countDown()
            assertTrue(oldFlush.await())
            assertTrue(withContext(Dispatchers.Default) { viewModel.flushLatestDraft() })
            assertEquals("latest", store.savedDrafts.last().notesText)
        } finally {
            store.releaseSave.countDown()
            io.close()
        }
    }

    @Test
    fun rapidEditsCoalesceAndFlushAndCloseLeavesNoDetachedWrite() = runTest(dispatcher) {
        val store = BlockingDraftStore()
        val viewModel = CheatEditorViewModel(
            FakeEditorGateway("[Valid]\n04000000 00000020 00000001\n", "old"),
            draftStore = store,
            ioDispatcher = dispatcher,
            draftDebounceMillis = 100L,
            injectedScope = backgroundScope,
        )
        viewModel.open(GAME)
        runCurrent()

        viewModel.updateNotesText("one")
        viewModel.updateNotesText("two")
        viewModel.selectTab(EditorTab.Notes)
        viewModel.updateNotesText("latest")
        advanceTimeBy(99L)
        assertTrue(store.savedDrafts.isEmpty())
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(1, store.savedDrafts.size)
        assertEquals("latest", store.savedDrafts.single().notesText)

        viewModel.updateNotesText("flushed")
        assertTrue(viewModel.flushAndClose())
        val writesAtClose = store.savedDrafts.size
        assertEquals("flushed", store.savedDrafts.last().notesText)
        assertFalse(viewModel.uiState.value.isOpen)
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(writesAtClose, store.savedDrafts.size)
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
    fun constructingFileDraftStorePerformsNoFileSystemIo() {
        val parent = Files.createTempDirectory("editor-draft-lazy-root-")
        val root = parent.resolve("drafts")

        FileEditorDraftStore(root)

        assertFalse(Files.exists(root, LinkOption.NOFOLLOW_LINKS))
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

    @Test
    fun cleanupRemovesStaleTemporaryMalformedOversizeAndExpiredFilesWithoutFollowingLinks() {
        val root = Files.createTempDirectory("editor-draft-cleanup-")
        var now = 10_000L
        val store = FileEditorDraftStore(
            root,
            maxTextBytes = 32,
            expiryMillis = 100L,
            clockMillis = { now },
        )
        val key = GameOperationKey("switch", GAME.titleId, GAME.buildId, 7L)
        val expired = store.save(null, EditorDraft(GAME, key, "old", "", "old", ""))
        val malformed = root.resolve("${UUID.randomUUID()}.draft")
        Files.write(malformed, byteArrayOf(1, 2, 3))
        val oversize = root.resolve("${UUID.randomUUID()}.draft")
        Files.write(oversize, ByteArray(8_193))
        val temporary = root.resolve(".tmp-${UUID.randomUUID()}")
        Files.write(temporary, byteArrayOf(1))
        Files.setLastModifiedTime(temporary, FileTime.fromMillis(now - 101L))
        val outside = Files.createTempFile("editor-draft-cleanup-outside-", ".bin")
        Files.write(outside, "sentinel".toByteArray())
        val link = root.resolve("${UUID.randomUUID()}.draft")
        val linkCreated = runCatching {
            Files.createSymbolicLink(link, outside)
        }.isSuccess

        now += 101L
        store.cleanupExpired()

        assertFalse(Files.exists(root.resolve("$expired.draft"), LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(malformed, LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(oversize, LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(temporary, LinkOption.NOFOLLOW_LINKS))
        if (linkCreated) assertTrue(Files.isSymbolicLink(link))
        assertEquals("sentinel", String(Files.readAllBytes(outside)))
    }

    @Test
    fun cleanupScanHonorsEntryAndByteBudgets() {
        val root = Files.createTempDirectory("editor-draft-budget-")
        repeat(8) { index ->
            Files.write(root.resolve("00000000-0000-0000-0000-${index.toString().padStart(12, '0')}.draft"), byteArrayOf(1, 2, 3))
        }
        val store = FileEditorDraftStore(
            root,
            maxTextBytes = 32,
            expiryMillis = 100L,
            clockMillis = { 1_000L },
            cleanupLimits = EditorDraftCleanupLimits(maxEntries = 2, maxReadBytes = 4L, maxDurationMillis = 1_000L),
        )

        store.cleanupExpired()

        val remaining = Files.list(root).use { it.count() }
        assertTrue("bounded cleanup removed too many entries: $remaining remain", remaining >= 7L)
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

    private class ThreadRecordingDraftStore(private val restored: EditorDraft) : com.nscheatmanager.app.data.files.EditorDraftStore {
        val threads = java.util.Collections.synchronizedList(mutableListOf<Thread>())
        val loadFinished = CompletableDeferred<Unit>()
        override fun save(token: String?, draft: EditorDraft): String {
            threads += Thread.currentThread()
            return token ?: RESTORE_TOKEN
        }
        override fun load(token: String): EditorDraft {
            threads += Thread.currentThread()
            loadFinished.complete(Unit)
            return restored
        }
        override fun delete(token: String) { threads += Thread.currentThread() }
        override fun cleanupExpired() { threads += Thread.currentThread() }
    }

    private class BlockingDraftStore : com.nscheatmanager.app.data.files.EditorDraftStore {
        @Volatile var blockNextSave = false
        val saveStarted = CompletableDeferred<Unit>()
        val releaseSave = CountDownLatch(1)
        val savedDrafts = java.util.Collections.synchronizedList(mutableListOf<EditorDraft>())
        override fun save(token: String?, draft: EditorDraft): String {
            if (blockNextSave) {
                blockNextSave = false
                saveStarted.complete(Unit)
                releaseSave.await()
            }
            savedDrafts += draft
            return token ?: UUID.randomUUID().toString()
        }
        override fun load(token: String): EditorDraft? = null
        override fun delete(token: String) = Unit
        override fun cleanupExpired() = Unit
    }

    private fun restoredHandle() = SavedStateHandle(
        mapOf(
            "editor.draftToken" to RESTORE_TOKEN,
            "editor.titleId" to GAME.titleId.hex,
            "editor.buildId" to GAME.buildId.hex,
            "editor.deviceId" to "switch",
            "editor.generation" to 7L,
            "editor.tab" to EditorTab.Cheat.name,
            "editor.dirty" to true,
            "editor.nextDiscardId" to 1L,
        ),
    )

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
        const val RESTORE_TOKEN = "11111111-1111-1111-1111-111111111111"
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
