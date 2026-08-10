package com.nscheatmanager.app.ui.memory

import com.nscheatmanager.app.core.model.BuildId
import com.nscheatmanager.app.core.model.TitleId
import com.nscheatmanager.app.core.model.ValueType
import com.nscheatmanager.app.protocol.sysbot.GameIdentity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressExpressionTest {
    private val identity = GameIdentity(TitleId.parse("0100000000000001"), BuildId.parse("0123456789ABCDEF"), 0x1000u, 0x2000u)

    @Test fun parsesAbsoluteMainHeapAndNestedPointersCaseInsensitively() = runTest {
        assertEquals(0x1CE0uL, AddressExpressionParser("0x1CE0").parse().resolve(identity) { _, _ -> error("unexpected") })
        assertEquals(0x1010uL, AddressExpressionParser(" MAIN + 10 ").parse().resolve(identity) { _, _ -> error("unexpected") })
        assertEquals(0x2010uL, AddressExpressionParser("heap+10").parse().resolve(identity) { _, _ -> error("unexpected") })
        val reads = mutableListOf<ULong>()
        val resolved = AddressExpressionParser("[[main+10]+20]+8").parse().resolve(identity) { address, size ->
            assertEquals(8, size); reads += address; if (reads.size == 1) 0x3000u else 0x4000u
        }
        assertEquals(listOf(0x1010uL, 0x3020uL), reads)
        assertEquals(0x4008uL, resolved)
    }

    @Test fun supportsBpPointerAndRejectsNinthDereference() = runTest {
        val slot = identity.mainBase + 0x46f5258u
        val final = AddressExpressionParser("[main+46f5258]+e9a8e30").parse().resolve(identity) { address, _ ->
            assertEquals(slot, address); 0x1CD4BF4500u
        }
        assertEquals(0x1CE359D330uL, final)
        assertTrue(runCatching { AddressExpressionParser("[[[[[[[[[main]]]]]]]]]").parse() }.isFailure)
    }

    @Test fun nativeAndNoexesJsonRoundTripWithoutUnsafeLockState() {
        val tid = identity.titleId; val bid = identity.buildId
        val bookmarks = listOf(
            MemoryBookmark("BP", "[main+46f5258]+e9a8e30", ValueType.UInt32, note = "points", modifiedAtEpochMillis = 1),
            MemoryBookmark("float", "main+10", ValueType.Float, modifiedAtEpochMillis = 2),
        )
        val native = MemoryBookmarkJson.exportNative(tid, bid, bookmarks)
        assertEquals(bookmarks.map { it.copy(modifiedAtEpochMillis = 9) }, MemoryBookmarkJson.import(native, tid, bid, 9).map { it.copy(modifiedAtEpochMillis = 9) })
        val (noexes, skipped) = MemoryBookmarkJson.exportNoexes(bookmarks)
        assertEquals(1, skipped); assertTrue(noexes.contains("\"locked\": false")); assertTrue(noexes.contains("\"value\": 0"))
        val imported = MemoryBookmarkJson.import(noexes, tid, bid, 20)
        assertEquals(ValueType.UInt32, imported.single().valueType); assertEquals("BP", imported.single().name)
    }
}
