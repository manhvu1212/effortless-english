package io.github.manhvu1212.effortless.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.manhvu1212.effortless.AppContainer
import io.github.manhvu1212.effortless.R
import io.github.manhvu1212.effortless.container
import io.github.manhvu1212.effortless.data.SyncState
import io.github.manhvu1212.effortless.data.db.CourseEntity
import io.github.manhvu1212.effortless.data.db.LessonEntity
import io.github.manhvu1212.effortless.data.db.LessonProgressEntity
import io.github.manhvu1212.effortless.data.db.TrackEntity
import io.github.manhvu1212.effortless.data.db.TrackStateEntity
import io.github.manhvu1212.effortless.data.settings.ContentSource
import io.github.manhvu1212.effortless.ui.appViewModel
import io.github.manhvu1212.effortless.ui.formatClock
import io.github.manhvu1212.effortless.ui.label
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CourseRow(val course: CourseEntity, val lessons: Int, val completed: Int)

class LibraryViewModel(c: AppContainer) : ViewModel() {
    val courses: StateFlow<List<CourseRow>?> = combine(
        c.db.library().courses(),
        c.db.library().allLessons(),
        c.db.study().allProgress(),
    ) { courses, lessons, progress ->
        val completed = progress.filter { it.completedDay != null }.map { it.lessonId }.toSet()
        courses.map { course ->
            val ofCourse = lessons.filter { it.courseId == course.id }
            CourseRow(course, ofCourse.size, ofCourse.count { it.id in completed })
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun LibraryScreen(contentPadding: PaddingValues, onOpenCourse: (String) -> Unit, onOpenSettings: () -> Unit) {
    val vm = appViewModel { LibraryViewModel(it) }
    val courses by vm.courses.collectAsStateWithLifecycle()
    val syncing = LocalContext.current.container.sync.state.collectAsStateWithLifecycle().value is SyncState.Running
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(contentPadding),
    ) {
        val list = courses ?: return@LazyColumn
        if (list.isEmpty()) {
            item {
                if (syncing) MessageCard(stringResource(R.string.library_syncing), null, {})
                else MessageCard(stringResource(R.string.no_library), stringResource(R.string.choose_folder), onOpenSettings)
            }
        }
        items(list, key = { it.course.id }) { row ->
            Card(Modifier.fillMaxWidth().clickable { onOpenCourse(row.course.id) }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(row.course.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.lessons_completed, row.completed, row.lessons),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    LinearProgressIndicator(
                        progress = { if (row.lessons == 0) 0f else row.completed.toFloat() / row.lessons },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

data class CourseUi(
    val drive: Boolean = false,
    val course: CourseEntity? = null,
    val lessons: List<LessonEntity> = emptyList(),
    val progress: Map<String, LessonProgressEntity> = emptyMap(),
    val currentId: String? = null,
)

class CourseViewModel(private val c: AppContainer, courseId: String) : ViewModel() {
    val ui: StateFlow<CourseUi> = combine(
        c.db.library().course(courseId),
        c.db.library().lessons(courseId),
        c.db.library().allLessons(),
        c.db.study().allProgress(),
        c.settings.settings,
    ) { course, lessons, all, progress, settings ->
        CourseUi(
            drive = settings.contentSource == ContentSource.DRIVE,
            course = course,
            lessons = lessons,
            progress = progress.associateBy { it.lessonId },
            currentId = c.study.currentLesson(all, progress, settings.pinnedLessonId)?.id,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CourseUi())

    fun downloadAll() = viewModelScope.launch { c.drive.enqueue(ui.value.lessons.map { it.id }) }
}

@Composable
fun CourseScreen(contentPadding: PaddingValues, courseId: String, onOpenLesson: (String) -> Unit) {
    val vm = appViewModel(key = courseId) { CourseViewModel(it, courseId) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(contentPadding),
    ) {
        if (ui.drive && ui.lessons.isNotEmpty()) {
            item {
                OutlinedButton(onClick = { vm.downloadAll() }) { Text(stringResource(R.string.download_course)) }
            }
        }
        items(ui.lessons, key = { it.id }) { lesson ->
            val progress = ui.progress[lesson.id]
            Card(Modifier.fillMaxWidth().clickable { onOpenLesson(lesson.id) }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    val (icon, tint) = when {
                        progress?.completedDay != null -> Icons.Default.CheckCircle to MaterialTheme.colorScheme.primary
                        lesson.id == ui.currentId -> Icons.Default.Schedule to MaterialTheme.colorScheme.tertiary
                        else -> Icons.Default.RadioButtonUnchecked to MaterialTheme.colorScheme.outline
                    }
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.padding(end = 12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(lesson.title, style = MaterialTheme.typography.titleSmall)
                        if (progress != null && progress.daysStudied > 0) {
                            Text(
                                stringResource(R.string.days_studied, progress.daysStudied),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

data class LessonUi(
    val drive: Boolean = false,
    val downloaded: Set<String> = emptySet(),
    val lesson: LessonEntity? = null,
    val tracks: List<TrackEntity> = emptyList(),
    val states: Map<String, TrackStateEntity> = emptyMap(),
    val progress: LessonProgressEntity? = null,
    val isCurrent: Boolean = false,
)

class LessonViewModel(private val c: AppContainer, private val lessonId: String) : ViewModel() {
    private val currentId = combine(
        c.db.library().allLessons(),
        c.db.study().allProgress(),
        c.settings.settings,
    ) { lessons, progress, settings -> c.study.currentLesson(lessons, progress, settings.pinnedLessonId)?.id }

    private val base = combine(
        c.db.library().lesson(lessonId),
        c.db.library().tracks(lessonId),
        c.db.library().trackStates(lessonId),
        c.db.study().progress(lessonId),
        currentId,
    ) { lesson, tracks, states, progress, current ->
        LessonUi(lesson = lesson, tracks = tracks, states = states.associateBy { it.trackId }, progress = progress, isCurrent = current == lessonId)
    }

    private val refresh = MutableStateFlow(0)

    /** Re-checked whenever the download queue changes or a download is deleted. */
    val ui: StateFlow<LessonUi> = combine(base, c.settings.settings, c.drive.downloading(), refresh) { ui, settings, _, _ ->
        ui.copy(
            drive = settings.contentSource == ContentSource.DRIVE,
            downloaded = ui.tracks.filter { c.drive.isDownloaded(it) }.map { it.id }.toSet(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LessonUi())

    fun download() = viewModelScope.launch { c.drive.enqueue(listOf(lessonId)) }

    fun deleteDownload() = viewModelScope.launch {
        c.drive.deleteLesson(lessonId)
        refresh.value++
    }

    fun play(index: Int) {
        val s = ui.value
        c.player.open(s.lesson ?: return, s.tracks, index)
    }

    fun pin() = viewModelScope.launch { c.study.pin(lessonId) }

    fun complete() = viewModelScope.launch { c.study.complete(lessonId) }

    fun reopen() = viewModelScope.launch { c.study.reopen(lessonId) }
}

@Composable
fun LessonScreen(contentPadding: PaddingValues, lessonId: String, onOpenPlayer: () -> Unit) {
    val vm = appViewModel(key = lessonId) { LessonViewModel(it, lessonId) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(contentPadding),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(ui.lesson?.title.orEmpty(), style = MaterialTheme.typography.headlineSmall)
                val progress = ui.progress
                Text(
                    when {
                        progress?.completedDay != null -> stringResource(R.string.lesson_completed)
                        progress != null -> stringResource(R.string.days_studied, progress.daysStudied)
                        else -> stringResource(R.string.lesson_not_started)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (ui.drive && ui.tracks.isNotEmpty()) {
                    val all = ui.tracks.all { it.id in ui.downloaded }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (all) {
                            OutlinedButton(onClick = { vm.deleteDownload() }) { Text(stringResource(R.string.delete_download)) }
                        } else {
                            OutlinedButton(onClick = { vm.download() }) { Text(stringResource(R.string.download_lesson)) }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (progress?.completedDay != null) {
                        OutlinedButton(onClick = { vm.reopen() }) { Text(stringResource(R.string.reopen_lesson)) }
                    } else {
                        if (!ui.isCurrent) Button(onClick = { vm.pin() }) { Text(stringResource(R.string.study_this)) }
                        OutlinedButton(onClick = { vm.complete() }) { Text(stringResource(R.string.mark_completed)) }
                    }
                }
            }
        }
        itemsIndexed(ui.tracks, key = { _, track -> track.id }) { index, track ->
            val state = ui.states[track.id]
            Card(Modifier.fillMaxWidth().clickable { vm.play(index); onOpenPlayer() }) {
                Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(track.kind.label()), style = MaterialTheme.typography.titleSmall)
                        Text(track.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val details = buildList {
                            if (ui.drive) add(stringResource(if (track.id in ui.downloaded) R.string.downloaded else R.string.not_downloaded))
                            if (state != null && state.durationMs > 0) add(formatClock(state.durationMs))
                            if (track.timingUri != null) add(stringResource(R.string.has_timing))
                            else if (track.transcriptUri != null) add(stringResource(R.string.has_transcript))
                        }
                        if (details.isNotEmpty()) {
                            Text(details.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                    IconButton(onClick = { vm.play(index); onOpenPlayer() }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.play))
                    }
                }
            }
        }
    }
}
