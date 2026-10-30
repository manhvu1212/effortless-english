package io.github.manhvu1212.effortless

import io.github.manhvu1212.effortless.data.db.TrackEntity
import io.github.manhvu1212.effortless.data.db.TrackKind
import io.github.manhvu1212.effortless.domain.StudyPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyPlanTest {
    private fun track(kind: TrackKind, title: String, position: Int) =
        TrackEntity("l/$title", "l", kind, title, position, "file:///$title.mp3", null, null)

    @Test
    fun effortlessOrderPutsTheAudioAfterTheStories() {
        val tracks = listOf(
            track(TrackKind.MAIN, "Day of the Dead Audio", 0),
            track(TrackKind.VOCABULARY, "Day of the Dead Vocab", 1),
            track(TrackKind.MINI_STORY, "Day of the Dead MS", 2),
            track(TrackKind.POINT_OF_VIEW, "Day of the Dead POV", 3),
            track(TrackKind.COMMENTARY, "Day of the Dead Commentary", 4),
        )
        assertEquals(
            listOf(TrackKind.VOCABULARY, TrackKind.MINI_STORY, TrackKind.POINT_OF_VIEW, TrackKind.MAIN, TrackKind.COMMENTARY),
            StudyPlan.order(tracks).map { it.kind },
        )
    }

    @Test
    fun learnRealEnglishStartsWithTheConversation() {
        val tracks = listOf(
            track(TrackKind.MAIN, "Earthquakes Conversation", 0),
            track(TrackKind.VOCABULARY, "Earthquakes Vocabulary", 1),
            track(TrackKind.MINI_STORY, "Earthquakes MS", 2),
        )
        assertEquals(listOf(TrackKind.MAIN, TrackKind.VOCABULARY, TrackKind.MINI_STORY), StudyPlan.order(tracks).map { it.kind })
    }

    @Test
    fun sameKindKeepsFileOrder() {
        val tracks = listOf(track(TrackKind.MINI_STORY, "MS-B", 2), track(TrackKind.MINI_STORY, "MS-A", 1))
        assertEquals(listOf("MS-A", "MS-B"), StudyPlan.order(tracks).map { it.title })
    }

    @Test
    fun targetsAndOptionalSteps() {
        assertEquals(3, StudyPlan.target(TrackKind.MINI_STORY))
        assertEquals(2, StudyPlan.target(TrackKind.VOCABULARY))
        assertTrue(StudyPlan.optional(TrackKind.COMMENTARY))
        assertFalse(StudyPlan.optional(TrackKind.MAIN))
    }
}
