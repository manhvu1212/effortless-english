package io.github.manhvu1212.effortless

import io.github.manhvu1212.effortless.data.content.ContentNode
import io.github.manhvu1212.effortless.data.content.LibraryScanner
import io.github.manhvu1212.effortless.data.content.NaturalOrder
import io.github.manhvu1212.effortless.data.db.TrackKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private class FakeNode(
    override val name: String,
    override val children: List<ContentNode> = emptyList(),
    private val path: String = name,
    val text: String? = null,
) : ContentNode {
    override val isDirectory: Boolean get() = text == null && '.' !in name
    override val uri: String get() = "fake://$path"
}

private fun dir(name: String, vararg children: ContentNode) = FakeNode(name, children.toList())

private fun file(name: String, text: String = "") = FakeNode(name, text = text)

class LibraryScannerTest {
    @Test
    fun foldersBecomeCoursesAndLessons() {
        val root = dir(
            "EE",
            dir(
                "1 - Original",
                dir(
                    "02 Day of Fear",
                    file("Day of Fear.mp3"),
                    file("Day of Fear.txt"),
                    file("Day of Fear.timing.json"),
                    file("Day of Fear Vocab.mp3"),
                    file("Day of Fear Vocab.txt"),
                    file("Day of Fear MS.mp3"),
                    file("Day of Fear POV.mp3"),
                ),
                dir("10 Bubba", file("Bubba.mp3")),
                dir("3 Changed", file("Changed.mp3")),
            ),
            dir("2 - Power English", dir("Lesson 1", file("Main.mp3"))),
        )
        val lib = LibraryScanner.scan(root, "EE") { null }

        assertEquals(listOf("1 - Original", "2 - Power English"), lib.courses.map { it.title })
        assertEquals(listOf("02 Day of Fear", "3 Changed", "10 Bubba"), lib.lessons.filter { it.courseId == "1-original" }.map { it.title })

        val tracks = lib.tracks.filter { it.lessonId == "1-original/02-day-of-fear" }
        assertEquals(
            listOf(TrackKind.MAIN, TrackKind.VOCABULARY, TrackKind.MINI_STORY, TrackKind.POINT_OF_VIEW),
            tracks.map { it.kind },
        )
        assertEquals("fake://Day of Fear.txt", tracks[0].transcriptUri)
        assertEquals("fake://Day of Fear.timing.json", tracks[0].timingUri)
        assertEquals("fake://Day of Fear Vocab.txt", tracks[1].transcriptUri)
        assertNull(tracks[2].transcriptUri)
    }

    @Test
    fun flatFolderSplitsByLeadingNumber() {
        val root = dir(
            "Flow",
            dir(
                "Flow English",
                file("01 Rich Man.mp3"),
                file("01 Rich Man Mini Story.mp3"),
                file("01 Rich Man.txt"),
                file("02 Poor Man.mp3"),
                file("02 Poor Man.txt"),
            ),
        )
        val lib = LibraryScanner.scan(root, "Flow") { null }
        assertEquals(listOf("01 Rich Man", "02 Poor Man"), lib.lessons.map { it.title })
        val first = lib.tracks.filter { it.lessonId == lib.lessons[0].id }
        assertEquals(listOf(TrackKind.MAIN, TrackKind.MINI_STORY), first.map { it.kind })
        assertEquals("fake://01 Rich Man.txt", first[0].transcriptUri)
        assertEquals("fake://02 Poor Man.txt", lib.tracks.single { it.lessonId == lib.lessons[1].id }.transcriptUri)
    }

    @Test
    fun manifestWins() {
        val manifest = """
            {"courses":[{"id":"orig","title":"Original","lessons":[
              {"id":"l1","title":"Lesson One","tracks":[
                {"kind":"VOCABULARY","title":"Vocab","audio":"a/v.mp3","transcript":"a/v.txt"},
                {"title":"Missing","audio":"a/none.mp3"}
              ]}]}]}
        """.trimIndent()
        val root = dir("root", file("manifest.json", manifest), dir("a", file("v.mp3"), file("v.txt")))
        val lib = LibraryScanner.scan(root, "root") { (it as FakeNode).text }
        assertEquals("orig/l1", lib.lessons.single().id)
        val track = lib.tracks.single()
        assertEquals(TrackKind.VOCABULARY, track.kind)
        assertEquals("fake://v.txt", track.transcriptUri)
    }

    @Test
    fun naturalOrder() {
        assertEquals(listOf("Lesson 2", "lesson 10", "Lesson 10b"), listOf("Lesson 10b", "lesson 10", "Lesson 2").sortedWith(NaturalOrder))
    }
}
