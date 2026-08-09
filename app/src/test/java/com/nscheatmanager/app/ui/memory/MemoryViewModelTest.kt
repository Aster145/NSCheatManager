package com.nscheatmanager.app.ui.memory

import com.nscheatmanager.app.core.model.BuildId
import com.nscheatmanager.app.core.model.TitleId
import com.nscheatmanager.app.core.model.ValueType
import com.nscheatmanager.app.core.model.MemoryTarget
import com.nscheatmanager.app.domain.GameOperationKey
import com.nscheatmanager.app.domain.ImmutableBytes
import com.nscheatmanager.app.domain.LockedValue
import com.nscheatmanager.app.domain.MemoryReadResult
import com.nscheatmanager.app.domain.MemoryWriteResult
import com.nscheatmanager.app.protocol.sysbot.GameIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MemoryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun writeConfirmationIsImmutableAndClaimedOnce() = runTest(dispatcher) {
        val gateway = FakeMemoryGateway()
        val vm = MemoryViewModel(gateway, scope = backgroundScope, clockMillis = { 123 })
        vm.updateAddress("10"); vm.updateValue("42"); vm.selectType(ValueType.Int32)
        vm.requestWrite(); val pending = requireNotNull(vm.uiState.value.confirmation)
        vm.updateValue("99")
        vm.confirmWrite(pending.id); vm.confirmWrite(pending.id); runCurrent()
        assertEquals(1, gateway.writes.size)
        assertArrayEquals(byteArrayOf(42, 0, 0, 0), gateway.writes.single().third)
    }

    @Test fun relativeModeRequiresValidatedKeyAndHexReadIsLimited() = runTest(dispatcher) {
        val gateway = FakeMemoryGateway(key = null)
        val vm = MemoryViewModel(gateway, scope = backgroundScope)
        vm.selectMode(AddressMode.Main); vm.updateAddress("10"); vm.read()
        runCurrent(); assertEquals(MemoryError.SessionRequired, vm.uiState.value.error)
        gateway.key = key
        vm.selectMode(AddressMode.Absolute); vm.selectType(ValueType.Hex); vm.updateLength("4097"); vm.read()
        runCurrent(); assertEquals(MemoryError.InvalidLength, vm.uiState.value.error)
    }

    @Test fun lockKeepsResolvedAddressAndDisablesInputsUntilExactUnlock() = runTest(dispatcher) {
        val gateway = FakeMemoryGateway()
        val vm = MemoryViewModel(gateway, scope = backgroundScope)
        vm.selectMode(AddressMode.Main); vm.updateAddress("20"); vm.updateValue("7")
        vm.toggleLock(true); runCurrent()
        val locked = requireNotNull(vm.uiState.value.locked)
        assertTrue(vm.uiState.value.parametersLocked)
        vm.updateAddress("99"); assertEquals("20", vm.uiState.value.address)
        vm.toggleLock(false); runCurrent()
        assertEquals(listOf(locked.absoluteAddress), gateway.unlocks)
    }

    @Test fun sessionSwitchInvalidatesPendingConfirmationAndTrustedResult() = runTest(dispatcher) {
        val gateway = FakeMemoryGateway()
        val vm = MemoryViewModel(gateway, scope = backgroundScope)
        vm.updateAddress("10"); vm.updateValue("42"); vm.requestWrite()
        assertNotNull(vm.uiState.value.confirmation)
        gateway.key = key.copy(generation = 2)
        vm.refreshSession()
        assertNull(vm.uiState.value.confirmation)
        assertEquals(MemoryError.SessionChanged, vm.uiState.value.error)
    }

    @Test fun confirmationOwnsBytesAndExposesOnlyCopies() = runTest(dispatcher) {
        val gateway = FakeMemoryGateway()
        val vm = MemoryViewModel(gateway, scope = backgroundScope)
        vm.updateAddress("10"); vm.updateValue("2A 00"); vm.selectType(ValueType.Hex); vm.requestWrite()
        val pending = requireNotNull(vm.uiState.value.confirmation)
        val exposed = pending.bytes.copyToByteArray().also { it[0] = 0 }
        vm.confirmWrite(pending.id); runCurrent()
        assertEquals(0, exposed[0].toInt())
        assertArrayEquals(byteArrayOf(0x2A, 0), gateway.writes.single().third)
    }

    @Test fun lateReadAfterSessionSwitchCannotPublishOrLeaveBusy() = runTest(dispatcher) {
        val gateway = FakeMemoryGateway().apply { readGate = CompletableDeferred() }
        val vm = MemoryViewModel(gateway, scope = backgroundScope)
        vm.updateAddress("10"); vm.read(); runCurrent()
        assertTrue(vm.uiState.value.busy)
        gateway.key = key.copy(generation = 2); vm.refreshSession()
        assertFalse(vm.uiState.value.busy)
        gateway.readGate!!.complete(Unit); runCurrent()
        assertNull(vm.uiState.value.result)
        assertEquals(MemoryError.SessionChanged, vm.uiState.value.error)
    }

    @Test fun rawReadAcceptsInclusiveBoundsAndAllAddressModesResolveWithoutOverflow() = runTest(dispatcher) {
        val gateway = FakeMemoryGateway()
        val vm = MemoryViewModel(gateway, scope = backgroundScope)
        for (mode in AddressMode.entries) {
            vm.selectMode(mode); vm.selectType(ValueType.Hex); vm.updateAddress("1")
            for (size in listOf(1, 4096)) { vm.updateLength(size.toString()); vm.read(); runCurrent(); assertEquals(size, gateway.reads.last().third) }
        }
        assertEquals(listOf(MemoryTarget.Absolute(1u), MemoryTarget.MainRelative(1u), MemoryTarget.HeapRelative(1u)), gateway.reads.map { it.second }.distinct())
    }

    @Test fun everyValueTypeBuildsExactLittleEndianConfirmationIncludingIeeeSpecials() = runTest(dispatcher) {
        val cases = linkedMapOf(ValueType.Int8 to "-1", ValueType.UInt8 to "255", ValueType.Int16 to "-2", ValueType.UInt16 to "65535",
            ValueType.Int32 to "-3", ValueType.UInt32 to "4294967295", ValueType.Int64 to "-4", ValueType.UInt64 to "18446744073709551615",
            ValueType.Float to "-0.0", ValueType.Double to "Infinity", ValueType.Hex to "AA")
        val vm = MemoryViewModel(FakeMemoryGateway(), scope = backgroundScope); vm.updateAddress("10")
        cases.forEach { (type, value) -> vm.selectType(type); vm.updateValue(value); vm.requestWrite(); val bytes = requireNotNull(vm.uiState.value.confirmation).bytes.copyToByteArray(); assertEquals(type.byteSize ?: 1, bytes.size); vm.dismissWrite(vm.uiState.value.confirmation!!.id) }
        vm.selectType(ValueType.Float); vm.updateValue("NaN"); vm.requestWrite(); assertTrue(java.lang.Float.intBitsToFloat(java.nio.ByteBuffer.wrap(vm.uiState.value.confirmation!!.bytes.copyToByteArray()).order(java.nio.ByteOrder.LITTLE_ENDIAN).int).isNaN())
    }

    @Test fun zeroAndUnsignedSpanOverflowAreRejectedBeforeIo() = runTest(dispatcher) {
        val gateway = FakeMemoryGateway(); val vm = MemoryViewModel(gateway, scope = backgroundScope)
        vm.updateAddress("0"); vm.read(); assertEquals(MemoryError.InvalidAddress, vm.uiState.value.error)
        vm.updateAddress("FFFFFFFFFFFFFFFF"); vm.selectType(ValueType.UInt64); vm.read()
        assertEquals(MemoryError.InvalidAddress, vm.uiState.value.error); assertTrue(gateway.reads.isEmpty())
    }

    @Test fun recreationReconcilesAuthoritativeLockAndPendingCleanupWithoutRestoringConfirmation() = runTest(dispatcher) {
        val gateway = FakeMemoryGateway()
        val lock = LockedValue(MemoryTarget.Absolute(0x44u), 0x44u, ValueType.UInt8, ImmutableBytes.copyOf(byteArrayOf(1)))
        gateway.snapshot = gateway.snapshot.copy(activeLocks = mapOf(0x44uL to lock), pendingCleanup = setOf(0x55uL))
        val restored = MemoryViewModel(gateway, scope = backgroundScope); runCurrent()
        assertEquals(lock, restored.uiState.value.locked); assertEquals(setOf(0x55uL), restored.uiState.value.pendingCleanup)
        assertNull(restored.uiState.value.confirmation)
    }

    private class FakeMemoryGateway(var key: GameOperationKey? = Companion.key) : MemorySessionGateway {
        var snapshot = MemorySessionSnapshot(key, identity, emptyMap(), emptySet())
        val writes = mutableListOf<Triple<GameOperationKey, ULong, ByteArray>>()
        val reads = mutableListOf<Triple<GameOperationKey, MemoryTarget, Int>>()
        val unlocks = mutableListOf<ULong>()
        var readGate: CompletableDeferred<Unit>? = null
        override fun currentSnapshot() = snapshot.copy(operationKey = key)
        override suspend fun read(expected: GameOperationKey, target: MemoryTarget, type: ValueType, count: Int?): MemoryReadResult {
            readGate?.await()
            reads += Triple(expected, target, count ?: type.byteSize!!)
            return MemoryReadResult(target, absolute(target), type,
                ImmutableBytes.copyOf(byteArrayOf(42,0,0,0)), "42")
        }
        override suspend fun write(expected: GameOperationKey, target: MemoryTarget, type: ValueType, bytes: ByteArray) {
            writes += Triple(expected, absolute(target), bytes.copyOf())
        }
        override suspend fun lock(expected: GameOperationKey, target: MemoryTarget, type: ValueType, bytes: ByteArray): LockedValue =
            LockedValue(target, absolute(target), type, ImmutableBytes.copyOf(bytes)).also { snapshot = snapshot.copy(activeLocks = mapOf(it.absoluteAddress to it)) }
        override suspend fun unlock(expected: GameOperationKey, address: ULong) { unlocks += address; snapshot = snapshot.copy(activeLocks = snapshot.activeLocks - address) }
        private fun absolute(target: MemoryTarget) = when (target) {
            is MemoryTarget.Absolute -> target.address
            is MemoryTarget.MainRelative -> identity.mainBase + target.offset
            is MemoryTarget.HeapRelative -> identity.heapBase + target.offset
        }
    }
    companion object {
        val identity = GameIdentity(TitleId.parse("0100000000000001"), BuildId.parse("0123456789ABCDEF"), 0x1000u, 0x2000u)
        val key = GameOperationKey("device", identity.titleId, identity.buildId, 1)
    }
}
