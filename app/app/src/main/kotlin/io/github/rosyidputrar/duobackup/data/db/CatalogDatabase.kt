package io.github.rosyidputrar.duobackup.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/** The local catalog (plan §3). Schema changes need a version bump and an explicit migration. */
@Database(
    entities = [
        FileEntity::class,
        ContentEntity::class,
        ContentPartEntity::class,
        PartLocationEntity::class,
        FileVersionEntity::class,
        DriveSessionEntity::class,
        SnapshotEntity::class,
        RunEntity::class,
        ScanEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class CatalogDatabase : RoomDatabase() {
    abstract fun fileDao(): FileDao

    abstract fun contentDao(): ContentDao

    abstract fun versionDao(): VersionDao

    abstract fun runDao(): RunDao

    abstract fun snapshotDao(): SnapshotDao

    abstract fun driveSessionDao(): DriveSessionDao

    companion object {
        const val NAME: String = "catalog.db"
    }
}
