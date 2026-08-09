package com.nscheatmanager.app.cheats.vm

import com.nscheatmanager.app.core.model.checkedAdd
import com.nscheatmanager.app.protocol.sysbot.GameIdentity

internal fun checkedMemorySpan(startAddress: ULong, widthBytes: Int): ULong {
    require(widthBytes > 0) { "Memory access width must be positive" }
    return checkedAdd(startAddress, (widthBytes - 1).toULong())
}

enum class CheatMemoryRegion {
    Main,
    Heap,
    Absolute,
}

sealed interface AddressExpression {
    fun resolve(identity: GameIdentity, registers: Array<ULong>): ULong

    data class Region(
        val region: CheatMemoryRegion,
        val offsetRegister: Int? = null,
        val immediateOffset: ULong = 0u,
    ) : AddressExpression {
        override fun resolve(identity: GameIdentity, registers: Array<ULong>): ULong {
            val base = when (region) {
                CheatMemoryRegion.Main -> identity.mainBase
                CheatMemoryRegion.Heap -> identity.heapBase
                CheatMemoryRegion.Absolute -> 0u
            }
            val registerOffset = offsetRegister?.let(registers::get) ?: 0u
            return checkedAdd(checkedAdd(base, registerOffset), immediateOffset)
        }
    }

    data class Register(
        val baseRegister: Int,
        val offsetRegister: Int? = null,
        val immediateOffset: ULong = 0u,
    ) : AddressExpression {
        override fun resolve(identity: GameIdentity, registers: Array<ULong>): ULong {
            val registerOffset = offsetRegister?.let(registers::get) ?: 0u
            return checkedAdd(
                checkedAdd(registers[baseRegister], registerOffset),
                immediateOffset,
            )
        }
    }
}

sealed interface CheatValue {
    data class Constant(val value: ULong) : CheatValue
    data class Register(val index: Int) : CheatValue
}

enum class ArithmeticKind {
    Add,
    Subtract,
    ShiftLeft,
    ShiftRight,
    And,
    Or,
    Xor,
    Move,
}

sealed interface CheatOperation {
    val sourceLine: Int

    data class LoadConstant(
        val register: Int,
        val value: ULong,
        override val sourceLine: Int,
    ) : CheatOperation

    data class Read(
        val address: AddressExpression,
        val widthBytes: Int,
        val destinationRegister: Int,
        override val sourceLine: Int,
    ) : CheatOperation

    data class Write(
        val address: AddressExpression,
        val widthBytes: Int,
        val value: CheatValue,
        val incrementRegister: Int? = null,
        override val sourceLine: Int,
    ) : CheatOperation

    data class Arithmetic(
        val widthBytes: Int,
        val kind: ArithmeticKind,
        val destinationRegister: Int,
        val leftRegister: Int,
        val rightRegister: Int? = null,
        val immediate: ULong? = null,
        override val sourceLine: Int,
    ) : CheatOperation
}

data class CheatProgram(val operations: List<CheatOperation>)

sealed interface CheatValidationError {
    val line: Int

    data class UnsupportedOpcode(
        override val line: Int,
        val opcode: Int,
    ) : CheatValidationError

    data class UnsupportedForm(
        override val line: Int,
        val reason: String,
    ) : CheatValidationError

    data class UnsupportedMemoryRegion(
        override val line: Int,
        val region: Int,
    ) : CheatValidationError

    data class ArithmeticOverflow(
        override val line: Int,
    ) : CheatValidationError

    data class InstructionLimitExceeded(
        override val line: Int,
        val limit: Int,
    ) : CheatValidationError

    data class IoLimitExceeded(
        override val line: Int,
        val limitBytes: Long,
    ) : CheatValidationError
}

sealed interface ValidationResult {
    data class Valid(val program: CheatProgram) : ValidationResult
    data class Invalid(val error: CheatValidationError) : ValidationResult
}
