package io.github.manhvu1212.effortless.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.manhvu1212.effortless.AppContainer
import io.github.manhvu1212.effortless.R
import io.github.manhvu1212.effortless.container
import io.github.manhvu1212.effortless.data.SyncState
import io.github.manhvu1212.effortless.data.StudyStep
import io.github.manhvu1212.effortless.data.db.CourseEntity
import io.github.manhvu1212.effortless.data.db.LessonEntity
import io.github.manhvu1212.effortless.data.db.LessonProgressEntity
import io.github.manhvu1212.effortless.data.db.TrackEntity
import io.github.manhvu1212.effortless.data.db.TrackKind
import io.github.manhvu1212.effortless.domain.currentStreak
import io.github.manhvu1212.effortless.domain.today
import io.github.manhvu1212.effortless.ui.appViewModel
import io.github.manhvu1212.effortless.ui.label
import io.github.manhvu1212.effortless.ui.minutesOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TodayUi(
    val loaded: Boolean = false,
    val hasLibrary: Boolean = false,
    val lesson: LessonEntity? = null,
    val course: CourseEntity? = null,
    val tracks: List<TrackEntity> = emptyList(),
    val stepsDone: Long = 0,
    val listenedTodayMs: Long = 0,
    val progress: LessonProgressEntity? = null,
    val daysPerLesson: Int = 7,
    val streak: Int = 0,
    val dueCards: Int = 0,
)

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModel(private val c: AppContainer) : ViewModel() {
    private val day = today()

    private val lessonUi = combine(
        c.db.library().allLessons(),
        c.db.study().allProgress(),
        c.settings.settings,
    ) { lessons, progress, settings ->
        Triple(lessons.isNotEmpty(), c.study.currentLesson(lessons, progress, settings.pinnedLessonId), settings.daysPerLesson)
    }.flatMapLatest { (hasLibrary, lesson, daysPerLesson) ->
        if (lesson == null) {
            flowOf(TodayUi(loaded = true, hasLibrary = hasLibrary, daysPerLesson = daysPerLesson))
        } else {
            combine(
                c.db.library().tracks(lesson.id),
                c.db.study().log(day, lesson.id),
                c.db.study().progress(lesson.id),
                c.db.library().course(lesson.courseId),
            ) { tracks, log, progress, course ->
                TodayUi(
                    loaded = true,
                    hasLibrary = true,
                    lesson = lesson,
                    course = course,
                    tracks = tracks,
                    stepsDone = log?.steps ?: 0,
                    listenedTodayMs = log?.listenedMs ?: 0,
                    progress = progress,
                    daysPerLesson = daysPerLesson,
                )
            }
        }
    }

    val ui: StateFlow<TodayUi> = combine(
        lessonUi,
        c.db.study().activeDays(),
        c.db.cards().dueCount(day),
        c.db.study().listenedPerDay(day),
    ) { ui, activeDays, due, listenedToday ->
        ui.copy(
            streak = currentStreak(activeDays, day),
            dueCards = due,
            listenedTodayMs = listenedToday.sumOf { it.listenedMs },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUi())

    fun steps(tracks: List<TrackEntity>): List<StudyStep> = c.study.steps(tracks)

    fun setStep(step: StudyStep, done: Boolean) {
        val lesson = ui.value.lesson ?: return
        viewModelScope.launch { c.study.setStepDone(lesson.id, step.mask, done) }
    }

    fun complete() {
        val lesson = ui.value.lesson ?: return
        viewModelScope.launch { c.study.complete(lesson.id) }
    }

    /** Opens the player on [track], or on the first track with a transcript for the reading step. */
    fun open(track: TrackEntity?, play: Boolean) {
        val s = ui.value
        val lesson = s.lesson ?: return
        val index = if (track != null) s.tracks.indexOf(track)
        else s.tracks.indexOfFirst { it.transcriptUri != null || it.timingUri != null }.coerceAtLeast(0)
        c.player.open(lesson, s.tracks, index, play)
    }
}

@Composable
fun TodayScreen(
    contentPadding: PaddingValues,
    onOpenPlayer: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenReview: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    val vm = appViewModel { TodayViewModel(it) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val syncing = LocalContext.current.container.sync.state.collectAsStateWithLifecycle().value is SyncState.Running

    LazyColumn(
        contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 16.dp + contentPadding.calculateBottomPadding()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(top = contentPadding.calculateTopPadding()),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                StatChip(Icons.Default.LocalFireDepartment, pluralStringResource(R.plurals.streak_days, ui.streak, ui.streak), Modifier.weight(1f), stringResource(R.string.stat_streak))
                StatChip(Icons.Default.Timer, stringResource(R.string.minutes_today, minutesOf(ui.listenedTodayMs)), Modifier.weight(1f), stringResource(R.string.listened_today))
                StatChip(Icons.Default.Style, pluralStringResource(R.plurals.cards_due, ui.dueCards, ui.dueCards), Modifier.weight(1f), stringResource(R.string.cards_due_label), onClick = onOpenReview)
            }
        }
        if (!ui.loaded) return@LazyColumn
        val lesson = ui.lesson
        if (lesson == null && !ui.hasLibrary && syncing) {
            item { MessageCard(stringResource(R.string.library_syncing), null, {}) }
            return@LazyColumn
        }
        if (lesson == null) {
            item {
                MessageCard(
                    text = stringResource(if (ui.hasLibrary) R.string.all_lessons_done else R.string.no_library),
                    action = stringResource(if (ui.hasLibrary) R.string.open_library else R.string.choose_folder),
                    onAction = if (ui.hasLibrary) onOpenLibrary else onOpenSettings,
                )
            }
            return@LazyColumn
        }

        item { LessonHeader(ui) }
        val progress = ui.progress
        if (progress != null && progress.daysStudied >= ui.daysPerLesson) {
            item {
                MessageCard(
                    text = stringResource(R.string.ready_for_next, progress.daysStudied),
                    action = stringResource(R.string.next_lesson),
                    onAction = vm::complete,
                )
            }
        }
        item {
            Text(stringResource(R.string.today_steps), style = MaterialTheme.typography.titleMedium)
        }
        items(vm.steps(ui.tracks), key = { it.mask }) { step ->
            StepRow(
                step = step,
                done = ui.stepsDone and step.mask != 0L,
                onCheck = { vm.setStep(step, it) },
                onOpen = {
                    vm.open(step.track, play = step.track != null)
                    onOpenPlayer()
                },
            )
        }
    }
}

