package com.nscheatmanager.app.cheats.vm

import com.nscheatmanager.app.core.model.MemoryTarget
import com.nscheatmanager.app.core.model.checkedAdd
import com.nscheatmanager.app.protocol.ProtocolError
import com.nscheatmanager.app.protocol.sysbot.GameIdentity
import com.nscheatmanager.app.protocol.sysbot.SysBotbase

enum class ExecutionStatus {
    Complete,
    Rejected,
    Failed,
    Partial,
}

data class ExecutionReport(
    val status: ExecutionStatus,
    val completedWrites: Int,
    val failureLine: Int? = null,
    val error: ProtocolError? = null,
    val validationError: CheatValidationError? = null,
)

class CheatExecutor(
    private val validator: CheatValidator = CheatValidator(),
) {
    suspend fun execute(
        group: com.nscheatmanager.app.cheats.parser.CheatGroup,
        identity: GameIdentity,
        memory: SysBotbase,
    ): ExecutionReport {
        val validation = validator.validate(group, identity)
        if (validation is ValidationResult.Invalid) {
            return ExecutionReport(
                status = ExecutionStatus.Rejected,
                completedWrites = 0,
                failureLine = validation.error.line,
                validationError = validation.error,
            )
        }

        val program = (validation as ValidationResult.Valid).program
        val registers = Array(CheatValidator.REGISTER_COUNT) { 0uL }
        var completedWrites = 0
        for (operation in program.operations) {
            try {
                when (operation) {
                    is CheatOperation.LoadConstant -> registers[operation.register] = operation.value
                    is CheatOperation.Read -> executeRead(operation, identity, registers, memory)
                    is CheatOperation.Write -> {
                        executeWrite(operation, identity, registers, memory)
                        completedWrites++
                    }
                    is CheatOperation.Arithmetic -> {
                        val right = operation.immediate
                            ?: operation.rightRegister?.let(registers::get)
                            ?: 0u
                        registers[operation.destinationRegister] = CheatValidator.evaluateArithmetic(
                            operation,
                            registers[operation.leftRegister],
                            right,
                        )
                    }
                }
            } catch (error: ProtocolError) {
                return failure(completedWrites, operation.sourceLine, error)
            } catch (_: ArithmeticException) {
                return failure(completedWrites, operation.sourceLine)
            } catch (_: InvalidPointerException) {
                return failure(completedWrites, operation.sourceLine)
            }
        }
        return ExecutionReport(ExecutionStatus.Complete, completedWrites)
    }

    private suspend fun executeRead(
        operation: CheatOperation.Read,
        identity: GameIdentity,
        registers: Array<ULong>,
        memory: SysBotbase,
    ) {
        val address = operation.address.resolve(identity, registers)
        if (address == 0uL) throw InvalidPointerException()
        val bytes = memory.read(MemoryTarget.Absolute(address), operation.widthBytes)
        if (bytes.size != operation.widthBytes) {
            throw ProtocolError.MalformedResponse(
                "Expected ${operation.widthBytes} bytes, received ${bytes.size}",
            )
        }
        val value = bytes.foldIndexed(0uL) { index, result, byte ->
            result or (byte.toUByte().toULong() shl (index * 8))
        }
        if (value == 0uL) throw InvalidPointerException()
        registers[operation.destinationRegister] = value
    }

    private suspend fun executeWrite(
        operation: CheatOperation.Write,
        identity: GameIdentity,
        registers: Array<ULong>,
        memory: SysBotbase,
    ) {
        val address = operation.address.resolve(identity, registers)
        if (address == 0uL) throw InvalidPointerException()
        val incremented = operation.incrementRegister?.let { register ->
            register to checkedAdd(registers[register], operation.widthBytes.toULong())
        }
        val value = when (val source = operation.value) {
            is CheatValue.Constant -> source.value
            is CheatValue.Register -> registers[source.index]
        }
        val bytes = ByteArray(operation.widthBytes) { index ->
            (value shr (index * 8)).toByte()
        }
        memory.write(MemoryTarget.Absolute(address), bytes)
        incremented?.let { (register, nextValue) -> registers[register] = nextValue }
    }

    private fun failure(
        completedWrites: Int,
        line: Int,
        error: ProtocolError? = null,
    ): ExecutionReport = ExecutionReport(
        status = if (completedWrites == 0) ExecutionStatus.Failed else ExecutionStatus.Partial,
        completedWrites = completedWrites,
        failureLine = line,
        error = error,
    )

    private class InvalidPointerException : RuntimeException()
}
