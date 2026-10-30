package io.github.manhvu1212.effortless

import io.github.manhvu1212.effortless.data.content.Sentence
import io.github.manhvu1212.effortless.data.content.Words
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WordsTest {
    @Test
    fun wordAtTap() {
        val text = "I’m curious about this holiday, so I go."
        assertEquals("I’m", Words.wordAt(text, 1))
        assertEquals("holiday", Words.wordAt(text, text.indexOf("holiday") + 3))
        assertEquals("holiday", Words.wordAt(text, text.indexOf(",")))
        assertNull(Words.wordAt(text, text.indexOf(" so") ))
    }

    @Test
    fun findsInflectedUses() {
        fun s(i: Int, t: String) = Sentence(i, t, null, null, false)
        val vocab = listOf(
            s(0, "Okay, this is the vocabulary for day of the dead."),
            s(1, "A grave is the place where a dead person is buried."),
            s(2, "Admire means to look at something and like it."),
            s(3, "They were admiring the flowers."),
            s(4, "Gravel is small stones."),
        )
        assertEquals(listOf(1), Words.sentencesUsing("graves", vocab))
        assertEquals(listOf(2, 3), Words.sentencesUsing("admire", vocab))
        assertEquals(listOf(0, 1), Words.sentencesUsing("dead", vocab))
    }
}
