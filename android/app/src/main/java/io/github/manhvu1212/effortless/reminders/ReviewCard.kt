package io.github.manhvu1212.effortless.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.manhvu1212.effortless.AppContainer
import io.github.manhvu1212.effortless.MainActivity
import io.github.manhvu1212.effortless.R
import io.github.manhvu1212.effortless.container
import io.github.manhvu1212.effortless.data.content.Words
import io.github.manhvu1212.effortless.data.db.TrackKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

/** One thing to recall: [front] is shown, [meaning] and [detail] only after "Xem nghĩa". */
@Serializable
data class ReviewItem(
    val kind: Kind,
    val front: String,
    val meaning: String,
    val detail: String = "",
    val example: String = "",
    val source: String,
) {
    enum class Kind { WORD, SENTENCE, CARD }
}

/**
 * The lock-screen review card: a word from a lesson glossary, a sentence of a main text, or one of
 * the user's cards, picked at random from the lessons started so far. It changes when the screen
 * turns off (while the app runs) and at least every 15 minutes, so picking the phone up shows
 * something new. "Xem nghĩa" and "Câu khác" work without unlocking.
 */
object ReviewCard {
    const val CHANNEL = "review"
    private const val ID = 6
    private const val PREFS = "review_card"
    private const val KEY_ITEM = "item"
    private const val KEY_REVEALED = "revealed"
    private const val KEY_CHANGED = "changed"
    private const val WORK = "review-card"
    private val json = Json { ignoreUnknownKeys = true }

