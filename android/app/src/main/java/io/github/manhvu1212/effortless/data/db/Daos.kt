package io.github.manhvu1212.effortless.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
    @Query("SELECT * FROM course ORDER BY position")
    fun courses(): Flow<List<CourseEntity>>

    @Query("SELECT * FROM course ORDER BY position")
    suspend fun coursesOnce(): List<CourseEntity>

    @Query("SELECT * FROM course WHERE id = :id")
    fun course(id: String): Flow<CourseEntity?>

    @Query(
        "SELECT lesson.* FROM lesson JOIN course ON course.id = lesson.courseId " +
            "ORDER BY course.position, lesson.position"
    )
    fun allLessons(): Flow<List<LessonEntity>>

    @Query(
        "SELECT lesson.* FROM lesson JOIN course ON course.id = lesson.courseId " +
            "ORDER BY course.position, lesson.position"
    )
    suspend fun allLessonsOnce(): List<LessonEntity>

    @Query("SELECT * FROM lesson WHERE courseId = :courseId ORDER BY position")
    fun lessons(courseId: String): Flow<List<LessonEntity>>

    @Query("SELECT * FROM lesson WHERE courseId = :courseId ORDER BY position")
    suspend fun lessonsOnce(courseId: String): List<LessonEntity>

    @Query("SELECT * FROM lesson WHERE id = :id")
    fun lesson(id: String): Flow<LessonEntity?>

    @Query("SELECT * FROM lesson WHERE id = :id")
    suspend fun lessonOnce(id: String): LessonEntity?

    @Query("SELECT * FROM track WHERE lessonId = :lessonId ORDER BY position")
    fun tracks(lessonId: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM track WHERE lessonId = :lessonId ORDER BY position")
    suspend fun tracksOnce(lessonId: String): List<TrackEntity>

    @Query("SELECT * FROM track WHERE id = :id")
    suspend fun track(id: String): TrackEntity?

    @Query("SELECT COUNT(*) FROM track")
    fun trackCount(): Flow<Int>

    @Query("SELECT * FROM track_state WHERE trackId IN (SELECT id FROM track WHERE lessonId = :lessonId)")
    fun trackStates(lessonId: String): Flow<List<TrackStateEntity>>

    @Query("SELECT * FROM track_state WHERE trackId = :trackId")
    suspend fun trackState(trackId: String): TrackStateEntity?

    @Query("INSERT OR IGNORE INTO track_state (trackId, durationMs, resumeMs) VALUES (:trackId, 0, 0)")
    suspend fun ensureTrackState(trackId: String)

    @Query("UPDATE track_state SET resumeMs = :ms WHERE trackId = :trackId")
    suspend fun setResume(trackId: String, ms: Long)

    @Query("UPDATE track_state SET durationMs = :ms WHERE trackId = :trackId")
    suspend fun setDuration(trackId: String, ms: Long)

    @Insert
    suspend fun insertCourses(courses: List<CourseEntity>)

    @Insert
    suspend fun insertLessons(lessons: List<LessonEntity>)

    @Insert
    suspend fun insertTracks(tracks: List<TrackEntity>)

    @Query("DELETE FROM course")
    suspend fun deleteCourses()

    @Query("DELETE FROM lesson")
    suspend fun deleteLessons()

    @Query("DELETE FROM track")
    suspend fun deleteTracks()

    @Transaction
    suspend fun replaceLibrary(courses: List<CourseEntity>, lessons: List<LessonEntity>, tracks: List<TrackEntity>) {
        deleteTracks()
        deleteLessons()
        deleteCourses()
        insertCourses(courses)
        insertLessons(lessons)
        insertTracks(tracks)
    }
}

@Dao
interface StudyDao {
    @Query("SELECT * FROM lesson_progress")
    fun allProgress(): Flow<List<LessonProgressEntity>>

    @Query("SELECT * FROM lesson_progress")
    suspend fun allProgressOnce(): List<LessonProgressEntity>

    @Query("SELECT * FROM lesson_progress WHERE lessonId = :lessonId")
    fun progress(lessonId: String): Flow<LessonProgressEntity?>

    @Query("SELECT * FROM lesson_progress WHERE lessonId = :lessonId")
    suspend fun progressOnce(lessonId: String): LessonProgressEntity?

    @Upsert
    suspend fun upsertProgress(progress: LessonProgressEntity)

