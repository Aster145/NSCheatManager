package com.nscheatmanager.app.protocol.ftp

import com.nscheatmanager.app.core.model.BuildId
import com.nscheatmanager.app.core.model.TitleId
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommonsNetSwitchFtpTest {
    private val titleId = TitleId.parse("0100F2C0115B6000")
    private val buildId = BuildId.parse("A4A8D3E7F29C81A2")
    private val cheatPath = "/atmosphere/contents/${titleId.hex}/cheats/${buildId.hex}.txt"
    private val notesPath = "/atmosphere/contents/${titleId.hex}/cheats/${buildId.hex}/notes.txt"
    private val cheat = "[Money]\n04000000 00112233 00000063\n".toByteArray()

    @Test
    fun downloadUsesAnonymousBinaryPassiveModeAndOnlyCurrentGamePaths() = runTest { FakeFtpServer().use { server ->
        server.files[cheatPath] = cheat
        server.files[notesPath] = "my notes".toByteArray()
        val client = client(server)

        val result = client.downloadCurrent(titleId, buildId)

        assertArrayEquals(cheat, result.cheat)
        assertArrayEquals("my notes".toByteArray(), result.notes)
        assertTrue(server.commands.contains("USER anonymous"))
        assertTrue(server.commands.contains("PASS anonymous@"))
        assertTrue(server.commands.contains("TYPE I"))
        assertTrue(server.commands.count { it == "PASV" || it == "EPSV" } >= 2)
        assertEquals(listOf(cheatPath, notesPath), server.retrievedPaths)
        assertFalse(server.commands.any { ".." in it || '\\' in it })
        assertTrue(server.commands.contains("QUIT"))
    } }

    @Test
    fun missingNotesAreNonfatal() = runTest { FakeFtpServer().use { server ->
        server.files[cheatPath] = cheat

        val result = client(server).downloadCurrent(titleId, buildId)

        assertArrayEquals(cheat, result.cheat)
        assertEquals(null, result.notes)
        assertEquals(listOf(cheatPath, notesPath), server.retrievedPaths)
    } }

    @Test
    fun retrievePermissionDeniedIsNeverReportedAsMissing() = runTest {
        FakeFtpServer().use { server ->
            server.permissionDeniedRetrievePaths += cheatPath

            val error = runCatching { client(server).downloadCurrent(titleId, buildId) }.exceptionOrNull()

            assertTrue(error is FtpReplyError)
            assertEquals("RETR", (error as FtpReplyError).operation)
        }
    }

    @Test
    fun safeUploadStagesBacksUpRenamesAndVerifiesBothFiles() = runTest { FakeFtpServer().use { server ->
        server.files[cheatPath] = "old cheat".toByteArray()
        server.files[notesPath] = "old notes".toByteArray()
        val newNotes = "new notes".toByteArray()

        val result = client(server).uploadCurrent(
            titleId,
            buildId,
            CurrentGameFiles(cheat, newNotes),
            null,
        )

        assertTrue(result is FtpUploadResult.Uploaded)
        assertArrayEquals(cheat, server.files.getValue(cheatPath))
        assertArrayEquals(newNotes, server.files.getValue(notesPath))
        assertTrue(server.commands.any { it == "RNFR $cheatPath" })
        assertTrue(server.commands.any { it.startsWith("RNTO $cheatPath.nscheatmanager-backup-") })
        assertTrue(server.commands.any { it.startsWith("RNFR $cheatPath.nscheatmanager-upload-") })
        assertTrue(server.commands.any { it == "RNTO $cheatPath" })
        assertTrue(server.commands.any { it == "SIZE $cheatPath" })
        assertTrue(server.retrievedPaths.count { it == cheatPath } >= 2)
        assertFalse(server.files.keys.any { ".nscheatmanager-" in it })
        assertTrue(server.createdDirectories.contains("/atmosphere/contents/${titleId.hex}/cheats/${buildId.hex}"))
    } }

    @Test
    fun unsupportedRenameRequestsDirectConfirmationWithoutOverwriting() = runTest { FakeFtpServer(renameSupported = false).use { server ->
        val old = "old cheat".toByteArray()
        server.files[cheatPath] = old

        val result = client(server).uploadCurrent(
            titleId,
            buildId,
            CurrentGameFiles(cheat, null),
            null,
        )

        assertTrue(result is FtpUploadResult.RequiresDirectOverwriteConfirmation)
        assertArrayEquals(old, server.files.getValue(cheatPath))
        assertFalse(server.files.keys.any { ".nscheatmanager-" in it })
    } }

    @Test
    fun unsupportedRenameToAlsoRequestsDirectConfirmationWithoutOverwriting() = runTest {
        FakeFtpServer(renameToSupported = false).use { server ->
            val old = "old cheat".toByteArray()
            server.files[cheatPath] = old

            val result = client(server).uploadCurrent(
                titleId,
                buildId,
                CurrentGameFiles(cheat, null),
                null,
            )

            assertTrue(result is FtpUploadResult.RequiresDirectOverwriteConfirmation)
            assertArrayEquals(old, server.files.getValue(cheatPath))
            assertFalse(server.files.keys.any { ".nscheatmanager-" in it })
        }
    }

    @Test
    fun stagedUploadRollsBackOldFileWhenPublishedSizeDoesNotMatch() = runTest {
        FakeFtpServer().use { server ->
            val old = "old cheat".toByteArray()
            server.files[cheatPath] = old
            server.wrongSizeAfterPublishPath = cheatPath

            val error = runCatching {
                client(server).uploadCurrent(
                    titleId,
                    buildId,
                    CurrentGameFiles(cheat, null),
                    null,
                )
            }.exceptionOrNull()

            assertTrue(error is FtpVerificationError)
            assertArrayEquals(old, server.files.getValue(cheatPath))
            assertFalse(server.files.keys.any { ".nscheatmanager-" in it })
        }
    }

    @Test
    fun permissionDeniedBackupDeleteIsReportedAsRetainedRecoveryArtifact() = runTest {
        FakeFtpServer().use { server ->
            server.files[cheatPath] = "old cheat".toByteArray()
            server.denyBackupDeletes = true

            val result = client(server).uploadCurrent(
                titleId,
                buildId,
                CurrentGameFiles(cheat, null),
                null,
            ) as FtpUploadResult.Uploaded

            assertTrue(result.retainedRecoveryArtifacts)
            assertTrue(server.files.keys.any { ".nscheatmanager-backup-" in it })
        }
    }

    @Test
    fun multiFilePublishRenameFailureRestoresBothOldFiles() = runTest {
        FakeFtpServer().use { server ->
            val oldCheat = "old cheat".toByteArray()
            val oldNotes = "old notes".toByteArray()
            server.files[cheatPath] = oldCheat
            server.files[notesPath] = oldNotes
            server.failRenameToPath = notesPath

            val error = runCatching {
                client(server).uploadCurrent(
                    titleId,
                    buildId,
                    CurrentGameFiles(cheat, "new notes".toByteArray()),
                    null,
                )
            }.exceptionOrNull()

            assertTrue(error is FtpReplyError)
            assertArrayEquals(oldCheat, server.files.getValue(cheatPath))
            assertArrayEquals(oldNotes, server.files.getValue(notesPath))
            assertFalse(server.files.keys.any { ".nscheatmanager-" in it })
        }
    }

    @Test
    fun directOverwriteRestoresRemoteBackupWhenVerificationFails() = runTest { FakeFtpServer().use { server ->
        val old = "old cheat".toByteArray()
        server.files[cheatPath] = old
        server.corruptNextTargetRetrieve = cheatPath

        val error = runCatching {
            client(server).uploadCurrent(
                titleId,
                buildId,
                CurrentGameFiles(cheat, null),
                DirectOverwriteAuthorization.confirmed(),
            )
        }.exceptionOrNull()

        assertTrue(error is FtpVerificationError)
        assertArrayEquals(old, server.files.getValue(cheatPath))
        assertTrue(server.commands.contains("QUIT"))
    } }

    @Test
    fun directOverwriteRestoresRemoteBackupWhenStoreFailsAfterTruncatingTarget() = runTest {
        FakeFtpServer().use { server ->
            val old = "old cheat".toByteArray()
            server.files[cheatPath] = old
            server.failStorePath = cheatPath

            val error = runCatching {
                client(server).uploadCurrent(
                    titleId,
                    buildId,
                    CurrentGameFiles(cheat, null),
                    DirectOverwriteAuthorization.confirmed(),
                )
            }.exceptionOrNull()

            assertTrue(error is FtpReplyError)
            assertArrayEquals(old, server.files.getValue(cheatPath))
        }
    }

    @Test
    fun directOverwriteReportsIncompleteRecoveryWhenANewPartialTargetCannotBeDeleted() = runTest {
        FakeFtpServer().use { server ->
            server.failStorePath = cheatPath
            server.failDeletePath = cheatPath

            val error = runCatching {
                client(server).uploadCurrent(
                    titleId,
                    buildId,
                    CurrentGameFiles(cheat, null),
                    DirectOverwriteAuthorization.confirmed(),
                )
            }.exceptionOrNull()

            assertTrue(error is FtpRollbackError)
            assertTrue(server.files.containsKey(cheatPath))
        }
    }

    @Test
    fun oversizedDownloadFailsAndAlwaysDisconnects() = runTest { FakeFtpServer().use { server ->
        server.files[cheatPath] = ByteArray(65) { 1 }

        val error = runCatching {
            client(server, maxFileBytes = 64).downloadCurrent(titleId, buildId)
        }.exceptionOrNull()

        assertTrue(error is FtpSizeLimitError)
        assertTrue(server.awaitNoControlConnections())
        assertTrue(server.commands.contains("QUIT"))
    } }

    @Test
    fun stalledDataTransferTimesOutAndDisconnects() = runTest { FakeFtpServer(retrieveDelayMillis = 500).use { server ->
        server.files[cheatPath] = cheat

        val error = runCatching {
            client(server, dataTimeoutMillis = 50).downloadCurrent(titleId, buildId)
        }.exceptionOrNull()

        assertTrue(error is FtpTimeoutError)
        assertTrue(server.awaitNoControlConnections())
    } }

    @Test
    fun totalDeadlineClosesADataSocketBlockedDuringUpload() = runTest {
        FakeFtpServer().use { server ->
            server.blockStorePath = cheatPath + ".nscheatmanager"
            val payload = ByteArray(1024 * 1024) { 0x41 }
            val boundedClient = client(
                server,
                maxFileBytes = payload.size,
                dataTimeoutMillis = 5_000,
                totalTransferTimeoutMillis = 250,
            )

            val elapsed = measureTimeMillis {
                val error = runCatching {
                    boundedClient.uploadCurrent(titleId, buildId, CurrentGameFiles(payload, null), null)
                }.exceptionOrNull()
                assertTrue(error is FtpTimeoutError)
            }

            assertTrue("deadline took $elapsed ms", elapsed < 2_000)
            server.releaseBlockedStore()
            assertTrue(server.awaitNoControlConnections())
            assertTrue(server.awaitNoDataConnections())
            assertFalse(server.files.keys.any { ".nscheatmanager-" in it })
        }
    }

    @Test
    fun cancellationClosesADataSocketBlockedDuringUpload() = runTest {
        FakeFtpServer().use { server ->
            server.blockStorePath = cheatPath + ".nscheatmanager"
            val payload = ByteArray(1024 * 1024) { 0x42 }
            val boundedClient = client(
                server,
                maxFileBytes = payload.size,
                dataTimeoutMillis = 5_000,
                totalTransferTimeoutMillis = 5_000,
            )
            val transfer = launch {
                boundedClient.uploadCurrent(titleId, buildId, CurrentGameFiles(payload, null), null)
            }
            withTimeout(2_000) { server.awaitBlockedStore() }

            val elapsed = measureTimeMillis { transfer.cancelAndJoin() }

            assertTrue("cancellation took $elapsed ms", elapsed < 2_000)
            server.releaseBlockedStore()
            assertTrue(server.awaitNoControlConnections())
            assertTrue(server.awaitNoDataConnections())
            assertFalse(server.files.keys.any { ".nscheatmanager-" in it })
        }
    }

    @Test
    fun directOverwriteDeadlineUsesFreshConnectionToRestoreOldTarget() = runTest {
        FakeFtpServer().use { server ->
            val old = "old cheat".toByteArray()
            server.files[cheatPath] = old
            server.blockStorePath = cheatPath
            val payload = ByteArray(1024 * 1024) { 0x43 }
            val boundedClient = client(
                server,
                maxFileBytes = payload.size,
                dataTimeoutMillis = 5_000,
                totalTransferTimeoutMillis = 250,
            )

            val error = runCatching {
                boundedClient.uploadCurrent(
                    titleId,
                    buildId,
                    CurrentGameFiles(payload, null),
                    DirectOverwriteAuthorization.confirmed(),
                )
            }.exceptionOrNull()

            assertTrue(error is FtpTimeoutError)
            assertArrayEquals(old, server.files.getValue(cheatPath))
            server.releaseBlockedStore()
            assertTrue(server.awaitNoControlConnections())
            assertTrue(server.awaitNoDataConnections())
            assertArrayEquals(old, server.files.getValue(cheatPath))
        }
    }

    private fun client(
        server: FakeFtpServer,
        maxFileBytes: Int = 1024 * 1024,
        dataTimeoutMillis: Int = 1_000,
        totalTransferTimeoutMillis: Int = 5_000,
    ) = CommonsNetSwitchFtp(
        host = "127.0.0.1",
        port = server.port,
        connectTimeoutMillis = 1_000,
        dataTimeoutMillis = dataTimeoutMillis,
        totalTransferTimeoutMillis = totalTransferTimeoutMillis,
        maxFileBytes = maxFileBytes,
    )
}

