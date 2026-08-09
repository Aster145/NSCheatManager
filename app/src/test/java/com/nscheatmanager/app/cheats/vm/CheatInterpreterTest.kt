package com.nscheatmanager.app.cheats.vm

import com.nscheatmanager.app.cheats.parser.CheatFileParser
import com.nscheatmanager.app.cheats.parser.CheatGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CheatInterpreterTest {
    private val interpreter = CheatInterpreter()

    @Test
    fun decodesEveryApprovedTopLevelOpcodeIntoDeterministicOperations() {
        val group = parseGroup(
            """
            [all]
            40000000 00000000 00001000
            58010000 00000020
            68000000 00000000 11223344
            78000000 00000008
            98000100 00000000 00000001
            A8000200 00000010
            04000000 00000040 DEADBEEF
            """.trimIndent(),
        )

        val result = interpreter.interpret(group)

        assertTrue(result is ValidationResult.Valid)
        val program = (result as ValidationResult.Valid).program
        assertEquals(
            listOf(
                CheatOperation.LoadConstant::class,
                CheatOperation.Read::class,
                CheatOperation.Write::class,
                CheatOperation.Arithmetic::class,
                CheatOperation.Arithmetic::class,
                CheatOperation.Write::class,
                CheatOperation.Write::class,
            ),
            program.operations.map { it::class },
        )
        assertEquals(listOf(2, 3, 4, 5, 6, 7, 8), program.operations.map { it.sourceLine })
    }

    @Test
    fun acceptsDocumentedCodeFiveAddressFormsUsedByPointerChains() {
        val forms = listOf(
            Triple(
                "58000000 00000020",
                0,
                AddressExpression.Region(CheatMemoryRegion.Main, immediateOffset = 0x20u),
            ),
            Triple(
                "58110000 00000020",
                1,
                AddressExpression.Region(CheatMemoryRegion.Heap, immediateOffset = 0x20u),
            ),
            Triple("58001000 00000020", 0, AddressExpression.Register(0, immediateOffset = 0x20u)),
            Triple("58012000 00000020", 1, AddressExpression.Register(0, immediateOffset = 0x20u)),
            Triple(
                "58013300 00000020",
                1,
                AddressExpression.Region(
                    CheatMemoryRegion.Main,
                    offsetRegister = 3,
                    immediateOffset = 0x20u,
                ),
            ),
        )

        forms.forEach { (encoded, destination, expectedAddress) ->
            val result = interpreter.interpret(parseGroup("[x]\n$encoded"))
            assertTrue("Expected valid form: $encoded, got $result", result is ValidationResult.Valid)
            val read = (result as ValidationResult.Valid).program.operations.single()
            assertTrue(read is CheatOperation.Read)
            read as CheatOperation.Read
            assertEquals(8, read.widthBytes)
            assertEquals(destination, read.destinationRegister)
            assertEquals(expectedAddress, read.address)
        }
    }

    @Test
    fun reportsDoubleExtendedOpcodesUsingAllThreeOpcodeNibbles() {
        listOf(
            "FFF00000" to 0xFFF,
            "FF000000" to 0xFF0,
            "FF100000" to 0xFF1,
        ).forEach { (encoded, expectedOpcode) ->
            val result = interpreter.interpret(parseGroup("[x]\n$encoded"))
            assertTrue(result is ValidationResult.Invalid)
            val error = (result as ValidationResult.Invalid).error
            assertTrue(error is CheatValidationError.UnsupportedOpcode)
            assertEquals(expectedOpcode, (error as CheatValidationError.UnsupportedOpcode).opcode)
        }
    }

    @Test
    fun acceptsEveryApprovedIntegerCodeNineOperationAndBothOperandForms() {
        val operations = listOf(
            0 to ArithmeticKind.Add,
            1 to ArithmeticKind.Subtract,
            3 to ArithmeticKind.ShiftLeft,
            4 to ArithmeticKind.ShiftRight,
            5 to ArithmeticKind.And,
            6 to ArithmeticKind.Or,
            8 to ArithmeticKind.Xor,
            9 to ArithmeticKind.Move,
        )

        operations.forEach { (code, expected) ->
            val registerForm = "98${code.toString(16).uppercase()}01020"
            val immediateForm = "98${code.toString(16).uppercase()}01100 00000000 00000002"
            listOf(registerForm, immediateForm).forEach { encoded ->
                val result = interpreter.interpret(
                    parseGroup("[x]\n40010000 00000000 00000009\n$encoded"),
                )
                assertTrue("Expected valid form: $encoded, got $result", result is ValidationResult.Valid)
                val operation = (result as ValidationResult.Valid).program.operations.last()
                assertEquals(expected, (operation as CheatOperation.Arithmetic).kind)
            }
        }
    }

    @Test
    fun rejectsConditionalsLoopsKeyTriggersAndExtendedConditionalFamilies() {
        val unsupported = listOf(
            "10000000 00000000 00000000" to 0x1,
            "20000000" to 0x2,
            "30000000 00000001" to 0x3,
            "80000001" to 0x8,
            "C0400000 00000000" to 0xC0,
            "C1000000" to 0xC1,
            "C2000000" to 0xC2,
            "C3000000" to 0xC3,
            "C4000000 00000000 00000001" to 0xC4,
        )

        unsupported.forEach { (encoded, expectedOpcode) ->
            val result = interpreter.interpret(parseGroup("[x]\n$encoded"))
            assertTrue("Expected rejection for $encoded, got $result", result is ValidationResult.Invalid)
            val error = (result as ValidationResult.Invalid).error
            assertTrue(error is CheatValidationError.UnsupportedOpcode)
            error as CheatValidationError.UnsupportedOpcode
            assertEquals(2, error.line)
            assertEquals(expectedOpcode, error.opcode)
        }
    }

    @Test
    fun rejectsFloatingArithmeticAndOtherUnapprovedIntegerOperations() {
        listOf(0x2, 0x7, 0xA, 0xB, 0xC, 0xD).forEach { arithmeticCode ->
            val encoded = "98${arithmeticCode.toString(16).uppercase()}01020"
            val result = interpreter.interpret(parseGroup("[x]\n$encoded"))
            assertTrue("Expected rejection for $encoded, got $result", result is ValidationResult.Invalid)
            assertTrue((result as ValidationResult.Invalid).error is CheatValidationError.UnsupportedForm)
        }
    }

    @Test
    fun rejectsAliasAslrAndOtherUnapprovedMemoryRegions() {
        listOf(
            "04200000 00000010 00000001",
            "04300000 00000010 00000001",
            "58200000 00000010",
            "58300000 00000010",
            "A8000420 00000010",
            "A8000430 00000010",
        ).forEach { encoded ->
            val result = interpreter.interpret(parseGroup("[x]\n$encoded"))
            assertTrue("Expected rejection for $encoded, got $result", result is ValidationResult.Invalid)
            assertTrue((result as ValidationResult.Invalid).error is CheatValidationError.UnsupportedMemoryRegion)
        }
    }

    @Test
    fun rejectsBadWidthsWordCountsAndReservedNibbles() {
        listOf(
            "03000000 00000010 00000001",
            "54000000 00000010",
            "64000000 00000001",
            "78000001 00000001",
            "98001021",
            "A8000001",
            "04000100 00000010 00000001",
        ).forEach { encoded ->
            val result = interpreter.interpret(parseGroup("[x]\n$encoded"))
            assertTrue("Expected bad form for $encoded, got $result", result is ValidationResult.Invalid)
            assertTrue((result as ValidationResult.Invalid).error is CheatValidationError.UnsupportedForm)
        }
    }

    @Test
    fun rejectsStaticallyProvableUnsignedArithmeticOverflow() {
        val result = interpreter.interpret(
            parseGroup(
                """
                [overflow]
                40000000 FFFFFFFF FFFFFFFF
                78000000 00000001
                """.trimIndent(),
            ),
        )

        assertTrue(result is ValidationResult.Invalid)
        val error = (result as ValidationResult.Invalid).error
        assertTrue(error is CheatValidationError.ArithmeticOverflow)
        assertEquals(3, error.line)
    }

    @Test
    fun appliesIndependentInstructionAndAggregateIoLimits() {
        val twoConstants = parseGroup(
            """
            [too many]
            40000000 00000000 00000001
            40010000 00000000 00000002
            """.trimIndent(),
        )
        val instructionLimited = CheatValidator(maxInstructions = 1).validate(twoConstants)
        assertTrue(instructionLimited is ValidationResult.Invalid)
        assertTrue(
            (instructionLimited as ValidationResult.Invalid).error is
                CheatValidationError.InstructionLimitExceeded,
        )

        val fourByteWrite = parseGroup("[io]\n04000000 00000010 DEADBEEF")
        val ioLimited = CheatValidator(maxTotalIoBytes = 3).validate(fourByteWrite)
        assertTrue(ioLimited is ValidationResult.Invalid)
        assertTrue((ioLimited as ValidationResult.Invalid).error is CheatValidationError.IoLimitExceeded)
    }

    private fun parseGroup(source: String): CheatGroup {
        val parsed = CheatFileParser().parse(source)
        assertTrue(parsed.diagnostics.toString(), parsed.diagnostics.isEmpty())
        return parsed.groups.single()
    }
}
