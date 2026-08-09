package com.nscheatmanager.app.cheats.vm

import com.nscheatmanager.app.cheats.parser.CheatGroup
import com.nscheatmanager.app.cheats.parser.EncodedInstruction

class CheatInterpreter {
    fun interpret(group: CheatGroup): ValidationResult = CheatValidator().validate(group)

    internal fun decode(instruction: EncodedInstruction): DecodeResult {
        val first = instruction.words.firstOrNull()
            ?: return invalidForm(instruction, "Instruction has no words")
        val opcode = decodeOpcode(first)
        return when (opcode) {
            0x0 -> decodeStaticWrite(instruction, first)
            0x4 -> decodeLoadConstant(instruction, first)
            0x5 -> decodeRead(instruction, first)
            0x6 -> decodeRegisterWrite(instruction, first)
            0x7 -> decodeLegacyArithmetic(instruction, first)
            0x9 -> decodeArithmetic(instruction, first)
            0xA -> decodeRegisterToMemory(instruction, first)
            else -> DecodeResult.Invalid(
                CheatValidationError.UnsupportedOpcode(instruction.sourceLine, opcode),
            )
        }
    }

    private fun decodeStaticWrite(instruction: EncodedInstruction, first: UInt): DecodeResult {
        val width = width(first) ?: return invalidForm(instruction, "Invalid write width")
        if (nibble(first, 4) != 0 || nibble(first, 5) != 0) {
            return invalidForm(instruction, "Reserved code 0 nibbles must be zero")
        }
        val expectedWords = if (width == 8) 4 else 3
        if (instruction.words.size != expectedWords) {
            return invalidForm(instruction, "Code 0 requires $expectedWords words for width $width")
        }
        val region = region(nibble(first, 2)) ?: return regionError(instruction, nibble(first, 2))
        val offset = immediate40(first, instruction.words[1])
        val value = value(instruction.words, 2, width)
        return DecodeResult.Valid(
            CheatOperation.Write(
                address = AddressExpression.Region(region, nibble(first, 3), offset),
                widthBytes = width,
                value = CheatValue.Constant(value),
                sourceLine = instruction.sourceLine,
            ),
        )
    }

    private fun decodeLoadConstant(instruction: EncodedInstruction, first: UInt): DecodeResult {
        if (instruction.words.size != 3 || (first and 0xFFF0FFFFu) != 0x40000000u) {
            return invalidForm(instruction, "Code 4 must match 400R0000 with three words")
        }
        return DecodeResult.Valid(
            CheatOperation.LoadConstant(
                register = nibble(first, 3),
                value = combine64(instruction.words[1], instruction.words[2]),
                sourceLine = instruction.sourceLine,
            ),
        )
    }

    private fun decodeRead(instruction: EncodedInstruction, first: UInt): DecodeResult {
        val width = width(first) ?: return invalidForm(instruction, "Invalid read width")
        if (width != 8) return invalidForm(instruction, "Pointer reads must be 8 bytes")
        if (instruction.words.size != 2) return invalidForm(instruction, "Code 5 requires two words")
        val memory = nibble(first, 2)
        val destination = nibble(first, 3)
        val mode = nibble(first, 4)
        val extra = nibble(first, 5)
        val offset = immediate40(first, instruction.words[1])
        val address = when (mode) {
            0 -> {
                if (extra != 0) return invalidForm(instruction, "Code 5 fixed form has a reserved nibble")
                val decodedRegion = readRegion(memory) ?: return regionError(instruction, memory)
                AddressExpression.Region(decodedRegion, immediateOffset = offset)
            }
            1 -> {
                if (memory != 0 || extra != 0) {
                    return invalidForm(instruction, "Code 5 same-register form has reserved nibbles")
                }
                AddressExpression.Register(destination, immediateOffset = offset)
            }
            2 -> {
                if (memory != 0) return invalidForm(instruction, "Code 5 separate-register form has a reserved nibble")
                AddressExpression.Register(extra, immediateOffset = offset)
            }
            3 -> {
                val decodedRegion = readRegion(memory) ?: return regionError(instruction, memory)
                AddressExpression.Region(decodedRegion, offsetRegister = extra, immediateOffset = offset)
            }
            else -> return invalidForm(instruction, "Unsupported code 5 address form")
        }
        return DecodeResult.Valid(
            CheatOperation.Read(address, width, destination, instruction.sourceLine),
        )
    }

