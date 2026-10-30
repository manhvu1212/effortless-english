package io.github.manhvu1212.effortless.data.content

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A word the teacher explains in a vocabulary lesson: what it means, his explanation in short,
 * and where in the vocabulary audio he gives it. [keys] are the words of the reading text that
 * open it (its forms there; a phrase's main words).
 */
@Serializable
data class GlossaryEntry(
    val term: String,
    val keys: List<String>,
    val vi: String,
    val explain: String,
    val startMs: Long,
    val endMs: Long,
)

/** `<vocabulary audio>.glossary.json`. */
@Serializable
data class GlossaryFile(val version: Int = 1, val entries: List<GlossaryEntry>)

object Glossary {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): List<GlossaryEntry>? =
        runCatching { json.decodeFromString(GlossaryFile.serializer(), text).entries }.getOrNull()

    /** Entries for a tapped [word]: "graves" opens the entry keyed "grave" and the one keyed "graves". */
    fun find(word: String, entries: List<GlossaryEntry>): List<GlossaryEntry> {
        // "couple’a" and "pullin’" are keyed by the part before the apostrophe.
        val wanted = Words.stems(word) + word.lowercase().replace('’', '\'').substringBefore('\'')
        return entries.filter { e -> e.keys.any { it.lowercase() in wanted } || e.term.lowercase() in wanted }
    }
}
