package io.github.manhvu1212.effortless.ui.screens

import android.content.ClipData
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.manhvu1212.effortless.AppContainer
import io.github.manhvu1212.effortless.R
import io.github.manhvu1212.effortless.data.content.Sentence
import io.github.manhvu1212.effortless.data.db.CardEntity
import io.github.manhvu1212.effortless.data.db.TrackKind
import io.github.manhvu1212.effortless.domain.today
import io.github.manhvu1212.effortless.player.PlayerState
import io.github.manhvu1212.effortless.ui.appViewModel
import io.github.manhvu1212.effortless.ui.formatClock
import io.github.manhvu1212.effortless.ui.label
import kotlinx.coroutines.launch

class PlayerViewModel(private val c: AppContainer) : ViewModel() {
    val player = c.player
    val state = player.state
    val transcript = player.transcript

    fun addCard(front: String, back: String, sentence: Sentence) {
        val track = state.value.track ?: return
        viewModelScope.launch {
            c.cards.add(
                CardEntity(
                    front = front.trim(),
                    back = back.trim(),
                    example = sentence.text,
                    lessonId = track.lessonId,
                    trackId = track.id,
                    clipStartMs = sentence.startMs,
                    clipEndMs = sentence.endMs,
                    dueDay = today(),
                )
            )
        }
    }
}

private val SPEEDS = listOf(0.5f, 0.6f, 0.75f, 0.85f, 1f, 1.1f, 1.25f, 1.5f)

