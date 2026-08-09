package com.nscheatmanager.app.ui.game

import com.nscheatmanager.app.cheats.parser.CheatFile
import com.nscheatmanager.app.cheats.parser.CheatFileParser
import com.nscheatmanager.app.data.files.CheatMirror
import com.nscheatmanager.app.data.files.CheatZipService
import com.nscheatmanager.app.data.files.ZipInspection
import com.nscheatmanager.app.domain.DeviceProfile
import com.nscheatmanager.app.domain.DirectOverwriteConfirmation
import com.nscheatmanager.app.domain.DownloadOverwriteConfirmation
import com.nscheatmanager.app.domain.SyncCurrentGameFiles
import com.nscheatmanager.app.domain.TransferReport
import com.nscheatmanager.app.domain.UploadConfirmation
import com.nscheatmanager.app.domain.UploadPreview
import com.nscheatmanager.app.protocol.sysbot.GameIdentity
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class EditableGameFiles(
    val cheatText: String,
    val notesText: String,
    val notesExist: Boolean,
)

class EditableCheatParseException(
    val line: Int,
    detail: String,
) : IllegalArgumentException("Cheat parse failed at line $line: $detail")

interface GameFileGateway {
    suspend fun loadEditable(identity: GameIdentity): EditableGameFiles
    suspend fun saveEditable(identity: GameIdentity, cheatText: String, notesText: String): CheatFile
    suspend fun inspectZip(bytes: ByteArray): ZipInspection
    suspend fun importZip(inspection: ZipInspection)
    suspend fun exportZip(identity: GameIdentity, includeEmptyNotes: Boolean): ByteArray
    suspend fun notesExist(identity: GameIdentity): Boolean
    suspend fun download(
        profile: DeviceProfile,
        identity: GameIdentity,
        confirmation: DownloadOverwriteConfirmation? = null,
    ): TransferReport
    suspend fun discardDownload(confirmation: DownloadOverwriteConfirmation)
    suspend fun previewUpload(profile: DeviceProfile, identity: GameIdentity): UploadPreview
    suspend fun upload(
        confirmation: UploadConfirmation,
        direct: DirectOverwriteConfirmation? = null,
    ): TransferReport
}

class MirrorGameFileGateway(
    private val mirror: CheatMirror,
    private val zipService: CheatZipService,
    private val sync: SyncCurrentGameFiles,
    private val parser: CheatFileParser = CheatFileParser(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : GameFileGateway {
    override suspend fun loadEditable(identity: GameIdentity): EditableGameFiles = withContext(dispatcher) {
        val cheatPath = mirror.cheatPath(identity.titleId, identity.buildId)
        requireRegularFile(cheatPath, "Cheat file")
        val notesPath = mirror.notesPath(identity.titleId, identity.buildId)
        val notesExist = Files.exists(notesPath, LinkOption.NOFOLLOW_LINKS)
        if (notesExist) requireRegularFile(notesPath, "notes.txt")
        EditableGameFiles(
            cheatText = decodeUtf8(Files.readAllBytes(cheatPath), "Cheat file"),
            notesText = if (notesExist) decodeUtf8(Files.readAllBytes(notesPath), "notes.txt") else "",
            notesExist = notesExist,
        )
    }

    override suspend fun saveEditable(
        identity: GameIdentity,
        cheatText: String,
        notesText: String,
    ): CheatFile = withContext(dispatcher) {
        val parsed = parser.parse(cheatText)
        parsed.diagnostics.firstOrNull()?.let { diagnostic ->
            throw EditableCheatParseException(diagnostic.line, diagnostic.message)
        }
        val cheatBytes = cheatText.toByteArray(StandardCharsets.UTF_8)
        val notesBytes = notesText.toByteArray(StandardCharsets.UTF_8)
        mirror.withWriteTransaction {
            mirror.atomicReplace(mirror.cheatPath(identity.titleId, identity.buildId), cheatBytes)
            mirror.atomicReplace(mirror.notesPath(identity.titleId, identity.buildId), notesBytes)
        }
        parsed
    }

    override suspend fun inspectZip(bytes: ByteArray): ZipInspection =
        withContext(dispatcher) { zipService.inspect(bytes) }

    override suspend fun importZip(inspection: ZipInspection) {
        withContext(dispatcher) { zipService.importConfirmed(inspection) }
    }

    override suspend fun exportZip(identity: GameIdentity, includeEmptyNotes: Boolean): ByteArray =
        withContext(dispatcher) {
            zipService.export(identity.titleId, identity.buildId, includeEmptyNotes)
        }

    override suspend fun notesExist(identity: GameIdentity): Boolean = withContext(dispatcher) {
        val path = mirror.notesPath(identity.titleId, identity.buildId)
        when {
            !Files.exists(path, LinkOption.NOFOLLOW_LINKS) -> false
            Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ->
                throw IllegalArgumentException("notes.txt is not a regular mirror file")
            else -> true
        }
    }

    override suspend fun download(
        profile: DeviceProfile,
        identity: GameIdentity,
        confirmation: DownloadOverwriteConfirmation?,
    ): TransferReport = sync.downloadCurrent(profile, identity.titleId, identity.buildId, confirmation)

    override suspend fun discardDownload(confirmation: DownloadOverwriteConfirmation) {
        sync.discardDownload(confirmation)
    }

    override suspend fun previewUpload(profile: DeviceProfile, identity: GameIdentity): UploadPreview =
        sync.previewUpload(profile, identity.titleId, identity.buildId)

    override suspend fun upload(
        confirmation: UploadConfirmation,
        direct: DirectOverwriteConfirmation?,
    ): TransferReport = sync.uploadConfirmed(confirmation, direct)

    private fun requireRegularFile(path: Path, label: String) {
        require(
            Files.exists(path, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(path) &&
                Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS),
        ) { "$label is missing or unsafe" }
    }

    private fun decodeUtf8(bytes: ByteArray, label: String): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (error: Exception) {
        throw IllegalArgumentException("$label is not valid UTF-8", error)
    }
}
