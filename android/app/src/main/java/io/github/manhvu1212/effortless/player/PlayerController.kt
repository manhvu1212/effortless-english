package io.github.manhvu1212.effortless.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import io.github.manhvu1212.effortless.data.LibraryRepository
import io.github.manhvu1212.effortless.data.StudyRepository
import io.github.manhvu1212.effortless.data.content.Transcript
import io.github.manhvu1212.effortless.data.db.AppDatabase
import io.github.manhvu1212.effortless.data.db.LessonEntity
import io.github.manhvu1212.effortless.data.db.TrackEntity
import io.github.manhvu1212.effortless.data.settings.AfterTrack
import io.github.manhvu1212.effortless.domain.StudyPlan
import io.github.manhvu1212.effortless.data.settings.SettingsRepository
import io.github.manhvu1212.effortless.drive.DriveApi
import io.github.manhvu1212.effortless.drive.DriveRepository
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** Local files as usual; Drive URLs get the user's access token, fetched on the loader thread. */
fun dataSourceFactory(context: Context, drive: DriveRepository): DataSource.Factory =
    ResolvingDataSource.Factory(DefaultDataSource.Factory(context, DefaultHttpDataSource.Factory())) { spec ->
        if (spec.uri.host != DriveApi.HOST) return@Factory spec
        val token = runBlocking { drive.auth.token() } ?: throw java.io.IOException("Google Drive is not connected")
        spec.withAdditionalHeaders(mapOf("Authorization" to "Bearer $token"))
    }

/**
 * Speaking practice while listening. ANSWER pauses after each question, briefly, so the learner
 * answers out loud and fast (the mini-story method); SHADOW pauses after every sentence for as long
 * as it lasted, to repeat it.
 */
enum class Practice { OFF, ANSWER, SHADOW }

data class Playlist(val items: List<MediaItem>, val index: Int, val startMs: Long)