    private fun decodeRegisterWrite(instruction: EncodedInstruction, first: UInt): DecodeResult {
        val width = width(first) ?: return invalidForm(instruction, "Invalid write width")
        if (instruction.words.size != 3 || nibble(first, 2) != 0 || nibble(first, 7) != 0) {
            return invalidForm(instruction, "Code 6 has invalid reserved nibbles or word count")
        }
        val increment = nibble(first, 4)
        val offsetEnabled = nibble(first, 5)
        val offsetRegister = nibble(first, 6)
        if (increment !in 0..1 || offsetEnabled !in 0..1 || (offsetEnabled == 0 && offsetRegister != 0)) {
            return invalidForm(instruction, "Code 6 flags are invalid")
        }
        val register = nibble(first, 3)
        val value = combine64(instruction.words[1], instruction.words[2])
        return DecodeResult.Valid(
            CheatOperation.Write(
                address = AddressExpression.Register(
                    baseRegister = register,
                    offsetRegister = offsetRegister.takeIf { offsetEnabled == 1 },
                ),
                widthBytes = width,
                value = CheatValue.Constant(value),
                incrementRegister = register.takeIf { increment == 1 },
                sourceLine = instruction.sourceLine,
            ),
        )
    }

    private fun decodeLegacyArithmetic(instruction: EncodedInstruction, first: UInt): DecodeResult {
        val width = width(first) ?: return invalidForm(instruction, "Invalid arithmetic width")
        if (instruction.words.size != 2 || nibble(first, 2) != 0 || (first and 0x00000FFFu) != 0u) {
            return invalidForm(instruction, "Code 7 has invalid reserved nibbles or word count")
        }
        val kind = when (nibble(first, 4)) {
            0 -> ArithmeticKind.Add
            1 -> ArithmeticKind.Subtract
            else -> return invalidForm(instruction, "Only legacy addition and subtraction are supported")
        }
        val immediate = instruction.words[1].toULong()
        if (!fitsWidth(immediate, width)) return invalidForm(instruction, "Immediate does not fit arithmetic width")
        val register = nibble(first, 3)
        return DecodeResult.Valid(
            CheatOperation.Arithmetic(
                widthBytes = width,
                kind = kind,
                destinationRegister = register,
                leftRegister = register,
                immediate = immediate,
                sourceLine = instruction.sourceLine,
            ),
        )
    }

    private fun decodeArithmetic(instruction: EncodedInstruction, first: UInt): DecodeResult {
        val width = width(first) ?: return invalidForm(instruction, "Invalid arithmetic width")
        val kind = when (nibble(first, 2)) {
            0 -> ArithmeticKind.Add
            1 -> ArithmeticKind.Subtract
            3 -> ArithmeticKind.ShiftLeft
            4 -> ArithmeticKind.ShiftRight
            5 -> ArithmeticKind.And
            6 -> ArithmeticKind.Or
            8 -> ArithmeticKind.Xor
            9 -> ArithmeticKind.Move
            else -> return invalidForm(instruction, "Unsupported integer or floating arithmetic operation")
        }
        val destination = nibble(first, 3)
        val left = nibble(first, 4)
        return when (nibble(first, 5)) {
            0 -> {
                if (instruction.words.size != 1 || nibble(first, 7) != 0) {
                    return invalidForm(instruction, "Invalid register arithmetic form")
                }
                DecodeResult.Valid(
                    CheatOperation.Arithmetic(
                        width, kind, destination, left,
                        rightRegister = nibble(first, 6),
                        sourceLine = instruction.sourceLine,
                    ),
                )
            }
            1 -> {
                val expectedWords = if (width == 8) 3 else 2
                if (instruction.words.size != expectedWords || nibble(first, 6) != 0 || nibble(first, 7) != 0) {
                    return invalidForm(instruction, "Invalid immediate arithmetic form")
                }
                val immediate = decodeVmInteger(instruction.words, 1, width)
                DecodeResult.Valid(
                    CheatOperation.Arithmetic(
                        width, kind, destination, left,
                        immediate = immediate,
                        sourceLine = instruction.sourceLine,
                    ),
                )
            }
            else -> invalidForm(instruction, "Unsupported arithmetic operand form")
        }
    }

