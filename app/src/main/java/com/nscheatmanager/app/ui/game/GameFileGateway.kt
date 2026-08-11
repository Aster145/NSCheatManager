package com.nscheatmanager.app.ui.game

import com.nscheatmanager.app.cheats.parser.CheatFile
import com.nscheatmanager.app.cheats.parser.CheatFileParser
import com.nscheatmanager.app.cheats.parser.CheatTextDecoding
import com.nscheatmanager.app.cheats.parser.CheatParseDiagnostic
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
    val diagnostic: CheatParseDiagnostic,
) : IllegalArgumentException("Cheat text is malformed")

interface GameFileGateway {
    suspend fun loadEditable(identity: GameIdentity, checkpoint: () -> Unit = {}): EditableGameFiles
    suspend fun saveEditable(
        identity: GameIdentity,
        cheatText: String,
        notesText: String,
        checkpoint: () -> Unit = {},
    ): CheatFile
    suspend fun inspectZip(bytes: ByteArray): ZipInspection
    suspend fun importZip(inspection: ZipInspection, checkpoint: () -> Unit = {})
    suspend fun exportZip(identity: GameIdentity, includeEmptyNotes: Boolean, checkpoint: () -> Unit = {}): ByteArray
    suspend fun notesExist(identity: GameIdentity, checkpoint: () -> Unit = {}): Boolean
    suspend fun download(
        profile: DeviceProfile,
        identity: GameIdentity,
        confirmation: DownloadOverwriteConfirmation? = null,
        checkpoint: () -> Unit = {},
    ): TransferReport
    suspend fun discardDownload(confirmation: DownloadOverwriteConfirmation)
    suspend fun previewUpload(
        profile: DeviceProfile,
        identity: GameIdentity,
        checkpoint: () -> Unit = {},
    ): UploadPreview
    suspend fun upload(
        confirmation: UploadConfirmation,
        direct: DirectOverwriteConfirmation? = null,
        checkpoint: () -> Unit = {},
    ): TransferReport
}

class MirrorGameFileGateway(
    private val mirror: CheatMirror,
    private val zipService: CheatZipService,
    private val sync: SyncCurrentGameFiles,
    private val parser: CheatFileParser = CheatFileParser(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : GameFileGateway {
    override suspend fun loadEditable(identity: GameIdentity, checkpoint: () -> Unit): EditableGameFiles = withContext(dispatcher) {
        val cheatPath = mirror.cheatPath(identity.titleId, identity.buildId)
        requireRegularFile(cheatPath, "Cheat file")
        val notesPath = mirror.notesPath(identity.titleId, identity.buildId)
        val notesExist = Files.exists(notesPath, LinkOption.NOFOLLOW_LINKS)
        if (notesExist) requireRegularFile(notesPath, "notes.txt")
        checkpoint()
        val cheatBytes = Files.readAllBytes(cheatPath)
        val notesBytes = if (notesExist) {
            checkpoint()
            Files.readAllBytes(notesPath)
        } else null
        EditableGameFiles(
            cheatText = CheatTextDecoding.decodeForParsing(cheatBytes),
            notesText = notesBytes?.let { decodeUtf8(it, "notes.txt") }.orEmpty(),
            notesExist = notesExist,
        )
    }

    override suspend fun saveEditable(
        identity: GameIdentity,
        cheatText: String,
        notesText: String,
        checkpoint: () -> Unit,
    ): CheatFile = withContext(dispatcher) {
        val parsed = parser.parse(cheatText)
        parsed.diagnostics.firstOrNull()?.let { diagnostic ->
            throw EditableCheatParseException(diagnostic)
        }
        val cheatBytes = cheatText.toByteArray(StandardCharsets.UTF_8)
        val notesBytes = notesText.toByteArray(StandardCharsets.UTF_8)
        checkpoint()
        mirror.atomicReplaceAll(
            linkedMapOf(
                mirror.cheatPath(identity.titleId, identity.buildId) to cheatBytes,
                mirror.notesPath(identity.titleId, identity.buildId) to notesBytes,
            ),
        )
        parsed
    }

    override suspend fun inspectZip(bytes: ByteArray): ZipInspection =
        withContext(dispatcher) { zipService.inspect(bytes) }

    override suspend fun importZip(inspection: ZipInspection, checkpoint: () -> Unit) {
        withContext(dispatcher) {
            checkpoint()
            zipService.importConfirmed(inspection)
        }
    }

    override suspend fun exportZip(identity: GameIdentity, includeEmptyNotes: Boolean, checkpoint: () -> Unit): ByteArray =
        withContext(dispatcher) {
            checkpoint()
            zipService.export(identity.titleId, identity.buildId, includeEmptyNotes)
        }

    override suspend fun notesExist(identity: GameIdentity, checkpoint: () -> Unit): Boolean = withContext(dispatcher) {
        val path = mirror.notesPath(identity.titleId, identity.buildId)
        checkpoint()
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
        checkpoint: () -> Unit,
    ): TransferReport = sync.downloadCurrent(profile, identity.titleId, identity.buildId, confirmation, checkpoint)

    override suspend fun discardDownload(confirmation: DownloadOverwriteConfirmation) {
        sync.discardDownload(confirmation)
    }

    override suspend fun previewUpload(
        profile: DeviceProfile,
        identity: GameIdentity,
        checkpoint: () -> Unit,
    ): UploadPreview = sync.previewUpload(profile, identity.titleId, identity.buildId, checkpoint)

    override suspend fun upload(
        confirmation: UploadConfirmation,
        direct: DirectOverwriteConfirmation?,
        checkpoint: () -> Unit,
    ): TransferReport = sync.uploadConfirmed(confirmation, direct, checkpoint)

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
