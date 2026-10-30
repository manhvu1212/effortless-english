package io.github.manhvu1212.effortless.data.content

import io.github.manhvu1212.effortless.data.db.CourseEntity
import io.github.manhvu1212.effortless.data.db.LessonEntity
import io.github.manhvu1212.effortless.data.db.TrackEntity
import io.github.manhvu1212.effortless.data.db.TrackKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class ScannedLibrary(
    val courses: List<CourseEntity>,
    val lessons: List<LessonEntity>,
    val tracks: List<TrackEntity>,
)

/** Written by tools/ next to the content; paths are relative to the folder holding the manifest. */
@Serializable
data class Manifest(val version: Int = 1, val courses: List<Course>) {
    @Serializable
    data class Course(val id: String, val title: String, val lessons: List<Lesson>)

    @Serializable
    data class Lesson(val id: String, val title: String, val tracks: List<Track>)

    @Serializable
    data class Track(
        val kind: TrackKind = TrackKind.MAIN,
        val title: String,
        val audio: String,
        val transcript: String? = null,
        val timing: String? = null,
    )
}

/**
 * Turns a content folder into courses, lessons and tracks.
 *
 * A manifest.json at the root wins. Without one, every folder holding audio files is a lesson, its
 * top-level folder is the course, and a folder whose audio files carry several leading numbers
 * ("01 Day of Fear.mp3", "02 ...") is split into one lesson per number.
 */
object LibraryScanner {
    const val MANIFEST = "manifest.json"
    private val AUDIO = setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac")
    private val TEXT = setOf("txt")
    private val LEADING_NUMBER = Regex("""^\s*(\d+)""")
    private val json = Json { ignoreUnknownKeys = true }

    fun scan(root: ContentNode, rootTitle: String, readText: (ContentNode) -> String?): ScannedLibrary {
        val manifestNode = root.child(MANIFEST)
        if (manifestNode != null) {
            val text = readText(manifestNode) ?: error("Cannot read $MANIFEST")
            return fromManifest(parseManifest(text)) { path -> root.resolve(path)?.uri }
        }
        return fromFolders(root, rootTitle)
    }

    fun parseManifest(text: String): Manifest = json.decodeFromString(Manifest.serializer(), text)

    /** [resolve] maps a manifest path to a uri; a track whose audio does not resolve is left out. */
    fun fromManifest(manifest: Manifest, resolve: (String) -> String?): ScannedLibrary {
        val courses = mutableListOf<CourseEntity>()
        val lessons = mutableListOf<LessonEntity>()
        val tracks = mutableListOf<TrackEntity>()
        manifest.courses.forEachIndexed { ci, course ->
            courses += CourseEntity(course.id, course.title, ci)
            course.lessons.forEachIndexed { li, lesson ->
                val lessonId = "${course.id}/${lesson.id}"
                lessons += LessonEntity(lessonId, course.id, lesson.title, li)
                lesson.tracks.forEachIndexed { ti, track ->
                    val audio = resolve(track.audio) ?: return@forEachIndexed
                    tracks += TrackEntity(
                        id = "$lessonId/${baseName(track.audio.substringAfterLast('/'))}",
                        lessonId = lessonId,
                        kind = track.kind,
                        title = track.title,
                        position = ti,
                        audioUri = audio,
                        transcriptUri = track.transcript?.let(resolve),
                        timingUri = track.timing?.let(resolve),
                    )
                }
            }
        }
        return ScannedLibrary(courses, lessons, tracks)
    }

    fun fromFolders(root: ContentNode, rootTitle: String): ScannedLibrary {
        val lessonFolders = mutableListOf<Pair<List<String>, ContentNode>>()
        fun walk(node: ContentNode, path: List<String>) {
            if (node.children.any { !it.isDirectory && extension(it.name) in AUDIO }) lessonFolders += path to node
            node.children.filter { it.isDirectory }.sortedWith(compareBy(NaturalOrder) { it.name })
                .forEach { walk(it, path + it.name) }
        }
        walk(root, emptyList())

        val courses = linkedMapOf<String, CourseEntity>()
        val lessons = mutableListOf<LessonEntity>()
        val tracks = mutableListOf<TrackEntity>()
        val lessonCountByCourse = mutableMapOf<String, Int>()

        for ((path, folder) in lessonFolders) {
            val courseName = path.firstOrNull() ?: rootTitle
            val courseId = slug(courseName)
            courses.getOrPut(courseId) { CourseEntity(courseId, prettyTitle(courseName), courses.size) }

            val files = folder.children.filter { !it.isDirectory }
            val audio = files.filter { extension(it.name) in AUDIO }.sortedWith(compareBy(NaturalOrder) { it.name })
            val texts = files.filter { extension(it.name) in TEXT }
            val numbers = audio.map { leadingNumber(it.name) }
            // Only a course folder holding its files directly is split; inside a lesson folder the
            // numbers order the tracks ("1_Intro_MAIN", "2_Intro_VOCAB").
            val split = path.size <= 1 && numbers.none { it == null } && numbers.distinct().size > 1
            val groups = if (split) audio.groupBy { leadingNumber(it.name) } else mapOf(null to audio)

            val folderKey = path.drop(1).joinToString("/") { slug(it) }
            for ((number, group) in groups) {
                val key = listOfNotNull(folderKey.ifEmpty { null }, number?.let { "#$it" }).joinToString("/").ifEmpty { "_" }
                val lessonId = "$courseId/$key"
                val groupTexts = if (number == null) texts else texts.filter { leadingNumber(it.name) == number }
                val title = when {
                    split -> prettyTitle(baseName((group.firstOrNull { classify(it.name) == TrackKind.MAIN } ?: group.first()).name))
                    path.size >= 2 -> path.drop(1).joinToString(" / ") { prettyTitle(it) }
                    else -> prettyTitle(path.lastOrNull() ?: rootTitle)
                }
                val position = lessonCountByCourse.getOrDefault(courseId, 0)
                lessonCountByCourse[courseId] = position + 1
                lessons += LessonEntity(lessonId, courseId, title, position)

                val titleWords = if (split) emptySet() else words(folder.name).toSet()
                group.sortedWith(compareBy<ContentNode> { classify(it.name, titleWords).ordinal }.thenBy(NaturalOrder) { it.name })
                    .forEachIndexed { index, file ->
                        val base = baseName(file.name)
                        val kind = classify(file.name, titleWords)
                        tracks += TrackEntity(
                            id = "$lessonId/${slug(base)}",
                            lessonId = lessonId,
                            kind = kind,
                            title = prettyTitle(base),
                            position = index,
                            audioUri = file.uri,
                            transcriptUri = findTranscript(base, kind, groupTexts, titleWords)?.uri,
                            timingUri = (folder.child("$base.timing.json") ?: folder.child("$base.json"))?.uri,
                        )
                    }
            }
        }
        return ScannedLibrary(courses.values.toList(), lessons, tracks)
    }