    private fun decodeRegisterToMemory(instruction: EncodedInstruction, first: UInt): DecodeResult {
        val width = width(first) ?: return invalidForm(instruction, "Invalid write width")
        val source = nibble(first, 2)
        val addressRegister = nibble(first, 3)
        val increment = nibble(first, 4)
        val offsetType = nibble(first, 5)
        val x = nibble(first, 6)
        val highOffset = nibble(first, 7)
        if (increment !in 0..1) return invalidForm(instruction, "Invalid increment flag")

        val needsOffsetWord = offsetType in setOf(2, 4, 5)
        val expectedWords = if (needsOffsetWord) 2 else 1
        if (instruction.words.size != expectedWords) {
            return invalidForm(instruction, "Code A has an invalid word count")
        }
        val offset = if (needsOffsetWord) immediate36(highOffset, instruction.words[1]) else 0u
        val address = when (offsetType) {
            0 -> {
                if (x != 0 || highOffset != 0) return invalidForm(instruction, "Code A no-offset form has reserved nibbles")
                AddressExpression.Register(addressRegister)
            }
            1 -> {
                if (highOffset != 0) return invalidForm(instruction, "Code A register-offset form has a reserved nibble")
                AddressExpression.Register(addressRegister, offsetRegister = x)
            }
            2 -> {
                if (x != 0) return invalidForm(instruction, "Code A fixed-offset form has a reserved nibble")
                AddressExpression.Register(addressRegister, immediateOffset = offset)
            }
            3 -> {
                if (highOffset != 0) return invalidForm(instruction, "Code A memory-base form has a reserved nibble")
                val decodedRegion = readRegion(x) ?: return regionError(instruction, x)
                AddressExpression.Region(decodedRegion, offsetRegister = addressRegister)
            }
            4 -> {
                val decodedRegion = readRegion(x) ?: return regionError(instruction, x)
                AddressExpression.Region(decodedRegion, immediateOffset = offset)
            }
            5 -> {
                val decodedRegion = readRegion(x) ?: return regionError(instruction, x)
                AddressExpression.Region(decodedRegion, offsetRegister = addressRegister, immediateOffset = offset)
            }
            else -> return invalidForm(instruction, "Unsupported code A offset form")
        }
        return DecodeResult.Valid(
            CheatOperation.Write(
                address = address,
                widthBytes = width,
                value = CheatValue.Register(source),
                incrementRegister = addressRegister.takeIf { increment == 1 },
                sourceLine = instruction.sourceLine,
            ),
        )
    }

    private fun width(first: UInt): Int? =
        nibble(first, 1).takeIf { it in setOf(1, 2, 4, 8) }

    private fun region(encoded: Int): CheatMemoryRegion? = when (encoded) {
        0 -> CheatMemoryRegion.Main
        1 -> CheatMemoryRegion.Heap
        4 -> CheatMemoryRegion.Absolute
        else -> null
    }

    private fun readRegion(encoded: Int): CheatMemoryRegion? = when (encoded) {
        0 -> CheatMemoryRegion.Main
        1 -> CheatMemoryRegion.Heap
        else -> null
    }

    private fun regionError(instruction: EncodedInstruction, encoded: Int): DecodeResult =
        if (encoded == 2 || encoded == 3) {
            DecodeResult.Invalid(CheatValidationError.UnsupportedMemoryRegion(instruction.sourceLine, encoded))
        } else {
            invalidForm(instruction, "Unsupported memory region $encoded")
        }

    private fun invalidForm(instruction: EncodedInstruction, reason: String): DecodeResult.Invalid =
        DecodeResult.Invalid(CheatValidationError.UnsupportedForm(instruction.sourceLine, reason))

    private fun nibble(word: UInt, index: Int): Int =
        ((word shr ((7 - index) * 4)) and 0xFu).toInt()

    private fun immediate40(first: UInt, low: UInt): ULong =
        ((first and 0xFFu).toULong() shl 32) or low.toULong()

    private fun immediate36(high: Int, low: UInt): ULong =
        (high.toULong() shl 32) or low.toULong()

    private fun value(words: List<UInt>, start: Int, width: Int): ULong =
        if (width == 8) combine64(words[start], words[start + 1]) else words[start].toULong()

    private fun decodeVmInteger(words: List<UInt>, start: Int, width: Int): ULong = when (width) {
        1 -> (words[start] and 0xFFu).toULong()
        2 -> (words[start] and 0xFFFFu).toULong()
        4 -> words[start].toULong()
        8 -> combine64(words[start], words[start + 1])
        else -> error("Width was validated before decoding the VM integer")
    }

    private fun decodeOpcode(first: UInt): Int {
        var opcode = (first shr 28).toInt()
        if (opcode >= 0xC) {
            opcode = (opcode shl 4) or nibble(first, 1)
        }
        if (opcode >= 0xF0) {
            opcode = (opcode shl 4) or nibble(first, 2)
        }
        return opcode
    }

    private fun combine64(high: UInt, low: UInt): ULong =
        (high.toULong() shl 32) or low.toULong()

    private fun fitsWidth(value: ULong, width: Int): Boolean =
        width == 8 || value <= ((1uL shl (width * 8)) - 1u)

    internal sealed interface DecodeResult {
        data class Valid(val operation: CheatOperation) : DecodeResult
        data class Invalid(val error: CheatValidationError) : DecodeResult
    }
}
