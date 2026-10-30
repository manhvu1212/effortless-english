package io.github.manhvu1212.effortless

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaConstants
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.manhvu1212.effortless.data.db.CourseEntity
import io.github.manhvu1212.effortless.data.db.LessonEntity
import io.github.manhvu1212.effortless.data.db.TrackEntity
import io.github.manhvu1212.effortless.data.db.TrackKind
import io.github.manhvu1212.effortless.player.MediaTree
import io.github.manhvu1212.effortless.player.PlaybackService
import io.github.manhvu1212.effortless.player.Practice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile

/** Plays the part of Android Auto: a MediaBrowser talking to [PlaybackService] the way the car does. */
@RunWith(AndroidJUnit4::class)
class CarMediaLibraryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val c = context.container
    private lateinit var browser: MediaBrowser

    private val main = "c/l1/main"
    private val miniStory = "c/l1/ms"
    private val bubba = "c/l2/main"

    @Before
    fun setUp() = runBlocking {
        val audio = silentWav(File(context.cacheDir, "silence.wav"), seconds = 3)
        val uri = Uri.fromFile(audio).toString()
        c.db.library().replaceLibrary(
            listOf(CourseEntity("c", "Test Course", 0)),
            listOf(LessonEntity("c/l1", "c", "Day of Fear", 0), LessonEntity("c/l2", "c", "Bubba", 1)),
            listOf(
                TrackEntity(main, "c/l1", TrackKind.MAIN, "Day of Fear", 0, uri, null, null),
                TrackEntity(miniStory, "c/l1", TrackKind.MINI_STORY, "Day of Fear MS", 1, uri, null, null),
                TrackEntity(bubba, "c/l2", TrackKind.MAIN, "Bubba", 0, uri, null, null),
            ),
        )
        listOf("c/l1", "c/l2").forEach { c.study.reopen(it) }
        c.settings.update { it.copy(lastTrackId = null, pinnedLessonId = null, speed = 1f) }
        withContext(Dispatchers.Main) {
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            browser = MediaBrowser.Builder(context, token).buildAsync().await()
        }
    }

    @After
    fun tearDown() = runBlocking {
        withContext(Dispatchers.Main) {
            browser.stop()
            browser.release()
        }
    }

    @Test
    fun browseTree() = onMain {
        val root = browser.getLibraryRoot(null).await()
        assertEquals(MediaTree.ROOT, root.value!!.mediaId)
        assertEquals(
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
            root.params!!.extras.getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE),
        )

        val tabs = children(MediaTree.ROOT)
        assertEquals(listOf(MediaTree.TODAY, MediaTree.COURSES), tabs.map { it.mediaId })
        assertEquals("Day of Fear", tabs[0].mediaMetadata.subtitle)

        val today = children(MediaTree.TODAY)
        assertEquals(listOf(main, miniStory), today.map { it.mediaId })
        assertEquals(listOf("Bài chính", "Mini-Story"), today.map { it.mediaMetadata.title.toString() })
        assertTrue(today.all { it.mediaMetadata.isPlayable == true })

        val courses = children(MediaTree.COURSES)
        assertEquals("Đã xong 0/2 bài", courses.single().mediaMetadata.subtitle)
        val lessons = children(courses.single().mediaId)
        assertEquals(listOf("Day of Fear", "Bubba"), lessons.map { it.mediaMetadata.title.toString() })
        assertEquals(listOf(bubba), children(lessons[1].mediaId).map { it.mediaId })

        assertEquals(LibraryResult.RESULT_ERROR_BAD_VALUE, browser.getChildren("@nope", 0, 10, null).await().resultCode)
        assertEquals("Bài chính", browser.getItem(main).await().value!!.mediaMetadata.title)
    }

    @Test
    fun search() = onMain {
        browser.search("bubba", null).await()
        val results = browser.getSearchResult("bubba", 0, 10, null).await().value!!
        assertEquals(listOf(bubba), results.map { it.mediaId })
    }

    @Test
    fun choosingATrackQueuesTheWholeLessonAndSyncsThePhone() = onMain {
        browser.setMediaItem(MediaItem.Builder().setMediaId(miniStory).build())
        browser.prepare()
        waitFor { browser.currentMediaItem?.mediaId == miniStory }
        waitFor { c.player.state.value.tracks.size == 2 }
        assertEquals(2, browser.mediaItemCount)
        assertEquals(1, browser.currentMediaItemIndex)
        assertEquals(listOf(main, miniStory), c.player.state.value.tracks.map { it.id })
        assertEquals(1, c.player.state.value.index)
    }

    @Test
    fun voiceQueryFindsTheLesson() = onMain {
        val spoken = MediaItem.Builder()
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery("bubba").build())
            .build()
        browser.setMediaItem(spoken)
        browser.prepare()
        waitFor { browser.currentMediaItem?.mediaId == bubba }
        waitFor { c.player.state.value.lesson?.id == "c/l2" }
    }

    @Test
    fun blankVoiceQueryPlaysTodaysLesson() = onMain {
        val spoken = MediaItem.Builder()
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery("").build())
            .build()
        browser.setMediaItem(spoken)
        browser.prepare()
        waitFor { browser.currentMediaItem?.mediaId == main }
        waitFor { c.player.state.value.lesson?.id == "c/l1" }
    }

    @Test
    fun carButtons() = onMain {
        browser.setMediaItem(MediaItem.Builder().setMediaId(main).build())
        browser.prepare()
        waitFor { browser.currentMediaItem?.mediaId == main }

        val buttons = browser.mediaButtonPreferences
        assertEquals(4, buttons.size)
        assertEquals("Tốc độ 1x", buttons.single { it.sessionCommand?.customAction?.endsWith("SPEED") == true }.displayName)

        browser.sendCustomCommand(SessionCommand("io.github.manhvu1212.effortless.SPEED", Bundle.EMPTY), Bundle.EMPTY).await()
        assertEquals(1.1f, c.player.state.value.speed, 0.001f)
        browser.sendCustomCommand(SessionCommand("io.github.manhvu1212.effortless.SHADOWING", Bundle.EMPTY), Bundle.EMPTY).await()
        assertTrue(c.player.state.value.practice != Practice.OFF)
        waitFor { browser.mediaButtonPreferences.any { it.displayName == "Tốc độ 1.1x" } }

        // Leave the shared player as found.
        c.player.setPractice(Practice.OFF)
        c.player.setSpeed(1f)
    }

    private suspend fun children(parentId: String): List<MediaItem> =
        browser.getChildren(parentId, 0, 100, null).await().value!!.toList()

    private fun onMain(block: suspend () -> Unit) = runBlocking { withContext(Dispatchers.Main) { block() } }

    private suspend fun waitFor(condition: () -> Boolean) {
        repeat(50) {
            if (condition()) return
            delay(100)
        }
        assertTrue("condition not met within 5 s", condition())
    }

    /** 8 kHz mono 16-bit PCM silence. */
    private fun silentWav(file: File, seconds: Int): File {
        val rate = 8000
        val dataSize = rate * 2 * seconds
        RandomAccessFile(file, "rw").use { f ->
            f.setLength(0)
            fun intLe(v: Int) = f.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
            fun shortLe(v: Int) = f.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
            f.writeBytes("RIFF"); intLe(36 + dataSize); f.writeBytes("WAVE")
            f.writeBytes("fmt "); intLe(16); shortLe(1); shortLe(1); intLe(rate); intLe(rate * 2); shortLe(2); shortLe(16)
            f.writeBytes("data"); intLe(dataSize)
            f.write(ByteArray(dataSize))
        }
        return file
    }
}
