package io.github.manhvu1212.effortless.domain

import io.github.manhvu1212.effortless.data.db.TrackEntity
import io.github.manhvu1212.effortless.data.db.TrackKind

/**
 * The daily routine from the courses' "How To Use The Lessons" guides:
 *
 * - Effortless English: read the text → vocabulary a few times → mini-story several times (the most
 *   important lesson) → point of view a few times → the audio article many times → commentary once,
 *   only if it can be understood (the least important lesson).
 * - Learn Real English: read → the conversation → vocabulary → mini-story.
 *
 * Every lesson at least twice a day; one lesson set for a week or more.
 */
object StudyPlan {
    private val EFFORTLESS = listOf(
        TrackKind.VOCABULARY, TrackKind.MINI_STORY, TrackKind.POINT_OF_VIEW, TrackKind.MAIN, TrackKind.OTHER, TrackKind.COMMENTARY,
    )
    private val CONVERSATION_FIRST = listOf(
        TrackKind.MAIN, TrackKind.VOCABULARY, TrackKind.MINI_STORY, TrackKind.POINT_OF_VIEW, TrackKind.OTHER, TrackKind.COMMENTARY,
    )

    /** Tracks in the order they are studied each day. */
    fun order(tracks: List<TrackEntity>): List<TrackEntity> {
        val conversation = tracks.any { it.kind == TrackKind.MAIN && "conversation" in it.title.lowercase() }
        val rank = if (conversation) CONVERSATION_FIRST else EFFORTLESS
        return tracks.sortedWith(compareBy({ rank.indexOf(it.kind) }, { it.position }))
    }

    /** Listens a day that make the step done; "many times" lessons ask for three. */
    fun target(kind: TrackKind): Int = when (kind) {
        TrackKind.MINI_STORY, TrackKind.MAIN -> 3
        TrackKind.COMMENTARY -> 1
        else -> 2
    }

    /** Skipped when too difficult; a day counts without it. */
    fun optional(kind: TrackKind): Boolean = kind == TrackKind.COMMENTARY
}
