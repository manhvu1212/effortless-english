package io.github.manhvu1212.effortless

import android.Manifest
import android.app.UiModeManager
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.manhvu1212.effortless.data.settings.ThemeMode
import io.github.manhvu1212.effortless.reminders.Notifications
import io.github.manhvu1212.effortless.reminders.StudyStatus
import io.github.manhvu1212.effortless.ui.AppNav
import io.github.manhvu1212.effortless.ui.theme.EffortlessTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var openPlayer by mutableStateOf(false)

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // The status notification could not be shown before the permission was granted.
        if (granted) container.scope.launch { StudyStatus.refresh(applicationContext, container) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openPlayer = intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Notifications.canPost(this)) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val settingsFlow = (application as EffortlessApp).container.settings.settings
        setContent {
            // Nothing until the settings are read, so the app never shows in the wrong theme first.
            val settings = settingsFlow.collectAsStateWithLifecycle(initialValue = null).value ?: return@setContent
            val dark = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            LaunchedEffect(settings.themeMode, dark) { applyTheme(settings.themeMode, dark) }
            EffortlessTheme(darkTheme = dark, dynamicColor = settings.dynamicColor) {
                AppNav(openPlayer = openPlayer, onPlayerOpened = { openPlayer = false })
            }
        }
    }

    /** Bar icons follow the app's theme, not the system's; Android 12+ also keeps the choice for the launch screen. */
    private fun applyTheme(mode: ThemeMode, dark: Boolean) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
            navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val night = when (mode) {
                ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
                ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
                ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
            }
            getSystemService(UiModeManager::class.java).setApplicationNightMode(night)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false)) openPlayer = true
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "open_player"

        // The scrims enableEdgeToEdge uses by default for three-button navigation.
        private val LIGHT_SCRIM = Color.argb(0xE6, 0xFF, 0xFF, 0xFF)
        private val DARK_SCRIM = Color.argb(0x80, 0x1B, 0x1B, 0x1B)
    }
}