    @Query("INSERT OR IGNORE INTO daily_log (day, lessonId, steps, listenedMs) VALUES (:day, :lessonId, 0, 0)")
    suspend fun ensureLog(day: Long, lessonId: String)

    @Query("UPDATE daily_log SET listenedMs = listenedMs + :ms WHERE day = :day AND lessonId = :lessonId")
    suspend fun addListened(day: Long, lessonId: String, ms: Long)

    @Query("UPDATE daily_log SET steps = steps | :mask WHERE day = :day AND lessonId = :lessonId")
    suspend fun addSteps(day: Long, lessonId: String, mask: Long)

    @Query("UPDATE daily_log SET steps = steps & ~:mask WHERE day = :day AND lessonId = :lessonId")
    suspend fun removeSteps(day: Long, lessonId: String, mask: Long)

    @Query("SELECT * FROM daily_log WHERE day = :day AND lessonId = :lessonId")
    fun log(day: Long, lessonId: String): Flow<DailyLogEntity?>

    @Query("SELECT * FROM daily_log WHERE day = :day AND lessonId = :lessonId")
    suspend fun logOnce(day: Long, lessonId: String): DailyLogEntity?

    @Query("SELECT day, SUM(listenedMs) AS listenedMs FROM daily_log WHERE day >= :from GROUP BY day ORDER BY day")
    fun listenedPerDay(from: Long): Flow<List<DayTotal>>

    @Query("SELECT DISTINCT day FROM daily_log WHERE listenedMs > 0 OR steps != 0 ORDER BY day DESC")
    fun activeDays(): Flow<List<Long>>

    @Query("SELECT COALESCE(SUM(listenedMs), 0) FROM daily_log")
    fun totalListened(): Flow<Long>

    @Query("SELECT COALESCE(SUM(listenedMs), 0) FROM daily_log WHERE day = :day")
    suspend fun listenedOnce(day: Long): Long

    @Query("SELECT COUNT(*) FROM daily_log WHERE day = :day AND (listenedMs > 0 OR steps != 0)")
    suspend fun activityCount(day: Long): Int

    @Query("INSERT OR IGNORE INTO track_listen (day, trackId, count) VALUES (:day, :trackId, 0)")
    suspend fun ensureListen(day: Long, trackId: String)

    @Query("UPDATE track_listen SET count = count + 1 WHERE day = :day AND trackId = :trackId")
    suspend fun incrementListen(day: Long, trackId: String)

    @Query("SELECT count FROM track_listen WHERE day = :day AND trackId = :trackId")
    suspend fun listenCount(day: Long, trackId: String): Int?

    /** Listens on [day] per track of the lesson. */
    @Query(
        "SELECT * FROM track_listen WHERE day = :day AND trackId IN (SELECT id FROM track WHERE lessonId = :lessonId)"
    )
    fun listens(day: Long, lessonId: String): Flow<List<TrackListenEntity>>
}

@Dao
interface CardDao {
    @Insert
    suspend fun insert(card: CardEntity): Long

    @Update
    suspend fun update(card: CardEntity)

    @Delete
    suspend fun delete(card: CardEntity)

    @Query("SELECT * FROM card ORDER BY createdAt DESC")
    fun all(): Flow<List<CardEntity>>

    @Query("SELECT * FROM card WHERE dueDay <= :day ORDER BY dueDay, id")
    suspend fun due(day: Long): List<CardEntity>

    @Query("SELECT COUNT(*) FROM card WHERE dueDay <= :day")
    fun dueCount(day: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM card WHERE dueDay <= :day")
    suspend fun dueCountOnce(day: Long): Int

    @Insert
    suspend fun insertReview(review: ReviewLogEntity)

    @Query("SELECT day, COUNT(*) AS count FROM review_log WHERE day >= :from GROUP BY day ORDER BY day")
    fun reviewsPerDay(from: Long): Flow<List<DayCount>>
}

@Dao
interface RemoteFileDao {
    @Query("SELECT * FROM remote_file WHERE path = :path")
    suspend fun byPath(path: String): RemoteFileEntity?

    @Query("SELECT * FROM remote_file WHERE path IN (:paths)")
    suspend fun byPaths(paths: List<String>): List<RemoteFileEntity>

    @Query("DELETE FROM remote_file")
    suspend fun deleteAll()

    @Insert
    suspend fun insertAll(files: List<RemoteFileEntity>)

    @Transaction
    suspend fun replaceAll(files: List<RemoteFileEntity>) {
        deleteAll()
        insertAll(files)
    }
}