    private fun findTranscript(audioBase: String, kind: TrackKind, texts: List<ContentNode>, titleWords: Set<String>): ContentNode? {
        texts.firstOrNull { baseName(it.name).equals(audioBase, ignoreCase = true) }?.let { return it }
        val sameKind = texts.filter { classify(it.name, titleWords) == kind }
        if (kind != TrackKind.MAIN) return sameKind.singleOrNull()
        return sameKind.singleOrNull() ?: texts.singleOrNull()
    }

    /**
     * Kind from the words a file name adds to its lesson's title, so "MS Censorship Vocab" in
     * "3.10 MS Censorship" is vocabulary and "MS Censorship" itself is the main track. The same
     * rules as tools/prepare_content.py.
     */
    fun classify(fileName: String, titleWords: Set<String> = emptySet()): TrackKind {
        val w = words(baseName(fileName)).filter { it !in titleWords }
        val s = w.joinToString(" ")
        return when {
            w.any { it.startsWith("vocab") } -> TrackKind.VOCABULARY
            "pov" in w || "point of view" in s -> TrackKind.POINT_OF_VIEW
            w.any { it.startsWith("comm") } -> TrackKind.COMMENTARY
            w.any { it.startsWith("mini") || MS_WORD.matches(it) } -> TrackKind.MINI_STORY
            else -> NUMBERED_KINDS[TRACK_NUMBER.find(fileName)?.groupValues?.get(1)] ?: TrackKind.MAIN
        }
    }

    private val MS_WORD = Regex("""ms(i{1,3}|\d)?""")
    private val TRACK_NUMBER = Regex("""^\s*(\d)_""")

    /** Power English numbers its tracks: 1 main, 2 vocabulary, 3 mini-story, 4 point of view. */
    private val NUMBERED_KINDS = mapOf(
        "1" to TrackKind.MAIN,
        "2" to TrackKind.VOCABULARY,
        "3" to TrackKind.MINI_STORY,
        "4" to TrackKind.POINT_OF_VIEW,
    )

    /** Lower-case words, splitting camel case ("ComfortZoneMS") and underscores. */
    fun words(name: String): List<String> =
        Regex("""[a-z0-9]+""").findAll(name.replace('_', ' ').replace(Regex("""(?<=[a-z])(?=[A-Z])"""), " ").lowercase())
            .map { it.value }.toList()

    private fun leadingNumber(name: String): Int? = LEADING_NUMBER.find(name)?.groupValues?.get(1)?.toIntOrNull()

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun baseName(name: String): String = if ('.' in name) name.substringBeforeLast('.') else name

    fun prettyTitle(name: String): String = name.replace('_', ' ').replace(Regex("""\s+"""), " ").trim()

    fun slug(name: String): String =
        name.lowercase().replace(Regex("""[^a-z0-9]+"""), "-").trim('-').ifEmpty { "x" }
}

/** Compares digit runs by value, so "Lesson 2" sorts before "Lesson 10". */
object NaturalOrder : Comparator<String> {
    private val chunk = Regex("""\d+|\D+""")

    override fun compare(a: String, b: String): Int {
        val x = chunk.findAll(a.lowercase()).map { it.value }.toList()
        val y = chunk.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(x.size, y.size)) {
            val p = x[i]
            val q = y[i]
            val result = if (p[0].isDigit() && q[0].isDigit()) {
                compareValuesBy(p, q, { it.trimStart('0').length }, { it.trimStart('0') })
            } else {
                p.compareTo(q)
            }
            if (result != 0) return result
        }
        return x.size - y.size
    }
}
