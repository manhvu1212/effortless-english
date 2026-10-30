package io.github.manhvu1212.effortless.ui.screens

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
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
import io.github.manhvu1212.effortless.data.content.Words
import io.github.manhvu1212.effortless.data.db.TrackKind
import io.github.manhvu1212.effortless.container
import io.github.manhvu1212.effortless.player.ClipPlayer
import io.github.manhvu1212.effortless.ui.translate
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

/** A sentence of a vocabulary or mini-story lesson that uses the looked-up word. */
data class Explanation(val track: TrackEntity, val text: String, val startMs: Long?, val endMs: Long?)

data class LookupUi(val word: String, val sentence: Sentence, val explanations: List<Explanation>? = null)

data class ReadUi(
    val lesson: LessonEntity? = null,
    /** Tracks that have a transcript to read. */
    val tracks: List<TrackEntity> = emptyList(),
    val selected: TrackEntity? = null,
    val transcript: Transcript? = null,
    /** Vietnamese, one entry per paragraph; null when this transcript has none. */
    val translation: List<String>? = null,
    val loading: Boolean = true,
)

class ReadViewModel(private val c: AppContainer, private val lessonId: String) : ViewModel() {
    private val _ui = MutableStateFlow(ReadUi())
    val ui: StateFlow<ReadUi> = _ui.asStateFlow()

    private val _lookup = MutableStateFlow<LookupUi?>(null)
    val lookup: StateFlow<LookupUi?> = _lookup.asStateFlow()

    private val transcripts = mutableMapOf<String, Transcript?>()

    init {
        viewModelScope.launch {
            val lesson = c.db.library().lessonOnce(lessonId)
            val tracks = c.db.library().tracksOnce(lessonId).filter { it.transcriptUri != null || it.timingUri != null }
            _ui.value = ReadUi(lesson = lesson, tracks = tracks)
            tracks.firstOrNull()?.let { select(it) } ?: _ui.update { it.copy(loading = false) }
        }
    }

    fun select(track: TrackEntity) {
        _ui.update { it.copy(selected = track, transcript = null, translation = null, loading = true) }
        viewModelScope.launch {
            val transcript = c.library.loadTranscript(track)
            val translation = transcript?.let { c.library.loadTranslation(track, it) }
            if (_ui.value.selected?.id == track.id) {
                _ui.update { it.copy(transcript = transcript, translation = translation, loading = false) }
            }
        }
    }

    /**
     * Where the teacher explains [word]: sentences of the vocabulary lesson first, then the
     * mini-story, each with the sentence after it for context.
     */
    fun lookUp(word: String, sentence: Sentence) {
        _lookup.value = LookupUi(word, sentence)
        viewModelScope.launch {
            val tracks = c.db.library().tracksOnce(lessonId)
            val sources = tracks.filter { it.kind == TrackKind.VOCABULARY } + tracks.filter { it.kind == TrackKind.MINI_STORY }
            val found = mutableListOf<Explanation>()
            for (track in sources) {
                val transcript = transcripts.getOrPut(track.id) { c.library.loadTranscript(track) } ?: continue
                val limit = if (track.kind == TrackKind.VOCABULARY) 3 else 2
                var shownUntil = -1
                var taken = 0
                for (i in Words.sentencesUsing(word, transcript.sentences)) {
                    // A hit inside the previous excerpt (it shows the next sentence too) adds nothing.
                    if (i <= shownUntil) continue
                    if (taken++ == limit) break
                    shownUntil = i + 1
                    val here = transcript.sentences[i]
                    val next = transcript.sentences.getOrNull(i + 1)
                    found += Explanation(
                        track = track,
                        text = listOfNotNull(here.text, next?.text).joinToString(" "),
                        startMs = here.startMs,
                        endMs = (next ?: here).endMs,
                    )
                }
            }
            if (_lookup.value?.word == word) _lookup.value = LookupUi(word, sentence, found)
        }
    }

    fun closeLookup() {
        _lookup.value = null
    }

    suspend fun audioUri(track: TrackEntity): String = c.drive.playableUri(track)

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

    fun addCard(front: String, back: String, sentence: Sentence) = addCardFrom(_ui.value.selected, front, back, sentence)

