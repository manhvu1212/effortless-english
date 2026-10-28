package io.github.manhvu1212.effortless

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.manhvu1212.effortless.reminders.Notifications
import io.github.manhvu1212.effortless.ui.AppNav
import io.github.manhvu1212.effortless.ui.theme.EffortlessTheme

class MainActivity : ComponentActivity() {
    private var openPlayer by mutableStateOf(false)

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openPlayer = intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Notifications.canPost(this)) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            EffortlessTheme {
                AppNav(openPlayer = openPlayer, onPlayerOpened = { openPlayer = false })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false)) openPlayer = true
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "open_player"
    }
}
