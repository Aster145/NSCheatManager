package com.nscheatmanager.app.protocol.sysbot

import com.nscheatmanager.app.core.model.BuildId
import com.nscheatmanager.app.core.model.MemoryTarget
import com.nscheatmanager.app.core.model.TitleId

data class GameIdentity(
    val titleId: TitleId,
    val buildId: BuildId,
    val mainBase: ULong,
    val heapBase: ULong,
)

interface SysBotbase {
    suspend fun connect()
    suspend fun disconnect()
    suspend fun recognizeGame(): GameIdentity
    suspend fun read(target: MemoryTarget, size: Int): ByteArray
    suspend fun write(target: MemoryTarget, bytes: ByteArray)
    suspend fun freeze(absoluteAddress: ULong, bytes: ByteArray)
    suspend fun unfreeze(absoluteAddress: ULong)
    suspend fun captureScreenshot(): ByteArray = throw UnsupportedOperationException("Screenshot is not supported")
    suspend fun setScreenEnabled(enabled: Boolean): Unit { throw UnsupportedOperationException("Screen control is not supported") }
}
