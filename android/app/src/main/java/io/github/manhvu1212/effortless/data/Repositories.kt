package io.github.manhvu1212.effortless.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import io.github.manhvu1212.effortless.data.content.LibraryScanner
import io.github.manhvu1212.effortless.data.content.Transcript
import io.github.manhvu1212.effortless.data.content.TreeNode
import io.github.manhvu1212.effortless.data.db.AppDatabase
import io.github.manhvu1212.effortless.data.db.CardEntity
import io.github.manhvu1212.effortless.data.db.LessonEntity
import io.github.manhvu1212.effortless.data.db.LessonProgressEntity
import io.github.manhvu1212.effortless.data.db.ReviewLogEntity
import io.github.manhvu1212.effortless.data.db.TrackEntity
import io.github.manhvu1212.effortless.data.settings.ContentSource
import io.github.manhvu1212.effortless.data.settings.SettingsRepository
import io.github.manhvu1212.effortless.domain.Grade
import io.github.manhvu1212.effortless.domain.Sm2
import io.github.manhvu1212.effortless.domain.Sm2State
import io.github.manhvu1212.effortless.domain.StudyPlan
import io.github.manhvu1212.effortless.domain.today
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

data class ScanSummary(val courses: Int, val lessons: Int, val tracks: Int, val withTranscript: Int, val withTiming: Int)

class LibraryRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
) {
    /** Keeps read access to the picked folder across reboots, then scans it. */
    suspend fun importTree(treeUri: Uri): ScanSummary {
        context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val summary = scan(treeUri)
        settings.update { it.copy(contentRootUri = treeUri.toString(), contentSource = ContentSource.FOLDER) }
        return summary
    }

    suspend fun rescan(): ScanSummary? {
        val root = settings.current().contentRootUri ?: return null
        return scan(Uri.parse(root))
    }

    private suspend fun scan(treeUri: Uri): ScanSummary = withContext(Dispatchers.IO) {
        val rootName = DocumentsContract.getTreeDocumentId(treeUri).substringAfterLast(':').substringAfterLast('/')
            .ifEmpty { "Effortless English" }
        val root = TreeNode.root(context.contentResolver, treeUri, rootName)
        val library = LibraryScanner.scan(root, rootName) { node -> readText(node.uri) }
        db.library().replaceLibrary(library.courses, library.lessons, library.tracks)
        ScanSummary(
            courses = library.courses.size,
            lessons = library.lessons.size,
            tracks = library.tracks.size,
            withTranscript = library.tracks.count { it.transcriptUri != null },
            withTiming = library.tracks.count { it.timingUri != null },
        )
    }

    fun readText(uri: String): String? = runCatching {
        context.contentResolver.openInputStream(Uri.parse(uri))?.use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()

    suspend fun loadTranscript(track: TrackEntity): Transcript? = withContext(Dispatchers.IO) {
        track.timingUri?.let { uri -> readText(uri)?.let { runCatching { Transcript.fromTiming(it) }.getOrNull() } }
            ?: track.transcriptUri?.let { uri -> readText(uri)?.let { Transcript.fromPlainText(it) } }
    }
}

/**
 * One step of today's plan: read the transcript (bit 0) or listen to a track (bit position + 1),
 * [target] times; an [optional] step is not needed for the day to count.
 */
data class StudyStep(val mask: Long, val track: TrackEntity?, val target: Int = 1, val optional: Boolean = false)

class StudyRepository(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
) {
    private val dao get() = db.study()

    /** Reading first, then the tracks in the order of [StudyPlan]. */
    fun steps(tracks: List<TrackEntity>): List<StudyStep> =
        listOf(StudyStep(READ_MASK, null)) + StudyPlan.order(tracks).map {
            StudyStep(trackMask(it), it, StudyPlan.target(it.kind), StudyPlan.optional(it.kind))
        }

    private fun requiredMask(tracks: List<TrackEntity>): Long =
        steps(tracks).filter { !it.optional }.fold(0L) { acc, step -> acc or step.mask }

    /** The pinned lesson while it is open, otherwise the first lesson not completed. */
    fun currentLesson(lessons: List<LessonEntity>, progress: List<LessonProgressEntity>, pinnedId: String?): LessonEntity? {
        val completed = progress.filter { it.completedDay != null }.map { it.lessonId }.toSet()
        lessons.firstOrNull { it.id == pinnedId && it.id !in completed }?.let { return it }
        return lessons.firstOrNull { it.id !in completed }
    }

    suspend fun currentLesson(): LessonEntity? =
        currentLesson(db.library().allLessonsOnce(), dao.allProgressOnce(), settings.current().pinnedLessonId)

    suspend fun addListened(lessonId: String, ms: Long) {
        val day = today()
        ensureStarted(lessonId)
        dao.ensureLog(day, lessonId)
        dao.addListened(day, lessonId, ms)
    }

    suspend fun setStepDone(lessonId: String, mask: Long, done: Boolean) {
        val day = today()
        ensureStarted(lessonId)
        dao.ensureLog(day, lessonId)
        if (done) dao.addSteps(day, lessonId, mask) else dao.removeSteps(day, lessonId, mask)
        countDayIfComplete(lessonId, day)
    }

    /**
     * Counts a listen to the end. With auto-tick on, reaching the step's target ticks it; the
     * player keeps repeating and the learner moves on when ready.
     */
    suspend fun addFullListen(track: TrackEntity) {
        val day = today()
        ensureStarted(track.lessonId)
        dao.ensureLog(day, track.lessonId)
        dao.ensureListen(day, track.id)
        dao.incrementListen(day, track.id)
        if (settings.current().autoTick && (dao.listenCount(day, track.id) ?: 0) >= StudyPlan.target(track.kind)) {
            setStepDone(track.lessonId, trackMask(track), true)
        }
    }

    fun listensToday(lessonId: String): Flow<Map<String, Int>> =
        dao.listens(today(), lessonId).map { rows -> rows.associate { it.trackId to it.count } }

    /** A day counts towards the lesson once every required step of it is done. */
    private suspend fun countDayIfComplete(lessonId: String, day: Long) {
        val all = requiredMask(db.library().tracksOnce(lessonId))
        val done = dao.logOnce(day, lessonId)?.steps ?: 0
        val progress = dao.progressOnce(lessonId) ?: return
        if (done and all == all && progress.lastCountedDay != day) {
            dao.upsertProgress(progress.copy(daysStudied = progress.daysStudied + 1, lastCountedDay = day))
        }
    }

    private suspend fun ensureStarted(lessonId: String) {
        if (dao.progressOnce(lessonId) == null) dao.upsertProgress(LessonProgressEntity(lessonId, startedDay = today()))
    }

    suspend fun complete(lessonId: String) {
        ensureStarted(lessonId)
        dao.upsertProgress(dao.progressOnce(lessonId)!!.copy(completedDay = today()))
        settings.update { if (it.pinnedLessonId == lessonId) it.copy(pinnedLessonId = null) else it }
    }

    suspend fun reopen(lessonId: String) {
        dao.progressOnce(lessonId)?.let { dao.upsertProgress(it.copy(completedDay = null)) }
    }

    suspend fun pin(lessonId: String) {
        reopen(lessonId)
        settings.update { it.copy(pinnedLessonId = lessonId) }
    }

    /** Whether today's plan for the current lesson is finished, for the reminders. */
    suspend fun todayDone(lessonId: String): Boolean {
        val all = requiredMask(db.library().tracksOnce(lessonId))
        val done = dao.logOnce(today(), lessonId)?.steps ?: 0
        return done and all == all
    }

    suspend fun remainingStepsToday(lessonId: String): Int {
        val done = dao.logOnce(today(), lessonId)?.steps ?: 0
        return steps(db.library().tracksOnce(lessonId)).count { !it.optional && it.mask and done == 0L }
    }

    suspend fun studiedToday(): Boolean = dao.activityCount(today()) > 0

    companion object {
        const val READ_MASK = 1L
        fun trackMask(track: TrackEntity): Long = 1L shl (track.position + 1).coerceAtMost(62)
    }
}

class CardRepository(private val db: AppDatabase) {
    private val dao get() = db.cards()

    suspend fun add(card: CardEntity) = dao.insert(card)

    suspend fun save(card: CardEntity) = dao.update(card)

    suspend fun delete(card: CardEntity) = dao.delete(card)

    suspend fun due(): List<CardEntity> = dao.due(today())

    /** Applies SM-2, schedules the card and logs the review. Returns the updated card. */
    suspend fun grade(card: CardEntity, grade: Grade): CardEntity {
        val next = Sm2.review(Sm2State(card.ease, card.intervalDays, card.repetitions, card.lapses), grade)
        val updated = card.copy(
            ease = next.ease,
            intervalDays = next.intervalDays,
            repetitions = next.repetitions,
            lapses = next.lapses,
            dueDay = today() + next.intervalDays,
        )
        dao.update(updated)
        dao.insertReview(ReviewLogEntity(cardId = card.id, day = today(), grade = grade.quality))
        return updated
    }

    companion object {
        fun preview(card: CardEntity, grade: Grade): Int =
            Sm2.review(Sm2State(card.ease, card.intervalDays, card.repetitions, card.lapses), grade).intervalDays
    }
}
