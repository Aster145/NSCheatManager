package com.nscheatmanager.app.domain

import com.nscheatmanager.app.core.model.BuildId
import com.nscheatmanager.app.core.model.TitleId
import com.nscheatmanager.app.data.files.CheatMirror
import com.nscheatmanager.app.protocol.ftp.CurrentGameFiles
import com.nscheatmanager.app.protocol.ftp.DownloadedCurrentGameFiles
import com.nscheatmanager.app.protocol.ftp.DirectOverwriteAuthorization
import com.nscheatmanager.app.protocol.ftp.FtpUploadResult
import com.nscheatmanager.app.protocol.ftp.SwitchFtp
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncCurrentGameFilesTest {
    private val titleId = TitleId.parse("0100F2C0115B6000")
    private val buildId = BuildId.parse("A4A8D3E7F29C81A2")
    private val profile = DeviceProfile(
        id = "living-room",
        name = "Switch",
        host = "192.168.1.42",
        ftpPort = 2121,
    )
    private val validCheat = "[Money]\n04000000 00112233 00000063\n".toByteArray()

    @Test
    fun malformedDownloadedCheatNeverChangesTheMirror() = runTest {
        val mirror = mirrorWith(cheat = "[Old]\n04000000 00000000 00000001\n".toByteArray())
        val old = Files.readAllBytes(mirror.cheatPath(titleId, buildId))
        val ftp = FakeSwitchFtp(
            download = DownloadedCurrentGameFiles("[Broken]\nnot hex\n".toByteArray(), null),
        )
        val sync = service(mirror, ftp)

        val error = runCatching {
            sync.downloadCurrent(profile, titleId, buildId)
        }.exceptionOrNull()

        assertTrue(error is DownloadedCheatParseError)
        assertArrayEquals(old, Files.readAllBytes(mirror.cheatPath(titleId, buildId)))
    }

    @Test
    fun existingMirrorRequiresBoundConfirmationAndMissingRemoteNotesPreserveLocalNotes() = runTest {
        val localNotes = "keep these notes".toByteArray()
        val mirror = mirrorWith(
            cheat = "[Old]\n04000000 00000000 00000001\n".toByteArray(),
            notes = localNotes,
        )
        val ftp = FakeSwitchFtp(download = DownloadedCurrentGameFiles(validCheat, null))
        val sync = service(mirror, ftp)

        val preview = sync.downloadCurrent(profile, titleId, buildId)

        assertTrue(preview is TransferReport.RequiresLocalOverwriteConfirmation)
        assertFalse(Files.readAllBytes(mirror.cheatPath(titleId, buildId)).contentEquals(validCheat))
        val confirmation = (preview as TransferReport.RequiresLocalOverwriteConfirmation).confirmation

        val report = sync.downloadCurrent(profile, titleId, buildId, confirmation)

        assertEquals(NotesDownloadDisposition.MissingRemotePreservedLocal, (report as TransferReport.Downloaded).notes)
        assertArrayEquals(validCheat, Files.readAllBytes(mirror.cheatPath(titleId, buildId)))
        assertArrayEquals(localNotes, Files.readAllBytes(mirror.notesPath(titleId, buildId)))
        assertEquals(1, ftp.downloadCalls)
    }

    @Test
    fun discardingADownloadConfirmationDeletesItsStageAndExpiresTheToken() = runTest {
        val mirror = mirrorWith("[Old]\n04000000 00000000 00000001\n".toByteArray())
        val stagingRoot = mirror.root.parent.resolve("ftp-stage")
        val ftp = FakeSwitchFtp(download = DownloadedCurrentGameFiles(validCheat, null))
        val sync = SyncCurrentGameFiles(
            mirror = mirror,
            stagingRoot = stagingRoot,
            ftpFactory = { ftp },
        )
        val preview = sync.downloadCurrent(profile, titleId, buildId)
            as TransferReport.RequiresLocalOverwriteConfirmation

        sync.discardDownload(preview.confirmation)
        val error = runCatching {
            sync.downloadCurrent(profile, titleId, buildId, preview.confirmation)
        }.exceptionOrNull()

        assertTrue(error is InvalidSyncConfirmation)
        Files.list(stagingRoot).use { assertEquals(0L, it.count()) }
    }

    @Test
    fun missingRemoteCheatDoesNotCreateAnEmptyMirrorFile() = runTest {
        val root = Files.createTempDirectory("ftp-domain-missing-")
        val mirror = CheatMirror(root.resolve("mirror"))
        val ftp = FakeSwitchFtp(download = DownloadedCurrentGameFiles(null, "orphan notes".toByteArray()))

        val report = service(mirror, ftp).downloadCurrent(profile, titleId, buildId)

        assertEquals(TransferReport.RemoteCheatMissing, report)
        assertFalse(Files.exists(mirror.cheatPath(titleId, buildId)))
        assertFalse(Files.exists(mirror.notesPath(titleId, buildId)))
    }

    @Test
    fun invalidUtf8RemoteNotesRejectTheWholeDownloadBeforeMirrorPublication() = runTest {
        val root = Files.createTempDirectory("ftp-domain-notes-encoding-")
        val mirror = CheatMirror(root.resolve("mirror"))
        val ftp = FakeSwitchFtp(
            download = DownloadedCurrentGameFiles(validCheat, byteArrayOf(0xC3.toByte(), 0x28)),
        )

        val error = runCatching {
            service(mirror, ftp).downloadCurrent(profile, titleId, buildId)
        }.exceptionOrNull()

        assertTrue(error is NotesEncodingError)
        assertFalse(Files.exists(mirror.cheatPath(titleId, buildId)))
        assertFalse(Files.exists(mirror.notesPath(titleId, buildId)))
    }

    @Test
    fun uploadPreviewIsBoundToTheLocalSnapshotAndRevalidatedBeforeNetworkIo() = runTest {
        val mirror = mirrorWith(validCheat, "notes".toByteArray())
        val ftp = FakeSwitchFtp()
        val sync = service(mirror, ftp)
        val preview = sync.previewUpload(profile, titleId, buildId)
        mirror.atomicReplace(mirror.cheatPath(titleId, buildId), validCheat + "# changed\n".toByteArray())

        val report = sync.uploadConfirmed(preview.confirmation)

        assertEquals(TransferReport.StaleLocalSnapshot, report)
        assertEquals(0, ftp.uploadCalls)
    }

    @Test
    fun renameFallbackRequiresASeparateDirectOverwriteConfirmation() = runTest {
        val mirror = mirrorWith(validCheat, "notes".toByteArray())
        val ftp = FakeSwitchFtp(
            uploadResults = ArrayDeque(
                listOf(
                    FtpUploadResult.RequiresDirectOverwriteConfirmation,
                    FtpUploadResult.Uploaded(validCheat.size, "notes".length),
                ),
            ),
        )
        val sync = service(mirror, ftp)
        val preview = sync.previewUpload(profile, titleId, buildId)

        val requiresDirect = sync.uploadConfirmed(preview.confirmation)

        assertTrue(requiresDirect is TransferReport.RequiresDirectOverwriteConfirmation)
        val direct = (requiresDirect as TransferReport.RequiresDirectOverwriteConfirmation).confirmation
        assertNotEquals(preview.confirmation.toString(), direct.toString())

        val uploaded = sync.uploadConfirmed(preview.confirmation, direct)

        assertTrue(uploaded is TransferReport.Uploaded)
        assertEquals(listOf(false, true), ftp.directOverwriteAuthorizations)
        assertEquals(profile, ftp.lastFactoryProfile)
    }

    @Test
    fun replacingRemoteNotesRequiresOnlyTheCurrentBuildScopedLocalFile() = runTest {
        val notes = "current build notes".toByteArray()
        val mirror = mirrorWith(validCheat, notes)
        val ftp = FakeSwitchFtp()
        val sync = service(mirror, ftp)

        val preview = sync.previewUpload(profile, titleId, buildId)
        val report = sync.uploadConfirmed(preview.confirmation)

        assertTrue(report is TransferReport.Uploaded)
        assertArrayEquals(validCheat, ftp.lastUpload!!.cheat)
        assertArrayEquals(notes, ftp.lastUpload!!.notes)
        assertEquals(titleId, ftp.lastTitleId)
        assertEquals(buildId, ftp.lastBuildId)
    }

    @Test
    fun anUploadConfirmationCannotPublishTwiceWhenTappedConcurrently() = runTest {
        val mirror = mirrorWith(validCheat)
        val ftp = FakeSwitchFtp().apply {
            uploadStarted = CompletableDeferred()
            secondUploadStarted = CompletableDeferred()
            releaseUpload = CompletableDeferred()
        }
        val sync = service(mirror, ftp)
        val preview = sync.previewUpload(profile, titleId, buildId)

        val first = async { sync.uploadConfirmed(preview.confirmation) }
        ftp.uploadStarted!!.await()
        val second = async { runCatching { sync.uploadConfirmed(preview.confirmation) } }
        val secondReachedProtocol = withTimeoutOrNull(200) {
            ftp.secondUploadStarted!!.await()
            true
        } ?: false
        ftp.releaseUpload!!.complete(Unit)

        assertFalse(secondReachedProtocol)
        assertTrue(first.await() is TransferReport.Uploaded)
        assertTrue(second.await().exceptionOrNull() is InvalidSyncConfirmation)
        assertEquals(1, ftp.uploadCalls)
    }

    private fun mirrorWith(cheat: ByteArray, notes: ByteArray? = null): CheatMirror {
        val root = Files.createTempDirectory("ftp-domain-")
        val mirror = CheatMirror(root.resolve("mirror"))
        mirror.atomicReplace(mirror.cheatPath(titleId, buildId), cheat)
        notes?.let { mirror.atomicReplace(mirror.notesPath(titleId, buildId), it) }
        return mirror
    }

    private fun service(mirror: CheatMirror, ftp: FakeSwitchFtp) = SyncCurrentGameFiles(
        mirror = mirror,
        stagingRoot = mirror.root.parent.resolve("ftp-stage"),
        ftpFactory = { selected -> ftp.also { it.lastFactoryProfile = selected } },
    )
}

