package com.nscheatmanager.app.data.files

import com.nscheatmanager.app.core.model.BuildId
import com.nscheatmanager.app.core.model.TitleId
import com.nscheatmanager.app.domain.GameOperationKey
import com.nscheatmanager.app.protocol.sysbot.GameIdentity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class EditorDraft(
    val identity: GameIdentity,
    val operationKey: GameOperationKey,
    val cheatText: String,
    val notesText: String,
    val originalCheat: String,
    val originalNotes: String,
)

interface EditorDraftStore {
    fun save(token: String?, draft: EditorDraft): String
    fun load(token: String): EditorDraft?
    fun delete(token: String)
    fun cleanupExpired()
}

data class EditorDraftCleanupLimits(
    val maxEntries: Int = 256,
    val maxReadBytes: Long = 8L * 1024L * 1024L,
    val maxDurationMillis: Long = 250L,
) {
    init {
        require(maxEntries > 0)
        require(maxReadBytes > 0)
        require(maxDurationMillis in 1..(Long.MAX_VALUE / 1_000_000L))
    }
}

internal class InMemoryEditorDraftStore : EditorDraftStore {
    private val drafts = mutableMapOf<String, EditorDraft>()
    override fun save(token: String?, draft: EditorDraft): String = synchronized(drafts) {
        (token ?: UUID.randomUUID().toString()).also { drafts[it] = draft }
    }
    override fun load(token: String): EditorDraft? = synchronized(drafts) { drafts[token] }
    override fun delete(token: String) { synchronized(drafts) { drafts.remove(token) } }
    override fun cleanupExpired() = Unit
}

