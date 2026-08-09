package com.nscheatmanager.app.domain

import androidx.room.withTransaction
import com.nscheatmanager.app.core.model.BuildId
import com.nscheatmanager.app.core.model.TitleId
import com.nscheatmanager.app.data.db.AppDatabase
import com.nscheatmanager.app.data.db.CheckedCheatEntity
import com.nscheatmanager.app.data.db.DeviceProfileEntity
import com.nscheatmanager.app.data.db.GameSessionEntity
import com.nscheatmanager.app.data.preferences.AppPreferences
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class DeviceProfile(
    val id: String,
    val name: String,
    val host: String,
    val sysBotPort: Int = DeviceProfileEntity.DEFAULT_SYS_BOT_PORT,
    val ftpPort: Int = DeviceProfileEntity.DEFAULT_FTP_PORT,
    val noexsPort: Int = DeviceProfileEntity.DEFAULT_NOEXS_PORT,
    val isDefault: Boolean = false,
)

/**
 * Persists device state and owns the trust epoch for one live connection coordinator.
 * Profile transactions may use other repository instances, but session save/disconnect events for
 * one connection must be routed through the same instance because validated memory bases are local
 * to that live connection and are intentionally never shared through Room.
 */
class DeviceRepository(
    private val database: AppDatabase,
    private val preferences: AppPreferences,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { UUID.randomUUID().toString() },
    internal val beforeSessionPersist: suspend (deviceId: String) -> Unit = {},
) {
    private val mutationMutex = Mutex()
    private val sessionStateMutex = Mutex()
    private val devices = database.deviceProfileDao()
    private val sessions = database.gameSessionDao()
    private val checkedCheats = database.checkedCheatDao()
    private val activeValidatedSessions = MutableStateFlow<Map<String, GameSessionEntity>>(emptyMap())
    private val sessionEpochs = mutableMapOf<String, Long>()

    fun observeDevices(): Flow<List<DeviceProfile>> = devices.observeAll().map { rows -> rows.map(::toProfile) }

    /** Resolves cross-store selection safely; stale DataStore ids are never exposed as selectable devices. */
    fun observeSelectedDeviceId(): Flow<String?> =
        combine(preferences.selectedDeviceId, devices.observeAll()) { selectedDeviceId, rows ->
            selectedDeviceId?.takeIf { selected -> rows.any { it.id == selected } }
        }.distinctUntilChanged()

    suspend fun addDevice(
        name: String,
        host: String,
        sysBotPort: Int = DeviceProfileEntity.DEFAULT_SYS_BOT_PORT,
        ftpPort: Int = DeviceProfileEntity.DEFAULT_FTP_PORT,
        noexsPort: Int = DeviceProfileEntity.DEFAULT_NOEXS_PORT,
    ): DeviceProfile = saveDevice(
        DeviceProfile(
            id = newId(),
            name = name,
            host = host,
            sysBotPort = sysBotPort,
            ftpPort = ftpPort,
            noexsPort = noexsPort,
        ),
    )

    suspend fun saveDevice(profile: DeviceProfile): DeviceProfile = mutationMutex.withLock {
        val normalized = profile.normalized()
        validateProfile(normalized)
        database.withTransaction {
            require(devices.findOtherByName(normalized.name, normalized.id) == null) {
                "A device already uses this name"
            }
            require(devices.findOtherByHost(normalized.host, normalized.id) == null) {
                "A device already uses this host"
            }
            val exists = devices.findById(normalized.id) != null
            val shouldBeDefault = normalized.isDefault || (!exists && devices.count() == 0)
            if (shouldBeDefault) devices.clearDefault()
            devices.upsert(
                DeviceProfileEntity(
                    id = normalized.id,
                    name = normalized.name,
                    host = normalized.host,
                    sysBotPort = normalized.sysBotPort,
                    ftpPort = normalized.ftpPort,
                    noexsPort = normalized.noexsPort,
                    isDefault = shouldBeDefault || (exists && devices.defaultDevice()?.id == normalized.id),
                    createdAtEpochMillis = devices.findById(normalized.id)?.createdAtEpochMillis ?: clockMillis(),
                ),
            )
            if (devices.defaultCount() == 0) devices.makeDefault(normalized.id)
        }
        toProfile(requireNotNull(devices.findById(normalized.id)))
    }

    suspend fun setDefaultDevice(deviceId: String) = mutationMutex.withLock {
        database.withTransaction {
            require(devices.findById(deviceId) != null) { "Unknown device" }
            devices.clearDefault()
            check(devices.makeDefault(deviceId) == 1) { "Unable to select default device" }
        }
    }

    suspend fun selectDevice(deviceId: String) = mutationMutex.withLock {
        require(devices.findById(deviceId) != null) { "Unknown device" }
        preferences.setSelectedDeviceId(deviceId)
    }

    suspend fun deleteDevice(deviceId: String) = mutationMutex.withLock {
        val selectedDeviceId = preferences.selectedDeviceId.first()
        val deleted = database.withTransaction {
            val removed = devices.findById(deviceId) ?: return@withTransaction false
            val wasDefault = removed.isDefault
            devices.delete(deviceId)
            if (wasDefault && devices.defaultCount() == 0) {
                devices.firstByCreation()?.let { fallback -> devices.makeDefault(fallback.id) }
            }
            true
        }
        if (deleted && selectedDeviceId == deviceId) preferences.clearSelectedDevice()
        if (deleted) {
            sessionStateMutex.withLock {
                advanceSessionEpoch(deviceId)
                activeValidatedSessions.update { it - deviceId }
            }
        }
    }

    suspend fun saveValidatedSession(
        deviceId: String,
        titleId: String,
        buildId: String,
        mainBase: String?,
        heapBase: String?,
    ) {
        TitleId.parse(titleId)
        BuildId.parse(buildId)
        val capturedEpoch = sessionStateMutex.withLock { advanceSessionEpoch(deviceId) }
        require(devices.findById(deviceId) != null) { "Unknown device" }
        val trustedSession = GameSessionEntity(
            deviceId = deviceId,
            titleId = titleId.uppercase(),
            buildId = buildId.uppercase(),
            mainBase = mainBase,
            heapBase = heapBase,
            validated = true,
            recognizedAtEpochMillis = clockMillis(),
        )
        beforeSessionPersist(deviceId)
        // Persist identity and display-only bases, but keep connection trust process-local.
        sessions.upsert(trustedSession.copy(validated = false))
        sessionStateMutex.withLock {
            if (sessionEpochs[deviceId] == capturedEpoch) {
                activeValidatedSessions.update { it + (deviceId to trustedSession) }
            }
        }
    }

    fun observeSession(deviceId: String): Flow<GameSessionEntity?> =
        combine(sessions.observeByDevice(deviceId), activeValidatedSessions) { cached, trusted ->
            trusted[deviceId] ?: cached?.copy(validated = false)
        }

    suspend fun markDeviceDisconnected(deviceId: String) {
        sessionStateMutex.withLock {
            advanceSessionEpoch(deviceId)
            activeValidatedSessions.update { it - deviceId }
            sessions.invalidate(deviceId)
        }
    }

    suspend fun setChecked(
        deviceId: String,
        titleId: String,
        buildId: String,
        groupName: String,
        checked: Boolean,
        lastExecutedAtEpochMillis: Long? = if (checked) clockMillis() else null,
    ) {
        TitleId.parse(titleId)
        BuildId.parse(buildId)
        require(groupName.isNotBlank()) { "Cheat group name must not be blank" }
        require(devices.findById(deviceId) != null) { "Unknown device" }
        checkedCheats.upsert(
            CheckedCheatEntity(
                deviceId = deviceId,
                titleId = titleId.uppercase(),
                buildId = buildId.uppercase(),
                groupName = groupName,
                isChecked = checked,
                lastExecutedAtEpochMillis = lastExecutedAtEpochMillis,
            ),
        )
    }

    fun observeCheckedGroupNames(deviceId: String, titleId: String, buildId: String): Flow<Set<String>> {
        val canonicalTitleId = TitleId.parse(titleId).hex
        val canonicalBuildId = BuildId.parse(buildId).hex
        return checkedCheats.observeChecked(deviceId, canonicalTitleId, canonicalBuildId)
            .map { rows -> rows.mapTo(linkedSetOf()) { it.groupName } }
    }

    private fun validateProfile(profile: DeviceProfile) {
        require(profile.id.isNotBlank()) { "Device ID must not be blank" }
        require(profile.name.isNotBlank()) { "Device name must not be blank" }
        require(profile.host.isNotBlank()) { "Device host must not be blank" }
        listOf(profile.sysBotPort, profile.ftpPort, profile.noexsPort).forEach { port ->
            require(port in 1..65535) { "Port must be in 1..65535" }
        }
    }

    private fun advanceSessionEpoch(deviceId: String): Long {
        val next = (sessionEpochs[deviceId] ?: 0L) + 1L
        sessionEpochs[deviceId] = next
        return next
    }

    private fun DeviceProfile.normalized(): DeviceProfile = copy(name = name.trim(), host = host.trim())

    private fun toProfile(entity: DeviceProfileEntity) = DeviceProfile(
        id = entity.id,
        name = entity.name,
        host = entity.host,
        sysBotPort = entity.sysBotPort,
        ftpPort = entity.ftpPort,
        noexsPort = entity.noexsPort,
        isDefault = entity.isDefault,
    )
}
