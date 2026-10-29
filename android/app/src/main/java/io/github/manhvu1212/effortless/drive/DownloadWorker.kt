package io.github.manhvu1212.effortless.drive

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import io.github.manhvu1212.effortless.R
import io.github.manhvu1212.effortless.container
import java.io.IOException

/** Downloads the audio of a list of lessons, as a foreground job with a progress notification. */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val drive = context.container.drive
    private var lastNotified = 0L

    override suspend fun doWork(): Result {
        val lessonIds = inputData.getNullableStringArray(KEY_LESSONS)?.filterNotNull().orEmpty()
        val total = drive.missingBytes(lessonIds)
        if (total == 0L) return Result.success()
        setForeground(foregroundInfo(0, total))
        return try {
            var before = 0L
            for (id in lessonIds) {
                val lessonTotal = drive.missingBytes(listOf(id))
                drive.downloadLesson(id) { bytes -> progress(before + bytes.coerceAtMost(lessonTotal), total) }
                before += lessonTotal
            }
            Result.success()
        } catch (e: NotAuthorizedException) {
            Result.failure()
        } catch (e: IOException) {
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, 0)

    private suspend fun progress(done: Long, total: Long) {
        val now = System.currentTimeMillis()
        if (now - lastNotified < 1_000) return
        lastNotified = now
        setForeground(foregroundInfo(done, total))
    }

    private fun foregroundInfo(done: Long, total: Long): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, applicationContext.getString(R.string.channel_downloads), NotificationManager.IMPORTANCE_LOW)
        )
        val percent = if (total > 0) (done * 100 / total).toInt() else 0
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(R.string.downloading_audio))
            .setContentText(applicationContext.getString(R.string.download_progress, mb(done), mb(total)))
            .setProgress(100, percent, total == 0L)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val KEY_LESSONS = "lessons"
        private const val CHANNEL = "downloads"
        private const val NOTIFICATION_ID = 3

        fun mb(bytes: Long): String = "%.0f MB".format(bytes / 1_000_000.0)
    }
}
