package io.github.manhvu1212.effortless.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        CourseEntity::class,
        LessonEntity::class,
        TrackEntity::class,
        TrackStateEntity::class,
        LessonProgressEntity::class,
        DailyLogEntity::class,
        CardEntity::class,
        ReviewLogEntity::class,
        RemoteFileEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao
    abstract fun study(): StudyDao
    abstract fun cards(): CardDao
    abstract fun remoteFiles(): RemoteFileDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `remote_file` (`path` TEXT NOT NULL, `driveId` TEXT NOT NULL, " +
                        "`size` INTEGER NOT NULL, `md5` TEXT, PRIMARY KEY(`path`))"
                )
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "effortless.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
