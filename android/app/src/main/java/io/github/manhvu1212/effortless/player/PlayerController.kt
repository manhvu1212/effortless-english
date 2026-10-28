package io.github.manhvu1212.effortless.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import io.github.manhvu1212.effortless.data.LibraryRepository
import io.github.manhvu1212.effortless.data.StudyRepository
import io.github.manhvu1212.effortless.data.content.Transcript
import io.github.manhvu1212.effortless.data.db.AppDatabase
import io.github.manhvu1212.effortless.data.db.LessonEntity
import io.github.manhvu1212.effortless.data.db.TrackEntity
import io.github.manhvu1212.effortless.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlayerState(
    val lesson: LessonEntity? = null,
    val tracks: List<TrackEntity> = emptyList(),
    val index: Int = 0,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val loopA: Long? = null,
    val loopB: Long? = null,
    val repeatTrack: Boolean = false,
    val shadowing: Boolean = false,
    /** Set while shadowing holds the pause after a sentence, for the user to repeat it. */
    val shadowPause: Boolean = false,
) {
    val track: TrackEntity? get() = tracks.getOrNull(index)
}

/**
 * Owns the one ExoPlayer of the app. [PlaybackService] wraps it in a MediaSession for the notification and
 * background playback; screens call this class directly since everything runs in one process.
 * Must be used from the main thread.
 */
