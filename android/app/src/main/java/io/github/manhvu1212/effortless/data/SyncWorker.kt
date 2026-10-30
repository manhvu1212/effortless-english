package io.github.manhvu1212.effortless.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.manhvu1212.effortless.R
import io.github.manhvu1212.effortless.container
import kotlinx.coroutines.flow.takeWhile

/**
 * Keeps the app in the foreground, with a progress notification, while a [LibrarySync] refresh runs,
 * so Android does not freeze it when the screen turns off or the user switches apps. The refresh
 * itself stays in the application's scope; this worker only waits for it to end.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private var lastNotified = 0L

    override suspend fun doWork(): Result {
        applicationContext.container.sync.state.takeWhile { it is SyncState.Running }.collect { state ->
            val now = System.currentTimeMillis()
            if (now - lastNotified >= 1_000) {
                lastNotified = now
                setForeground(foregroundInfo(state as SyncState.Running))
            }
        }
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(SyncState.Running(SyncStage.LISTING))

    private fun foregroundInfo(state: SyncState.Running): ForegroundInfo {
        val context = applicationContext
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.channel_sync), NotificationManager.IMPORTANCE_LOW)
        )
        val text = when (state.stage) {
            SyncStage.LISTING -> context.getString(R.string.sync_listing)
            SyncStage.FILES -> context.getString(R.string.sync_files, state.done, state.total)
            SyncStage.BUILDING -> context.getString(R.string.sync_building)
            SyncStage.SCANNING -> context.getString(R.string.sync_scanning)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.syncing_library))
            .setContentText(text)
            .setProgress(state.total, state.done, state.total == 0)
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
        private const val NAME = "library-sync"
        private const val CHANNEL = "sync"
        private const val NOTIFICATION_ID = 4

        fun start(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
