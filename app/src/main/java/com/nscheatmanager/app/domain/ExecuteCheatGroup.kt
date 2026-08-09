package com.nscheatmanager.app.domain

import com.nscheatmanager.app.cheats.parser.CheatGroup
import com.nscheatmanager.app.cheats.vm.CheatExecutor
import com.nscheatmanager.app.cheats.vm.ExecutionReport
import com.nscheatmanager.app.cheats.vm.ExecutionStatus
import com.nscheatmanager.app.protocol.sysbot.GameIdentity
import com.nscheatmanager.app.protocol.sysbot.SysBotbase

class ExecuteCheatGroup(
    private val executor: CheatExecutor = CheatExecutor(),
    private val persistence: SessionPersistence,
) {
    suspend fun checkedGroups(device: DeviceProfile, identity: GameIdentity): Map<String, Long?> =
        persistence.checkedGroups(device.id, identity)

    suspend fun uncheck(device: DeviceProfile, identity: GameIdentity, groupName: String) {
        require(groupName.isNotBlank()) { "Cheat group name must not be blank" }
        persistence.setChecked(
            deviceId = device.id,
            identity = identity,
            groupName = groupName,
            checked = false,
        )
    }

    suspend fun execute(
        device: DeviceProfile,
        identity: GameIdentity,
        group: CheatGroup,
        client: SysBotbase,
        checkpoint: suspend () -> Unit = {},
    ): ExecutionReport {
        checkpoint()
        val report = executor.execute(group, identity, client)
        checkpoint()
        if (report.status == ExecutionStatus.Complete) {
            persistence.setChecked(
                deviceId = device.id,
                identity = identity,
                groupName = group.name,
                checked = true,
            )
            checkpoint()
        }
        return report
    }
}
