package com.nscheatmanager.app.domain

import com.nscheatmanager.app.cheats.parser.CheatGroup
import com.nscheatmanager.app.cheats.vm.CheatExecutor
import com.nscheatmanager.app.cheats.vm.ExecutionReport
import com.nscheatmanager.app.cheats.vm.ExecutionStatus
import com.nscheatmanager.app.protocol.sysbot.GameIdentity
import com.nscheatmanager.app.protocol.sysbot.SysBotbase

class ExecuteCheatGroup(
    private val executor: CheatExecutor = CheatExecutor(),
    @Suppress("unused") private val persistence: SessionPersistence? = null,
) {
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
        return report
    }
}