class PlayerController(
    private val context: Context,
    private val db: AppDatabase,
    private val library: LibraryRepository,
    private val study: StudyRepository,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _transcript = MutableStateFlow<Transcript?>(null)
    val transcript: StateFlow<Transcript?> = _transcript.asStateFlow()

    val player: ExoPlayer by lazy {
        ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
            .also { it.addListener(listener) }
    }

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var ticker: Job? = null
    private var shadowJob: Job? = null
    private var shadowWatch: Int? = null
    private var unflushedListenMs = 0L
    private var lastTick = 0L
    private var lastSaveAt = 0L

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying) }
            if (isPlaying) startTicker() else {
                ticker?.cancel()
                flush()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val previous = _state.value.track
            if (previous != null &&
                (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
            ) {
                scope.launch { study.setTrackListened(previous) }
            }
            _state.update { it.copy(index = player.currentMediaItemIndex, loopA = null, loopB = null, positionMs = 0) }
            shadowWatch = null
            loadTranscript()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0
                    _state.update { it.copy(durationMs = duration) }
                    val track = _state.value.track ?: return
                    if (duration > 0) scope.launch { db.library().setDuration(track.id, duration) }
                }
                Player.STATE_ENDED -> {
                    val track = _state.value.track ?: return
                    scope.launch {
                        study.setTrackListened(track)
                        db.library().setResume(track.id, 0)
                    }
                }
            }
        }
    }

    /** Loads the lesson as a playlist starting at [index], resuming where that track was left. */
    fun open(lesson: LessonEntity, tracks: List<TrackEntity>, index: Int, play: Boolean = true) {
        if (tracks.isEmpty()) return
        val current = _state.value
        if (current.lesson?.id == lesson.id && current.tracks.map { it.id } == tracks.map { it.id }) {
            if (current.index != index) player.seekTo(index, 0)
            if (play) play()
            return
        }
        scope.launch(Dispatchers.Main) {
            val resume = withContext(Dispatchers.IO) {
                tracks[index].let { db.library().ensureTrackState(it.id); db.library().trackState(it.id) }
            }
            val speed = settings.current().speed
            val items = tracks.map { track ->
                MediaItem.Builder()
                    .setMediaId(track.id)
                    .setUri(Uri.parse(track.audioUri))
                    .setMediaMetadata(
                        MediaMetadata.Builder().setTitle(track.title).setArtist(lesson.title).build()
                    )
                    .build()
            }
            _state.update { PlayerState(lesson = lesson, tracks = tracks, index = index, speed = speed, shadowing = it.shadowing) }
            val start = resume?.takeIf { it.durationMs == 0L || it.resumeMs < it.durationMs - 3_000 }?.resumeMs ?: 0
            player.setMediaItems(items, index, start)
            player.setPlaybackSpeed(speed)
            player.repeatMode = Player.REPEAT_MODE_OFF
            player.prepare()
            loadTranscript()
            if (play) play()
        }
    }

    fun play() {
        ensureService()
        cancelShadowPause()
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(player.currentMediaItemIndex, 0)
        player.play()
    }

    fun pause() {
        cancelShadowPause()
        player.pause()
    }

    fun togglePlay() = if (player.isPlaying || _state.value.shadowPause) pause() else play()

    fun seekTo(positionMs: Long) {
        cancelShadowPause()
        shadowWatch = null
        player.seekTo(positionMs.coerceAtLeast(0))
        _state.update { it.copy(positionMs = positionMs.coerceAtLeast(0)) }
    }

    fun rewind() = scope.launch(Dispatchers.Main) {
        seekTo(player.currentPosition - settings.current().rewindSeconds * 1000L)
    }

    fun forward() = scope.launch(Dispatchers.Main) {
        seekTo(player.currentPosition + settings.current().rewindSeconds * 1000L)
    }

    fun selectTrack(index: Int) {
        cancelShadowPause()
        player.seekTo(index, 0)
    }

    fun next() {
        if (player.hasNextMediaItem()) selectTrack(player.currentMediaItemIndex + 1)
    }

    fun previous() {
        if (player.currentPosition > 3_000 || !player.hasPreviousMediaItem()) seekTo(0)
        else selectTrack(player.currentMediaItemIndex - 1)
    }

    fun setSpeed(speed: Float) {
        player.setPlaybackSpeed(speed)
        _state.update { it.copy(speed = speed) }
        scope.launch { settings.update { it.copy(speed = speed) } }
    }

    /** First tap sets A, second sets B and starts looping, third clears the loop. */
    fun markLoop() {
        val position = player.currentPosition
        _state.update { s ->
            when {
                s.loopA == null -> s.copy(loopA = position)
                s.loopB == null && position > s.loopA -> s.copy(loopB = position)
                s.loopB == null -> s.copy(loopA = position)
                else -> s.copy(loopA = null, loopB = null)
            }
        }
    }

    fun loopRange(startMs: Long, endMs: Long) {
        _state.update { it.copy(loopA = startMs, loopB = endMs) }
        seekTo(startMs)
        if (!player.isPlaying) play()
    }

    fun clearLoop() = _state.update { it.copy(loopA = null, loopB = null) }

    fun toggleRepeatTrack() {
        val repeat = !_state.value.repeatTrack
        player.repeatMode = if (repeat) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        _state.update { it.copy(repeatTrack = repeat) }
    }

    fun toggleShadowing() {
        cancelShadowPause()
        shadowWatch = null
        _state.update { it.copy(shadowing = !it.shadowing) }
    }

    private fun loadTranscript() {
        val track = _state.value.track
        _transcript.value = null
        if (track == null) return
        scope.launch(Dispatchers.Main) {
            val transcript = library.loadTranscript(track)
            if (_state.value.track?.id == track.id) _transcript.value = transcript
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        lastTick = System.currentTimeMillis()
        ticker = scope.launch(Dispatchers.Main) {
            while (isActive) {
                tick()
                delay(100)
            }
        }
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        unflushedListenMs += now - lastTick
        lastTick = now
        val position = player.currentPosition
        val s = _state.value
        _state.update { it.copy(positionMs = position, durationMs = player.duration.takeIf { d -> d > 0 } ?: it.durationMs) }

        if (s.loopA != null && s.loopB != null && position >= s.loopB) {
            player.seekTo(s.loopA)
            shadowWatch = null
            return
        }
        if (s.shadowing && s.loopB == null) shadowTick(position)
        if (now - lastSaveAt > 10_000) flush()
    }

    /** Pauses at the end of each sentence for as long as the sentence lasted, scaled by the gap setting. */
    private fun shadowTick(position: Long) {
        val transcript = _transcript.value?.takeIf { it.isTimed } ?: return
        val watched = shadowWatch
        if (watched == null) {
            shadowWatch = transcript.indexAt(position).coerceAtLeast(0)
            return
        }
        val sentence = transcript.sentences.getOrNull(watched) ?: return
        if (position >= sentence.endMs!!) {
            shadowWatch = watched + 1
            player.pause()
            _state.update { it.copy(shadowPause = true) }
            shadowJob = scope.launch(Dispatchers.Main) {
                val gapPercent = settings.current().shadowGapPercent
                val length = (sentence.endMs - sentence.startMs!!) / _state.value.speed
                delay((length * gapPercent / 100).toLong().coerceIn(800, 20_000))
                _state.update { it.copy(shadowPause = false) }
                player.play()
            }
        }
    }

    private fun cancelShadowPause() {
        shadowJob?.cancel()
        shadowJob = null
        if (_state.value.shadowPause) _state.update { it.copy(shadowPause = false) }
    }

    private fun flush() {
        lastSaveAt = System.currentTimeMillis()
        val s = _state.value
        val track = s.track ?: return
        val listened = unflushedListenMs
        unflushedListenMs = 0
        val position = player.currentPosition
        scope.launch {
            if (listened > 0) study.addListened(track.lessonId, listened)
            db.library().ensureTrackState(track.id)
            db.library().setResume(track.id, position)
        }
    }

    /** Connecting a controller starts [PlaybackService], which then keeps playback alive in the background. */
    private fun ensureService() {
        val future = controllerFuture
        if (future != null && (!future.isDone || runCatching { future.get().isConnected }.getOrDefault(false))) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(context, token).buildAsync()
    }
}
