package io.github.manhvu1212.effortless.ui.screens

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.manhvu1212.effortless.AppContainer
import io.github.manhvu1212.effortless.R
import io.github.manhvu1212.effortless.data.SyncState
import io.github.manhvu1212.effortless.ui.syncLabel
import io.github.manhvu1212.effortless.data.settings.ContentSource
import io.github.manhvu1212.effortless.data.settings.Settings
import io.github.manhvu1212.effortless.drive.DownloadWorker
import io.github.manhvu1212.effortless.drive.FolderNotFoundException
import io.github.manhvu1212.effortless.drive.NotAuthorizedException
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import kotlinx.coroutines.Dispatchers
import io.github.manhvu1212.effortless.reminders.ReminderScheduler
import io.github.manhvu1212.effortless.ui.appViewModel
import io.github.manhvu1212.effortless.ui.formatMinuteOfDay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val c: AppContainer, private val context: Context) : ViewModel() {
    val settings: StateFlow<Settings> = c.settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Settings())
    val trackCount: StateFlow<Int> = c.db.library().trackCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Lives in the app, not here: leaving Settings does not stop a sync. */
    val sync: StateFlow<SyncState> = c.sync.state

    val downloading: StateFlow<Boolean> = c.drive.downloading()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _downloadedBytes = MutableStateFlow(0L)
    val downloadedBytes: StateFlow<Long> = _downloadedBytes.asStateFlow()

    private var pendingFolder: String? = null

    init {
        refreshStorage()
        viewModelScope.launch { c.sync.state.collect { if (it is SyncState.Done) refreshStorage() } }
    }

    fun refreshStorage() = viewModelScope.launch(Dispatchers.IO) { _downloadedBytes.value = c.drive.downloadedBytes() }

    fun import(uri: Uri) = c.sync.importFolder(uri)

    fun rescan() = if (settings.value.contentSource == ContentSource.DRIVE) c.sync.syncDrive() else c.sync.rescanFolder()

    /** Asks for Drive access first; [launch] shows Google's consent screen when it is needed. */
    fun connectDrive(folderName: String, launch: (PendingIntent) -> Unit) {
        if (c.sync.running) return
        viewModelScope.launch {
            val result = runCatching { c.drive.auth.authorize() }.getOrElse {
                c.sync.fail(it)
                return@launch
            }
            val consent = result.pendingIntent
            if (result.hasResolution() && consent != null) {
                pendingFolder = folderName
                launch(consent)
            } else {
                c.sync.connectDrive(folderName)
            }
        }
    }

    fun onConsent(data: Intent?) {
        val folder = pendingFolder ?: return
        pendingFolder = null
        runCatching { c.drive.auth.resultFromIntent(data) }.exceptionOrNull()?.let {
            c.sync.fail(it)
            return
        }
        c.sync.connectDrive(folder)
    }

    fun failureMessage(e: Throwable): String =
        when {
            // No OAuth client for this package + signing key: DEVELOPER_ERROR, or INTERNAL_ERROR saying UNREGISTERED_ON_API_CONSOLE.
            e is ApiException && (e.statusCode == CommonStatusCodes.DEVELOPER_ERROR || "UNREGISTERED_ON_API_CONSOLE" in e.message.orEmpty()) ->
                context.getString(R.string.drive_error_client)
            e is ApiException && e.statusCode == CommonStatusCodes.CANCELED -> context.getString(R.string.drive_error_cancelled)
            e is FolderNotFoundException -> context.getString(R.string.drive_error_folder, e.name)
            e is NotAuthorizedException -> context.getString(R.string.drive_error_auth)
            else -> e.message ?: e.javaClass.simpleName
        }

    fun update(rescheduleReminders: Boolean = false, transform: (Settings) -> Settings) = viewModelScope.launch {
        c.settings.update(transform)
        if (rescheduleReminders) ReminderScheduler.rescheduleAll(context, c.settings.current())
    }
}

