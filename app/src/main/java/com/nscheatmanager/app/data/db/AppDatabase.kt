package com.nscheatmanager.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [DeviceProfileEntity::class, GameSessionEntity::class, CheckedCheatEntity::class, MemoryBookmarkEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun deviceProfileDao(): DeviceProfileDao
    abstract fun gameSessionDao(): GameSessionDao
    abstract fun checkedCheatDao(): CheckedCheatDao
    abstract fun memoryBookmarkDao(): MemoryBookmarkDao

    companion object {
        fun create(context: Context, name: String = "nscheatmanager.db"): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2)
                .build()

        fun inMemory(context: Context): AppDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `memory_bookmarks` (`titleId` TEXT NOT NULL, `buildId` TEXT NOT NULL, `name` TEXT NOT NULL, `addressExpression` TEXT NOT NULL, `valueType` TEXT NOT NULL, `hexLength` INTEGER, `note` TEXT NOT NULL, `modifiedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`titleId`, `buildId`, `name`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_bookmarks_titleId_buildId_modifiedAtEpochMillis` ON `memory_bookmarks` (`titleId`, `buildId`, `modifiedAtEpochMillis`)")
            }
        }
    }
}