@Composable
private fun LessonHeader(ui: TodayUi) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ui.course?.let { Text(it.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
            Text(ui.lesson?.title.orEmpty(), style = MaterialTheme.typography.headlineSmall)
            val days = ui.progress?.daysStudied ?: 0
            Text(stringResource(R.string.days_progress, days, ui.daysPerLesson), style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(
                progress = { (days.toFloat() / ui.daysPerLesson).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
fun StatChip(icon: ImageVector, text: String, modifier: Modifier = Modifier, description: String? = null, onClick: (() -> Unit)? = null) {
    Card(
        modifier = modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = description, modifier = Modifier.padding(end = 6.dp))
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun MessageCard(text: String, action: String?, onAction: () -> Unit, secondary: String? = null, onSecondary: () -> Unit = {}) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (action != null) Button(onClick = onAction) { Text(action) }
                if (secondary != null) OutlinedButton(onClick = onSecondary) { Text(secondary) }
            }
        }
    }
}

@Composable
fun StepRow(
    step: StudyStep,
    done: Boolean,
    onCheck: (Boolean) -> Unit,
    onOpen: () -> Unit,
) {
    val track = step.track
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = done, onCheckedChange = onCheck)
            Column(Modifier.weight(1f)) {
                Text(
                    if (track == null) stringResource(R.string.step_read) else stringResource(track.kind.label()),
                    style = MaterialTheme.typography.titleSmall,
                )
                val hint = when {
                    track == null -> stringResource(R.string.step_read_hint)
                    track.kind == TrackKind.MINI_STORY -> stringResource(R.string.step_mini_story_hint)
                    else -> track.title
                }
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onOpen) {
                Icon(
                    if (track == null) Icons.AutoMirrored.Filled.MenuBook else Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.open),
                )
            }
        }
    }
}
