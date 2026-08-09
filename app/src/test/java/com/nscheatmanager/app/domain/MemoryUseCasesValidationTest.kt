package com.nscheatmanager.app.domain

import com.nscheatmanager.app.core.model.*
import com.nscheatmanager.app.protocol.sysbot.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

class MemoryUseCasesValidationTest {
    private val identity = GameIdentity(TitleId.parse("0100000000000001"), BuildId.parse("0123456789ABCDEF"), 0x1000u, 0x2000u)

    @Test fun rejectsBothShortAndLongDeviceResponsesForRequestedRawLength() {
        for (actual in listOf(3, 5)) assertThrows(IllegalArgumentException::class.java) {
            runTest { MemoryUseCases().readValue(ResponseClient(ByteArray(actual)), identity, MemoryTarget.Absolute(1u), ValueType.Hex, 4) }
        }
    }

    private class ResponseClient(private val response: ByteArray) : SysBotbase {
        override suspend fun connect() = Unit
        override suspend fun disconnect() = Unit
        override suspend fun recognizeGame() = error("unused")
        override suspend fun read(target: MemoryTarget, size: Int) = response.copyOf()
        override suspend fun write(target: MemoryTarget, bytes: ByteArray) = Unit
        override suspend fun freeze(absoluteAddress: ULong, bytes: ByteArray) = Unit
        override suspend fun unfreeze(absoluteAddress: ULong) = Unit
    }
}
