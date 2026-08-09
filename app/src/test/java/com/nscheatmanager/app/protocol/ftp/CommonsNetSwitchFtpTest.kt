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

    private fun client(
        server: FakeFtpServer,
        maxFileBytes: Int = 1024 * 1024,
        dataTimeoutMillis: Int = 1_000,
    ) = CommonsNetSwitchFtp(
        host = "127.0.0.1",
        port = server.port,
        connectTimeoutMillis = 1_000,
        dataTimeoutMillis = dataTimeoutMillis,
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
                        if (bytes == null) {
                            passive?.close()
                            passive = null
                            reply("550 not found")
                        } else {
                            reply("150 opening data")
                            passive!!.accept().use { data ->
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
                            }
                            passive.close()
                            passive = null
                            reply("226 transfer complete")
                        }
                    }
                    "STOR" -> {
                        reply("150 opening data")
                        val received = passive!!.accept().use { data -> data.getInputStream().readBytes() }
                        files[argument] = if (failStorePath == argument) {
                            received.copyOf(received.size.coerceAtMost(3))
                        } else {
                            received
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
                    "SIZE" -> files[argument]?.let {
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
                    "RNTO" -> if (!renameToSupported) {
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
                    "DELE" -> if (failDeletePath == argument) {
                        reply("450 delete failed")
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
        listener.close()
        executor.shutdownNow()
        executor.awaitTermination(2, TimeUnit.SECONDS)
    }
}