private class FakeSwitchFtp(
    var download: DownloadedCurrentGameFiles = DownloadedCurrentGameFiles(null, null),
    private val uploadResults: ArrayDeque<FtpUploadResult> = ArrayDeque(),
) : SwitchFtp {
    var downloadCalls = 0
    var uploadCalls = 0
    var lastFactoryProfile: DeviceProfile? = null
    var lastUpload: CurrentGameFiles? = null
    var lastTitleId: TitleId? = null
    var lastBuildId: BuildId? = null
    val directOverwriteAuthorizations = mutableListOf<Boolean>()
    var uploadStarted: CompletableDeferred<Unit>? = null
    var secondUploadStarted: CompletableDeferred<Unit>? = null
    var releaseUpload: CompletableDeferred<Unit>? = null

    override suspend fun downloadCurrent(titleId: TitleId, buildId: BuildId): DownloadedCurrentGameFiles {
        downloadCalls++
        lastTitleId = titleId
        lastBuildId = buildId
        return download
    }

    override suspend fun uploadCurrent(
        titleId: TitleId,
        buildId: BuildId,
        files: CurrentGameFiles,
        directOverwriteAuthorization: DirectOverwriteAuthorization?,
    ): FtpUploadResult {
        val callNumber = synchronized(this) {
            uploadCalls++
            uploadCalls
        }
        lastTitleId = titleId
        lastBuildId = buildId
        lastUpload = files
        directOverwriteAuthorizations += directOverwriteAuthorization != null
        uploadStarted?.complete(Unit)
        if (callNumber == 2) secondUploadStarted?.complete(Unit)
        releaseUpload?.await()
        return if (uploadResults.isEmpty()) {
            FtpUploadResult.Uploaded(files.cheat.size, files.notes?.size)
        } else {
            uploadResults.removeFirst()
        }
    }
}
