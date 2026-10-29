package io.github.manhvu1212.effortless.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.manhvu1212.effortless.AppContainer
import io.github.manhvu1212.effortless.R
import io.github.manhvu1212.effortless.container
import io.github.manhvu1212.effortless.data.CardRepository
import io.github.manhvu1212.effortless.data.db.CardEntity
import io.github.manhvu1212.effortless.domain.Grade
import io.github.manhvu1212.effortless.domain.today
import io.github.manhvu1212.effortless.player.ClipPlayer
import io.github.manhvu1212.effortless.ui.appViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class CardsViewModel(private val c: AppContainer) : ViewModel() {
    val cards: StateFlow<List<CardEntity>> = c.db.cards().all()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val due: StateFlow<Int> = c.db.cards().dueCount(today())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun save(card: CardEntity?, front: String, back: String, example: String) = viewModelScope.launch {
        if (card == null) {
            c.cards.add(CardEntity(front = front.trim(), back = back.trim(), example = example.trim(), dueDay = today()))
        } else {
            c.cards.save(card.copy(front = front.trim(), back = back.trim(), example = example.trim()))
        }
    }

    fun delete(card: CardEntity) = viewModelScope.launch { c.cards.delete(card) }
}

@Composable
fun CardsScreen(contentPadding: PaddingValues, onStartReview: () -> Unit) {
    val vm = appViewModel { CardsViewModel(it) }
    val cards by vm.cards.collectAsStateWithLifecycle()
    val due by vm.due.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<CardEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    val dateFormat = remember { DateTimeFormatter.ofPattern("dd/MM") }

    Box(Modifier.fillMaxSize().padding(contentPadding)) {
        LazyColumn(contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                MessageCard(
                    text = pluralStringResource(R.plurals.cards_due_long, due, due, cards.size),
                    action = if (due > 0) stringResource(R.string.start_review) else null,
                    onAction = onStartReview,
                )
            }
            if (cards.isEmpty()) {
                item { Text(stringResource(R.string.no_cards_hint), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(cards, key = { it.id }) { card ->
                Card(Modifier.fillMaxWidth().clickable { editing = card }) {
                    Column(Modifier.padding(12.dp)) {
                        Text(card.front, style = MaterialTheme.typography.titleSmall)
                        if (card.back.isNotBlank()) Text(card.back, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (card.dueDay <= today()) stringResource(R.string.due_now)
                            else stringResource(R.string.due_on, LocalDate.ofEpochDay(card.dueDay).format(dateFormat)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = { adding = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Icon(Icons.Default.Add, stringResource(R.string.add_card)) }
    }

    if (adding || editing != null) {
        val card = editing
        EditCardDialog(
            card = card,
            onDismiss = { adding = false; editing = null },
            onSave = { front, back, example ->
                vm.save(card, front, back, example)
                adding = false
                editing = null
            },
            onDelete = card?.let { { vm.delete(it); editing = null } },
        )
    }
}

@Composable
private fun EditCardDialog(
    card: CardEntity?,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var front by remember { mutableStateOf(card?.front.orEmpty()) }
    var back by remember { mutableStateOf(card?.back.orEmpty()) }
    var example by remember { mutableStateOf(card?.example.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (card == null) R.string.add_card else R.string.edit_card)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(front, { front = it }, label = { Text(stringResource(R.string.card_front)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(back, { back = it }, label = { Text(stringResource(R.string.card_back)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(example, { example = it }, label = { Text(stringResource(R.string.card_example)) }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(front, back, example) }, enabled = front.isNotBlank()) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

data class ReviewUi(
    val loaded: Boolean = false,
    val queue: List<CardEntity> = emptyList(),
    val revealed: Boolean = false,
    val reviewed: Int = 0,
) {
    val card: CardEntity? get() = queue.firstOrNull()
}

class ReviewViewModel(private val c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(ReviewUi())
    val ui: StateFlow<ReviewUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch { _ui.value = ReviewUi(loaded = true, queue = c.cards.due()) }
    }

    fun reveal() = _ui.update { it.copy(revealed = true) }

    /** AGAIN puts the card back at the end of the session queue. */
    fun grade(grade: Grade) {
        val card = _ui.value.card ?: return
        viewModelScope.launch {
            val updated = c.cards.grade(card, grade)
            _ui.update { s ->
                val rest = s.queue.drop(1)
                s.copy(
                    queue = if (grade == Grade.AGAIN) rest + updated else rest,
                    revealed = false,
                    reviewed = s.reviewed + 1,
                )
            }
        }
    }

    suspend fun audioUri(card: CardEntity): String? = card.trackId?.let { c.db.library().track(it) }?.let { c.drive.playableUri(it) }
}

@Composable
fun ReviewScreen(contentPadding: PaddingValues, onDone: () -> Unit) {
    val vm = appViewModel { ReviewViewModel(it) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clip = remember { ClipPlayer(context, context.container.drive) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    DisposableEffect(Unit) { onDispose { clip.release() } }

    Column(
        Modifier.fillMaxSize().padding(contentPadding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!ui.loaded) return@Column
        val card = ui.card
        if (card == null) {
            Spacer(Modifier.weight(1f))
            Text(
                pluralStringResource(R.plurals.review_done, ui.reviewed, ui.reviewed),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = onDone, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(stringResource(R.string.done)) }
            Spacer(Modifier.weight(1f))
            return@Column
        }
        Text(
            pluralStringResource(R.plurals.cards_left, ui.queue.size, ui.queue.size),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card(Modifier.fillMaxWidth().weight(1f)) {
            Column(
                Modifier.fillMaxSize().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(card.front, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                if (card.trackId != null) {
                    IconButton(onClick = {
                        scope.launch { vm.audioUri(card)?.let { clip.play(it, card.clipStartMs, card.clipEndMs) } }
                    }) { Icon(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.play_sentence)) }
                }
                if (ui.revealed) {
                    if (card.back.isNotBlank()) Text(card.back, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    if (card.example.isNotBlank()) {
                        Text(
                            card.example,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
        if (!ui.revealed) {
            Button(onClick = vm::reveal, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(stringResource(R.string.show_answer)) }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Grade.entries.forEach { grade ->
                    val days = CardRepository.preview(card, grade)
                    OutlinedButton(onClick = { vm.grade(grade) }, modifier = Modifier.weight(1f)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(grade.label()), style = MaterialTheme.typography.labelLarge)
                            Text(
                                if (days == 0) stringResource(R.string.interval_now) else stringResource(R.string.interval_days, days),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun Grade.label(): Int = when (this) {
    Grade.AGAIN -> R.string.grade_again
    Grade.HARD -> R.string.grade_hard
    Grade.GOOD -> R.string.grade_good
    Grade.EASY -> R.string.grade_easy
}
