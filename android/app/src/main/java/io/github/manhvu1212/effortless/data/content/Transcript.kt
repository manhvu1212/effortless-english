package io.github.manhvu1212.effortless.data.content

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** `<audio>.timing.json`, written by tools/align.py: one entry per sentence, times in milliseconds. */
@Serializable
data class TimingFile(val version: Int = 1, val sentences: List<TimedSentence>) {
    /** [p] marks the first sentence of a paragraph. */
    @Serializable
    data class TimedSentence(val s: Long, val e: Long, val t: String, val p: Boolean = false)
}

/** `<audio>.vi.json`: a Vietnamese translation, one entry per paragraph of the transcript. */
@Serializable
data class TranslationFile(val version: Int = 1, val paragraphs: List<String>)

data class Sentence(
    val index: Int,
    val text: String,
    val startMs: Long?,
    val endMs: Long?,
    val paragraphStart: Boolean,
)

data class Transcript(val sentences: List<Sentence>) {
    val isTimed: Boolean = sentences.isNotEmpty() && sentences.all { it.startMs != null && it.endMs != null }

    /** Sentences grouped into paragraphs, as the reading page shows them. */
    val paragraphs: List<List<Sentence>> by lazy {
        val out = mutableListOf<MutableList<Sentence>>()
        sentences.forEach { if (it.paragraphStart || out.isEmpty()) out += mutableListOf(it) else out.last() += it }
        out
    }

    /** Index of the sentence playing at [positionMs], or -1 before the first one. */
    fun indexAt(positionMs: Long): Int {
        if (!isTimed) return -1
        var low = 0
        var high = sentences.lastIndex
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (sentences[mid].startMs!! <= positionMs) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Paragraph translations, or null when they do not line up with [paragraphCount]. */
        fun translation(text: String, paragraphCount: Int): List<String>? =
            runCatching { json.decodeFromString(TranslationFile.serializer(), text).paragraphs }
                .getOrNull()
                ?.takeIf { it.size == paragraphCount }

        fun fromTiming(text: String): Transcript {
            val file = json.decodeFromString(TimingFile.serializer(), text)
            return Transcript(
                file.sentences.mapIndexed { i, s -> Sentence(i, s.t.trim(), s.s, s.e, s.p || i == 0) }
            )
        }

        fun fromPlainText(text: String): Transcript {
            var index = 0
            val sentences = mutableListOf<Sentence>()
            for (paragraph in SentenceSplitter.paragraphs(text)) {
                SentenceSplitter.split(paragraph).forEachIndexed { i, sentence ->
                    sentences += Sentence(index++, sentence, null, null, i == 0)
                }
            }
            return Transcript(sentences)
        }
    }
}

object SentenceSplitter {
    private val boundary = Regex("""(?<=[.!?…]["”’)]?)\s+(?=["“‘(]?[A-Z0-9])""")

    fun paragraphs(text: String): List<String> =
        text.trimStart(Char(0xFEFF)).replace("\r\n", "\n")
            .split(Regex("""\n\s*\n"""))
            .map { it.replace(Regex("""\s+"""), " ").trim() }
            .filter { it.isNotEmpty() }

    /** Titles and short forms whose period does not end the sentence. */
    private val abbreviations = setOf("mr.", "mrs.", "ms.", "dr.", "st.", "jr.", "sr.", "vs.", "etc.", "e.g.", "i.e.", "u.s.", "a.m.", "p.m.")

    fun split(paragraph: String): List<String> {
        val sentences = mutableListOf<String>()
        for (piece in paragraph.split(boundary).map { it.trim() }.filter { it.isNotEmpty() }) {
            val previous = sentences.lastOrNull()
            if (previous != null && previous.substringAfterLast(' ').lowercase() in abbreviations) {
                sentences[sentences.lastIndex] = "$previous $piece"
            } else {
                sentences += piece
            }
        }
        return sentences
    }
}
