package com.nscheatmanager.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "device_profiles",
    indices = [
        Index(value = ["name"], unique = true),
        Index(value = ["host"], unique = true),
    ],
)
data class DeviceProfileEntity(
    @androidx.room.PrimaryKey val id: String,
    val name: String,
    val host: String,
    val sysBotPort: Int = DEFAULT_SYS_BOT_PORT,
    val ftpPort: Int = DEFAULT_FTP_PORT,
    val noexsPort: Int = DEFAULT_NOEXS_PORT,
    val isDefault: Boolean = false,
    val createdAtEpochMillis: Long,
) {
    companion object {
        const val DEFAULT_SYS_BOT_PORT = 6000
        const val DEFAULT_FTP_PORT = 21
        const val DEFAULT_NOEXS_PORT = 7331
    }
}

@Entity(
    tableName = "game_sessions",
    foreignKeys = [
        ForeignKey(
            entity = DeviceProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["deviceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["deviceId"])],
)
data class GameSessionEntity(
    @androidx.room.PrimaryKey val deviceId: String,
    val titleId: String,
    val buildId: String,
    /** Cached only for display. [validated] must be true before these bases are used. */
    val mainBase: String?,
    /** Cached only for display. [validated] must be true before these bases are used. */
    val heapBase: String?,
    val validated: Boolean,
    val recognizedAtEpochMillis: Long,
)

@Entity(
    tableName = "checked_cheats",
    primaryKeys = ["deviceId", "titleId", "buildId", "groupName"],
    foreignKeys = [
        ForeignKey(
            entity = DeviceProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["deviceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["deviceId", "titleId", "buildId"])],
)
data class CheckedCheatEntity(
    val deviceId: String,
    val titleId: String,
    val buildId: String,
    val groupName: String,
    val isChecked: Boolean,
    val lastExecutedAtEpochMillis: Long?,
)
