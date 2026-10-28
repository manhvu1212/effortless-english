package io.github.manhvu1212.effortless.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.manhvu1212.effortless.AppContainer
import io.github.manhvu1212.effortless.R
import io.github.manhvu1212.effortless.domain.currentStreak
import io.github.manhvu1212.effortless.domain.longestStreak
import io.github.manhvu1212.effortless.domain.today
import io.github.manhvu1212.effortless.ui.appViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private const val CHART_DAYS = 14

data class StatsUi(
    val streak: Int = 0,
    val longest: Int = 0,
    val totalMinutes: Long = 0,
    /** Minutes per day, oldest first, [CHART_DAYS] entries ending today. */
    val minutesPerDay: List<Int> = List(CHART_DAYS) { 0 },
    val lessonsCompleted: Int = 0,
    val lessonsTotal: Int = 0,
    val cards: Int = 0,
    val reviews14: Int = 0,
)

class StatsViewModel(c: AppContainer) : ViewModel() {
    private val day = today()
    private val from = day - CHART_DAYS + 1

    private val listening = combine(
        c.db.study().activeDays(),
        c.db.study().totalListened(),
        c.db.study().listenedPerDay(from),
    ) { active, total, perDay ->
        val byDay = perDay.associate { it.day to (it.listenedMs / 60_000).toInt() }
        StatsUi(
            streak = currentStreak(active, day),
            longest = longestStreak(active),
            totalMinutes = total / 60_000,
            minutesPerDay = (from..day).map { byDay[it] ?: 0 },
        )
    }

    val ui: StateFlow<StatsUi> = combine(
        listening,
        c.db.library().allLessons(),
        c.db.study().allProgress(),
        c.db.cards().all(),
        c.db.cards().reviewsPerDay(from),
    ) { ui, lessons, progress, cards, reviews ->
        val ids = lessons.map { it.id }.toSet()
        ui.copy(
            lessonsCompleted = progress.count { it.completedDay != null && it.lessonId in ids },
            lessonsTotal = lessons.size,
            cards = cards.size,
            reviews14 = reviews.sumOf { it.count },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUi())
}

@Composable
fun StatsScreen(contentPadding: PaddingValues) {
    val vm = appViewModel { StatsViewModel(it) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(contentPadding),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(stringResource(R.string.stat_streak), ui.streak.toString(), stringResource(R.string.stat_longest, ui.longest), Modifier.weight(1f))
                StatTile(
                    stringResource(R.string.stat_total),
                    "%d:%02d".format(ui.totalMinutes / 60, ui.totalMinutes % 60),
                    stringResource(R.string.stat_hours_minutes),
                    Modifier.weight(1f),
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(
                    stringResource(R.string.stat_lessons),
                    "${ui.lessonsCompleted}/${ui.lessonsTotal}",
                    stringResource(R.string.stat_lessons_sub),
                    Modifier.weight(1f),
                )
                StatTile(
                    stringResource(R.string.stat_cards),
                    ui.cards.toString(),
                    stringResource(R.string.stat_reviews, ui.reviews14),
                    Modifier.weight(1f),
                )
            }
        }
        item { MinutesChart(ui.minutesPerDay) }
    }
}

@Composable
private fun StatTile(label: String, value: String, caption: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineMedium)
            Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** One series, so no legend: the title names it. Tap a bar for its value. */
@Composable
private fun MinutesChart(minutes: List<Int>) {
    var selected by remember { mutableStateOf<Int?>(null) }
    val days = remember(minutes.size) {
        val end = LocalDate.now()
        List(minutes.size) { end.minusDays((minutes.size - 1 - it).toLong()) }
    }
    val dayFormat = remember { DateTimeFormatter.ofPattern("dd/MM") }
    val barColor = MaterialTheme.colorScheme.primary
    val axisColor = MaterialTheme.colorScheme.outlineVariant
    val max = (minutes.maxOrNull() ?: 0).coerceAtLeast(10)
    val summary = stringResource(R.string.chart_summary, minutes.sum(), minutes.size)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.chart_title, minutes.size), style = MaterialTheme.typography.titleSmall)
            val pick = selected
            Text(
                if (pick != null) stringResource(R.string.chart_value, days[pick].format(dayFormat), minutes[pick])
                else stringResource(R.string.chart_max, max),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .semantics { contentDescription = summary }
                    .pointerInput(minutes.size) {
                        detectTapGestures { offset ->
                            val index = (offset.x / (size.width / minutes.size)).toInt().coerceIn(0, minutes.lastIndex)
                            selected = if (selected == index) null else index
                        }
                    },
            ) {
                drawBars(minutes, max, selected, barColor, axisColor)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(days.first().format(dayFormat), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.today), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun DrawScope.drawBars(
    minutes: List<Int>,
    max: Int,
    selected: Int?,
    color: androidx.compose.ui.graphics.Color,
    axisColor: androidx.compose.ui.graphics.Color,
) {
    val gap = 2.dp.toPx()
    val radius = 4.dp.toPx()
    val slot = size.width / minutes.size
    val barWidth = minOf(slot - gap, 24.dp.toPx())
    minutes.forEachIndexed { i, value ->
        if (value <= 0) return@forEachIndexed
        val height = size.height * value / max
        val left = i * slot + (slot - barWidth) / 2
        val top = size.height - height
        val alpha = if (selected == null || selected == i) 1f else 0.4f
        // Rounded top only: the bar stays anchored flat on the baseline.
        val r = minOf(radius, height, barWidth / 2)
        val path = Path().apply {
            moveTo(left, size.height)
            lineTo(left, top + r)
            quadraticTo(left, top, left + r, top)
            lineTo(left + barWidth - r, top)
            quadraticTo(left + barWidth, top, left + barWidth, top + r)
            lineTo(left + barWidth, size.height)
            close()
        }
        drawPath(path, color.copy(alpha = alpha))
    }
    drawRect(axisColor, topLeft = Offset(0f, size.height - 1.dp.toPx()), size = Size(size.width, 1.dp.toPx()))
}