private class FakeFtpServer(
    private val renameSupported: Boolean = true,
    private val renameToSupported: Boolean = renameSupported,
    private val retrieveDelayMillis: Long = 0,
) : AutoCloseable {
    private val listener = ServerSocket(0, 20, InetAddress.getLoopbackAddress())
    private val executor = Executors.newCachedThreadPool()
    private val activeControls = java.util.concurrent.atomic.AtomicInteger()
    private val activeData = java.util.concurrent.atomic.AtomicInteger()
    private val blockedStoreAccepted = java.util.concurrent.CountDownLatch(1)
    private val releaseBlockedStore = java.util.concurrent.CountDownLatch(1)
    val port: Int = listener.localPort
    val files: MutableMap<String, ByteArray> = ConcurrentHashMap()
    val commands: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val retrievedPaths: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val createdDirectories: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    private val storedTargets: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    private val publishedTargets: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    @Volatile var corruptNextTargetRetrieve: String? = null
    @Volatile var wrongSizeAfterPublishPath: String? = null
    @Volatile var failStorePath: String? = null
    @Volatile var failDeletePath: String? = null
    val permissionDeniedRetrievePaths: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    @Volatile var denyBackupDeletes: Boolean = false
    @Volatile var failRenameToPath: String? = null
    @Volatile var blockStorePath: String? = null
    @Volatile private var closed = false

    init {
        executor.execute {
            while (!closed) {
                try {
                    val socket = listener.accept()
                    activeControls.incrementAndGet()
                    executor.execute { handleControl(socket) }
                } catch (_: Exception) {
                    if (!closed) throw AssertionError("FTP control accept failed")
                }
            }
        }
    }

    fun awaitNoControlConnections(): Boolean {
        repeat(50) {
            if (activeControls.get() == 0) return true
            Thread.sleep(10)
        }
        return activeControls.get() == 0
    }

    fun awaitNoDataConnections(): Boolean {
        repeat(50) {
            if (activeData.get() == 0) return true
            Thread.sleep(10)
        }
        return activeData.get() == 0
    }

    suspend fun awaitBlockedStore() {
        while (!blockedStoreAccepted.await(10, TimeUnit.MILLISECONDS)) kotlinx.coroutines.yield()
    }

    fun releaseBlockedStore() {
        releaseBlockedStore.countDown()
    }

    private fun handleControl(socket: Socket) {
        var passive: ServerSocket? = null
        var renameFrom: String? = null
        try {
            socket.soTimeout = 5_000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII))
            fun reply(line: String) {
                writer.write(line)
                writer.write("\r\n")
                writer.flush()
            }
            reply("220 fake ready")
            while (true) {
                val line = reader.readLine() ?: break
                commands += line
                val command = line.substringBefore(' ').uppercase()
                val argument = line.substringAfter(' ', "")
                when (command) {
                    "USER" -> reply("331 password required")
                    "PASS" -> reply("230 logged in")
                    "TYPE" -> reply("200 type set")
                    "SYST" -> reply("215 UNIX Type: L8")
                    "OPTS" -> reply("200 options accepted")
                    "NOOP" -> reply("200 okay")
                    "PASV" -> {
                        passive?.close()
                        passive = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
                        val p = passive.localPort
                        reply("227 Entering Passive Mode (127,0,0,1,${p / 256},${p % 256})")
                    }
                    "EPSV" -> {
                        passive?.close()
                        passive = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
                        reply("229 Entering Extended Passive Mode (|||${passive.localPort}|)")
                    }
                    "RETR" -> {
                        retrievedPaths += argument
                        val bytes = files[argument]
                        if (permissionDeniedRetrievePaths.contains(argument)) {
                            passive?.close()
                            passive = null
                            reply("550 permission denied")
                        } else if (bytes == null) {
                            passive?.close()
                            passive = null
                            reply("550 not found")
                        } else {
                            reply("150 opening data")
                            passive!!.accept().use { data ->
                                activeData.incrementAndGet()
                                if (retrieveDelayMillis > 0) Thread.sleep(retrieveDelayMillis)
                                val outgoing = if (
                                    corruptNextTargetRetrieve == argument && storedTargets.contains(argument)
                                ) {
                                    corruptNextTargetRetrieve = null
                                    bytes + 0x55.toByte()
                                } else {
                                    bytes
                                }
                                data.getOutputStream().write(outgoing)
                                activeData.decrementAndGet()
                            }
                            passive.close()
                            passive = null
                            reply("226 transfer complete")
                        }
                    }
                    "STOR" -> {
                        reply("150 opening data")
                        val received: ByteArray? = passive!!.accept().use { data ->
                            activeData.incrementAndGet()
                            try {
                                if (blockStorePath != null && argument.startsWith(blockStorePath!!)) {
                                    val marker = byteArrayOf()
                                    files[argument] = marker
                                    blockStorePath = null
                                    blockedStoreAccepted.countDown()
                                    releaseBlockedStore.await(10, TimeUnit.SECONDS)
                                    val bytes = data.getInputStream().readBytes()
                                    bytes.takeIf { files[argument] === marker }
                                } else {
                                    data.getInputStream().readBytes()
                                }
                            } finally {
                                activeData.decrementAndGet()
                            }
                        }
                        if (received != null) {
                            files[argument] = if (failStorePath == argument) {
                                received.copyOf(received.size.coerceAtMost(3))
                            } else {
                                received
                            }
                        }
                        storedTargets += argument
                        passive.close()
                        passive = null
                        if (failStorePath == argument) {
                            failStorePath = null
                            reply("451 local write failed")
                        } else {
                            reply("226 transfer complete")
                        }
                    }
                    "MLST" -> if (permissionDeniedRetrievePaths.contains(argument)) {
                        reply("550 permission denied")
                    } else if (files.containsKey(argument)) {
                        reply("250 type=file;size=${files.getValue(argument).size}; $argument")
                    } else {
                        reply("550 not found")
                    }
                    "SIZE" -> if (permissionDeniedRetrievePaths.contains(argument)) {
                        reply("550 permission denied")
                    } else files[argument]?.let {
                        val size = if (
                            wrongSizeAfterPublishPath == argument && publishedTargets.remove(argument)
                        ) {
                            wrongSizeAfterPublishPath = null
                            it.size + 1
                        } else {
                            it.size
                        }
                        reply("213 $size")
                    } ?: reply("550 not found")
                    "MKD" -> {
                        createdDirectories += argument
                        reply("257 created")
                    }
                    "CWD" -> reply("250 directory changed")
                    "RNFR" -> if (!renameSupported) {
                        reply("502 rename unsupported")
                    } else if (files.containsKey(argument)) {
                        renameFrom = argument
                        reply("350 ready for destination")
                    } else {
                        reply("550 not found")
                    }
                    "RNTO" -> if (failRenameToPath == argument) {
                        failRenameToPath = null
                        reply("450 rename failed")
                    } else if (!renameToSupported) {
                        reply("502 rename unsupported")
                    } else {
                        val source = renameFrom
                        if (source == null || !files.containsKey(source)) {
                            reply("503 bad sequence")
                        } else {
                            files[argument] = files.remove(source)!!
                            if (".nscheatmanager-upload-" in source) publishedTargets += argument
                            renameFrom = null
                            reply("250 renamed")
                        }
                    }
                    "DELE" -> if (denyBackupDeletes && ".nscheatmanager-backup-" in argument) {
                        reply("550 permission denied")
                    } else if (failDeletePath == argument) {
                        reply("550 permission denied")
                    } else if (files.remove(argument) != null) {
                        reply("250 deleted")
                    } else {
                        reply("550 not found")
                    }
                    "QUIT" -> {
                        reply("221 bye")
                        break
                    }
                    else -> reply("502 unsupported $command")
                }
            }
        } catch (_: SocketTimeoutException) {
            // The client timeout/cleanup behavior is the subject of the test.
        } catch (_: Exception) {
            // A timed-out data socket may close while the fixture is writing.
        } finally {
            runCatching { passive?.close() }
            runCatching { socket.close() }
            activeControls.decrementAndGet()
        }
    }

    override fun close() {
        closed = true
        releaseBlockedStore.countDown()
        listener.close()
        executor.shutdownNow()
        executor.awaitTermination(2, TimeUnit.SECONDS)
    }
}
