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

    private class FakeMemoryGateway(var key: GameOperationKey? = Companion.key) : MemorySessionGateway {
        var snapshot = MemorySessionSnapshot(key, identity, emptyMap(), emptySet())
        val writes = mutableListOf<Triple<GameOperationKey, ULong, ByteArray>>()
        val unlocks = mutableListOf<ULong>()
        override fun currentSnapshot() = snapshot.copy(operationKey = key)
        override suspend fun read(expected: GameOperationKey, target: MemoryTarget, type: ValueType, count: Int?) =
            MemoryReadResult(target, absolute(target), type,
                ImmutableBytes.copyOf(byteArrayOf(42,0,0,0)), "42")
        override suspend fun write(expected: GameOperationKey, target: MemoryTarget, type: ValueType, bytes: ByteArray) {
            writes += Triple(expected, absolute(target), bytes.copyOf())
        }
        override suspend fun lock(expected: GameOperationKey, target: MemoryTarget, type: ValueType, bytes: ByteArray) =
            LockedValue(target, absolute(target), type, ImmutableBytes.copyOf(bytes))
        override suspend fun unlock(expected: GameOperationKey, address: ULong) { unlocks += address }
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