/** Speeds the car's speed button steps through. */
private val CAR_SPEEDS = listOf(0.75f, 0.85f, 1f, 1.1f, 1.25f)

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
    val afterTrack: AfterTrack = AfterTrack.REPEAT,
    val practice: Practice = Practice.OFF,
    /** Set while practice holds the pause after a sentence, for the user to answer or repeat it. */
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
    private val drive: DriveRepository,
) {
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _transcript = MutableStateFlow<Transcript?>(null)
    val transcript: StateFlow<Transcript?> = _transcript.asStateFlow()

    val player: ExoPlayer by lazy {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory(context, drive)))
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
    private var pausedAtItemEnd = false

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying) }
            if (isPlaying) startTicker() else {
                ticker?.cancel()
                flush()
            }
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) syncPlaylist()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            mediaItem?.mediaId?.let { id -> scope.launch { settings.update { it.copy(lastTrackId = id) } } }
            val previous = _state.value.track
            if (previous != null &&
                (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
            ) {
                scope.launch { study.addFullListen(previous) }
            }
            _state.update { it.copy(index = player.currentMediaItemIndex, loopA = null, loopB = null, positionMs = 0) }
            shadowWatch = null
            loadTranscript()
        }

        /** In STOP mode the player pauses at the end of each track: that is a full listen too. */
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                pausedAtItemEnd = true
                val track = _state.value.track ?: return
                scope.launch {
                    study.addFullListen(track)
                    db.library().setResume(track.id, 0)
                }
            }
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
                        study.addFullListen(track)
                        db.library().setResume(track.id, 0)
                    }
                }
            }
        }
    }

    /** Loads the lesson as a playlist starting at [index], resuming where that track was left. */
    fun open(lesson: LessonEntity, lessonTracks: List<TrackEntity>, startIndex: Int, play: Boolean = true) {
        if (lessonTracks.isEmpty()) return
        // Played in the order they are studied, so "next" follows the plan.
        val tracks = StudyPlan.order(lessonTracks)
        val index = tracks.indexOf(lessonTracks[startIndex]).coerceAtLeast(0)
        val current = _state.value
        if (current.lesson?.id == lesson.id && current.tracks.map { it.id } == tracks.map { it.id }) {
            if (current.index != index) player.seekTo(index, 0)
            if (play) play()
            return
        }
        scope.launch(Dispatchers.Main) {
            val playlist = playlist(lesson, tracks, index)
            val speed = settings.current().speed
            val afterTrack = settings.current().afterTrack
            _state.update { PlayerState(lesson = lesson, tracks = tracks, index = index, speed = speed, practice = it.practice, afterTrack = afterTrack) }
            player.setMediaItems(playlist.items, playlist.index, playlist.startMs)
            player.setPlaybackSpeed(speed)
            applyAfterTrack(afterTrack)
            player.prepare()
            if (play) play()
        }
    }

    /** A lesson's tracks as player items, starting at [index] where that track was left. */
    suspend fun playlist(lesson: LessonEntity, tracks: List<TrackEntity>, index: Int): Playlist = withContext(Dispatchers.IO) {
        val track = tracks[index]
        db.library().ensureTrackState(track.id)
        val resume = db.library().trackState(track.id)
        val start = resume?.takeIf { it.durationMs == 0L || it.resumeMs < it.durationMs - 3_000 }?.resumeMs ?: 0
        Playlist(tracks.map { mediaItem(it, lesson, drive.playableUri(it)) }, index, start)
    }

    suspend fun playlistForTrack(trackId: String): Playlist? {
        val track = db.library().track(trackId) ?: return null
        val lesson = db.library().lessonOnce(track.lessonId) ?: return null
        val tracks = StudyPlan.order(db.library().tracksOnce(lesson.id))
        return playlist(lesson, tracks, tracks.indexOfFirst { it.id == trackId }.coerceAtLeast(0))
    }

    /** Starts at the track played last when it belongs to this lesson, otherwise at the first one. */
    suspend fun playlistForLesson(lessonId: String): Playlist? {
        val lesson = db.library().lessonOnce(lessonId) ?: return null
        val tracks = StudyPlan.order(db.library().tracksOnce(lessonId)).ifEmpty { return null }
        val last = settings.current().lastTrackId
        return playlist(lesson, tracks, tracks.indexOfFirst { it.id == last }.coerceAtLeast(0))
    }

    /** What to play when nothing is chosen: the track played last, else today's lesson. */
    suspend fun resumption(): Playlist? =
        settings.current().lastTrackId?.let { playlistForTrack(it) }
            ?: study.currentLesson()?.let { playlistForLesson(it.id) }

    /** Lesson or track titles containing [query]; a blank query means [resumption]. */
    suspend fun playlistForSearch(query: String): Playlist? {
        if (query.isBlank()) return resumption()
        val lessons = db.library().allLessonsOnce()
        lessons.firstOrNull { it.title.contains(query, ignoreCase = true) }?.let { return playlistForLesson(it.id) }
        for (lesson in lessons) {
            val track = db.library().tracksOnce(lesson.id).firstOrNull { it.title.contains(query, ignoreCase = true) }
            if (track != null) return playlistForTrack(track.id)
        }
        return null
    }

    private fun mediaItem(track: TrackEntity, lesson: LessonEntity, uri: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(Uri.parse(uri))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(lesson.title)
                    .setAlbumTitle(lesson.title)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
                    .setArtworkData(Artwork.png(context), MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                    .build()
            )
            .build()

    /**
     * Brings [state] in line with the player's playlist after something else set it, such as
     * Android Auto through the media session.
     */
    private fun syncPlaylist() {
        val ids = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        if (ids.isEmpty() || ids == _state.value.tracks.map { it.id }) return
        scope.launch(Dispatchers.Main) {
            val tracks = withContext(Dispatchers.IO) { ids.mapNotNull { db.library().track(it) } }
            val lesson = tracks.firstOrNull()?.let { withContext(Dispatchers.IO) { db.library().lessonOnce(it.lessonId) } } ?: return@launch
            if ((0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId } != ids) return@launch
            val speed = settings.current().speed
            val afterTrack = settings.current().afterTrack
            player.setPlaybackSpeed(speed)
            applyAfterTrack(afterTrack)
            _state.update {
                PlayerState(
                    lesson = lesson,
                    tracks = tracks,
                    index = player.currentMediaItemIndex,
                    speed = speed,
                    practice = it.practice,
                    afterTrack = afterTrack,
                )
            }
            loadTranscript()
        }
    }

    fun play() {
        ensureService()
        cancelShadowPause()
        if (player.playbackState == Player.STATE_ENDED || pausedAtItemEnd) player.seekTo(player.currentMediaItemIndex, 0)
        pausedAtItemEnd = false
        player.play()
    }

    fun pause() {
        cancelShadowPause()
        player.pause()
    }

    fun togglePlay() = if (player.isPlaying || _state.value.shadowPause) pause() else play()

    fun seekTo(positionMs: Long) {
        cancelShadowPause()
        pausedAtItemEnd = false
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
        pausedAtItemEnd = false
        player.seekTo(index, 0)
    }

    fun next() {
        if (player.hasNextMediaItem()) selectTrack(player.currentMediaItemIndex + 1)
    }

    fun previous() {
        if (player.currentPosition > 3_000 || !player.hasPreviousMediaItem()) seekTo(0)
        else selectTrack(player.currentMediaItemIndex - 1)
    }

    fun cycleSpeed() {
        val current = _state.value.speed
        setSpeed(CAR_SPEEDS.firstOrNull { it > current + 0.01f } ?: CAR_SPEEDS.first())
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

    /** Repeat the track → stop at its end → go on to the next track; remembered for next time. */
    fun cycleAfterTrack() {
        val next = AfterTrack.entries[(_state.value.afterTrack.ordinal + 1) % AfterTrack.entries.size]
        applyAfterTrack(next)
        _state.update { it.copy(afterTrack = next) }
        scope.launch { settings.update { it.copy(afterTrack = next) } }
    }

    private fun applyAfterTrack(mode: AfterTrack) {
        player.repeatMode = if (mode == AfterTrack.REPEAT) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        player.pauseAtEndOfMediaItems = mode == AfterTrack.STOP
    }

    /** Off → answer → shadow → off. */
    fun cyclePractice() {
        setPractice(Practice.entries[(_state.value.practice.ordinal + 1) % Practice.entries.size])
    }

    fun setPractice(practice: Practice) {
        cancelShadowPause()
        shadowWatch = null
        _state.update { it.copy(practice = practice) }
    }

    fun toggleShadowing() {
        cancelShadowPause()
        shadowWatch = null
        _state.update { it.copy(practice = if (it.practice == Practice.OFF) Practice.SHADOW else Practice.OFF) }
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
        if (s.practice != Practice.OFF && s.loopB == null) shadowTick(position)
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
            val answering = _state.value.practice == Practice.ANSWER
            if (answering && !isQuestion(sentence.text)) return
            player.pause()
            _state.update { it.copy(shadowPause = true) }
            shadowJob = scope.launch(Dispatchers.Main) {
                val s = settings.current()
                val pause = if (answering) {
                    s.answerGapMs.toLong()
                } else {
                    val length = (sentence.endMs - sentence.startMs!!) / _state.value.speed
                    (length * s.shadowGapPercent / 100).toLong().coerceIn(800, 20_000)
                }
                delay(pause)
                _state.update { it.copy(shadowPause = false) }
                player.play()
            }
        }
    }

    private fun isQuestion(text: String): Boolean = text.trimEnd(' ', '"', '”', '’', ')').endsWith("?")

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
