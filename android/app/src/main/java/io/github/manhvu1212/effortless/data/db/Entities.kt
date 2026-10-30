package io.github.manhvu1212.effortless.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Order of the tracks inside a lesson; also the order of the daily study steps. */
enum class TrackKind { MAIN, VOCABULARY, MINI_STORY, POINT_OF_VIEW, COMMENTARY, OTHER }

@Entity(tableName = "course")
data class CourseEntity(
    @PrimaryKey val id: String,
    val title: String,
    val position: Int,
)

@Entity(tableName = "lesson", indices = [Index("courseId")])
data class LessonEntity(
    @PrimaryKey val id: String,
    val courseId: String,
    val title: String,
    val position: Int,
)

/** Uris are content:// (picked folder) or file:// (downloaded copy). Ids derive from paths, so a rescan keeps progress. */
@Entity(tableName = "track", indices = [Index("lessonId")])
data class TrackEntity(
    @PrimaryKey val id: String,
    val lessonId: String,
    val kind: TrackKind,
    val title: String,
    val position: Int,
    val audioUri: String,
    val transcriptUri: String?,
    val timingUri: String?,
)

/** Kept apart from [TrackEntity] so that replacing the library does not reset it. */
@Entity(tableName = "track_state")
data class TrackStateEntity(
    @PrimaryKey val trackId: String,
    val durationMs: Long = 0,
    val resumeMs: Long = 0,
)

/** Days are epoch days in the device time zone. */
@Entity(tableName = "lesson_progress")
data class LessonProgressEntity(
    @PrimaryKey val lessonId: String,
    val startedDay: Long,
    val daysStudied: Int = 0,
    val lastCountedDay: Long? = null,
    val completedDay: Long? = null,
)

/** [steps] is a bitmask: bit 0 = transcript read, bit i+1 = track at position i listened through. */
@Entity(tableName = "daily_log", primaryKeys = ["day", "lessonId"])
data class DailyLogEntity(
    val day: Long,
    val lessonId: String,
    val steps: Long = 0,
    val listenedMs: Long = 0,
)

@Entity(tableName = "card", indices = [Index("dueDay")])
data class CardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val front: String,
    val back: String = "",
    val example: String = "",
    val lessonId: String? = null,
    val trackId: String? = null,
    val clipStartMs: Long? = null,
    val clipEndMs: Long? = null,
    val ease: Double = 2.5,
    val intervalDays: Int = 0,
    val repetitions: Int = 0,
    val lapses: Int = 0,
    val dueDay: Long,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "review_log", indices = [Index("day")])
data class ReviewLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardId: Long,
    val day: Long,
    val grade: Int,
)

/** How many times a track was listened to the end on a day. */
@Entity(tableName = "track_listen", primaryKeys = ["day", "trackId"])
data class TrackListenEntity(
    val day: Long,
    val trackId: String,
    val count: Int = 0,
)

/** A file of the Google Drive content folder; [path] is relative to that folder, as in manifest.json. */
@Entity(tableName = "remote_file")
data class RemoteFileEntity(
    @PrimaryKey val path: String,
    val driveId: String,
    val size: Long,
    val md5: String?,
)

data class DayTotal(val day: Long, val listenedMs: Long)

data class DayCount(val day: Long, val count: Int)
