package io.github.manhvu1212.effortless.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.manhvu1212.effortless.MainActivity
import io.github.manhvu1212.effortless.R
import io.github.manhvu1212.effortless.container
import io.github.manhvu1212.effortless.data.settings.Settings
import io.github.manhvu1212.effortless.domain.today
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

object Notifications {
    const val CHANNEL_REMINDERS = "reminders"
    private const val ID_STUDY = 1
    private const val ID_NAG = 2

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REMINDERS,
                context.getString(R.string.channel_reminders),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun showStudy(context: Context, title: String, text: String) = show(context, ID_STUDY, title, text)

    fun showNag(context: Context, title: String, text: String) = show(context, ID_NAG, title, text)

    private fun show(context: Context, id: Int, title: String, text: String) {
        if (!canPost(context)) return
        val open = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(context).notify(id, notification)
    }
}

/**
 * Inexact alarms (no exact-alarm permission needed): each fires within a few minutes of its time,
 * and the receiver schedules the next day's occurrence.
 */
object ReminderScheduler {
    const val KIND_STUDY = "study"
    const val KIND_NAG = "nag"
    /** Just after midnight: a new day for the status notification. */
    const val KIND_STATUS = "status"
    const val EXTRA_KIND = "kind"
    const val EXTRA_MINUTE = "minute"
    const val EXTRA_CODE = "code"
    private const val MAX_STUDY = 10
    private const val CODE_NAG = 100
    private const val CODE_STATUS = 101

    fun rescheduleAll(context: Context, settings: Settings) {
        for (code in 0 until MAX_STUDY) cancel(context, code)
        cancel(context, CODE_NAG)
        settings.reminderTimes.take(MAX_STUDY).forEachIndexed { code, minute -> schedule(context, code, minute, KIND_STUDY) }
        if (settings.nagEnabled) schedule(context, CODE_NAG, settings.nagTime, KIND_NAG)
        schedule(context, CODE_STATUS, 1, KIND_STATUS)
    }

    fun schedule(context: Context, code: Int, minuteOfDay: Int, kind: String) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextOccurrence(minuteOfDay), pendingIntent(context, code, minuteOfDay, kind))
    }

    private fun cancel(context: Context, code: Int) {
        val intent = Intent(context, ReminderReceiver::class.java)
        PendingIntent.getBroadcast(context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE)
            ?.let { context.getSystemService(AlarmManager::class.java).cancel(it) }
    }

    private fun pendingIntent(context: Context, code: Int, minuteOfDay: Int, kind: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .putExtra(EXTRA_KIND, kind)
            .putExtra(EXTRA_MINUTE, minuteOfDay)
            .putExtra(EXTRA_CODE, code)
        return PendingIntent.getBroadcast(context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun nextOccurrence(minuteOfDay: Int, now: LocalDateTime = LocalDateTime.now()): Long {
        val time = LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)
        var at = LocalDateTime.of(LocalDate.from(now), time)
        if (!at.isAfter(now.plusSeconds(30))) at = at.plusDays(1)
        return at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val kind = intent.getStringExtra(ReminderScheduler.EXTRA_KIND) ?: return
        val minute = intent.getIntExtra(ReminderScheduler.EXTRA_MINUTE, -1)
        val code = intent.getIntExtra(ReminderScheduler.EXTRA_CODE, -1)
        if (minute >= 0 && code >= 0) ReminderScheduler.schedule(context, code, minute, kind)

        val pending = goAsync()
        val container = context.container
        container.scope.launch {
            try {
                when (kind) {
                    ReminderScheduler.KIND_STUDY -> remindStudy(context)
                    ReminderScheduler.KIND_STATUS -> StudyStatus.refresh(context, container)
                    ReminderScheduler.KIND_NAG -> if (!container.study.studiedToday()) {
                        Notifications.showNag(
                            context,
                            context.getString(R.string.nag_title),
                            context.getString(R.string.nag_text),
                        )
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun remindStudy(context: Context) {
        val container = context.container
        val due = container.db.cards().dueCountOnce(today())
        val lesson = container.study.currentLesson()
        val remaining = lesson?.let { container.study.remainingStepsToday(it.id) } ?: 0
        if (remaining == 0 && due == 0) return
        val parts = buildList {
            if (lesson != null && remaining > 0) {
                add(context.resources.getQuantityString(R.plurals.remind_steps, remaining, remaining, lesson.title))
            }
            if (due > 0) add(context.resources.getQuantityString(R.plurals.remind_cards, due, due))
        }
        Notifications.showStudy(context, context.getString(R.string.remind_title), parts.joinToString("\n"))
    }
}

class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val container = context.container
        container.scope.launch {
            try {
                ReminderScheduler.rescheduleAll(context, container.settings.current())
                StudyStatus.refresh(context, container)
            } finally {
                pending.finish()
            }
        }
    }
}
