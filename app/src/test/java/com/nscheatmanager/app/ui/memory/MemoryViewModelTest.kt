package com.nscheatmanager.app.ui.memory

import com.nscheatmanager.app.core.model.BuildId
import com.nscheatmanager.app.core.model.TitleId
import com.nscheatmanager.app.core.model.ValueType
import com.nscheatmanager.app.core.model.MemoryTarget
import com.nscheatmanager.app.core.binary.LittleEndianCodec
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
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
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
        vm.requestWrite(); runCurrent(); val pending = requireNotNull(vm.uiState.value.confirmation)
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
        vm.updateAddress("10"); vm.updateValue("42"); vm.requestWrite(); runCurrent()
        assertNotNull(vm.uiState.value.confirmation)
        gateway.key = key.copy(generation = 2)
        vm.refreshSession()
        assertNull(vm.uiState.value.confirmation)
        assertEquals(MemoryError.SessionChanged, vm.uiState.value.error)
    }

    @Test fun confirmationOwnsBytesAndExposesOnlyCopies() = runTest(dispatcher) {
        val gateway = FakeMemoryGateway()
        val vm = MemoryViewModel(gateway, scope = backgroundScope)
        vm.updateAddress("10"); vm.updateValue("2A 00"); vm.selectType(ValueType.Hex); vm.requestWrite(); runCurrent()
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
            for (size in listOf(1, 4096)) {
                vm.updateLength(size.toString()); vm.read(); runCurrent(); assertEquals(size, gateway.reads.last().third)
                assertEquals(size * 3 - 1, vm.uiState.value.result!!.raw.length)
                assertEquals(size * 2, vm.uiState.value.result!!.value.length)
            }
        }
        assertEquals(listOf(MemoryTarget.Absolute(1u), MemoryTarget.Absolute(0x1001u), MemoryTarget.Absolute(0x2001u)), gateway.reads.map { it.second }.distinct())
    }

    @Test fun everyValueTypeBuildsExactLittleEndianConfirmationIncludingIeeeSpecials() = runTest(dispatcher) {
        val cases = linkedMapOf(ValueType.Int8 to "-1", ValueType.UInt8 to "255", ValueType.Int16 to "-2", ValueType.UInt16 to "65535",
            ValueType.Int32 to "-3", ValueType.UInt32 to "4294967295", ValueType.Int64 to "-4", ValueType.UInt64 to "18446744073709551615",
            ValueType.Float to "-0.0", ValueType.Double to "Infinity", ValueType.Hex to "AA")
        val vm = MemoryViewModel(FakeMemoryGateway(), scope = backgroundScope); vm.updateAddress("10")
        cases.forEach { (type, value) -> vm.selectType(type); vm.updateValue(value); vm.requestWrite(); runCurrent(); val bytes = requireNotNull(vm.uiState.value.confirmation).bytes.copyToByteArray(); assertEquals(type.byteSize ?: 1, bytes.size); vm.dismissWrite(vm.uiState.value.confirmation!!.id) }
        vm.selectType(ValueType.Float); vm.updateValue("NaN"); vm.requestWrite(); runCurrent(); assertTrue(java.lang.Float.intBitsToFloat(java.nio.ByteBuffer.wrap(vm.uiState.value.confirmation!!.bytes.copyToByteArray()).order(java.nio.ByteOrder.LITTLE_ENDIAN).int).isNaN())
    }

    @Test fun zeroAndUnsignedSpanOverflowAreRejectedBeforeIo() = runTest(dispatcher) {
        val gateway = FakeMemoryGateway(); val vm = MemoryViewModel(gateway, scope = backgroundScope)
        vm.updateAddress("0"); vm.read(); runCurrent(); assertEquals(MemoryError.InvalidAddress, vm.uiState.value.error)
        vm.updateAddress("FFFFFFFFFFFFFFFF"); vm.selectType(ValueType.UInt64); vm.read(); runCurrent()
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

    @Test fun nonCancellableLateReadSuccessAndFailureCannotOverwriteNewClaim() = runTest(dispatcher) {
        val gateway = FakeMemoryGateway(); val oldSuccess = ManualBarrier<MemoryReadResult>(); gateway.readBarrier = oldSuccess
        val vm = MemoryViewModel(gateway, scope = backgroundScope, clockMillis = { 7 })
        vm.updateAddress("10"); vm.read(); runCurrent()
        gateway.switchTo(2); vm.refreshSession(); vm.updateAddress("20"); vm.read(); runCurrent()
        assertEquals("42", vm.uiState.value.result?.value)
        oldSuccess.succeed(MemoryReadResult(MemoryTarget.Absolute(0x10u), 0x10u, ValueType.Int32, ImmutableBytes.copyOf(byteArrayOf(99,0,0,0)), "99")); runCurrent()
        assertEquals("42", vm.uiState.value.result?.value); assertFalse(vm.uiState.value.busy); assertNull(vm.uiState.value.error)
        val oldFailure = ManualBarrier<MemoryReadResult>(); gateway.readBarrier = oldFailure
        vm.read(); runCurrent(); gateway.switchTo(3); vm.refreshSession(); vm.read(); runCurrent()
        oldFailure.fail(IllegalStateException("late")); runCurrent()
        assertEquals("42", vm.uiState.value.result?.value); assertFalse(vm.uiState.value.busy); assertNull(vm.uiState.value.error)
    }

    @Test fun lateWriteLockAndUnlockCannotAffectNewSessionOrAuthoritativeLock() = runTest(dispatcher) {
        val gateway = FakeMemoryGateway(); val vm = MemoryViewModel(gateway, scope = backgroundScope)
        val oldWrite = ManualBarrier<Unit>(); gateway.writeBarrier = oldWrite
        vm.updateAddress("10"); vm.updateValue("1"); vm.requestWrite(); runCurrent(); vm.confirmWrite(vm.uiState.value.confirmation!!.id); runCurrent()
        gateway.switchTo(2); vm.refreshSession(); vm.updateValue("2"); vm.requestWrite(); runCurrent(); vm.confirmWrite(vm.uiState.value.confirmation!!.id); runCurrent()
        oldWrite.fail(IllegalStateException("late write")); runCurrent(); assertFalse(vm.uiState.value.busy); assertNull(vm.uiState.value.error)
        val oldLock = ManualBarrier<LockedValue>(); gateway.lockBarrier = oldLock
        vm.toggleLock(true); runCurrent(); gateway.switchTo(3); vm.refreshSession(); vm.updateAddress("30"); vm.updateValue("3"); vm.toggleLock(true); runCurrent()
        val newLock = vm.uiState.value.locked!!
        oldLock.succeed(LockedValue(MemoryTarget.Absolute(0x10u), 0x10u, ValueType.Int32, ImmutableBytes.copyOf(byteArrayOf(1,0,0,0)))); runCurrent()
        assertEquals(newLock, vm.uiState.value.locked)
        val oldUnlock = ManualBarrier<Unit>(); gateway.unlockBarrier = oldUnlock
        vm.toggleLock(false); runCurrent(); gateway.switchTo(4)
        val authoritative = LockedValue(MemoryTarget.Absolute(0x77u), 0x77u, ValueType.UInt8, ImmutableBytes.copyOf(byteArrayOf(7)))
        gateway.snapshot = gateway.snapshot.copy(activeLocks = mapOf(0x77uL to authoritative)); vm.refreshSession()
        oldUnlock.succeed(Unit); runCurrent(); assertEquals(authoritative, vm.uiState.value.locked); assertFalse(vm.uiState.value.busy)
    }

    private class FakeMemoryGateway(var key: GameOperationKey? = Companion.key) : MemorySessionGateway {
        var snapshot = MemorySessionSnapshot(key, identity, emptyMap(), emptySet())
        val writes = mutableListOf<Triple<GameOperationKey, ULong, ByteArray>>()
        val reads = mutableListOf<Triple<GameOperationKey, MemoryTarget, Int>>()
        val unlocks = mutableListOf<ULong>()
        var readGate: CompletableDeferred<Unit>? = null
        var readBarrier: ManualBarrier<MemoryReadResult>? = null
        var writeBarrier: ManualBarrier<Unit>? = null
        var lockBarrier: ManualBarrier<LockedValue>? = null
        var unlockBarrier: ManualBarrier<Unit>? = null
        override fun currentSnapshot() = snapshot.copy(operationKey = key)
        override suspend fun read(expected: GameOperationKey, target: MemoryTarget, type: ValueType, count: Int?): MemoryReadResult {
            readBarrier?.also { readBarrier = null }?.let { return it.await() }
            readGate?.await()
            reads += Triple(expected, target, count ?: type.byteSize!!)
            val size = count ?: type.byteSize!!
            val bytes = ByteArray(size).also { it[0] = 42 }
            return MemoryReadResult(target, absolute(target), type, ImmutableBytes.copyOf(bytes), LittleEndianCodec.decode(type, bytes))
        }
        override suspend fun write(expected: GameOperationKey, target: MemoryTarget, type: ValueType, bytes: ByteArray) {
            writeBarrier?.also { writeBarrier = null }?.await()
            writes += Triple(expected, absolute(target), bytes.copyOf())
        }
        override suspend fun lock(expected: GameOperationKey, target: MemoryTarget, type: ValueType, bytes: ByteArray): LockedValue =
            (lockBarrier?.also { lockBarrier = null }?.await() ?: LockedValue(target, absolute(target), type, ImmutableBytes.copyOf(bytes)))
                .also { if (key == expected) snapshot = snapshot.copy(activeLocks = mapOf(it.absoluteAddress to it)) }
        override suspend fun unlock(expected: GameOperationKey, address: ULong) { unlockBarrier?.also { unlockBarrier = null }?.await(); unlocks += address; if (key == expected) snapshot = snapshot.copy(activeLocks = snapshot.activeLocks - address) }
        fun switchTo(generation: Long) { key = Companion.key.copy(generation = generation); snapshot = snapshot.copy(operationKey = key, activeLocks = emptyMap()) }
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

private class ManualBarrier<T> {
    private var continuation: Continuation<T>? = null
    suspend fun await(): T = suspendCoroutine { continuation = it }
    fun succeed(value: T) = requireNotNull(continuation).resume(value)
    fun fail(error: Throwable) = requireNotNull(continuation).resumeWithException(error)
}
