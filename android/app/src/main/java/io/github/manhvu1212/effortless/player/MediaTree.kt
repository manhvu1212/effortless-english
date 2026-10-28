package io.github.manhvu1212.effortless.player

import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaConstants
import io.github.manhvu1212.effortless.AppContainer
import io.github.manhvu1212.effortless.R
import io.github.manhvu1212.effortless.data.StudyRepository
import io.github.manhvu1212.effortless.data.db.LessonEntity
import io.github.manhvu1212.effortless.data.db.TrackEntity
import io.github.manhvu1212.effortless.domain.today
import io.github.manhvu1212.effortless.ui.label

/**
 * The browse tree Android Auto shows. The root's children become the car's tabs:
 *
 *     @root ── @today ── tracks of the current lesson
 *           └─ @courses ── @course:<id> ── @lesson:<id> ── tracks
 *
 * Folder ids start with '@'; playable items carry the plain track id, the same id the player uses.
 */
class MediaTree(private val context: Context, private val c: AppContainer) {
    fun root(): MediaItem = folder(ROOT, context.getString(R.string.app_name))

    /** For a controller asking only for something to resume, such as the car's "recent" row. */
    fun recentRoot(): MediaItem = folder(RECENT, context.getString(R.string.app_name))

    /** Lists, not grids: the titles are long and there is one artwork for everything. */
    fun rootExtras(): Bundle = Bundle().apply {
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
    }

    suspend fun children(parentId: String): List<MediaItem>? = when {
        parentId == ROOT -> listOf(
            folder(TODAY, context.getString(R.string.tab_today), c.study.currentLesson()?.title),
            folder(COURSES, context.getString(R.string.car_courses)),
        )
        parentId == TODAY -> c.study.currentLesson()?.let { tracks(it) }.orEmpty()
        parentId == COURSES -> courses()
        parentId == RECENT -> c.player.resumption()?.let { listOfNotNull(item(it.items[it.index].mediaId)) }.orEmpty()
        parentId.startsWith(COURSE) -> lessons(parentId.removePrefix(COURSE))
        parentId.startsWith(LESSON) -> c.db.library().lessonOnce(parentId.removePrefix(LESSON))?.let { tracks(it) }
        else -> null
    }

    suspend fun item(id: String): MediaItem? = when {
        id == ROOT -> root()
        id == RECENT -> recentRoot()
        id == TODAY || id == COURSES -> children(ROOT)?.firstOrNull { it.mediaId == id }
        id.startsWith(LESSON) -> c.db.library().lessonOnce(id.removePrefix(LESSON))?.let { lessonFolder(it) }
        id.startsWith(COURSE) -> courses().firstOrNull { it.mediaId == id }
        else -> c.db.library().track(id)?.let { track ->
            c.db.library().lessonOnce(track.lessonId)?.let { lesson -> tracks(lesson).firstOrNull { it.mediaId == id } }
        }
    }

    /** Tracks whose own title or lesson title contains [query]. */
    suspend fun search(query: String): List<MediaItem> {
        if (query.isBlank()) return emptyList()
        return c.db.library().allLessonsOnce().flatMap { lesson ->
            val lessonMatches = lesson.title.contains(query, ignoreCase = true)
            tracks(lesson).filter { lessonMatches || it.mediaMetadata.subtitle?.contains(query, ignoreCase = true) == true }
        }
    }

    private suspend fun courses(): List<MediaItem> {
        val lessons = c.db.library().allLessonsOnce()
        val completed = c.db.study().allProgressOnce().filter { it.completedDay != null }.map { it.lessonId }.toSet()
        return c.db.library().coursesOnce().map { course ->
            val ofCourse = lessons.filter { it.courseId == course.id }
            folder(
                COURSE + course.id,
                course.title,
                context.getString(R.string.lessons_completed, ofCourse.count { it.id in completed }, ofCourse.size),
            )
        }
    }

    private suspend fun lessons(courseId: String): List<MediaItem> {
        val progress = c.db.study().allProgressOnce().associateBy { it.lessonId }
        return c.db.library().lessonsOnce(courseId).map { lesson ->
            val p = progress[lesson.id]
            val subtitle = when {
                p?.completedDay != null -> context.getString(R.string.lesson_completed)
                p != null && p.daysStudied > 0 -> context.getString(R.string.days_studied, p.daysStudied)
                else -> null
            }
            lessonFolder(lesson, subtitle)
        }
    }

    private fun lessonFolder(lesson: LessonEntity, subtitle: String? = null) = folder(LESSON + lesson.id, lesson.title, subtitle)

    /** Titled by their role in the lesson; done today shows as fully played, a started track as partly played. */
    private suspend fun tracks(lesson: LessonEntity): List<MediaItem> {
        val tracks = c.db.library().tracksOnce(lesson.id)
        val states = tracks.associate { it.id to c.db.library().trackState(it.id) }
        val doneToday = c.db.study().logOnce(today(), lesson.id)?.steps ?: 0
        return tracks.map { track ->
            val state = states[track.id]
            val extras = Bundle().apply {
                when {
                    doneToday and StudyRepository.trackMask(track) != 0L ->
                        putInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS, MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED)
                    state != null && state.resumeMs > 0 && state.durationMs > 0 -> {
                        putInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS, MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED)
                        putDouble(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE, state.resumeMs.toDouble() / state.durationMs)
                    }
                    else ->
                        putInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS, MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED)
                }
            }
            playable(track, lesson, extras)
        }
    }

    private fun playable(track: TrackEntity, lesson: LessonEntity, extras: Bundle): MediaItem =
        MediaItem.Builder()
            .setMediaId(track.id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(context.getString(track.kind.label()))
                    .setSubtitle(track.title)
                    .setArtist(lesson.title)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
                    .setArtworkData(Artwork.png(context), MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                    .setExtras(extras)
                    .build()
            )
            .build()

    private fun folder(id: String, title: String, subtitle: String? = null): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build()
            )
            .build()

    companion object {
        const val ROOT = "@root"
        const val RECENT = "@recent"
        const val TODAY = "@today"
        const val COURSES = "@courses"
        const val COURSE = "@course:"
        const val LESSON = "@lesson:"
    }
}
