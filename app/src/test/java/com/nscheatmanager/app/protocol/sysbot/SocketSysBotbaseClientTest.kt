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
                "peekAbsolute 0x0000000000001234 4" to "01020304\n",
                "peekMain 0x0000000000000020 2" to "A0B0\n",
                "peek 0x00000030 1" to "FF\n",
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
                    "peekAbsolute 0x0000000000001234 4",
                    "peekMain 0x0000000000000020 2",
                    "peek 0x00000030 1",
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
                    "pokeAbsolute 0x0000000000000089 0xDEAD",
                    "pokeMain 0x00000000000000AB 0x00FF",
                    "poke 0x000000CD 0x12",
                ),
                server.commands,
            )
        }
    }

    @Test
    fun splitsLargeReadsAndWritesInto448ByteChunksWithIncrementedAddresses() = runTest {
        val firstRead = "AA".repeat(448)
        val secondRead = "BB".repeat(448)
        val thirdRead = "CC".repeat(4)
        FakeLineServer(responses = mapOf(
            "peekAbsolute 0x0000000000001000 448" to "$firstRead\n",
            "peekAbsolute 0x00000000000011C0 448" to "$secondRead\n",
            "peekAbsolute 0x0000000000001380 4" to "$thirdRead\n",
        )).use { server ->
            val client = newClient(server)
            val payload = ByteArray(900) { (it and 0xFF).toByte() }

            val read = client.read(MemoryTarget.Absolute(0x1000u), 900)
            client.write(MemoryTarget.Absolute(0x2000u), payload)
            server.awaitCommandCount(6)

            assertEquals(900, read.size)
            assertEquals(448, read.takeWhile { it == 0xAA.toByte() }.size)
            assertEquals(
                listOf(
                    "peekAbsolute 0x0000000000001000 448",
                    "peekAbsolute 0x00000000000011C0 448",
                    "peekAbsolute 0x0000000000001380 4",
                    "pokeAbsolute 0x0000000000002000 0x${payload.copyOfRange(0, 448).toHex()}",
                    "pokeAbsolute 0x00000000000021C0 0x${payload.copyOfRange(448, 896).toHex()}",
                    "pokeAbsolute 0x0000000000002380 0x${payload.copyOfRange(896, 900).toHex()}",
                ),
                server.commands,
            )
        }
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
                    "freeze 0x0000007100ABCDEF 0x78563412",
                    "unFreeze 0x0000007100ABCDEF",
                ),
                server.commands,
            )
        }
    }

    @Test
    fun timesOutWhenAResponseDoesNotArrive() = runTest {
        FakeLineServer(responses = mapOf("peekMain 0x0000000000000010 1" to "AA\n"), responseDelayMillis = 250)
            .use { server ->
                val client = newClient(server, readTimeoutMillis = 30)

                val error = expectThrows<ProtocolError.Timeout> {
                    client.read(MemoryTarget.MainRelative(0x10u), 1)
                }

                assertEquals("response", error.operation)
                assertEquals(listOf("peekMain 0x0000000000000010 1"), server.commands)
            }
    }

    @Test
    fun readTimeoutIsATotalDeadlineForSlowDripResponses() = runTest {
        FakeLineServer(
            responses = mapOf("peekAbsolute 0x0000000000000001 2" to "AABB\n"),
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
        FakeLineServer(responses = mapOf("peekAbsolute 0x0000000000000001 5" to "0011223344\n"))
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
        FakeLineServer(responses = mapOf("peekAbsolute 0x0000000000000001 1" to "\r\r\r\r\r\r\r\r\r\n"))
            .use { server ->
                val client = newClient(server, maxResponseBytes = 8)

                expectThrows<ProtocolError.ResponseTooLarge> {
                    client.read(MemoryTarget.Absolute(1u), 1)
                }
            }
    }

    @Test
    fun disconnectPreventsRequestsUntilExplicitReconnect() = runTest {
        FakeLineServer(responses = mapOf("peek 0x00000004 1" to "7F\n")).use { server ->
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
        FakeLineServer(closeOnCommand = "peekAbsolute 0x0000000000000055 2").use { server ->
            val client = newClient(server)

            expectThrows<ProtocolError.Disconnected> {
                client.read(MemoryTarget.Absolute(0x55u), 2)
            }

            assertEquals(listOf("peekAbsolute 0x0000000000000055 2"), server.commands)
        }
    }

    @Test
    fun concurrentCallersReceiveTheResponseForTheirOwnCommand() = runTest {
        FakeLineServer(
            responses = mapOf(
                "peekAbsolute 0x0000000000000001 1" to "11\n",
                "peekAbsolute 0x0000000000000002 1" to "22\n",
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
                listOf("peekAbsolute 0x0000000000000001 1", "peekAbsolute 0x0000000000000002 1"),
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

private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it.toInt() and 0xFF) }
