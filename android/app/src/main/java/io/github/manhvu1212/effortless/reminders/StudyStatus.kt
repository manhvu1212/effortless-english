package io.github.manhvu1212.effortless.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.manhvu1212.effortless.AppContainer
import io.github.manhvu1212.effortless.MainActivity
import io.github.manhvu1212.effortless.R
import io.github.manhvu1212.effortless.container
import io.github.manhvu1212.effortless.domain.today
import io.github.manhvu1212.effortless.ui.label
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

/**
 * The ongoing "today" notification, a constant nudge to study: listening minutes against the daily
 * goal, the next step of the current lesson, and the cards due. It follows the database and the
 * settings while the app runs; after midnight and on boot the reminder alarms refresh it. Android 14+
 * lets the user swipe even an ongoing notification away; [StatusReceiver] puts it straight back.
 */
object StudyStatus {
    // Default importance so its icon stays in the status bar (silent ones are hidden there); no
    // sound or vibration, and every update is silent.
    const val CHANNEL = "today"
    private const val ID = 5

    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.channel_status), NotificationManager.IMPORTANCE_DEFAULT).apply {
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    /** Keeps the notification in step with what is studied, at most every couple of seconds. */
    fun observe(context: Context, c: AppContainer) {
        c.scope.launch {
            val tables = c.db.invalidationTracker.createFlow("daily_log", "track_listen", "card", "lesson", "lesson_progress")
            combine(c.settings.settings, tables) { _, _ -> }
                .conflate()
                .collect {
                    refresh(context, c)
                    delay(2_000)
                }
        }
    }

    suspend fun refresh(context: Context, c: AppContainer) {
        val manager = NotificationManagerCompat.from(context)
        val settings = c.settings.current()
        val lesson = c.study.currentLesson()
        if (!settings.statusNotification || lesson == null || !Notifications.canPost(context)) {
            manager.cancel(ID)
            return
        }
        val minutes = (c.db.study().listenedOnce(today()) / 60_000).toInt()
        val goal = settings.dailyGoalMinutes
        val next = c.study.nextStep(lesson.id)
        val due = c.db.cards().dueCountOnce(today())

        val title = when {
            next != null -> context.getString(
                R.string.status_next,
                minutes,
                goal,
                context.getString(next.track?.kind?.label() ?: R.string.step_read),
            )
            minutes < goal -> context.getString(R.string.status_steps_done, minutes, goal)
            else -> context.getString(R.string.status_all_done, minutes, goal)
        }
        val text = buildList {
            add(lesson.title)
            if (due > 0) add(context.resources.getQuantityString(R.plurals.status_cards, due, due))
        }.joinToString(" · ")
        val open = PendingIntent.getActivity(
            context,
            ID,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(goal, minutes.coerceAtMost(goal), false)
            .setContentIntent(open)
            .setDeleteIntent(
                PendingIntent.getBroadcast(context, ID, Intent(context, StatusReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        @Suppress("MissingPermission")
        manager.notify(ID, notification)
    }
}

/** Shows the status notification again when it is swiped away (it stays off when turned off in Settings). */
class StatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val container = context.container
        container.scope.launch {
            try {
                delay(500)
                StudyStatus.refresh(context, container)
            } finally {
                pending.finish()
            }
        }
    }
}
