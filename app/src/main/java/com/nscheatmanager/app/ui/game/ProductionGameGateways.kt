package com.nscheatmanager.app.ui.game

import com.nscheatmanager.app.cheats.parser.CheatGroup
import com.nscheatmanager.app.cheats.vm.ExecutionReport
import com.nscheatmanager.app.domain.DeviceProfile
import com.nscheatmanager.app.domain.DeviceRepository
import com.nscheatmanager.app.domain.DeviceSession
import com.nscheatmanager.app.domain.DeviceSessionState
import com.nscheatmanager.app.domain.GameOperationKey
import com.nscheatmanager.app.core.model.MemoryTarget
import com.nscheatmanager.app.core.model.ValueType
import com.nscheatmanager.app.domain.LockedValue
import com.nscheatmanager.app.domain.MemoryReadResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

class DeviceRepositoryGameStore(private val repository: DeviceRepository) : GameDeviceStore {
    override val devices: Flow<List<DeviceProfile>> = repository.observeDevices()
    override val selectedDeviceId: Flow<String?> = repository.observeSelectedDeviceId()

    override suspend fun selectDevice(deviceId: String) {
        repository.selectDevice(deviceId)
    }
}

class DeviceSessionGateway(private val delegate: DeviceSession) : GameSessionGateway {
    override val state: StateFlow<DeviceSessionState> = delegate.state

    override fun connectAndRecognize(device: DeviceProfile) {
        delegate.connectAndRecognize(device)
    }

    override fun switchDevice(device: DeviceProfile) {
        delegate.switchDevice(device)
    }

    override fun disconnect() {
        delegate.disconnect()
    }

    override fun recognizeAgain() {
        delegate.recognizeAgain()
    }

    override suspend fun detachDmnt() {
        delegate.detachDmnt()
    }

    override fun currentOperationKey(): GameOperationKey? = delegate.currentOperationKey()

    override fun requireCurrentOperationKey(expected: GameOperationKey) {
        delegate.requireCurrentOperationKey(expected)
    }

    override suspend fun executeGroup(expected: GameOperationKey, group: CheatGroup): ExecutionReport =
        delegate.executeGroup(expected, group)

    override suspend fun uncheckGroup(expected: GameOperationKey, groupName: String) {
        delegate.uncheckGroup(expected, groupName)
    }

    override suspend fun readMemory(expected: GameOperationKey, target: MemoryTarget, type: ValueType, count: Int?): MemoryReadResult = delegate.readValue(expected, target, type, count)
    override suspend fun writeMemory(expected: GameOperationKey, target: MemoryTarget, type: ValueType, bytes: ByteArray) { delegate.writePrepared(expected, target, type, bytes) }
    override suspend fun lockMemory(expected: GameOperationKey, target: MemoryTarget, type: ValueType, bytes: ByteArray): LockedValue = delegate.lockPrepared(expected, target, type, bytes)
    override suspend fun unlockMemory(expected: GameOperationKey, address: ULong) { delegate.unlockValue(expected, address) }

    override suspend fun close() {
        delegate.closeAndJoin()
    }
}
