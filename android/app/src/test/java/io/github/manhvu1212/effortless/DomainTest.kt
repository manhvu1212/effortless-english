package io.github.manhvu1212.effortless

import io.github.manhvu1212.effortless.data.content.SentenceSplitter
import io.github.manhvu1212.effortless.data.content.Transcript
import io.github.manhvu1212.effortless.domain.Grade
import io.github.manhvu1212.effortless.domain.Sm2
import io.github.manhvu1212.effortless.domain.Sm2State
import io.github.manhvu1212.effortless.domain.currentStreak
import io.github.manhvu1212.effortless.domain.longestStreak
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Sm2Test {
    @Test
    fun goodAnswersGrowTheInterval() {
        var state = Sm2State()
        val intervals = (1..4).map { state = Sm2.review(state, Grade.GOOD); state.intervalDays }
        assertEquals(listOf(1, 6), intervals.take(2))
        assertTrue(intervals[2] > 6 && intervals[3] > intervals[2])
    }

    @Test
    fun againResetsAndLowersEase() {
        val learned = Sm2State(ease = 2.5, intervalDays = 20, repetitions = 4)
        val lapsed = Sm2.review(learned, Grade.AGAIN)
        assertEquals(0, lapsed.intervalDays)
        assertEquals(0, lapsed.repetitions)
        assertEquals(1, lapsed.lapses)
        assertEquals(2.3, lapsed.ease, 1e-9)
    }

    @Test
    fun easeNeverDropsBelowMinimum() {
        var state = Sm2State(ease = 1.35)
        repeat(5) { state = Sm2.review(state, Grade.AGAIN) }
        assertEquals(Sm2.MIN_EASE, state.ease, 1e-9)
    }

    @Test
    fun hardStillMovesForward() {
        val state = Sm2State(ease = 1.3, intervalDays = 2, repetitions = 3)
        assertEquals(3, Sm2.review(state, Grade.HARD).intervalDays)
    }
}

class StreakTest {
    @Test
    fun countsBackFromToday() = assertEquals(3, currentStreak(listOf(10, 9, 8, 5), today = 10))

    @Test
    fun todayNotStudiedYetKeepsYesterdaysStreak() = assertEquals(2, currentStreak(listOf(9, 8), today = 10))

    @Test
    fun gapBreaksStreak() = assertEquals(0, currentStreak(listOf(7, 6), today = 10))

    @Test
    fun longest() = assertEquals(3, longestStreak(listOf(1, 2, 3, 7, 8)))
}

class TranscriptTest {
    @Test
    fun splitsSentencesAndParagraphs() {
        val text = Char(0xFEFF) + "Hello there. How are you? I'm \"fine!\" Thanks.\r\n\r\nMr. Smith went home.\nHe was 3.5 m tall."
        val paragraphs = SentenceSplitter.paragraphs(text)
        assertEquals(2, paragraphs.size)
        assertEquals(listOf("Hello there.", "How are you?", "I'm \"fine!\"", "Thanks."), SentenceSplitter.split(paragraphs[0]))
        val t = Transcript.fromPlainText(text)
        assertFalse(t.isTimed)
        assertEquals(listOf("Mr. Smith went home.", "He was 3.5 m tall."), SentenceSplitter.split(paragraphs[1]))
        assertTrue(t.sentences.first { it.text.startsWith("Mr.") }.paragraphStart)
    }

    @Test
    fun indexAtFindsPlayingSentence() {
        val json = """{"sentences":[{"s":0,"e":900,"t":"A."},{"s":1000,"e":1900,"t":"B."},{"s":2000,"e":2900,"t":"C.","p":true}]}"""
        val t = Transcript.fromTiming(json)
        assertTrue(t.isTimed)
        assertEquals(0, t.indexAt(0))
        assertEquals(1, t.indexAt(1500))
        assertEquals(2, t.indexAt(99_000))
        assertTrue(t.sentences[2].paragraphStart)
    }
}