@Composable
fun SettingsScreen(contentPadding: PaddingValues) {
    val context = LocalContext.current.applicationContext
    val vm = appViewModel { SettingsViewModel(it, context) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val trackCount by vm.trackCount.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    val running = sync is SyncState.Running
    var pickTimeFor by remember { mutableStateOf<TimeTarget?>(null) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.import(uri)
    }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        vm.onConsent(result.data)
    }
    val downloading by vm.downloading.collectAsStateWithLifecycle()
    val downloadedBytes by vm.downloadedBytes.collectAsStateWithLifecycle()
    var folderName by remember(settings.driveFolderName) { mutableStateOf(settings.driveFolderName) }
    val drive = settings.contentSource == ContentSource.DRIVE

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(contentPadding),
    ) {
        item {
            Section(stringResource(R.string.settings_drive)) {
                Text(
                    if (drive) stringResource(R.string.drive_connected, settings.driveFolderName, DownloadWorker.mb(downloadedBytes))
                    else stringResource(R.string.drive_hint),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!drive) {
                    OutlinedTextField(
                        folderName,
                        { folderName = it },
                        label = { Text(stringResource(R.string.drive_folder)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (drive) {
                        Button(onClick = { vm.rescan() }, enabled = !running) { Text(stringResource(R.string.drive_sync)) }
                    } else {
                        Button(
                            onClick = {
                                vm.connectDrive(folderName) { intent ->
                                    consent.launch(IntentSenderRequest.Builder(intent.intentSender).build())
                                }
                            },
                            enabled = !running && folderName.isNotBlank(),
                        ) { Text(stringResource(R.string.drive_connect)) }
                    }
                    if (running) CircularProgressIndicator(Modifier.padding(start = 8.dp))
                }
                ScanResult(sync, vm::failureMessage)
                if (drive) {
                    if (downloading) {
                        Text(stringResource(R.string.downloading_audio), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.wifi_only), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Switch(settings.wifiOnly, { on -> vm.update { it.copy(wifiOnly = on) } })
                    }
                    Text(stringResource(R.string.ahead_lessons), style = MaterialTheme.typography.bodyMedium)
                    ChoiceRow(listOf(0, 1, 2, 3, 5), settings.aheadLessons, { "$it" }) { n ->
                        vm.update { it.copy(aheadLessons = n) }
                    }
                }
            }
        }
        item {
            Section(stringResource(R.string.settings_content)) {
                Text(
                    if (settings.contentRootUri == null || drive) stringResource(R.string.content_none)
                    else pluralStringResource(R.plurals.content_tracks, trackCount, trackCount),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(stringResource(R.string.content_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { pickFolder.launch(null) }, enabled = !running) {
                        Text(stringResource(R.string.choose_folder))
                    }
                    if (settings.contentRootUri != null && !drive) {
                        OutlinedButton(onClick = { vm.rescan() }, enabled = !running) { Text(stringResource(R.string.rescan)) }
                    }
                }
                if (!drive) ScanResult(sync, vm::failureMessage)
            }
        }
        item {
            Section(stringResource(R.string.settings_plan)) {
                Text(stringResource(R.string.days_per_lesson), style = MaterialTheme.typography.bodyMedium)
                ChoiceRow(listOf(3, 5, 7, 10, 14), settings.daysPerLesson, { "$it" }) { days ->
                    vm.update { it.copy(daysPerLesson = days) }
                }
            }
        }
        item {
            Section(stringResource(R.string.settings_reminders)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    settings.reminderTimes.forEach { minute ->
                        InputChip(
                            selected = false,
                            onClick = { pickTimeFor = TimeTarget.Replace(minute) },
                            label = { Text(formatMinuteOfDay(minute)) },
                            trailingIcon = {
                                IconButton(onClick = {
                                    vm.update(rescheduleReminders = true) { s -> s.copy(reminderTimes = s.reminderTimes - minute) }
                                }) { Icon(Icons.Default.Close, stringResource(R.string.delete)) }
                            },
                        )
                    }
                    IconButton(onClick = { pickTimeFor = TimeTarget.Add }) { Icon(Icons.Default.Add, stringResource(R.string.add_reminder)) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.nag_setting), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { pickTimeFor = TimeTarget.Nag }, enabled = settings.nagEnabled) {
                            Text(formatMinuteOfDay(settings.nagTime))
                        }
                    }
                    Switch(settings.nagEnabled, { on -> vm.update(rescheduleReminders = true) { it.copy(nagEnabled = on) } })
                }
            }
        }
        item {
            Section(stringResource(R.string.settings_player)) {
                Text(stringResource(R.string.rewind_seconds), style = MaterialTheme.typography.bodyMedium)
                ChoiceRow(listOf(3, 5, 10, 15), settings.rewindSeconds, { "${it}s" }) { seconds ->
                    vm.update { it.copy(rewindSeconds = seconds) }
                }
                Text(stringResource(R.string.shadow_gap), style = MaterialTheme.typography.bodyMedium)
                ChoiceRow(listOf(50, 100, 150, 200), settings.shadowGapPercent, { "$it%" }) { gap ->
                    vm.update { it.copy(shadowGapPercent = gap) }
                }
            }
        }
    }

    pickTimeFor?.let { target ->
        val initial = when (target) {
            TimeTarget.Add -> 8 * 60
            TimeTarget.Nag -> settings.nagTime
            is TimeTarget.Replace -> target.minute
        }
        TimeDialog(
            initialMinute = initial,
            onDismiss = { pickTimeFor = null },
            onPick = { minute ->
                vm.update(rescheduleReminders = true) { s ->
                    when (target) {
                        TimeTarget.Add -> s.copy(reminderTimes = (s.reminderTimes + minute).distinct())
                        TimeTarget.Nag -> s.copy(nagTime = minute)
                        is TimeTarget.Replace -> s.copy(reminderTimes = (s.reminderTimes - target.minute + minute).distinct())
                    }
                }
                pickTimeFor = null
            },
        )
    }
}

@Composable
private fun ScanResult(scan: SyncState, failureMessage: (Throwable) -> String) {
    when (scan) {
        is SyncState.Running -> Text(syncLabel(scan), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        is SyncState.Done -> Text(
            stringResource(
                R.string.scan_result,
                scan.summary.courses,
                scan.summary.lessons,
                scan.summary.tracks,
                scan.summary.withTranscript,
                scan.summary.withTiming,
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        is SyncState.Failed -> Text(stringResource(R.string.scan_failed, failureMessage(scan.error)), color = MaterialTheme.colorScheme.error)
        else -> Unit
    }
}

private sealed interface TimeTarget {
    data object Add : TimeTarget
    data object Nag : TimeTarget
    data class Replace(val minute: Int) : TimeTarget
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun <T> ChoiceRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(label(option)) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(initialMinute: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val state = rememberTimePickerState(initialHour = initialMinute / 60, initialMinute = initialMinute % 60, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { TimePicker(state) },
        confirmButton = { TextButton(onClick = { onPick(state.hour * 60 + state.minute) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
