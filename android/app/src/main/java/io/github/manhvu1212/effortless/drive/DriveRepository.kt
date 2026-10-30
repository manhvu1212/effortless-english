package io.github.manhvu1212.effortless.drive

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.github.manhvu1212.effortless.data.ScanSummary
import io.github.manhvu1212.effortless.data.StudyRepository
import io.github.manhvu1212.effortless.data.SyncProgress
import io.github.manhvu1212.effortless.data.SyncStage
import io.github.manhvu1212.effortless.data.content.LibraryScanner
import io.github.manhvu1212.effortless.data.db.AppDatabase
import io.github.manhvu1212.effortless.data.db.RemoteFileEntity
import io.github.manhvu1212.effortless.data.db.TrackEntity
import io.github.manhvu1212.effortless.data.settings.ContentSource
import io.github.manhvu1212.effortless.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class FolderNotFoundException(val name: String) : IOException("No Drive folder named \"$name\"")

/**
 * Content kept in the user's Google Drive (the package written by tools/prepare_content.py).
 * Syncing fetches the small files (manifest, transcripts, timings) at once; audio is downloaded per
 * lesson in the background and streamed from Drive until then.
 */
class DriveRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val study: StudyRepository,
) {
    val auth = DriveAuth(context)
    private val api = DriveApi(auth)

    /** Mirror of the Drive folder: the same relative paths as in manifest.json. */
    val root: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "content")

    suspend fun connect(folderName: String, progress: SyncProgress = { _, _, _ -> }): ScanSummary {
        progress(SyncStage.LISTING, 0, 0)
        val id = api.findFolder(folderName.trim()) ?: throw FolderNotFoundException(folderName.trim())
        settings.update { it.copy(driveFolderId = id, driveFolderName = folderName.trim()) }
        return sync(progress)
    }

    suspend fun sync(progress: SyncProgress = { _, _, _ -> }): ScanSummary = withContext(Dispatchers.IO) {
        val folderId = settings.current().driveFolderId ?: throw FolderNotFoundException(settings.current().driveFolderName)
        progress(SyncStage.LISTING, 0, 0)
        val entries = api.listTree(folderId)
        db.remoteFiles().replaceAll(entries.map { RemoteFileEntity(it.path, it.id, it.size, it.md5) })
        val small = entries.filter { isSmall(it.path) }
        val done = AtomicInteger()
        progress(SyncStage.FILES, 0, small.size)
        val limit = Semaphore(DriveApi.PARALLEL)
        coroutineScope {
            small.map { entry ->
                async {
                    val file = File(root, entry.path)
                    if (!file.exists() || file.length() != entry.size) limit.withPermit { api.download(entry.id, file) }
                    progress(SyncStage.FILES, done.incrementAndGet(), small.size)
                }
            }.awaitAll()
        }
        progress(SyncStage.BUILDING, 0, 0)
        val manifest = File(root, LibraryScanner.MANIFEST).takeIf { it.exists() }
            ?: throw IOException("${LibraryScanner.MANIFEST} is missing from the Drive folder")
        val library = LibraryScanner.fromManifest(LibraryScanner.parseManifest(manifest.readText())) { path ->
            Uri.fromFile(File(root, path)).toString()
        }
        db.library().replaceLibrary(library.courses, library.lessons, library.tracks)
        settings.update { it.copy(contentSource = ContentSource.DRIVE) }
        ensureAhead()
        ScanSummary(
            courses = library.courses.size,
            lessons = library.lessons.size,
            tracks = library.tracks.size,
            withTranscript = library.tracks.count { it.transcriptUri != null },
            withTiming = library.tracks.count { it.timingUri != null },
        )
    }

    fun localFile(track: TrackEntity): File? =
        Uri.parse(track.audioUri).takeIf { it.scheme == "file" }?.path?.let(::File)?.takeIf { it.startsWith(root) }

    fun isDownloaded(track: TrackEntity): Boolean = localFile(track)?.exists() ?: true

    /** The local copy when there is one, otherwise the Drive URL the player streams with a token. */
    suspend fun playableUri(track: TrackEntity): String {
        val file = localFile(track) ?: return track.audioUri
        if (file.exists()) return track.audioUri
        val remote = db.remoteFiles().byPath(relativePath(file)) ?: return track.audioUri
        return DriveApi.mediaUrl(remote.driveId)
    }

    /** Downloads the audio of [lessonId] that is missing locally; [onBytes] gets the running total. */
    suspend fun downloadLesson(lessonId: String, onBytes: suspend (Long) -> Unit = {}) = withContext(Dispatchers.IO) {
        var done = 0L
        for (track in db.library().tracksOnce(lessonId)) {
            val file = localFile(track) ?: continue
            val remote = db.remoteFiles().byPath(relativePath(file)) ?: continue
            if (file.exists() && file.length() == remote.size) {
                done += remote.size
                continue
            }
            api.download(remote.driveId, file) { onBytes(done + it) }
            done += remote.size
        }
    }

    /** Bytes still to download for these lessons. */
    suspend fun missingBytes(lessonIds: List<String>): Long = withContext(Dispatchers.IO) {
        lessonIds.flatMap { db.library().tracksOnce(it) }.sumOf { track ->
            val file = localFile(track) ?: return@sumOf 0L
            if (file.exists()) 0L else db.remoteFiles().byPath(relativePath(file))?.size ?: 0L
        }
    }

    suspend fun deleteLesson(lessonId: String) = withContext(Dispatchers.IO) {
        db.library().tracksOnce(lessonId).forEach { localFile(it)?.delete() }
    }

    fun downloadedBytes(): Long = root.walkTopDown().filter { it.isFile && !isSmall(it.name) }.sumOf { it.length() }

    /** Queues lessons for download; the queue runs one job at a time, in order. */
    suspend fun enqueue(lessonIds: List<String>) {
        if (lessonIds.isEmpty()) return
        val wifiOnly = settings.current().wifiOnly
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_LESSONS to lessonIds.toTypedArray()))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresStorageNotLow(true)
                    .build()
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(QUEUE, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** Keeps the current lesson and the next few downloaded. */
    suspend fun ensureAhead() {
        val s = settings.current()
        if (s.contentSource != ContentSource.DRIVE) return
        val lessons = db.library().allLessonsOnce()
        val current = study.currentLesson() ?: return
        val from = lessons.indexOfFirst { it.id == current.id }.coerceAtLeast(0)
        val wanted = lessons.drop(from).take(1 + s.aheadLessons).map { it.id }
        val missing = wanted.filter { id -> db.library().tracksOnce(id).any { !isDownloaded(it) } }
        enqueue(missing)
    }

    /** True while any download is queued or running. */
    fun downloading(): Flow<Boolean> =
        WorkManager.getInstance(context).getWorkInfosByTagFlow(TAG).map { infos ->
            infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
        }

    private fun relativePath(file: File): String = file.relativeTo(root).invariantSeparatorsPath

    private fun isSmall(path: String): Boolean =
        path.endsWith(LibraryScanner.MANIFEST) || path.endsWith(".txt") || path.endsWith(".json")

    companion object {
        private const val QUEUE = "audio-downloads"
        const val TAG = "audio-download"
    }
}
