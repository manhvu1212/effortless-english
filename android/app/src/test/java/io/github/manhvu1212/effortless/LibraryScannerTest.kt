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
    override val isDirectory: Boolean,
    override val children: List<ContentNode> = emptyList(),
    val text: String? = null,
) : ContentNode {
    override val uri: String get() = "fake://$name"
}

private fun dir(name: String, vararg children: ContentNode) = FakeNode(name, isDirectory = true, children = children.toList())

private fun file(name: String, text: String = "") = FakeNode(name, isDirectory = false, text = text)

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

    /** Names as they are on the course's Google Drive. */
    @Test
    fun realCourseNames() {
        val root = dir(
            "Effortless English",
            dir(
                "1. Original English",
                dir("3.10  MS Censorship", file("MS Censorship.mp3"), file("MS Censorship Vocab.mp3"), file("MS Censorship Commentary.mp3"), file("MS Censorship.txt")),
                dir("4.02 Hitch", file("Hitch 2.mp3"), file("Hitch 2 POV-MS.mp3")),
                dir("3.06 Lifestyle Diseases", file("Lifestyle Diseases.mp3"), file("Lifestyle Diseases Ministor.mp3")),
            ),
            dir(
                "5. Power English",
                dir("05_Thought_Mastery", file("1_Thought_Mastery.mp3"), file("2_Thought_Mastery.mp3"), file("3_Thought_Mastery_MINI_STORY.mp3")),
            ),
            dir("3. Flow English", dir("17_movie_star_lessons", file("Movie Star.mp3"), file("Movie Star MSI.mp3"), file("Movie Star MSII.mp3"))),
            dir("6. Global Leadership Program", dir("ComfortZoneAudios", file("ComfortZoneMS.mp3"), file("ComfortZonePOV.mp3"))),
        )
        val lib = LibraryScanner.scan(root, "Effortless English") { null }
        fun kinds(lesson: String) = lib.tracks.filter { it.lessonId.endsWith(lesson) }.associate { it.title to it.kind }

        assertEquals(
            mapOf("MS Censorship" to TrackKind.MAIN, "MS Censorship Vocab" to TrackKind.VOCABULARY, "MS Censorship Commentary" to TrackKind.COMMENTARY),
            kinds("ms-censorship"),
        )
        assertEquals("fake://MS Censorship.txt", lib.tracks.single { it.title == "MS Censorship" }.transcriptUri)
        assertEquals(TrackKind.POINT_OF_VIEW, kinds("hitch")["Hitch 2 POV-MS"])
        assertEquals(TrackKind.MINI_STORY, kinds("lifestyle-diseases")["Lifestyle Diseases Ministor"])
        // One lesson, not three: the leading numbers order the tracks inside a lesson folder.
        assertEquals(1, lib.lessons.count { it.courseId == "5-power-english" })
        assertEquals(
            listOf(TrackKind.MAIN, TrackKind.VOCABULARY, TrackKind.MINI_STORY),
            lib.tracks.filter { it.lessonId.startsWith("5-power-english") }.map { it.kind },
        )
        assertEquals(listOf(TrackKind.MAIN, TrackKind.MINI_STORY, TrackKind.MINI_STORY), kinds("17-movie-star-lessons").values.toList())
        assertEquals(setOf(TrackKind.MINI_STORY, TrackKind.POINT_OF_VIEW), kinds("comfortzoneaudios").values.toSet())
    }

    @Test
    fun naturalOrder() {
        assertEquals(listOf("Lesson 2", "lesson 10", "Lesson 10b"), listOf("Lesson 10b", "lesson 10", "Lesson 2").sortedWith(NaturalOrder))
    }
}
