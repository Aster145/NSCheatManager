package com.nscheatmanager.app.ui.memory

import com.nscheatmanager.app.core.model.checkedAdd
import com.nscheatmanager.app.protocol.sysbot.GameIdentity

internal sealed interface AddressExpression {
    data class Constant(val value: ULong) : AddressExpression
    data object Main : AddressExpression
    data object Heap : AddressExpression
    data class Add(val left: AddressExpression, val right: AddressExpression) : AddressExpression
    data class Subtract(val left: AddressExpression, val right: AddressExpression) : AddressExpression
    data class Dereference(val address: AddressExpression) : AddressExpression
}

internal class AddressExpressionParser(private val source: String) {
    private var index = 0
    private var dereferenceCount = 0

    fun parse(): AddressExpression {
        val expression = parseExpression()
        skipWhitespace()
        require(index == source.length) { "Unexpected token at position $index" }
        require(dereferenceCount <= MAX_DEREFERENCES) { "At most $MAX_DEREFERENCES pointer levels are supported" }
        return expression
    }

    private fun parseExpression(): AddressExpression {
        var left = parseAtom()
        while (true) {
            skipWhitespace()
            left = when (peek()) {
                '+' -> { index++; AddressExpression.Add(left, parseAtom()) }
                '-' -> { index++; AddressExpression.Subtract(left, parseAtom()) }
                else -> return left
            }
        }
    }

    private fun parseAtom(): AddressExpression {
        skipWhitespace()
        if (peek() == '[') {
            index++
            val nested = parseExpression()
            skipWhitespace()
            require(peek() == ']') { "Missing closing bracket" }
            index++
            dereferenceCount++
            return AddressExpression.Dereference(nested)
        }
        val start = index
        while (peek()?.let { it.isLetterOrDigit() || it == 'x' || it == 'X' } == true) index++
        require(index > start) { "Expected an address term at position $index" }
        val token = source.substring(start, index)
        return when (token.lowercase()) {
            "main" -> AddressExpression.Main
            "heap" -> AddressExpression.Heap
            else -> AddressExpression.Constant(
                token.removePrefix("0x").removePrefix("0X").takeIf { value ->
                    value.isNotEmpty() && value.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
                }?.toULongOrNull(16) ?: throw IllegalArgumentException("Invalid hexadecimal term: $token"),
            )
        }
    }

    private fun skipWhitespace() { while (peek()?.isWhitespace() == true) index++ }
    private fun peek(): Char? = source.getOrNull(index)

    private companion object { const val MAX_DEREFERENCES = 8 }
}

internal suspend fun AddressExpression.resolve(
    identity: GameIdentity,
    dereference: suspend (ULong, Int) -> ULong,
): ULong = when (this) {
    is AddressExpression.Constant -> value
    AddressExpression.Main -> identity.mainBase
    AddressExpression.Heap -> identity.heapBase
    is AddressExpression.Add -> checkedAdd(left.resolve(identity, dereference), right.resolve(identity, dereference))
    is AddressExpression.Subtract -> {
        val leftValue = left.resolve(identity, dereference)
        val rightValue = right.resolve(identity, dereference)
        require(leftValue >= rightValue) { "Unsigned address underflow" }
        leftValue - rightValue
    }
    is AddressExpression.Dereference -> {
        val pointerAddress = address.resolve(identity, dereference)
        require(pointerAddress != 0uL) { "Null pointer address" }
        dereference(pointerAddress, 8).also { require(it != 0uL) { "Null pointer value" } }
    }
}
