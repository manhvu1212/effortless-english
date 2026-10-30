package io.github.manhvu1212.effortless.ui.screens

import android.content.ClipData
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.manhvu1212.effortless.AppContainer
import io.github.manhvu1212.effortless.R
import io.github.manhvu1212.effortless.data.StudyRepository
import io.github.manhvu1212.effortless.data.content.Sentence
import io.github.manhvu1212.effortless.data.content.Transcript
import io.github.manhvu1212.effortless.data.db.CardEntity
import io.github.manhvu1212.effortless.data.db.LessonEntity
import io.github.manhvu1212.effortless.data.db.TrackEntity
import io.github.manhvu1212.effortless.domain.today
import io.github.manhvu1212.effortless.ui.appViewModel
import io.github.manhvu1212.effortless.ui.label
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReadUi(
    val lesson: LessonEntity? = null,
    /** Tracks that have a transcript to read. */
    val tracks: List<TrackEntity> = emptyList(),
    val selected: TrackEntity? = null,
    val transcript: Transcript? = null,
    val loading: Boolean = true,
)

class ReadViewModel(private val c: AppContainer, private val lessonId: String) : ViewModel() {
    private val _ui = MutableStateFlow(ReadUi())
    val ui: StateFlow<ReadUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            val lesson = c.db.library().lessonOnce(lessonId)
            val tracks = c.db.library().tracksOnce(lessonId).filter { it.transcriptUri != null || it.timingUri != null }
            _ui.value = ReadUi(lesson = lesson, tracks = tracks)
            tracks.firstOrNull()?.let { select(it) } ?: _ui.update { it.copy(loading = false) }
        }
    }

    fun select(track: TrackEntity) {
        _ui.update { it.copy(selected = track, transcript = null, loading = true) }
        viewModelScope.launch {
            val transcript = c.library.loadTranscript(track)
            if (_ui.value.selected?.id == track.id) _ui.update { it.copy(transcript = transcript, loading = false) }
        }
    }

    /** Ticks the reading step and opens the first listening step. */
    fun finish(onListen: () -> Unit) {
        viewModelScope.launch {
            c.study.setStepDone(lessonId, StudyRepository.READ_MASK, true)
            val lesson = _ui.value.lesson ?: return@launch
            val tracks = c.db.library().tracksOnce(lessonId)
            if (tracks.isNotEmpty()) c.player.open(lesson, tracks, 0, play = true)
            onListen()
        }
    }

    fun addCard(front: String, back: String, sentence: Sentence) {
        val track = _ui.value.selected ?: return
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

/** Reading only: the transcript as paragraphs, no player. Long-press a sentence to keep a word. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadScreen(contentPadding: PaddingValues, lessonId: String, onListen: () -> Unit) {
    val vm = appViewModel(key = lessonId) { ReadViewModel(it, lessonId) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    var menuSentence by remember { mutableStateOf<Sentence?>(null) }
    var cardSentence by remember { mutableStateOf<Sentence?>(null) }

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        if (ui.tracks.size > 1) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(ui.tracks, key = { it.id }) { track ->
                    FilterChip(
                        selected = track.id == ui.selected?.id,
                        onClick = { vm.select(track) },
                        label = { Text(stringResource(track.kind.label())) },
                    )
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val transcript = ui.transcript
            when {
                ui.loading -> Text(stringResource(R.string.loading), Modifier.align(Alignment.Center))
                transcript == null || transcript.sentences.isEmpty() ->
                    Text(stringResource(R.string.no_transcript), Modifier.align(Alignment.Center).padding(24.dp))
                else -> ReadingText(transcript, onLongPress = { menuSentence = it })
            }
        }
        Surface(tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.read_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { vm.finish(onListen) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.read_done)) }
            }
        }
    }

    menuSentence?.let { sentence ->
        val clipboard = LocalClipboard.current
        val scope = rememberCoroutineScope()
        ModalBottomSheet(onDismissRequest = { menuSentence = null }) {
            Text(sentence.text, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyLarge)
            ListItem(
                headlineContent = { Text(stringResource(R.string.add_card)) },
                leadingContent = { Icon(Icons.Default.Add, null) },
                modifier = Modifier.clickable {
                    cardSentence = sentence
                    menuSentence = null
                },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.copy)) },
                modifier = Modifier.clickable {
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("sentence", sentence.text))) }
                    menuSentence = null
                },
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

/** One Text per paragraph, so it reads like a page; a long-press finds the sentence under the finger. */
@Composable
private fun ReadingText(transcript: Transcript, onLongPress: (Sentence) -> Unit) {
    val paragraphs = remember(transcript) {
        val out = mutableListOf<MutableList<Sentence>>()
        transcript.sentences.forEach { if (it.paragraphStart || out.isEmpty()) out += mutableListOf(it) else out.last() += it }
        out
    }
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(paragraphs.size) { i ->
            val sentences = paragraphs[i]
            val ranges = remember(sentences) {
                var at = 0
                sentences.map { s -> (at until at + s.text.length).also { at += s.text.length + 1 } }
            }
            val text = remember(sentences) { buildAnnotatedString { append(sentences.joinToString(" ") { it.text }) } }
            var layout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 19.sp, lineHeight = 30.sp),
                onTextLayout = { layout = it },
                modifier = Modifier.pointerInput(sentences) {
                    detectTapGestures(onLongPress = { offset ->
                        val position = layout?.getOffsetForPosition(offset) ?: return@detectTapGestures
                        val index = ranges.indexOfFirst { position in it || position == it.last + 1 }
                        sentences.getOrNull(index)?.let(onLongPress)
                    })
                },
            )
        }
    }
}
