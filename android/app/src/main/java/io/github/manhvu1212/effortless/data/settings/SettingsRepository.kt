package io.github.manhvu1212.effortless.data.settings

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Times of day are minutes after midnight. */
data class Settings(
    val daysPerLesson: Int = 7,
    val reminderTimes: List<Int> = listOf(7 * 60 + 30, 12 * 60 + 30, 20 * 60 + 30),
    val nagEnabled: Boolean = true,
    val nagTime: Int = 21 * 60 + 30,
    val contentRootUri: String? = null,
    val pinnedLessonId: String? = null,
    /** Track played last, for resuming playback from the car or a headset button. */
    val lastTrackId: String? = null,
    val speed: Float = 1f,
    val rewindSeconds: Int = 5,
    val shadowGapPercent: Int = 100,
)

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(context: Context) {
    private val store = context.dataStore

    val settings: Flow<Settings> = store.data.map { it.toSettings() }

    suspend fun current(): Settings = settings.first()

    suspend fun update(transform: (Settings) -> Settings) {
        store.edit { prefs -> prefs.write(transform(prefs.toSettings())) }
    }

    private fun Preferences.toSettings(): Settings {
        val defaults = Settings()
        return Settings(
            daysPerLesson = this[DAYS_PER_LESSON] ?: defaults.daysPerLesson,
            reminderTimes = this[REMINDER_TIMES]?.let { raw ->
                raw.split(',').mapNotNull { it.toIntOrNull() }.sorted()
            } ?: defaults.reminderTimes,
            nagEnabled = this[NAG_ENABLED] ?: defaults.nagEnabled,
            nagTime = this[NAG_TIME] ?: defaults.nagTime,
            contentRootUri = this[CONTENT_ROOT],
            pinnedLessonId = this[PINNED_LESSON],
            lastTrackId = this[LAST_TRACK],
            speed = this[SPEED] ?: defaults.speed,
            rewindSeconds = this[REWIND_SECONDS] ?: defaults.rewindSeconds,
            shadowGapPercent = this[SHADOW_GAP] ?: defaults.shadowGapPercent,
        )
    }

    private fun MutablePreferences.write(s: Settings) {
        this[DAYS_PER_LESSON] = s.daysPerLesson
        this[REMINDER_TIMES] = s.reminderTimes.sorted().joinToString(",")
        this[NAG_ENABLED] = s.nagEnabled
        this[NAG_TIME] = s.nagTime
        if (s.contentRootUri != null) this[CONTENT_ROOT] = s.contentRootUri else remove(CONTENT_ROOT)
        if (s.pinnedLessonId != null) this[PINNED_LESSON] = s.pinnedLessonId else remove(PINNED_LESSON)
        if (s.lastTrackId != null) this[LAST_TRACK] = s.lastTrackId else remove(LAST_TRACK)
        this[SPEED] = s.speed
        this[REWIND_SECONDS] = s.rewindSeconds
        this[SHADOW_GAP] = s.shadowGapPercent
    }

    private companion object {
        val DAYS_PER_LESSON = intPreferencesKey("days_per_lesson")
        val REMINDER_TIMES = stringPreferencesKey("reminder_times")
        val NAG_ENABLED = booleanPreferencesKey("nag_enabled")
        val NAG_TIME = intPreferencesKey("nag_time")
        val CONTENT_ROOT = stringPreferencesKey("content_root")
        val PINNED_LESSON = stringPreferencesKey("pinned_lesson")
        val LAST_TRACK = stringPreferencesKey("last_track")
        val SPEED = floatPreferencesKey("speed")
        val REWIND_SECONDS = intPreferencesKey("rewind_seconds")
        val SHADOW_GAP = intPreferencesKey("shadow_gap_percent")
    }
}
