package io.github.manhvu1212.effortless.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.manhvu1212.effortless.AppContainer
import io.github.manhvu1212.effortless.R
import io.github.manhvu1212.effortless.container
import io.github.manhvu1212.effortless.data.SyncStage
import io.github.manhvu1212.effortless.data.SyncState
import io.github.manhvu1212.effortless.data.db.TrackKind

@Composable
inline fun <reified VM : ViewModel> appViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val container = LocalContext.current.container
    return viewModel(key = key) { create(container) }
}

@StringRes
fun TrackKind.label(): Int = when (this) {
    TrackKind.MAIN -> R.string.kind_main
    TrackKind.VOCABULARY -> R.string.kind_vocabulary
    TrackKind.MINI_STORY -> R.string.kind_mini_story
    TrackKind.POINT_OF_VIEW -> R.string.kind_pov
    TrackKind.COMMENTARY -> R.string.kind_commentary
    TrackKind.OTHER -> R.string.kind_other
}

@Composable
fun syncLabel(state: SyncState.Running): String = when (state.stage) {
    SyncStage.LISTING -> stringResource(R.string.sync_listing)
    SyncStage.FILES -> stringResource(R.string.sync_files, state.done, state.total)
    SyncStage.BUILDING -> stringResource(R.string.sync_building)
    SyncStage.SCANNING -> stringResource(R.string.sync_scanning)
}

fun formatClock(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = totalSeconds / 60 % 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

fun formatMinuteOfDay(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

fun minutesOf(ms: Long): Int = (ms / 60_000).toInt()
