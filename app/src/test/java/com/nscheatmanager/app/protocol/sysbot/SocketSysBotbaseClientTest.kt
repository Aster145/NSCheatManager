package com.nscheatmanager.app.protocol.sysbot

import com.nscheatmanager.app.core.model.MemoryTarget
import com.nscheatmanager.app.protocol.FakeLineServer
import com.nscheatmanager.app.protocol.ProtocolError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SocketSysBotbaseClientTest {
    @Test
    fun recognizesCurrentGameAcrossFragmentedResponses() = runTest {
        FakeLineServer(
            responses = mapOf(
                "getTitleID" to "0100F2C0115B6000\n",
                "getBuildID" to "A4A8D3E7F29C81A2\n",
                "getMainNsoBase" to "7100000000\n",
                "getHeapBase" to "120000000\n",
            ),
            fragmentResponses = true,
        ).use { server ->
            val client = newClient(server)

            val identity = client.recognizeGame()

            assertEquals("0100F2C0115B6000", identity.titleId.hex)
            assertEquals("A4A8D3E7F29C81A2", identity.buildId.hex)
            assertEquals(0x7100000000uL, identity.mainBase)
            assertEquals(0x120000000uL, identity.heapBase)
            assertEquals(
                listOf("getTitleID", "getBuildID", "getMainNsoBase", "getHeapBase"),
                server.commands.take(4),
            )
            assertEquals(List(4) { "\r\n" }, server.lineEndings.take(4))
        }
    }

    @Test
    fun readsEachMemoryTargetWithDocumentedCommand() = runTest {
        FakeLineServer(
            responses = mapOf(
                "peekAbsolute 0x1234 4" to "01020304\n",
                "peekMain 0x20 2" to "A0B0\n",
                "peek 0x30 1" to "FF\n",
            ),
            fragmentResponses = true,
        ).use { server ->
            val client = newClient(server)

            assertArrayEquals(
                byteArrayOf(1, 2, 3, 4),
                client.read(MemoryTarget.Absolute(0x1234u), 4),
            )
            assertArrayEquals(
                byteArrayOf(0xA0.toByte(), 0xB0.toByte()),
                client.read(MemoryTarget.MainRelative(0x20u), 2),
            )
            assertArrayEquals(
                byteArrayOf(0xFF.toByte()),
                client.read(MemoryTarget.HeapRelative(0x30u), 1),
            )
            assertEquals(
                listOf(
                    "peekAbsolute 0x1234 4",
                    "peekMain 0x20 2",
                    "peek 0x30 1",
                ),
                server.commands,
            )
        }
    }

    @Test
    fun writesEachMemoryTargetOnceWithUppercaseHexBytes() = runTest {
        FakeLineServer().use { server ->
            val client = newClient(server)

            client.write(MemoryTarget.Absolute(0x89u), byteArrayOf(0xDE.toByte(), 0xAD.toByte()))
            client.write(MemoryTarget.MainRelative(0xABu), byteArrayOf(0, 0xFF.toByte()))
            client.write(MemoryTarget.HeapRelative(0xCDu), byteArrayOf(0x12))
            server.awaitCommandCount(3)

            assertEquals(
                listOf(
                    "pokeAbsolute 0x89 0xDEAD",
                    "pokeMain 0xAB 0x00FF",
                    "poke 0xCD 0x12",
                ),
                server.commands,
            )
        }
    }

    @Test
    fun acceptsLargestAbsoluteWriteThatFitsUpstreamCommandBuffer() = runTest {
        FakeLineServer().use { server ->
            val client = newClient(server)
            val payload = ByteArray(10_990) { 0xAB.toByte() }

            client.write(MemoryTarget.Absolute(ULong.MAX_VALUE), payload)
            server.awaitCommandCount(1)

            val commandBytes = server.commands.single().toByteArray(Charsets.US_ASCII).size
            val lineEndingBytes = server.lineEndings.single().toByteArray(Charsets.US_ASCII).size
            assertEquals(22_016, commandBytes + lineEndingBytes)
        }
    }

    @Test
    fun rejectsOversizeWriteBeforeConnectingOrSending() = runTest {
        val server = FakeLineServer()
        val client = newClient(server)
        server.close()

        val error = expectThrows<ProtocolError.CommandTooLarge> {
            client.write(MemoryTarget.Absolute(ULong.MAX_VALUE), ByteArray(10_991))
        }

        assertEquals(22_016, error.limitBytes)
        assertEquals(22_018L, error.actualBytes)
        assertTrue(server.commands.isEmpty())
    }

    @Test
    fun freezesResolvedAbsoluteAddressAndUsesExactUnFreezeSpelling() = runTest {
        FakeLineServer().use { server ->
            val client = newClient(server)

            client.freeze(0x7100ABCDEFu, byteArrayOf(0x78, 0x56, 0x34, 0x12))
            client.unfreeze(0x7100ABCDEFu)
            server.awaitCommandCount(2)

            assertEquals(
                listOf(
                    "freeze 0x7100ABCDEF 0x78563412",
                    "unFreeze 0x7100ABCDEF",
                ),
                server.commands,
            )
        }
    }

    @Test
    fun timesOutWhenAResponseDoesNotArrive() = runTest {
        FakeLineServer(responses = mapOf("peekMain 0x10 1" to "AA\n"), responseDelayMillis = 250)
            .use { server ->
                val client = newClient(server, readTimeoutMillis = 30)

                val error = expectThrows<ProtocolError.Timeout> {
                    client.read(MemoryTarget.MainRelative(0x10u), 1)
                }

                assertEquals("response", error.operation)
                assertEquals(listOf("peekMain 0x10 1"), server.commands)
            }
    }

    @Test
    fun readTimeoutIsATotalDeadlineForSlowDripResponses() = runTest {
        FakeLineServer(
            responses = mapOf("peekAbsolute 0x1 2" to "AABB\n"),
            fragmentResponses = true,
            fragmentDelayMillis = 20,
        ).use { server ->
            val client = newClient(server, readTimeoutMillis = 30)

            expectThrows<ProtocolError.Timeout> {
                client.read(MemoryTarget.Absolute(1u), 2)
            }
        }
    }

    @Test
    fun rejectsAResponseBeyondTheConfiguredBound() = runTest {
        FakeLineServer(responses = mapOf("peekAbsolute 0x1 5" to "0011223344\n"))
            .use { server ->
                val client = newClient(server, maxResponseBytes = 8)

                val error = expectThrows<ProtocolError.ResponseTooLarge> {
                    client.read(MemoryTarget.Absolute(1u), 5)
                }

                assertEquals(8, error.limitBytes)
            }
    }

    @Test
    fun countsIgnoredCarriageReturnsTowardTheResponseBound() = runTest {
        FakeLineServer(responses = mapOf("peekAbsolute 0x1 1" to "\r\r\r\r\r\r\r\r\r\n"))
            .use { server ->
                val client = newClient(server, maxResponseBytes = 8)

                expectThrows<ProtocolError.ResponseTooLarge> {
                    client.read(MemoryTarget.Absolute(1u), 1)
                }
            }
    }

    @Test
    fun disconnectPreventsRequestsUntilExplicitReconnect() = runTest {
        FakeLineServer(responses = mapOf("peek 0x4 1" to "7F\n")).use { server ->
            val client = newClient(server)
            client.connect()
            client.disconnect()

            expectThrows<ProtocolError.Disconnected> {
                client.read(MemoryTarget.HeapRelative(4u), 1)
            }
            assertTrue(server.commands.isEmpty())

            client.connect()
            assertArrayEquals(byteArrayOf(0x7F), client.read(MemoryTarget.HeapRelative(4u), 1))
        }
    }

    @Test
    fun reportsRemoteDisconnectWhileWaitingForAResponse() = runTest {
        FakeLineServer(closeOnCommand = "peekAbsolute 0x55 2").use { server ->
            val client = newClient(server)

            expectThrows<ProtocolError.Disconnected> {
                client.read(MemoryTarget.Absolute(0x55u), 2)
            }

            assertEquals(listOf("peekAbsolute 0x55 2"), server.commands)
        }
    }

    @Test
    fun concurrentCallersReceiveTheResponseForTheirOwnCommand() = runTest {
        FakeLineServer(
            responses = mapOf(
                "peekAbsolute 0x1 1" to "11\n",
                "peekAbsolute 0x2 1" to "22\n",
            ),
            fragmentResponses = true,
            responseDelayMillis = 100,
        ).use { server ->
            val client = newClient(server)

            val results = coroutineScope {
                val first = async(Dispatchers.IO) { client.read(MemoryTarget.Absolute(1u), 1) }
                server.awaitCommandCount(1)
                val second = async(Dispatchers.IO) { client.read(MemoryTarget.Absolute(2u), 1) }
                listOf(first.await(), second.await())
            }

            assertArrayEquals(byteArrayOf(0x11), results[0])
            assertArrayEquals(byteArrayOf(0x22), results[1])
            assertEquals(
                listOf("peekAbsolute 0x1 1", "peekAbsolute 0x2 1"),
                server.commands,
            )
        }
    }

    private fun newClient(
        server: FakeLineServer,
        readTimeoutMillis: Int = 1_000,
        maxResponseBytes: Int = 1_024,
    ) = SocketSysBotbaseClient(
        host = server.host,
        port = server.port,
        dispatcher = Dispatchers.IO,
        connectTimeoutMillis = 1_000,
        readTimeoutMillis = readTimeoutMillis,
        maxResponseBytes = maxResponseBytes,
    )

    private suspend inline fun <reified T : Throwable> expectThrows(
        noinline block: suspend () -> Unit,
    ): T = try {
        block()
        fail("Expected ${T::class.java.simpleName}")
        throw AssertionError("unreachable")
    } catch (error: Throwable) {
        assertTrue("Expected ${T::class.java.name}, got ${error::class.java.name}", error is T)
        error as T
    }
}