private fun speedLabel(speed: Float): String = "${if (speed % 1f == 0f) speed.toInt() else speed}x"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(contentPadding: PaddingValues) {
    val vm = appViewModel { PlayerViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val transcript by vm.transcript.collectAsStateWithLifecycle()
    var menuSentence by remember { mutableStateOf<Sentence?>(null) }
    var cardSentence by remember { mutableStateOf<Sentence?>(null) }

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        if (state.lesson == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.nothing_playing))
            }
            return@Column
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(state.tracks, key = { _, t -> t.id }) { index, track ->
                // Several tracks of one kind (Mini-Story A and B) are numbered.
                val sameKind = state.tracks.filter { it.kind == track.kind }
                val label = stringResource(track.kind.label()) +
                    if (sameKind.size > 1) " ${sameKind.indexOf(track) + 1}" else ""
                FilterChip(
                    selected = index == state.index,
                    onClick = { vm.player.selectTrack(index) },
                    label = { Text(label) },
                )
            }
        }
        if (state.track?.kind == TrackKind.MINI_STORY) {
            Text(
                stringResource(R.string.step_mini_story_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            val t = transcript
            if (t == null || t.sentences.isEmpty()) {
                Text(
                    stringResource(if (state.track?.transcriptUri == null) R.string.no_transcript else R.string.loading),
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                TranscriptView(
                    sentences = t.sentences,
                    current = t.indexAt(state.positionMs),
                    timed = t.isTimed,
                    loop = state.loopA to state.loopB,
                    onTap = { s -> s.startMs?.let { vm.player.seekTo(it) } },
                    onLongPress = { menuSentence = it },
                )
            }
        }

        Controls(state, vm)
    }

    menuSentence?.let { sentence ->
        val clipboard = LocalClipboard.current
        val scope = rememberCoroutineScope()
        ModalBottomSheet(onDismissRequest = { menuSentence = null }) {
            Text(sentence.text, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyLarge)
            if (sentence.startMs != null && sentence.endMs != null) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.loop_sentence)) },
                    leadingContent = { Icon(Icons.Default.RepeatOne, null) },
                    modifier = Modifier.clickable(onClick = {
                        vm.player.loopRange(sentence.startMs, sentence.endMs)
                        menuSentence = null
                    }),
                )
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.add_card)) },
                leadingContent = { Icon(Icons.Default.Add, null) },
                modifier = Modifier.clickable(onClick = {
                    cardSentence = sentence
                    menuSentence = null
                }),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.copy)) },
                modifier = Modifier.clickable(onClick = {
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("sentence", sentence.text))) }
                    menuSentence = null
                }),
            )
            Box(Modifier.size(24.dp))
        }
    }

    cardSentence?.let { sentence ->
        AddCardDialog(
            sentence = sentence,
            onDismiss = { cardSentence = null },
            onSave = { front, back ->
                vm.addCard(front, back, sentence)
                cardSentence = null
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TranscriptView(
    sentences: List<Sentence>,
    current: Int,
    timed: Boolean,
    loop: Pair<Long?, Long?>,
    onTap: (Sentence) -> Unit,
    onLongPress: (Sentence) -> Unit,
) {
    val listState = rememberLazyListState()
    var lastUserScroll by remember { mutableLongStateOf(0L) }
    val userScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) lastUserScroll = System.currentTimeMillis()
                return Offset.Zero
            }
        }
    }
    // Follows the playing sentence unless the user scrolled in the last few seconds.
    LaunchedEffect(current) {
        if (timed && current >= 0 && System.currentTimeMillis() - lastUserScroll > 4_000) {
            listState.animateScrollToItem((current - 2).coerceAtLeast(0))
        }
    }
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        modifier = Modifier.fillMaxSize().nestedScroll(userScroll),
    ) {
        itemsIndexed(sentences, key = { _, s -> s.index }) { index, sentence ->
            val active = index == current
            val inLoop = loop.first != null && loop.second != null && sentence.startMs != null &&
                sentence.startMs >= loop.first!! && (sentence.endMs ?: 0) <= loop.second!! + 50
            Text(
                sentence.text,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    active -> MaterialTheme.colorScheme.onPrimaryContainer
                    timed && current >= 0 && index < current -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = if (sentence.paragraphStart && index > 0) 12.dp else 2.dp)
                    .background(
                        when {
                            active -> MaterialTheme.colorScheme.primaryContainer
                            inLoop -> MaterialTheme.colorScheme.tertiaryContainer
                            else -> MaterialTheme.colorScheme.surface
                        },
                        RoundedCornerShape(8.dp),
                    )
                    .combinedClickable(onClick = { onTap(sentence) }, onLongClick = { onLongPress(sentence) })
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun Controls(state: PlayerState, vm: PlayerViewModel) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    var speedMenu by remember { mutableStateOf(false) }
    val duration = state.durationMs.coerceAtLeast(1)

    Card(
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(state.track?.title.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Slider(
                value = if (dragging) dragValue else state.positionMs.toFloat() / duration,
                onValueChange = {
                    dragging = true
                    dragValue = it
                },
                onValueChangeFinished = {
                    vm.player.seekTo((dragValue * duration).toLong())
                    dragging = false
                },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatClock(state.positionMs), style = MaterialTheme.typography.labelSmall)
                Text(formatClock(state.durationMs), style = MaterialTheme.typography.labelSmall)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = vm.player::previous) { Icon(Icons.Default.SkipPrevious, stringResource(R.string.previous)) }
                IconButton(onClick = { vm.player.rewind() }) { Icon(Icons.Default.FastRewind, stringResource(R.string.rewind)) }
                FilledIconButton(onClick = vm.player::togglePlay, modifier = Modifier.size(64.dp)) {
                    val playing = state.isPlaying || state.shadowPause
                    Icon(
                        if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        stringResource(if (playing) R.string.pause else R.string.play),
                        modifier = Modifier.size(36.dp),
                    )
                }
                IconButton(onClick = { vm.player.forward() }) { Icon(Icons.Default.FastForward, stringResource(R.string.forward)) }
                IconButton(onClick = vm.player::next) { Icon(Icons.Default.SkipNext, stringResource(R.string.next)) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                Box {
                    TextButton(onClick = { speedMenu = true }) { Text(speedLabel(state.speed)) }
                    DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                        SPEEDS.forEach { speed ->
                            DropdownMenuItem(
                                text = { Text(speedLabel(speed)) },
                                onClick = {
                                    vm.player.setSpeed(speed)
                                    speedMenu = false
                                },
                            )
                        }
                    }
                }
                TextButton(onClick = vm.player::markLoop) {
                    Text(
                        when {
                            state.loopA == null -> "A–B"
                            state.loopB == null -> "A–?"
                            else -> "A–B ✕"
                        },
                        color = if (state.loopA != null) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                    )
                }
                IconToggleButton(checked = state.repeatTrack, onCheckedChange = { vm.player.toggleRepeatTrack() }) {
                    Icon(
                        if (state.repeatTrack) Icons.Default.RepeatOne else Icons.Default.Repeat,
                        stringResource(R.string.repeat_track),
                        tint = if (state.repeatTrack) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconToggleButton(checked = state.shadowing, onCheckedChange = { vm.player.toggleShadowing() }) {
                    Icon(
                        Icons.Default.RecordVoiceOver,
                        stringResource(R.string.shadowing),
                        tint = if (state.shadowing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.shadowPause) {
                Text(
                    stringResource(R.string.shadow_your_turn),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
    }
}

/** Tap words of the sentence to build the phrase, or type it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddCardDialog(sentence: Sentence, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    val words = remember(sentence) { sentence.text.split(Regex("""\s+""")).filter { it.isNotBlank() } }
    val picked = remember(sentence) { mutableStateListOf<Int>() }
    var front by remember { mutableStateOf("") }
    var back by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_card)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.pick_words), style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    words.forEachIndexed { i, word ->
                        FilterChip(
                            selected = i in picked,
                            onClick = {
                                if (i in picked) picked.remove(i) else picked.add(i)
                                front = picked.sorted().joinToString(" ") { words[it] }
                                    .trim { !it.isLetterOrDigit() && it != '\'' && it != '’' }
                            },
                            label = { Text(word) },
                        )
                    }
                }
                OutlinedTextField(front, { front = it }, label = { Text(stringResource(R.string.card_front)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(back, { back = it }, label = { Text(stringResource(R.string.card_back)) }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(front, back) }, enabled = front.isNotBlank()) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
