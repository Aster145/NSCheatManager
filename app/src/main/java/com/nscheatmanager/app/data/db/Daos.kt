package com.nscheatmanager.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceProfileDao {
    @Query("SELECT * FROM device_profiles ORDER BY isDefault DESC, createdAtEpochMillis ASC")
    fun observeAll(): Flow<List<DeviceProfileEntity>>

    @Query("SELECT * FROM device_profiles WHERE id = :id")
    suspend fun findById(id: String): DeviceProfileEntity?

    @Query("SELECT * FROM device_profiles WHERE name = :name AND id != :excludedId")
    suspend fun findOtherByName(name: String, excludedId: String): DeviceProfileEntity?

    @Query("SELECT * FROM device_profiles WHERE host = :host AND id != :excludedId")
    suspend fun findOtherByHost(host: String, excludedId: String): DeviceProfileEntity?

    @Query("SELECT COUNT(*) FROM device_profiles")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM device_profiles WHERE isDefault = 1")
    suspend fun defaultCount(): Int

    @Query("SELECT * FROM device_profiles WHERE isDefault = 1 LIMIT 1")
    suspend fun defaultDevice(): DeviceProfileEntity?

    @Query("SELECT * FROM device_profiles ORDER BY createdAtEpochMillis ASC LIMIT 1")
    suspend fun firstByCreation(): DeviceProfileEntity?

    @Query("UPDATE device_profiles SET isDefault = 0")
    suspend fun clearDefault()

    @Query("UPDATE device_profiles SET isDefault = 1 WHERE id = :id")
    suspend fun makeDefault(id: String): Int

    @Upsert
    suspend fun upsert(device: DeviceProfileEntity)

    @Query("DELETE FROM device_profiles WHERE id = :id")
    suspend fun delete(id: String): Int
}

@Dao
interface GameSessionDao {
    @Query("SELECT * FROM game_sessions WHERE deviceId = :deviceId")
    fun observeByDevice(deviceId: String): Flow<GameSessionEntity?>

    @Upsert
    suspend fun upsert(session: GameSessionEntity)

    @Query("UPDATE game_sessions SET validated = 0 WHERE deviceId = :deviceId")
    suspend fun invalidate(deviceId: String)
}

@Dao
interface CheckedCheatDao {
    @Query(
        "SELECT * FROM checked_cheats WHERE deviceId = :deviceId AND titleId = :titleId " +
            "AND buildId = :buildId AND isChecked = 1 ORDER BY groupName COLLATE NOCASE",
    )
    fun observeChecked(
        deviceId: String,
        titleId: String,
        buildId: String,
    ): Flow<List<CheckedCheatEntity>>

    @Upsert
    suspend fun upsert(checkedCheat: CheckedCheatEntity)
}

@Dao
interface MemoryBookmarkDao {
    @Query("SELECT * FROM memory_bookmarks WHERE titleId = :titleId AND buildId = :buildId ORDER BY modifiedAtEpochMillis DESC, name COLLATE NOCASE")
    fun observe(titleId: String, buildId: String): Flow<List<MemoryBookmarkEntity>>

    @Query("SELECT COUNT(*) FROM memory_bookmarks WHERE titleId = :titleId AND buildId = :buildId")
    suspend fun count(titleId: String, buildId: String): Int

    @Query("SELECT * FROM memory_bookmarks WHERE titleId = :titleId AND buildId = :buildId AND name = :name COLLATE NOCASE LIMIT 1")
    suspend fun find(titleId: String, buildId: String, name: String): MemoryBookmarkEntity?

    @Upsert suspend fun upsert(bookmark: MemoryBookmarkEntity)

    @Query("DELETE FROM memory_bookmarks WHERE titleId = :titleId AND buildId = :buildId AND name = :name")
    suspend fun delete(titleId: String, buildId: String, name: String): Int
}
