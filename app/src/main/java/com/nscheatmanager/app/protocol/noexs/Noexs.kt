package com.nscheatmanager.app.protocol.noexs

import java.io.IOException

/**
 * Minimal compatible Noexs operation required by NSCheatManager.
 *
 * PointerSearcher-SE sends [DETACH_DMNT] as exactly one byte for
 * `NoexsCommands.DetachDmnt`, then waits for exactly one 32-bit little-endian result code.
 * A result of zero succeeds. For a nonzero result, its module is `rc and 0x1FF` and its
 * description is `(rc ushr 9) and 0x1FFF`.
 */
interface Noexs {
    suspend fun detachDmnt()
}

const val DETACH_DMNT: Byte = 0x18

class NoexsResultError(
    val module: Int,
    val description: Int,
    val rawCode: Int,
) : IOException(
    "Noexs DetachDmnt failed with result 0x${rawCode.toUInt().toString(16).uppercase()} " +
        "(module $module, description $description)",
)
