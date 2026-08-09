package com.nscheatmanager.app.domain

import com.nscheatmanager.app.cheats.parser.CheatFile
import com.nscheatmanager.app.cheats.parser.CheatFileParser
import com.nscheatmanager.app.core.model.BuildId
import com.nscheatmanager.app.core.model.TitleId
import com.nscheatmanager.app.data.files.CheatMirror
import com.nscheatmanager.app.protocol.sysbot.GameIdentity
import com.nscheatmanager.app.protocol.sysbot.SysBotbase
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

data class GameKey(
    val titleId: TitleId,
    val buildId: BuildId,
)

data class LoadedCheatDocument(
    val identity: GameIdentity,
    val relativePath: String,
    val cheatFile: CheatFile?,
) {
    val exists: Boolean get() = cheatFile != null

    companion object {
        fun missing(identity: GameIdentity): LoadedCheatDocument = LoadedCheatDocument(
            identity = identity,
            relativePath = canonicalCheatRelative(identity),
            cheatFile = null,
        )
    }
}

data class RecognizedCurrentGame(
    val identity: GameIdentity,
    val document: LoadedCheatDocument,
    val checkedGroups: Set<String>,
)

/** Persistence boundary which keeps the Task 7 process-local trust epoch authoritative. */
interface SessionPersistence {
    suspend fun invalidate(deviceId: String)
    suspend fun saveValidated(deviceId: String, identity: GameIdentity)
    suspend fun checkedGroups(deviceId: String, identity: GameIdentity): Set<String>
    suspend fun setChecked(
        deviceId: String,
        identity: GameIdentity,
        groupName: String,
        checked: Boolean,
    )
}

class DeviceRepositorySessionPersistence(
    private val repository: DeviceRepository,
) : SessionPersistence {
    override suspend fun invalidate(deviceId: String) {
        repository.markDeviceDisconnected(deviceId)
    }

    override suspend fun saveValidated(deviceId: String, identity: GameIdentity) {
        repository.saveValidatedSession(
            deviceId = deviceId,
            titleId = identity.titleId.hex,
            buildId = identity.buildId.hex,
            mainBase = identity.mainBase.toString(16).uppercase(),
            heapBase = identity.heapBase.toString(16).uppercase(),
        )
    }

    override suspend fun checkedGroups(deviceId: String, identity: GameIdentity): Set<String> =
        repository.observeCheckedGroupNames(
            deviceId,
            identity.titleId.hex,
            identity.buildId.hex,
        ).first()

    override suspend fun setChecked(
        deviceId: String,
        identity: GameIdentity,
        groupName: String,
        checked: Boolean,
    ) {
        repository.setChecked(
            deviceId = deviceId,
            titleId = identity.titleId.hex,
            buildId = identity.buildId.hex,
            groupName = groupName,
            checked = checked,
        )
    }
}

fun interface CheatLibrary {
    suspend fun load(identity: GameIdentity): LoadedCheatDocument
}

class MirrorCheatLibrary(
    private val mirror: CheatMirror,
    private val parser: CheatFileParser = CheatFileParser(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CheatLibrary {
    override suspend fun load(identity: GameIdentity): LoadedCheatDocument = withContext(ioDispatcher) {
        val path = mirror.cheatPath(identity.titleId, identity.buildId)
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return@withContext LoadedCheatDocument.missing(identity)
        }
        require(!Files.isSymbolicLink(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            "Cheat mirror target must be a regular file"
        }
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val source = decoder.decode(ByteBuffer.wrap(Files.readAllBytes(path))).toString()
        LoadedCheatDocument(
            identity = identity,
            relativePath = mirror.cheatRelative(identity.titleId, identity.buildId),
            cheatFile = parser.parse(source),
        )
    }
}

class RecognizeCurrentGame(
    private val persistence: SessionPersistence,
    private val cheatLibrary: CheatLibrary,
) {
    suspend fun invalidate(deviceId: String) {
        persistence.invalidate(deviceId)
    }

    suspend fun execute(
        device: DeviceProfile,
        client: SysBotbase,
        checkpoint: suspend () -> Unit = {},
    ): RecognizedCurrentGame {
        val identity = client.recognizeGame()
        validateIdentity(identity)
        checkpoint()
        val document = cheatLibrary.load(identity)
        checkpoint()
        val checked = persistence.checkedGroups(device.id, identity).toSet()
        checkpoint()
        try {
            persistence.saveValidated(device.id, identity)
            checkpoint()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // If cancellation wins while Room is publishing, revoke its trust epoch before leaving.
            withContext(NonCancellable) { persistence.invalidate(device.id) }
            throw cancelled
        }
        return RecognizedCurrentGame(identity, document, checked)
    }

    private fun validateIdentity(identity: GameIdentity) {
        require(identity.mainBase != 0uL) { "Main base must not be zero" }
        require(identity.heapBase != 0uL) { "Heap base must not be zero" }
    }
}

internal fun canonicalCheatRelative(identity: GameIdentity): String =
    "atmosphere/contents/${identity.titleId.hex}/cheats/${identity.buildId.hex}.txt"