    private fun addCardFrom(track: TrackEntity?, front: String, back: String, sentence: Sentence) {
        track ?: return
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
    val lookup by vm.lookup.collectAsStateWithLifecycle()
    var menuSentence by remember { mutableStateOf<Sentence?>(null) }
    var cardSentence by remember { mutableStateOf<Sentence?>(null) }
    var cardFront by remember { mutableStateOf("") }
    val context = LocalContext.current
    val clip = remember { ClipPlayer(context, context.container.drive) }
    DisposableEffect(Unit) { onDispose { clip.release() } }
    val scope = rememberCoroutineScope()

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
                else -> ReadingText(
                    transcript,
                    translation = ui.translation,
                    onTapWord = { word, sentence -> vm.lookUp(word, sentence) },
                    onLongPress = { menuSentence = it },
                )
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
                    cardFront = ""
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

    lookup?.let { l ->
        LookupSheet(
            lookup = l,
            onDismiss = vm::closeLookup,
            onPlay = { e -> scope.launch { clip.play(vm.audioUri(e.track), e.startMs, e.endMs) } },
            onTranslate = { text -> translate(context, text) },
            onSave = {
                cardFront = l.word
                cardSentence = l.sentence
                vm.closeLookup()
            },
        )
    }

    cardSentence?.let { sentence ->
        AddCardDialog(
            sentence = sentence,
            initialFront = cardFront,
            onDismiss = { cardSentence = null },
            onSave = { front, back ->
                vm.addCard(front, back, sentence)
                cardSentence = null
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LookupSheet(
    lookup: LookupUi,
    onDismiss: () -> Unit,
    onPlay: (Explanation) -> Unit,
    onTranslate: (String) -> Unit,
    onSave: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(lookup.word, style = MaterialTheme.typography.headlineSmall)
            Text(lookup.sentence.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { onTranslate(lookup.word) }, label = { Text(stringResource(R.string.translate_word)) },
                    leadingIcon = { Icon(Icons.Default.Translate, null, Modifier.size(AssistChipDefaults.IconSize)) })
                AssistChip(onClick = { onTranslate(lookup.sentence.text) }, label = { Text(stringResource(R.string.translate_sentence)) },
                    leadingIcon = { Icon(Icons.Default.Translate, null, Modifier.size(AssistChipDefaults.IconSize)) })
                AssistChip(onClick = onSave, label = { Text(stringResource(R.string.save_card)) },
                    leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(AssistChipDefaults.IconSize)) })
            }
            Text(stringResource(R.string.teacher_explains), style = MaterialTheme.typography.titleSmall)
            val explanations = lookup.explanations
            when {
                explanations == null -> Text(stringResource(R.string.loading), style = MaterialTheme.typography.bodySmall)
                explanations.isEmpty() -> Text(
                    stringResource(R.string.teacher_not_found),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> explanations.forEach { e ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(e.track.kind.label()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Text(e.text, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (e.startMs != null) {
                            IconButton(onClick = { onPlay(e) }) {
                                Icon(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.play_sentence))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One Text per paragraph, so it reads like a page. A tap looks up the word under the finger; a
 * long-press offers the whole sentence.
 */
@Composable
private fun ReadingText(
    transcript: Transcript,
    translation: List<String>?,
    onTapWord: (String, Sentence) -> Unit,
    onLongPress: (Sentence) -> Unit,
) {
    val paragraphs = transcript.paragraphs
    // Hidden by default: the meaning is there for a paragraph that will not make sense, not to read instead.
    val shown = remember(transcript) { mutableStateListOf<Int>() }
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(paragraphs.size) { i ->
            Column {
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
                        fun sentenceAt(position: Int) = sentences.getOrNull(ranges.indexOfFirst { position in it || position == it.last + 1 })
                        detectTapGestures(
                            onTap = { offset ->
                                val position = layout?.getOffsetForPosition(offset) ?: return@detectTapGestures
                                val word = Words.wordAt(text.text, position) ?: return@detectTapGestures
                                sentenceAt(position)?.let { onTapWord(word, it) }
                            },
                            onLongPress = { offset ->
                                val position = layout?.getOffsetForPosition(offset) ?: return@detectTapGestures
                                sentenceAt(position)?.let(onLongPress)
                            },
                        )
                    },
                )
                val meaning = translation?.getOrNull(i)
                if (meaning != null) {
                    if (i in shown) {
                        Text(
                            meaning,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(8.dp))
                                .clickable { shown.remove(i) }
                                .padding(10.dp),
                        )
                    } else {
                        Text(
                            stringResource(R.string.show_meaning),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { shown.add(i) }.padding(vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}
