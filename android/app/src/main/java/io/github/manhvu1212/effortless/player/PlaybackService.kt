package io.github.manhvu1212.effortless.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.manhvu1212.effortless.MainActivity
import io.github.manhvu1212.effortless.R
import io.github.manhvu1212.effortless.container
import io.github.manhvu1212.effortless.data.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch

/**
 * Background playback, the media notification and Android Auto. The car browses [MediaTree] and
 * plays through the same [PlayerController] player as the phone, so progress is recorded either way.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var session: MediaLibrarySession? = null

    /** Sent with every new connection, since the car may connect before the first update below. */
    private var currentButtons: List<CommandButton> = emptyList()

    override fun onCreate() {
        super.onCreate()
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_PLAYER, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val c = container
        c.player.state.value.let { currentButtons = buttons(it.speed, it.practice, Settings().rewindSeconds) }
        session = MediaLibrarySession.Builder(this, c.player.player, Callback(MediaTree(this, c)))
            .setSessionActivity(openApp)
            .setMediaButtonPreferences(currentButtons)
            .build()

        // Car and notification buttons show the current speed and practice mode.
        scope.launch {
            combine(c.player.state, c.settings.settings) { state, settings ->
                Triple(state.speed, state.practice, settings.rewindSeconds)
            }.distinctUntilChanged().collect { (speed, practice, seconds) ->
                currentButtons = buttons(speed, practice, seconds)
                session?.setMediaButtonPreferences(currentButtons)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    /** Releases the session only: the player belongs to [PlayerController] and outlives the service. */
    override fun onDestroy() {
        scope.cancel()
        session?.release()
        session = null
        super.onDestroy()
    }

    private fun buttons(speed: Float, practice: Practice, rewindSeconds: Int): List<CommandButton> = listOf(
        CommandButton.Builder(
            when (rewindSeconds) {
                5 -> CommandButton.ICON_SKIP_BACK_5
                10 -> CommandButton.ICON_SKIP_BACK_10
                15 -> CommandButton.ICON_SKIP_BACK_15
                else -> CommandButton.ICON_SKIP_BACK
            }
        )
            .setDisplayName(getString(R.string.rewind))
            .setSessionCommand(REWIND)
            .setSlots(CommandButton.SLOT_BACK)
            .build(),
        CommandButton.Builder(
            when (rewindSeconds) {
                5 -> CommandButton.ICON_SKIP_FORWARD_5
                10 -> CommandButton.ICON_SKIP_FORWARD_10
                15 -> CommandButton.ICON_SKIP_FORWARD_15
                else -> CommandButton.ICON_SKIP_FORWARD
            }
        )
            .setDisplayName(getString(R.string.forward))
            .setSessionCommand(FORWARD)
            .setSlots(CommandButton.SLOT_FORWARD)
            .build(),
        CommandButton.Builder(CommandButton.ICON_PLAYBACK_SPEED)
            .setDisplayName(getString(R.string.car_speed, speedLabel(speed)))
            .setSessionCommand(SPEED)
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build(),
        CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setCustomIconResId(if (practice != Practice.OFF) R.drawable.ic_shadowing_on else R.drawable.ic_shadowing_off)
            .setDisplayName(getString(practiceLabel(practice)))
            .setSessionCommand(SHADOWING)
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build(),
    )

    private inner class Callback(private val tree: MediaTree) : MediaLibrarySession.Callback {
        private val player get() = container.player

        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(REWIND).add(FORWARD).add(SPEED).add(SHADOWING)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(commands)
                .setMediaButtonPreferences(currentButtons)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                REWIND.customAction -> player.rewind()
                FORWARD.customAction -> player.forward()
                SPEED.customAction -> player.cycleSpeed()
                SHADOWING.customAction -> player.cyclePractice()
                else -> return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val root = if (params?.isRecent == true) tree.recentRoot() else tree.root()
            return Futures.immediateFuture(LibraryResult.ofItem(root, LibraryParams.Builder().setExtras(tree.rootExtras()).build()))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val children = tree.children(parentId)
                ?: return@future LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
            LibraryResult.ofItemList(children.page(page, pageSize), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            tree.item(mediaId)?.let { LibraryResult.ofItem(it, null) }
                ?: LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = scope.future {
            session.notifySearchResultChanged(browser, query, tree.search(query).size, params)
            LibraryResult.ofVoid()
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            LibraryResult.ofItemList(tree.search(query).page(page, pageSize), params)
        }

        /**
         * The car sends bare ids or a voice query. A chosen track plays with the rest of its lesson
         * from where it was left; "play Effortless English" resumes; a spoken title finds the lesson.
         */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val first = mediaItems.firstOrNull()
            val query = first?.requestMetadata?.searchQuery
            val playlist = when {
                first == null -> null
                // An item without an id is a request to play something: a voice query, possibly blank.
                query != null || first.mediaId.isEmpty() -> player.playlistForSearch(query.orEmpty())
                first.mediaId.startsWith(MediaTree.LESSON) -> player.playlistForLesson(first.mediaId.removePrefix(MediaTree.LESSON))
                mediaItems.size == 1 -> player.playlistForTrack(first.mediaId)
                else -> null
            }
            if (playlist != null) {
                MediaSession.MediaItemsWithStartPosition(playlist.items, playlist.index, playlist.startMs)
            } else {
                // A whole queue handed back (e.g. restored by the controller): resolve each id.
                val resolved = mediaItems.mapNotNull { player.playlistForTrack(it.mediaId)?.let { p -> p.items[p.index] } }
                MediaSession.MediaItemsWithStartPosition(resolved, startIndex.coerceIn(0, (resolved.size - 1).coerceAtLeast(0)), startPositionMs)
            }
        }

        /** [isForPlayback] false means the system only wants metadata, e.g. for its resumption card. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val playlist = player.resumption() ?: throw UnsupportedOperationException("Nothing to resume")
            MediaSession.MediaItemsWithStartPosition(playlist.items, playlist.index, playlist.startMs)
        }
    }

    companion object {
        /** The button names the mode a tap switches to. */
        private fun practiceLabel(current: Practice): Int = when (current) {
            Practice.OFF -> R.string.car_practice_answer
            Practice.ANSWER -> R.string.car_practice_shadow
            Practice.SHADOW -> R.string.car_practice_off
        }

        private val REWIND = SessionCommand("io.github.manhvu1212.effortless.REWIND", Bundle.EMPTY)
        private val FORWARD = SessionCommand("io.github.manhvu1212.effortless.FORWARD", Bundle.EMPTY)
        private val SPEED = SessionCommand("io.github.manhvu1212.effortless.SPEED", Bundle.EMPTY)
        private val SHADOWING = SessionCommand("io.github.manhvu1212.effortless.SHADOWING", Bundle.EMPTY)

        private fun speedLabel(speed: Float): String = "${if (speed % 1f == 0f) speed.toInt() else speed}x"

        private fun <T> List<T>.page(page: Int, pageSize: Int): ImmutableList<T> {
            if (pageSize <= 0 || pageSize == Int.MAX_VALUE) return ImmutableList.copyOf(this)
            val from = (page * pageSize).coerceAtMost(size)
            return ImmutableList.copyOf(subList(from, (from + pageSize).coerceAtMost(size)))
        }
    }
}
