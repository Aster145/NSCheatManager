package com.nscheatmanager.app.cheats.vm

import com.nscheatmanager.app.cheats.parser.CheatFileParser
import com.nscheatmanager.app.cheats.parser.CheatGroup
import com.nscheatmanager.app.core.model.BuildId
import com.nscheatmanager.app.core.model.MemoryTarget
import com.nscheatmanager.app.core.model.TitleId
import com.nscheatmanager.app.protocol.ProtocolError
import com.nscheatmanager.app.protocol.sysbot.GameIdentity
import com.nscheatmanager.app.protocol.sysbot.SysBotbase
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CheatExecutorTest {
    private val identity = GameIdentity(
        titleId = TitleId.parse("0100F2C0115B6000"),
        buildId = BuildId.parse("A4A8D3E7F29C81A2"),
        mainBase = 0x1000_0000uL,
        heapBase = 0x2000_0000uL,
    )

    @Test
    fun staticWritesUseMainHeapAndAbsoluteAddressesWithSwitchLittleEndian() = runTest {
        val memory = RecordingSysBotbase()
        val group = parseGroup(
            """
            [static]
            04000000 00000010 DEADBEEF
            04100000 00000020 12345678
            04400000 00001234 A1B2C3D4
            """.trimIndent(),
        )

        val report = CheatExecutor().execute(group, identity, memory)

        assertEquals(ExecutionStatus.Complete, report.status)
        assertEquals(3, report.completedWrites)
        assertEquals(
            listOf(0x1000_0010uL, 0x2000_0020uL, 0x1234uL),
            memory.writes.map { (it.target as MemoryTarget.Absolute).address },
        )
        assertArrayEquals(
            byteArrayOf(0xEF.toByte(), 0xBE.toByte(), 0xAD.toByte(), 0xDE.toByte()),
            memory.writes[0].bytes,
        )
        assertArrayEquals(byteArrayOf(0x78, 0x56, 0x34, 0x12), memory.writes[1].bytes)
    }

    @Test
    fun narrowStaticWritesUseTheDocumentedLowLittleEndianBytes() = runTest {
        val memory = RecordingSysBotbase()
        val group = parseGroup(
            """
            [narrow]
            01000000 00000010 112233FF
            40000000 00000000 00001000
            62000000 00000000 AABBCCDD
            """.trimIndent(),
        )

        val report = CheatExecutor().execute(group, identity, memory)

        assertEquals(ExecutionStatus.Complete, report.status)
        assertArrayEquals(byteArrayOf(0xFF.toByte()), memory.writes[0].bytes)
        assertArrayEquals(byteArrayOf(0xDD.toByte(), 0xCC.toByte()), memory.writes[1].bytes)
    }

    @Test
    fun commonPointerChainReadsAbsolutePointersAndWritesExpectedAddressOnce() = runTest {
        val memory = RecordingSysBotbase(
            reads = mutableMapOf(
                0x146A_12B0uL to littleEndian(0x3000_0000uL),
                0x3000_0028uL to littleEndian(0x4000_0000uL),
            ),
        )
        val group = parseGroup(
            """
            [pointer]
            580F0000 046A12B0
            580F1000 00000028
            780F0000 00000084
            640F0000 00000000 00000064
            """.trimIndent(),
        )

        val report = CheatExecutor().execute(group, identity, memory)

        assertEquals(ExecutionStatus.Complete, report.status)
        assertEquals(
            listOf(
                ReadCall(MemoryTarget.Absolute(0x146A_12B0uL), 8),
                ReadCall(MemoryTarget.Absolute(0x3000_0028uL), 8),
            ),
            memory.readCalls,
        )
        assertEquals(0x4000_0084uL, (memory.writes.single().target as MemoryTarget.Absolute).address)
        assertArrayEquals(byteArrayOf(0x64, 0, 0, 0), memory.writes.single().bytes)
    }

    @Test
    fun codeSixIncrementAndOffsetRegisterFormsResolveSequentially() = runTest {
        val memory = RecordingSysBotbase()
        val group = parseGroup(
            """
            [six]
            40000000 00000000 00001000
            40010000 00000000 00000020
            68001000 01020304 05060708
            64000110 00000000 AABBCCDD
            """.trimIndent(),
        )

        val report = CheatExecutor().execute(group, identity, memory)

        assertEquals(ExecutionStatus.Complete, report.status)
        assertEquals(listOf(0x1000uL, 0x1028uL), memory.writeAddresses())
        assertArrayEquals(
            byteArrayOf(8, 7, 6, 5, 4, 3, 2, 1),
            memory.writes.first().bytes,
        )
    }

    @Test
    fun legacyAdditionAndSubtractionAreAppliedWithoutWrapping() = runTest {
        val memory = RecordingSysBotbase()
        val group = parseGroup(
            """
            [legacy]
            40000000 00000000 00001000
            78000000 00000020
            78001000 00000008
            64000000 00000000 00000001
            """.trimIndent(),
        )

        val report = CheatExecutor().execute(group, identity, memory)

        assertEquals(ExecutionStatus.Complete, report.status)
        assertEquals(listOf(0x1018uL), memory.writeAddresses())
    }

    @Test
    fun codeNineIntegerOperationsProduceHandCheckedResults() = runTest {
        val cases = listOf(
            0x0 to (9uL + 2uL),
            0x1 to (9uL - 2uL),
            0x3 to (9uL shl 2),
            0x4 to (9uL shr 2),
            0x5 to (9uL and 2uL),
            0x6 to (9uL or 2uL),
            0x8 to (9uL xor 2uL),
            0x9 to 9uL,
        )

        cases.forEach { (code, expected) ->
            val memory = RecordingSysBotbase()
            val group = parseGroup(
                """
                [nine]
                40010000 00000000 00000009
                40020000 00000000 00000002
                98${code.toString(16).uppercase()}01020
                A8000400 00000010
                """.trimIndent(),
            )

            val report = CheatExecutor().execute(group, identity, memory)

            assertEquals("operation $code", ExecutionStatus.Complete, report.status)
            assertEquals(0x1000_0010uL, memory.writeAddresses().single())
            assertArrayEquals("operation $code", littleEndian(expected), memory.writes.single().bytes)
        }
    }

    @Test
    fun codeARegisterFixedAndMemoryRelativeAddressFormsAreSupported() = runTest {
        val memory = RecordingSysBotbase()
        val group = parseGroup(
            """
            [a]
            40000000 01020304 05060708
            40010000 00000000 00001000
            40020000 00000000 00000020
            A8010000
            A8010120
            A8010200 00000010
            A8010300
            A8000400 00000018
            A8010510 00000028
            """.trimIndent(),
        )

        val report = CheatExecutor().execute(group, identity, memory)

        assertEquals(ExecutionStatus.Complete, report.status)
        assertEquals(
            listOf(
                0x1000uL,
                0x1020uL,
                0x1010uL,
                0x1000_1000uL,
                0x1000_0018uL,
                0x2000_1028uL,
            ),
            memory.writeAddresses(),
        )
        memory.writes.forEach { write ->
            assertArrayEquals(byteArrayOf(8, 7, 6, 5, 4, 3, 2, 1), write.bytes)
        }
    }

    @Test
    fun anyInvalidInstructionRejectsWholeGroupBeforeFirstNetworkCall() = runTest {
        val memory = RecordingSysBotbase()
        val group = parseGroup(
            """
            [invalid later]
            04000000 00000010 DEADBEEF
            10000000 00000000 00000000
            """.trimIndent(),
        )

        val report = CheatExecutor().execute(group, identity, memory)

        assertEquals(ExecutionStatus.Rejected, report.status)
        assertEquals(0, report.completedWrites)
        assertEquals(3, report.failureLine)
        assertTrue(memory.networkCalls.isEmpty())
    }

    @Test
    fun staticallyProvableOverflowRejectsWholeGroupBeforeEarlierWrite() = runTest {
        val memory = RecordingSysBotbase()
        val group = parseGroup(
            """
            [overflow later]
            04000000 00000010 DEADBEEF
            40000000 FFFFFFFF FFFFFFFF
            78000000 00000001
            """.trimIndent(),
        )

        val report = CheatExecutor().execute(group, identity, memory)

        assertEquals(ExecutionStatus.Rejected, report.status)
        assertEquals(4, report.failureLine)
        assertTrue(memory.networkCalls.isEmpty())
    }

    @Test
    fun identityBaseOverflowInLaterInstructionRejectsBeforeEarlierWrite() = runTest {
        val memory = RecordingSysBotbase()
        val overflowingIdentity = identity.copy(mainBase = ULong.MAX_VALUE)
        val group = parseGroup(
            """
            [base overflow]
            04400000 00001234 DEADBEEF
            04000000 00000001 00000001
            """.trimIndent(),
        )

        val report = CheatExecutor().execute(group, overflowingIdentity, memory)

        assertEquals(ExecutionStatus.Rejected, report.status)
        assertEquals(3, report.failureLine)
        assertEquals(0, report.completedWrites)
        assertTrue(memory.networkCalls.isEmpty())
    }

    @Test
    fun staticallyKnownWriteSpanCrossingAddressSpaceRejectsBeforeEarlierWrite() = runTest {
        val memory = RecordingSysBotbase()
        val group = parseGroup(
            """
            [span]
            04400000 00001000 DEADBEEF
            40000000 FFFFFFFF FFFFFFFE
            64000000 00000000 00000001
            """.trimIndent(),
        )

        val report = CheatExecutor().execute(group, identity, memory)

        assertEquals(ExecutionStatus.Rejected, report.status)
        assertEquals(4, report.failureLine)
        assertEquals(0, report.completedWrites)
        assertTrue(memory.networkCalls.isEmpty())
    }

    @Test
    fun dynamicPointerWriteSpanCrossingAddressSpaceStopsBeforeWrite() = runTest {
        val memory = RecordingSysBotbase(
            reads = mutableMapOf(0x1000_0010uL to littleEndian(ULong.MAX_VALUE - 1u)),
        )
        val group = parseGroup(
            """
            [dynamic write span]
            58000000 00000010
            64000000 00000000 00000001
            """.trimIndent(),
        )

        val report = CheatExecutor().execute(group, identity, memory)

        assertEquals(ExecutionStatus.Failed, report.status)
        assertEquals(3, report.failureLine)
        assertEquals(0, report.completedWrites)
        assertTrue(memory.writes.isEmpty())
    }

    @Test
    fun dynamicPointerReadSpanCrossingAddressSpaceStopsBeforeSecondRead() = runTest {
        val pointer = ULong.MAX_VALUE - 3u
        val memory = RecordingSysBotbase(
            reads = mutableMapOf(
                0x1000_0010uL to littleEndian(pointer),
                pointer to littleEndian(1u),
            ),
        )
        val group = parseGroup(
            """
            [dynamic read span]
            58000000 00000010
            58001000 00000000
            """.trimIndent(),
        )

        val report = CheatExecutor().execute(group, identity, memory)

        assertEquals(ExecutionStatus.Failed, report.status)
        assertEquals(3, report.failureLine)
        assertEquals(1, memory.readCalls.size)
    }

    @Test
    fun knownInvalidShiftCountRejectsEvenWhenLeftOperandComesFromPointerRead() = runTest {
        val memory = RecordingSysBotbase(
            reads = mutableMapOf(0x1000_0010uL to littleEndian(1u)),
        )
        val group = parseGroup(
            """
            [known shift]
            58000000 00000010
            40010000 00000000 00000040
            98320010
            """.trimIndent(),
        )

        val report = CheatExecutor().execute(group, identity, memory)

        assertEquals(ExecutionStatus.Rejected, report.status)
        assertEquals(4, report.failureLine)
        assertTrue(memory.networkCalls.isEmpty())
    }

    @Test
    fun knownTooWideAddOperandRejectsEvenWhenOtherOperandComesFromPointerRead() = runTest {
        val memory = RecordingSysBotbase(
            reads = mutableMapOf(0x1000_0010uL to littleEndian(1u)),
        )
        val group = parseGroup(
            """
            [known width]
            40000000 00000000 00000100
            58010000 00000010
            91000010
            """.trimIndent(),
        )

        val report = CheatExecutor().execute(group, identity, memory)

        assertEquals(ExecutionStatus.Rejected, report.status)
        assertEquals(4, report.failureLine)
        assertTrue(memory.networkCalls.isEmpty())
    }

    @Test
    fun codeNineMoveNarrowsResultsAtOneTwoAndFourBytes() = runTest {
        val cases = listOf(
            Triple(1, "00000000 000001FF", byteArrayOf(0xFF.toByte())),
            Triple(2, "00000000 0001FFFF", byteArrayOf(0xFF.toByte(), 0xFF.toByte())),
            Triple(4, "00000001 FFFFFFFF", byteArrayOf(-1, -1, -1, -1)),
        )

        cases.forEach { (width, sourceWords, expected) ->
            val memory = RecordingSysBotbase()
            val group = parseGroup(
                """
                [move $width]
                40010000 $sourceWords
                40030000 00000000 00001000
                9${width}901020
                A${width}030000
                """.trimIndent(),
            )

            val report = CheatExecutor().execute(group, identity, memory)

            assertEquals("width $width", ExecutionStatus.Complete, report.status)
            assertArrayEquals("width $width", expected, memory.writes.single().bytes)
        }
    }

    @Test
    fun immediateMoveIgnoresNonzeroDiscardedImmediateHighBits() = runTest {
        val memory = RecordingSysBotbase()
        val group = parseGroup(
            """
            [immediate move]
            40010000 00000000 000001FF
            40030000 00000000 00001000
            91901100 DEADBEEF
            A1030000
            """.trimIndent(),
        )

        val report = CheatExecutor().execute(group, identity, memory)

        assertEquals(ExecutionStatus.Complete, report.status)
        assertArrayEquals(byteArrayOf(0xFF.toByte()), memory.writes.single().bytes)
    }

    @Test
    fun narrowImmediateArithmeticDecodesTheDocumentedLowWidthVmInteger() = runTest {
        val memory = RecordingSysBotbase()
        val group = parseGroup(
            """
            [immediate vm integer]
            40010000 00000000 00000001
            40030000 00000000 00001000
            91001100 DEADBEEF
            A1030000
            """.trimIndent(),
        )

        val report = CheatExecutor().execute(group, identity, memory)

        assertEquals(ExecutionStatus.Complete, report.status)
        assertArrayEquals(byteArrayOf(0xF0.toByte()), memory.writes.single().bytes)
    }

    @Test
    fun narrowBitwiseAndRightShiftComputeThenNarrow() = runTest {
        val cases = listOf(
            Triple(0x4, 0x001uL, 0xFF),
            Triple(0x5, 0x10FuL, 0x0E),
            Triple(0x6, 0x10FuL, 0xFF),
            Triple(0x8, 0x10FuL, 0xF1),
        )

        cases.forEach { (code, right, expected) ->
            val memory = RecordingSysBotbase()
            val group = parseGroup(
                """
                [narrow $code]
                40010000 00000000 000001FE
                40020000 00000000 ${right.toString(16).uppercase().padStart(8, '0')}
                40030000 00000000 00001000
                91${code.toString(16).uppercase()}01020
                A1030000
                """.trimIndent(),
            )

            val report = CheatExecutor().execute(group, identity, memory)

            assertEquals("operation $code", ExecutionStatus.Complete, report.status)
            assertArrayEquals("operation $code", byteArrayOf(expected.toByte()), memory.writes.single().bytes)
        }
    }

    @Test
    fun codeAIncrementAdvancesAddressRegisterByWriteWidth() = runTest {
        val memory = RecordingSysBotbase()
        val group = parseGroup(
            """
            [increment]
            40000000 01020304 05060708
            40010000 00000000 00001000
            A8011000
            A8010000
            """.trimIndent(),
        )

        val report = CheatExecutor().execute(group, identity, memory)

        assertEquals(ExecutionStatus.Complete, report.status)
        assertEquals(listOf(0x1000uL, 0x1008uL), memory.writeAddresses())
    }

    @Test
    fun legacyArithmeticAndStaticWritesExecuteAtEveryApprovedWidth() = runTest {
        listOf(1, 2, 4, 8).forEach { width ->
            val memory = RecordingSysBotbase()
            val staticWrite = if (width == 8) {
                "08000000 00000020 01020304 05060708"
            } else {
                "0${width}000000 00000020 11223344"
            }
            val group = parseGroup(
                """
                [width $width]
                $staticWrite
                40000000 00000000 00000010
                7${width}000000 00000001
                6${width}000000 01020304 05060708
                """.trimIndent(),
            )

            val report = CheatExecutor().execute(group, identity, memory)

            assertEquals("width $width", ExecutionStatus.Complete, report.status)
            assertEquals(listOf(0x1000_0020uL, 0x11uL), memory.writeAddresses())
            assertEquals(width, memory.writes.last().bytes.size)
        }
    }

    @Test
    fun nullPointerStopsBeforeAnyWriteAndReportsItsSourceLine() = runTest {
        val memory = RecordingSysBotbase(
            reads = mutableMapOf(0x1000_0010uL to littleEndian(0uL)),
        )
        val group = parseGroup(
            """
            [null]
            58000000 00000010
            64000000 00000000 00000001
            """.trimIndent(),
        )

        val report = CheatExecutor().execute(group, identity, memory)

        assertEquals(ExecutionStatus.Failed, report.status)
        assertEquals(2, report.failureLine)
        assertEquals(0, report.completedWrites)
        assertTrue(memory.writes.isEmpty())
    }

    @Test
    fun protocolFailureAfterOneWriteReportsPartialExecutionAndNeverRetries() = runTest {
        val memory = RecordingSysBotbase(failWriteAttempt = 2)
        val group = parseGroup(
            """
            [partial]
            04000000 00000010 00000001
            04000000 00000020 00000002
            04000000 00000030 00000003
            """.trimIndent(),
        )

        val report = CheatExecutor().execute(group, identity, memory)

        assertEquals(ExecutionStatus.Partial, report.status)
        assertEquals(1, report.completedWrites)
        assertEquals(3, report.failureLine)
        assertTrue(report.error is ProtocolError.Disconnected)
        assertEquals(2, memory.writeAttempts)
    }

    private fun parseGroup(source: String): CheatGroup {
        val parsed = CheatFileParser().parse(source)
        assertTrue(parsed.diagnostics.toString(), parsed.diagnostics.isEmpty())
        return parsed.groups.single()
    }

    private fun littleEndian(value: ULong): ByteArray =
        ByteArray(8) { index -> (value shr (index * 8)).toByte() }

    private data class ReadCall(val target: MemoryTarget, val size: Int)
    private data class WriteCall(val target: MemoryTarget, val bytes: ByteArray)

    private class RecordingSysBotbase(
        private val reads: MutableMap<ULong, ByteArray> = mutableMapOf(),
        private val failWriteAttempt: Int? = null,
    ) : SysBotbase {
        val networkCalls = mutableListOf<String>()
        val readCalls = mutableListOf<ReadCall>()
        val writes = mutableListOf<WriteCall>()
        var writeAttempts = 0

        override suspend fun connect() {
            networkCalls += "connect"
        }

        override suspend fun disconnect() {
            networkCalls += "disconnect"
        }

        override suspend fun recognizeGame(): GameIdentity {
            networkCalls += "recognize"
            error("Not used")
        }

        override suspend fun read(target: MemoryTarget, size: Int): ByteArray {
            networkCalls += "read"
            readCalls += ReadCall(target, size)
            val absolute = (target as MemoryTarget.Absolute).address
            return reads[absolute] ?: error("No read fixture for 0x${absolute.toString(16)}")
        }

        override suspend fun write(target: MemoryTarget, bytes: ByteArray) {
            networkCalls += "write"
            writeAttempts++
            if (writeAttempts == failWriteAttempt) throw ProtocolError.Disconnected()
            writes += WriteCall(target, bytes.copyOf())
        }

        override suspend fun freeze(absoluteAddress: ULong, bytes: ByteArray) {
            networkCalls += "freeze"
        }

        override suspend fun unfreeze(absoluteAddress: ULong) {
            networkCalls += "unfreeze"
        }

        fun writeAddresses(): List<ULong> =
            writes.map { (it.target as MemoryTarget.Absolute).address }
    }
}