    fun createChannel(context: Context) {
        // Default importance: lock screens commonly hide silent notifications. No sound, no vibration.
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.channel_review), NotificationManager.IMPORTANCE_DEFAULT).apply {
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    /** Screen-off changes while the process lives, a periodic worker otherwise, and the Settings switch. */
    fun start(context: Context, c: AppContainer) {
        ContextCompat.registerReceiver(
            context,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val pending = goAsync()
                    c.scope.launch {
                        try {
                            next(context, c, minAgeMs = 10_000)
                        } finally {
                            pending.finish()
                        }
                    }
                }
            },
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ReviewWorker>(15, TimeUnit.MINUTES).build(),
        )
        c.scope.launch {
            show(context, c)
            c.settings.settings.map { it.reviewCard }.distinctUntilChanged().drop(1).collect { show(context, c) }
        }
    }

    /** Posts the current card again, or a first one; removes it when turned off. */
    suspend fun show(context: Context, c: AppContainer) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val item = prefs.getString(KEY_ITEM, null)?.let { runCatching { json.decodeFromString(ReviewItem.serializer(), it) }.getOrNull() }
        if (item == null) next(context, c) else post(context, c, item, prefs.getBoolean(KEY_REVEALED, false))
    }

    /** A new card, unless the current one is younger than [minAgeMs]. */
    suspend fun next(context: Context, c: AppContainer, minAgeMs: Long = 0) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - prefs.getLong(KEY_CHANGED, 0) < minAgeMs) return
        if (!c.settings.current().reviewCard) {
            NotificationManagerCompat.from(context).cancel(ID)
            return
        }
        // Never the card just shown, when there is anything else to show.
        val previous = prefs.getString(KEY_ITEM, null)?.let { runCatching { json.decodeFromString(ReviewItem.serializer(), it) }.getOrNull() }
        var picked = pick(c)
        var tries = 0
        while (picked != null && picked.front == previous?.front && tries++ < 5) picked = pick(c)
        val item = picked ?: run {
            NotificationManagerCompat.from(context).cancel(ID)
            return
        }
        prefs.edit()
            .putString(KEY_ITEM, json.encodeToString(ReviewItem.serializer(), item))
            .putBoolean(KEY_REVEALED, false)
            .putLong(KEY_CHANGED, System.currentTimeMillis())
            .apply()
        post(context, c, item, revealed = false)
    }

    suspend fun reveal(context: Context, c: AppContainer) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_REVEALED, true).apply()
        show(context, c)
    }

    private suspend fun post(context: Context, c: AppContainer, item: ReviewItem, revealed: Boolean) {
        val manager = NotificationManagerCompat.from(context)
        if (!c.settings.current().reviewCard || !Notifications.canPost(context)) {
            manager.cancel(ID)
            return
        }
        val title = if (revealed && item.kind != ReviewItem.Kind.SENTENCE && item.meaning.isNotBlank()) "${item.front} · ${item.meaning}" else item.front
        val kindLabel = context.getString(
            when (item.kind) {
                ReviewItem.Kind.WORD -> R.string.review_word
                ReviewItem.Kind.SENTENCE -> R.string.review_sentence
                ReviewItem.Kind.CARD -> R.string.review_card
            }
        )
        val body = buildList {
            if (revealed) {
                if (item.kind == ReviewItem.Kind.SENTENCE) add(item.meaning)
                if (item.detail.isNotBlank()) add(item.detail)
            }
            if (item.example.isNotBlank()) add("“${item.example}”")
            add("$kindLabel · ${item.source}")
        }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body.first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body.joinToString("\n")))
            .setContentIntent(
                PendingIntent.getActivity(context, ID, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            )
            .setDeleteIntent(action(context, ReviewReceiver.ACTION_SHOW))
            .apply {
                if (!revealed) addAction(0, context.getString(R.string.review_reveal), action(context, ReviewReceiver.ACTION_REVEAL))
                addAction(0, context.getString(R.string.review_next), action(context, ReviewReceiver.ACTION_NEXT))
            }
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setGroup("review") // not bundled with the status notification (see StudyStatus)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        @Suppress("MissingPermission")
        manager.notify(ID, notification)
    }

    private fun action(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        action.hashCode(),
        Intent(context, ReviewReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE,
    )

    /** A random kind first, then the others if it has nothing to offer yet. */
    private suspend fun pick(c: AppContainer): ReviewItem? = withContext(Dispatchers.IO) {
        val started = c.db.study().allProgressOnce().map { it.lessonId }
        val lessonIds = (started + listOfNotNull(c.study.currentLesson()?.id)).distinct().shuffled()
        for (kind in ReviewItem.Kind.entries.shuffled()) {
            val item = when (kind) {
                ReviewItem.Kind.WORD -> pickWord(c, lessonIds)
                ReviewItem.Kind.SENTENCE -> pickSentence(c, lessonIds)
                ReviewItem.Kind.CARD -> pickCard(c)
            }
            if (item != null) return@withContext item
        }
        null
    }

    private suspend fun pickWord(c: AppContainer, lessonIds: List<String>): ReviewItem? {
        for (lessonId in lessonIds) {
            val lesson = c.db.library().lessonOnce(lessonId) ?: continue
            val tracks = c.db.library().tracksOnce(lessonId)
            val entries = tracks.filter { it.kind == TrackKind.VOCABULARY }.flatMap { c.library.loadGlossary(it).orEmpty() }
            val entry = entries.randomOrNull() ?: continue
            // A sentence of the main text that uses it, as met while reading.
            val main = tracks.firstOrNull { it.kind == TrackKind.MAIN }?.let { c.library.loadTranscript(it) }
            val example = main?.let { t ->
                entry.keys.asSequence().flatMap { key -> Words.sentencesUsing(key, t.sentences).asSequence() }
                    .firstOrNull()?.let { t.sentences[it].text }
            }.orEmpty()
            return ReviewItem(ReviewItem.Kind.WORD, entry.term, entry.vi, entry.explain, example, lesson.title)
        }
        return null
    }

    private suspend fun pickSentence(c: AppContainer, lessonIds: List<String>): ReviewItem? {
        for (lessonId in lessonIds) {
            val lesson = c.db.library().lessonOnce(lessonId) ?: continue
            val track = c.db.library().tracksOnce(lessonId).firstOrNull { it.kind == TrackKind.MAIN } ?: continue
            val transcript = c.library.loadTranscript(track) ?: continue
            // A whole sentence, not a heading or a one-word reply.
            val sentence = transcript.sentences.filter { it.text.split(' ').size in 6..40 }.randomOrNull() ?: continue
            val paragraph = transcript.paragraphs.indexOfFirst { sentence in it }
            val meaning = c.library.loadTranslation(track, transcript)?.getOrNull(paragraph).orEmpty()
            return ReviewItem(ReviewItem.Kind.SENTENCE, sentence.text, meaning, source = lesson.title)
        }
        return null
    }

    private suspend fun pickCard(c: AppContainer): ReviewItem? {
        val card = c.db.cards().all().first().randomOrNull() ?: return null
        val lesson = card.lessonId?.let { c.db.library().lessonOnce(it)?.title }
        return ReviewItem(ReviewItem.Kind.CARD, card.front, card.back, example = card.example, source = lesson ?: "")
    }
}

/** The card's buttons, and putting it back when swiped away. */
class ReviewReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val c = context.container
        c.scope.launch {
            try {
                when (intent.action) {
                    ACTION_REVEAL -> ReviewCard.reveal(context, c)
                    ACTION_NEXT -> ReviewCard.next(context, c)
                    else -> ReviewCard.show(context, c)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_SHOW = "io.github.manhvu1212.effortless.review.SHOW"
        const val ACTION_REVEAL = "io.github.manhvu1212.effortless.review.REVEAL"
        const val ACTION_NEXT = "io.github.manhvu1212.effortless.review.NEXT"
    }
}

/** At least every 15 minutes a new card, even when the app is not running. */
class ReviewWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        ReviewCard.next(applicationContext, applicationContext.container, minAgeMs = 60_000)
        return Result.success()
    }
}