/** App-cache backed editor drafts; only the UUID token is placed in SavedStateHandle. */
class FileEditorDraftStore internal constructor(
    root: Path,
    private val maxTextBytes: Int,
    private val expiryMillis: Long,
    private val clockMillis: () -> Long,
    private val durability: FileDurability,
    private val cleanupLimits: EditorDraftCleanupLimits,
    private val monotonicNanos: () -> Long,
) : EditorDraftStore {
    constructor(
        root: Path,
        maxTextBytes: Int = DEFAULT_MAX_TEXT_BYTES,
        expiryMillis: Long = DEFAULT_EXPIRY_MILLIS,
        clockMillis: () -> Long = System::currentTimeMillis,
        cleanupLimits: EditorDraftCleanupLimits = EditorDraftCleanupLimits(),
    ) : this(
        root,
        maxTextBytes,
        expiryMillis,
        clockMillis,
        platformFileDurability(),
        cleanupLimits,
        System::nanoTime,
    )

    private val root = root.toAbsolutePath().normalize()
    private val lock = ReentrantLock()

    init {
        require(maxTextBytes > 0)
        require(expiryMillis > 0)
    }

    override fun save(token: String?, draft: EditorDraft): String = lock.withLock {
        ensureRoot()
        validateDraft(draft)
        val canonicalToken = token?.let(::validateToken) ?: UUID.randomUUID().toString()
        val target = path(canonicalToken)
        requireRegularOrMissing(target)
        val bytes = encode(canonicalToken, draft, clockMillis())
        val temporary = root.resolve(".tmp-$canonicalToken-${UUID.randomUUID()}")
        try {
            writeComplete(temporary, bytes)
            try {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
            durability.forceDirectory(root)
        } finally {
            if (Files.deleteIfExists(temporary)) durability.forceDirectory(root)
        }
        canonicalToken
    }

    override fun load(token: String): EditorDraft? = lock.withLock {
        ensureRoot()
        val canonicalToken = validateToken(token)
        val target = path(canonicalToken)
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return null
        requireRegularOrMissing(target)
        val size = Files.size(target)
        require(size in 1..maximumSerializedBytes()) { "Editor draft size is invalid" }
        decode(canonicalToken, Files.readAllBytes(target)).draft
    }

    override fun delete(token: String) = lock.withLock {
        ensureRoot()
        val target = path(validateToken(token))
        requireRegularOrMissing(target)
        if (Files.deleteIfExists(target)) durability.forceDirectory(root)
    }

    override fun cleanupExpired() = lock.withLock {
        ensureRoot()
        val cutoff = clockMillis() - expiryMillis
        val startedAt = monotonicNanos()
        val durationBudgetNanos = cleanupLimits.maxDurationMillis * 1_000_000L
        var visited = 0
        var readBytes = 0L
        var changed = false
        Files.newDirectoryStream(root).use { entries ->
            for (candidate in entries) {
                if (visited >= cleanupLimits.maxEntries) break
                if (monotonicNanos() - startedAt >= durationBudgetNanos) break
                visited++
                val attributes = runCatching {
                    Files.readAttributes(candidate, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                }.getOrNull() ?: continue
                if (attributes.isSymbolicLink || !attributes.isRegularFile) continue
                val name = candidate.fileName.toString()
                if (isTemporaryName(name)) {
                    if (attributes.lastModifiedTime().toMillis() < cutoff) {
                        changed = Files.deleteIfExists(candidate) || changed
                    }
                    continue
                }
                if (!name.endsWith(DRAFT_SUFFIX)) continue
                val size = attributes.size()
                if (size !in 1..maximumSerializedBytes()) {
                    changed = Files.deleteIfExists(candidate) || changed
                    continue
                }
                if (size > cleanupLimits.maxReadBytes - readBytes) break
                readBytes += size
                val token = name.removeSuffix(DRAFT_SUFFIX)
                val stored = runCatching {
                    decode(validateToken(token), Files.readAllBytes(candidate))
                }.getOrNull()
                if (stored == null || stored.savedAt < cutoff) {
                    changed = Files.deleteIfExists(candidate) || changed
                }
            }
        }
        if (changed) durability.forceDirectory(root)
    }

    private fun isTemporaryName(name: String): Boolean =
        name.startsWith(".tmp-") || (name.startsWith('.') && ".tmp-" in name)

    private fun validateDraft(draft: EditorDraft) {
        require(draft.operationKey.titleId == draft.identity.titleId)
        require(draft.operationKey.buildId == draft.identity.buildId)
        require(draft.operationKey.deviceId.isNotBlank() && draft.operationKey.deviceId.length <= MAX_DEVICE_ID_CHARS)
        listOf(draft.cheatText, draft.notesText, draft.originalCheat, draft.originalNotes).forEach {
            require(it.toByteArray(Charsets.UTF_8).size <= maxTextBytes) { "Editor draft text exceeds limit" }
        }
    }

    private fun encode(token: String, draft: EditorDraft, savedAt: Long): ByteArray {
        val payload = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(VERSION)
                output.writeString(token)
                output.writeLong(savedAt)
                output.writeString(draft.operationKey.deviceId)
                output.writeString(draft.operationKey.titleId.hex)
                output.writeString(draft.operationKey.buildId.hex)
                output.writeLong(draft.operationKey.generation)
                output.writeString(draft.identity.mainBase.toString(16))
                output.writeString(draft.identity.heapBase.toString(16))
                output.writeText(draft.cheatText)
                output.writeText(draft.notesText)
                output.writeText(draft.originalCheat)
                output.writeText(draft.originalNotes)
            }
            bytes.toByteArray()
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(payload)
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(payload.size)
                output.write(payload)
                output.write(digest)
            }
            bytes.toByteArray()
        }
    }

    private fun decode(expectedToken: String, bytes: ByteArray): StoredDraft {
        require(bytes.size.toLong() <= maximumSerializedBytes()) { "Editor draft is too large" }
        return try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(input.readInt() == MAGIC) { "Editor draft header is invalid" }
                val payloadSize = input.readInt()
                require(payloadSize in 1..(bytes.size - HEADER_AND_DIGEST_BYTES)) { "Editor draft payload is invalid" }
                val payload = ByteArray(payloadSize).also(input::readFully)
                val expectedDigest = ByteArray(DIGEST_BYTES).also(input::readFully)
                require(input.read() == -1) { "Editor draft has trailing data" }
                require(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(payload), expectedDigest)) {
                    "Editor draft integrity check failed"
                }
                DataInputStream(ByteArrayInputStream(payload)).use { data ->
                    require(data.readInt() == VERSION) { "Editor draft version is unsupported" }
                    require(data.readString(MAX_TOKEN_BYTES) == expectedToken) { "Editor draft token does not match" }
                    val savedAt = data.readLong()
                    val deviceId = data.readString(MAX_DEVICE_ID_CHARS)
                    val titleId = TitleId.parse(data.readString(CANONICAL_ID_BYTES))
                    val buildId = BuildId.parse(data.readString(CANONICAL_ID_BYTES))
                    val key = GameOperationKey(deviceId, titleId, buildId, data.readLong())
                    val identity = GameIdentity(
                        titleId,
                        buildId,
                        data.readString(32).toULong(16),
                        data.readString(32).toULong(16),
                    )
                    val draft = EditorDraft(
                        identity,
                        key,
                        data.readText(),
                        data.readText(),
                        data.readText(),
                        data.readText(),
                    )
                    require(data.read() == -1) { "Editor draft payload has trailing data" }
                    validateDraft(draft)
                    StoredDraft(savedAt, draft)
                }
            }
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: Exception) {
            throw IllegalArgumentException("Editor draft is malformed", error)
        }
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataOutputStream.writeText(value: String) = writeString(value)

    private fun DataInputStream.readString(maxBytes: Int): String {
        val size = readInt()
        require(size in 0..maxBytes) { "Editor draft field is too large" }
        return String(ByteArray(size).also(::readFully), Charsets.UTF_8)
    }

    private fun DataInputStream.readText(): String = readString(maxTextBytes)

    private fun writeComplete(path: Path, bytes: ByteArray) {
        FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
        }
        durability.forceFile(path)
        durability.forceDirectory(root)
    }

    private fun ensureRoot() {
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            require(!Files.isSymbolicLink(root) && Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                "Editor draft root must be a real directory"
            }
        } else {
            Files.createDirectories(root)
            require(!Files.isSymbolicLink(root)) { "Editor draft root must not be a symbolic link" }
            durability.forceDirectory(root)
        }
    }

    private fun requireRegularOrMissing(path: Path) {
        require(path.parent == root)
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            require(!Files.isSymbolicLink(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                "Editor draft must be a regular file"
            }
        }
    }

    private fun path(token: String): Path = root.resolve("$token$DRAFT_SUFFIX").normalize().also {
        require(it.parent == root)
    }

    private fun validateToken(token: String): String {
        require(token.length == 36 && UUID.fromString(token).toString() == token) { "Editor draft token is invalid" }
        return token
    }

    private fun maximumSerializedBytes(): Long = (maxTextBytes.toLong() * 4L) + MAX_METADATA_BYTES

    private data class StoredDraft(val savedAt: Long, val draft: EditorDraft)

    companion object {
        const val DEFAULT_MAX_TEXT_BYTES = 1024 * 1024
        const val DEFAULT_EXPIRY_MILLIS = 24L * 60L * 60L * 1_000L
        private const val MAGIC = 0x4E534344
        private const val VERSION = 1
        private const val DIGEST_BYTES = 32
        private const val HEADER_AND_DIGEST_BYTES = 8 + DIGEST_BYTES
        private const val MAX_METADATA_BYTES = 4_096L
        private const val MAX_DEVICE_ID_CHARS = 256
        private const val MAX_TOKEN_BYTES = 36
        private const val CANONICAL_ID_BYTES = 16
        private const val DRAFT_SUFFIX = ".draft"
    }
}
