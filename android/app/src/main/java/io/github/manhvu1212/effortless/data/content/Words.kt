package io.github.manhvu1212.effortless.data.content

/** Word lookups on transcripts: what was tapped, and where a lesson explains it. */
object Words {
    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '\'' || c == '’' || c == '-'

    /** The word around character [index] of [text], without surrounding punctuation. */
    fun wordAt(text: String, index: Int): String? {
        if (text.isEmpty()) return null
        var i = index.coerceIn(0, text.lastIndex)
        if (!isWordChar(text[i]) && i > 0 && isWordChar(text[i - 1])) i--
        if (!isWordChar(text[i])) return null
        var start = i
        var end = i + 1
        while (start > 0 && isWordChar(text[start - 1])) start--
        while (end < text.length && isWordChar(text[end])) end++
        return text.substring(start, end).trim('\'', '’', '-').ifEmpty { null }
    }

    /** "graves" → grave, "admired" → admire/admir, "flying" → fly, "Guatemala's" → guatemala. */
    fun stems(word: String): Set<String> {
        val w = word.lowercase().replace('’', '\'').removeSuffix("'s")
        val out = mutableSetOf(w)
        for (suffix in listOf("ies", "es", "s", "ed", "d", "ing", "ly")) {
            if (w.endsWith(suffix) && w.length - suffix.length >= 3) out += w.dropLast(suffix.length)
        }
        if (w.endsWith("ies") && w.length > 4) out += w.dropLast(3) + "y"
        return out
    }

    /** The stems with the common English endings put back: dead → deads, died…; admire → admiring. */
    private fun forms(stems: Set<String>): Set<String> = stems.flatMapTo(mutableSetOf()) { stem ->
        val base = listOf(stem, stem + "s", stem + "es", stem + "ed", stem + "d", stem + "ing", stem + "ly", stem + "'s")
        val dropE = if (stem.endsWith("e")) listOf(stem.dropLast(1) + "ing") else emptyList()
        val y = if (stem.endsWith("y")) listOf(stem.dropLast(1) + "ies", stem.dropLast(1) + "ied") else emptyList()
        base + dropE + y
    }

    /** Indices of the sentences that use [word], in order. */
    fun sentencesUsing(word: String, sentences: List<Sentence>): List<Int> {
        val forms = forms(stems(word))
        return sentences.indices.filter { i ->
            Regex("""[a-z0-9']+""").findAll(sentences[i].text.lowercase().replace('’', '\'')).any { it.value in forms }
        }
    }
}
