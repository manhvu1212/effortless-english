package io.github.manhvu1212.effortless.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao
    abstract fun study(): StudyDao
    abstract fun cards(): CardDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "effortless.db").build()
    }
}
