package io.github.manhvu1212.effortless.data

import android.net.Uri
import io.github.manhvu1212.effortless.drive.DriveRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class SyncStage { LISTING, FILES, BUILDING, SCANNING }

sealed interface SyncState {
    data object Idle : SyncState
    data class Running(val stage: SyncStage, val done: Int = 0, val total: Int = 0) : SyncState
    data class Done(val summary: ScanSummary) : SyncState
    data class Failed(val error: Throwable) : SyncState
}

/** Reports a sync's progress: the stage, and for [SyncStage.FILES] how many of how many files. */
typealias SyncProgress = (stage: SyncStage, done: Int, total: Int) -> Unit

/**
 * Library refreshes (Drive sync, folder scan) run here, in the application's scope, so they keep
 * going when the user leaves Settings; every screen can show [state].
 */
class LibrarySync(
    private val scope: CoroutineScope,
    private val library: LibraryRepository,
    private val drive: DriveRepository,
) {
    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private var job: Job? = null

    val running: Boolean get() = _state.value is SyncState.Running

    fun connectDrive(folderName: String) = start { drive.connect(folderName, ::report) }

    fun syncDrive() = start { drive.sync(::report) }

    fun importFolder(uri: Uri) = start {
        report(SyncStage.SCANNING, 0, 0)
        library.importTree(uri)
    }

    fun rescanFolder() = start {
        report(SyncStage.SCANNING, 0, 0)
        library.rescan()
    }

    /** For failures before a sync could start, such as Google refusing Drive access. */
    fun fail(error: Throwable) {
        if (!running) _state.value = SyncState.Failed(error)
    }

    private fun report(stage: SyncStage, done: Int, total: Int) {
        _state.value = SyncState.Running(stage, done, total)
    }

    /** Ignored while another refresh runs. */
    private fun start(block: suspend () -> ScanSummary?): Boolean {
        if (job?.isActive == true) return false
        _state.value = SyncState.Running(SyncStage.LISTING)
        job = scope.launch {
            _state.value = runCatching { block() }.fold(
                onSuccess = { summary -> summary?.let { SyncState.Done(it) } ?: SyncState.Idle },
                onFailure = { SyncState.Failed(it) },
            )
        }
        return true
    }
}
