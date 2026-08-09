package com.nscheatmanager.app.cheats.vm

import com.nscheatmanager.app.cheats.parser.CheatGroup
import com.nscheatmanager.app.core.model.checkedAdd
import com.nscheatmanager.app.protocol.sysbot.GameIdentity

class CheatValidator(
    private val maxInstructions: Int = DEFAULT_MAX_INSTRUCTIONS,
    private val maxTotalIoBytes: Long = DEFAULT_MAX_TOTAL_IO_BYTES,
) {
    init {
        require(maxInstructions > 0) { "Instruction limit must be positive" }
        require(maxTotalIoBytes >= 0) { "I/O limit must not be negative" }
    }

    fun validate(group: CheatGroup, identity: GameIdentity? = null): ValidationResult {
        if (group.instructions.size > maxInstructions) {
            return ValidationResult.Invalid(
                CheatValidationError.InstructionLimitExceeded(
                    line = group.instructions[maxInstructions].sourceLine,
                    limit = maxInstructions,
                ),
            )
        }

        val interpreter = CheatInterpreter()
        val operations = ArrayList<CheatOperation>(group.instructions.size)
        var totalIoBytes = 0L
        for (instruction in group.instructions) {
            when (val decoded = interpreter.decode(instruction)) {
                is CheatInterpreter.DecodeResult.Invalid -> return ValidationResult.Invalid(decoded.error)
                is CheatInterpreter.DecodeResult.Valid -> {
                    val operation = decoded.operation
                    operations += operation
                    val ioBytes = when (operation) {
                        is CheatOperation.Read -> operation.widthBytes
                        is CheatOperation.Write -> operation.widthBytes
                        else -> 0
                    }
                    if (totalIoBytes > maxTotalIoBytes - ioBytes) {
                        return ValidationResult.Invalid(
                            CheatValidationError.IoLimitExceeded(operation.sourceLine, maxTotalIoBytes),
                        )
                    }
                    totalIoBytes += ioBytes
                }
            }
        }

        val registers = arrayOfNulls<ULong>(REGISTER_COUNT)
        registers.fill(0u)
        for (operation in operations) {
            try {
                abstractExecute(operation, registers, identity)
            } catch (_: ArithmeticException) {
                return ValidationResult.Invalid(
                    CheatValidationError.ArithmeticOverflow(operation.sourceLine),
                )
            }
        }
        return ValidationResult.Valid(CheatProgram(operations.toList()))
    }

    private fun abstractExecute(
        operation: CheatOperation,
        registers: Array<ULong?>,
        identity: GameIdentity?,
    ) {
        when (operation) {
            is CheatOperation.LoadConstant -> registers[operation.register] = operation.value
            is CheatOperation.Read -> {
                validateKnownAddress(operation.address, registers, identity)
                registers[operation.destinationRegister] = null
            }
            is CheatOperation.Write -> {
                validateKnownAddress(operation.address, registers, identity)
                operation.incrementRegister?.let { register ->
                    registers[register] = registers[register]?.let { checkedAdd(it, operation.widthBytes.toULong()) }
                }
            }
            is CheatOperation.Arithmetic -> {
                val left = registers[operation.leftRegister]
                val right = operation.immediate ?: operation.rightRegister?.let(registers::get)
                registers[operation.destinationRegister] =
                    if (left != null && (right != null || operation.kind == ArithmeticKind.Move)) {
                        evaluateArithmetic(operation, left, right ?: 0u)
                    } else {
                        null
                    }
            }
        }
    }

    private fun validateKnownAddress(
        address: AddressExpression,
        registers: Array<ULong?>,
        identity: GameIdentity?,
    ) {
        var allPartsKnown = true
        var result = 0uL
        fun addKnown(value: ULong?) {
            if (value == null) {
                allPartsKnown = false
            } else {
                result = checkedAdd(result, value)
            }
        }

        when (address) {
            is AddressExpression.Region -> {
                val base = when (address.region) {
                    CheatMemoryRegion.Main -> identity?.mainBase
                    CheatMemoryRegion.Heap -> identity?.heapBase
                    CheatMemoryRegion.Absolute -> 0uL
                }
                addKnown(base)
                addKnown(address.offsetRegister?.let(registers::get) ?: 0uL)
                addKnown(address.immediateOffset)
            }
            is AddressExpression.Register -> {
                addKnown(registers[address.baseRegister])
                addKnown(address.offsetRegister?.let(registers::get) ?: 0uL)
                addKnown(address.immediateOffset)
            }
        }
        if (allPartsKnown && result == 0uL) {
            throw ArithmeticException("Null memory address")
        }
    }

    companion object {
        const val DEFAULT_MAX_INSTRUCTIONS = 4_096
        const val DEFAULT_MAX_TOTAL_IO_BYTES = 16_384L
        internal const val REGISTER_COUNT = 16

        internal fun evaluateArithmetic(
            operation: CheatOperation.Arithmetic,
            left: ULong,
            right: ULong,
        ): ULong {
            val maximum = widthMaximum(operation.widthBytes)
            if (left > maximum || (operation.kind != ArithmeticKind.Move && right > maximum)) {
                throw ArithmeticException("Operand does not fit arithmetic width")
            }
            return when (operation.kind) {
                ArithmeticKind.Add -> {
                    if (left > maximum - right) throw ArithmeticException("Addition overflow")
                    left + right
                }
                ArithmeticKind.Subtract -> {
                    if (left < right) throw ArithmeticException("Subtraction overflow")
                    left - right
                }
                ArithmeticKind.ShiftLeft -> {
                    val bits = operation.widthBytes * 8
                    if (right >= bits.toULong() || left > (maximum shr right.toInt())) {
                        throw ArithmeticException("Left shift overflow")
                    }
                    left shl right.toInt()
                }
                ArithmeticKind.ShiftRight -> {
                    val bits = operation.widthBytes * 8
                    if (right >= bits.toULong()) throw ArithmeticException("Invalid right shift")
                    left shr right.toInt()
                }
                ArithmeticKind.And -> left and right
                ArithmeticKind.Or -> left or right
                ArithmeticKind.Xor -> left xor right
                ArithmeticKind.Move -> left
            }
        }

        private fun widthMaximum(widthBytes: Int): ULong =
            if (widthBytes == 8) ULong.MAX_VALUE else (1uL shl (widthBytes * 8)) - 1u
    }
}
