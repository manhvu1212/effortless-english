package io.github.manhvu1212.effortless.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.manhvu1212.effortless.R
import io.github.manhvu1212.effortless.container
import io.github.manhvu1212.effortless.data.SyncState
import io.github.manhvu1212.effortless.ui.screens.CardsScreen
import io.github.manhvu1212.effortless.ui.screens.CourseScreen
import io.github.manhvu1212.effortless.ui.screens.LessonScreen
import io.github.manhvu1212.effortless.ui.screens.LibraryScreen
import io.github.manhvu1212.effortless.ui.screens.PlayerScreen
import io.github.manhvu1212.effortless.ui.screens.ReviewScreen
import io.github.manhvu1212.effortless.ui.screens.SettingsScreen
import io.github.manhvu1212.effortless.ui.screens.StatsScreen
import io.github.manhvu1212.effortless.ui.screens.TodayScreen

private enum class Tab(val route: String, @param:StringRes val label: Int, val icon: ImageVector) {
    TODAY("today", R.string.tab_today, Icons.Default.Today),
    LIBRARY("library", R.string.tab_library, Icons.AutoMirrored.Filled.LibraryBooks),
    CARDS("cards", R.string.tab_cards, Icons.Default.Style),
    STATS("stats", R.string.tab_stats, Icons.Default.BarChart),
}

private const val PLAYER = "player"
private const val REVIEW = "review"
private const val SETTINGS = "settings"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNav(openPlayer: Boolean, onPlayerOpened: () -> Unit) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val tab = Tab.entries.firstOrNull { it.route == route }

    LaunchedEffect(openPlayer) {
        if (openPlayer) {
            nav.navigate(PLAYER) { launchSingleTop = true }
            onPlayerOpened()
        }
    }

    val sync by LocalContext.current.container.sync.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            stringResource(
                                when {
                                    tab != null -> tab.label
                                    route == PLAYER -> R.string.title_player
                                    route == REVIEW -> R.string.title_review
                                    route == SETTINGS -> R.string.title_settings
                                    route?.startsWith("course") == true -> R.string.tab_library
                                    else -> R.string.title_lesson
                                }
                            )
                        )
                    },
                    navigationIcon = {
                        if (tab == null) {
                            IconButton(onClick = { nav.popBackStack() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                            }
                        }
                    },
                    actions = {
                        if (tab != null) {
                            IconButton(onClick = { nav.navigate(SETTINGS) }) {
                                Icon(Icons.Default.Settings, stringResource(R.string.title_settings))
                            }
                        }
                    },
                )
                // A library sync runs app-wide; show it on every screen.
                (sync as? SyncState.Running)?.let { running ->
                    if (running.total > 0) {
                        LinearProgressIndicator(progress = { running.done.toFloat() / running.total }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Text(
                        syncLabel(running),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
        },
        bottomBar = {
            // NavigationBar pads itself for the gesture bar; without it the column has to.
            Column(if (tab == null) Modifier.navigationBarsPadding() else Modifier) {
                if (route != PLAYER) MiniPlayer(onClick = { nav.navigate(PLAYER) { launchSingleTop = true } })
                if (tab != null) {
                    NavigationBar {
                        Tab.entries.forEach { t ->
                            NavigationBarItem(
                                selected = t == tab,
                                onClick = { nav.switchTab(t.route) },
                                icon = { Icon(t.icon, null) },
                                label = { Text(stringResource(t.label)) },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Tab.TODAY.route) {
            composable(Tab.TODAY.route) {
                TodayScreen(
                    contentPadding = padding,
                    onOpenPlayer = { nav.navigate(PLAYER) { launchSingleTop = true } },
                    onOpenSettings = { nav.navigate(SETTINGS) },
                    onOpenReview = { nav.navigate(REVIEW) },
                    onOpenLibrary = { nav.switchTab(Tab.LIBRARY.route) },
                )
            }
            composable(Tab.LIBRARY.route) {
                LibraryScreen(padding, onOpenCourse = { nav.navigate("course/${it.encode()}") }, onOpenSettings = { nav.navigate(SETTINGS) })
            }
            composable("course/{id}") { e ->
                CourseScreen(padding, e.arguments?.getString("id").orEmpty().decode()) { nav.navigate("lesson/${it.encode()}") }
            }
            composable("lesson/{id}") { e ->
                LessonScreen(padding, e.arguments?.getString("id").orEmpty().decode()) { nav.navigate(PLAYER) { launchSingleTop = true } }
            }
            composable(Tab.CARDS.route) { CardsScreen(padding, onStartReview = { nav.navigate(REVIEW) }) }
            composable(Tab.STATS.route) { StatsScreen(padding) }
            composable(PLAYER) { PlayerScreen(padding) }
            composable(REVIEW) { ReviewScreen(padding, onDone = { nav.popBackStack() }) }
            composable(SETTINGS) { SettingsScreen(padding) }
        }
    }
}

private fun NavHostController.switchTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

/** Ids contain '/', which a route argument cannot hold as-is. */
private fun String.encode(): String = android.net.Uri.encode(this)

private fun String.decode(): String = android.net.Uri.decode(this)

@Composable
private fun MiniPlayer(onClick: () -> Unit) {
    val player = LocalContext.current.container.player
    val state by player.state.collectAsStateWithLifecycle()
    val track = state.track ?: return
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column {
            LinearProgressIndicator(
                progress = { if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(track.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(state.lesson?.title.orEmpty(), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val playing = state.isPlaying || state.shadowPause
                IconButton(onClick = player::togglePlay) {
                    Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, stringResource(if (playing) R.string.pause else R.string.play))
                }
            }
        }
    }
}
