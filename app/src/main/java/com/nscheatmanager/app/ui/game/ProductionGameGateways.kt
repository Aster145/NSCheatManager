package com.nscheatmanager.app.ui.game

import com.nscheatmanager.app.cheats.parser.CheatGroup
import com.nscheatmanager.app.cheats.vm.ExecutionReport
import com.nscheatmanager.app.domain.DeviceProfile
import com.nscheatmanager.app.domain.DeviceRepository
import com.nscheatmanager.app.domain.DeviceSession
import com.nscheatmanager.app.domain.DeviceSessionState
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

    override suspend fun executeGroup(group: CheatGroup): ExecutionReport = delegate.executeGroup(group)

    override suspend fun uncheckGroup(groupName: String) {
        delegate.uncheckGroup(groupName)
    }

    override suspend fun close() {
        delegate.closeAndJoin()
    }
}
