package io.github.manhvu1212.effortless

import android.app.Application
import android.content.Context
import io.github.manhvu1212.effortless.data.CardRepository
import io.github.manhvu1212.effortless.data.LibraryRepository
import io.github.manhvu1212.effortless.data.LibrarySync
import io.github.manhvu1212.effortless.data.SyncWorker
import io.github.manhvu1212.effortless.data.StudyRepository
import io.github.manhvu1212.effortless.data.db.AppDatabase
import io.github.manhvu1212.effortless.data.settings.SettingsRepository
import io.github.manhvu1212.effortless.drive.DriveRepository
import io.github.manhvu1212.effortless.player.PlayerController
import io.github.manhvu1212.effortless.reminders.Notifications
import io.github.manhvu1212.effortless.reminders.ReminderScheduler
import io.github.manhvu1212.effortless.reminders.StudyStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = AppDatabase.build(context)
    val settings = SettingsRepository(context)
    val library = LibraryRepository(context, db, settings)
    val study = StudyRepository(db, settings)
    val cards = CardRepository(db)
    val drive = DriveRepository(context, db, settings, study)
    val player = PlayerController(context, db, library, study, settings, scope, drive)
    val sync = LibrarySync(scope, { SyncWorker.start(context) }, library, drive)
}

class EffortlessApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
        StudyStatus.createChannel(this)
        StudyStatus.observe(this, container)
        container.scope.launch {
            ReminderScheduler.rescheduleAll(this@EffortlessApp, container.settings.current())
            runCatching { container.drive.ensureAhead() }
        }
    }
}

val Context.container: AppContainer get() = (applicationContext as EffortlessApp).container
