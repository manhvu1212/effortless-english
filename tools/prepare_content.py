"""Turn the Effortless English course folders into the app's content package.

    python tools/prepare_content.py SRC OUT [--only GLOB] [--model small.en] [--no-whisper]

SRC holds one folder per course, each with one folder per lesson (the layout on Google Drive).
OUT receives, per lesson, the audio files plus `<audio>.txt` and `<audio>.timing.json`, and a
`manifest.json` at the root that names every track's kind, so the app does not have to guess.

Transcripts come from the course PDFs where a PDF belongs to the track, otherwise from Whisper.
Either way Whisper supplies the timing: PDF sentences are matched to Whisper's timed words.
Whisper results are cached in OUT/.whisper, so an interrupted run resumes where it stopped.
"""

from __future__ import annotations

import argparse
import collections
import difflib
import fnmatch
import hashlib
import json
import os
import re
import shutil
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path

AUDIO = {".mp3", ".m4a"}
KIND_ORDER = ["MAIN", "VOCABULARY", "MINI_STORY", "POINT_OF_VIEW", "COMMENTARY", "OTHER"]
# Power English numbers its tracks: 1 main, 2 vocabulary, 3 mini-story, 4 point of view.
NUMBERED_KINDS = {"1": "MAIN", "2": "VOCABULARY", "3": "MINI_STORY", "4": "POINT_OF_VIEW"}
SENTENCE_END = re.compile(r"(?<=[.!?…])[\"”’)]?\s+(?=[\"“‘(]?[A-Z0-9])")
ABBREVIATIONS = {"mr.", "mrs.", "ms.", "dr.", "st.", "jr.", "sr.", "vs.", "etc.", "e.g.", "i.e.", "u.s.", "a.m.", "p.m."}


# ---------------------------------------------------------------------------------------------
# Classification


def words_of(name: str) -> list[str]:
    spaced = re.sub(r"(?<=[a-z])(?=[A-Z])", " ", name.replace("_", " "))  # "ComfortZoneMS"
    return re.findall(r"[a-z0-9]+", spaced.lower())


def classify(base: str, lesson_words: set[str]) -> str | None:
    """Kind from the words a file name adds to its lesson's name; None when it adds nothing."""
    w = [x for x in words_of(base) if x not in lesson_words]
    s = " ".join(w)
    if any(x.startswith("vocab") for x in w):
        return "VOCABULARY"
    if "pov" in w or "point of view" in s:
        return "POINT_OF_VIEW"
    if any(x.startswith("comm") for x in w):
        return "COMMENTARY"
    if any(x.startswith("mini") or re.fullmatch(r"ms(i{1,3}|\d)?", x) for x in w):
        return "MINI_STORY"
    if any(x in ("audio", "main", "conversation", "article") for x in w):
        return "MAIN"
    return None


def lesson_words(folder: Path) -> set[str]:
    """Words of the lesson's own title ("3.10 MS Censorship"), which say nothing about a track's kind."""
    return set(words_of(folder.name))


# ---------------------------------------------------------------------------------------------
# Lessons


@dataclass
class Track:
    audio: Path
    kind: str
    pdf: Path | None = None


@dataclass
class Lesson:
    course: str
    folder: Path
    tracks: list[Track] = field(default_factory=list)

    @property
    def rel(self) -> str:
        return f"{self.course}/{self.folder.name}"


def lesson_tracks(folder: Path) -> list[Track]:
    files = [p for p in folder.iterdir() if p.is_file()]
    audio = sorted((p for p in files if p.suffix.lower() in AUDIO), key=lambda p: natural_key(p.name))
    pdfs = [p for p in files if p.suffix.lower() == ".pdf"]
    if not audio:
        return []
    shared = lesson_words(folder)
    tracks = []
    for p in audio:
        kind = classify(stem(p), shared)
        if kind is None:
            number = re.match(r"\s*(\d)_", p.name)
            kind = NUMBERED_KINDS.get(number.group(1), "MAIN") if number else "MAIN"
        tracks.append(Track(p, kind))
    # One main track at most: the shortest name keeps MAIN, others become OTHER.
    mains = [t for t in tracks if t.kind == "MAIN"]
    for t in sorted(mains, key=lambda t: len(t.audio.name))[1:]:
        t.kind = "OTHER"
    match_pdfs(tracks, pdfs, shared)
    tracks.sort(key=lambda t: (KIND_ORDER.index(t.kind), natural_key(t.audio.name)))
    return tracks


def match_pdfs(tracks: list[Track], pdfs: list[Path], shared: set[str]) -> None:
    """Same name first, then same kind; a PDF of no particular kind goes to the main track."""
    left = list(pdfs)
    for t in tracks:
        same = next((p for p in left if stem(p).lower() == stem(t.audio).lower()), None)
        if same:
            t.pdf = same
            left.remove(same)
    for t in tracks:
        if t.pdf:
            continue
        candidates = [p for p in left if classify(stem(p), shared) == t.kind]
        if len(candidates) == 1:
            t.pdf = candidates[0]
            left.remove(candidates[0])
    main = next((t for t in tracks if t.kind == "MAIN" and not t.pdf), None)
    untyped = [p for p in left if classify(stem(p), shared) in (None, "MAIN")
               and not re.search(r"welcome|guide|introduction", p.name, re.I)]
    if main and len(untyped) == 1:
        main.pdf = untyped[0]


def find_lessons(src: Path, only: str | None) -> list[Lesson]:
    lessons = []
    for course in sorted((p for p in src.iterdir() if p.is_dir()), key=lambda p: natural_key(p.name)):
        for folder in sorted((p for p in course.iterdir() if p.is_dir()), key=lambda p: natural_key(p.name)):
            lesson = Lesson(course.name, folder)
            if only and not fnmatch.fnmatch(lesson.rel, only):
                continue
            lesson.tracks = lesson_tracks(folder)
            if lesson.tracks:
                lessons.append(lesson)
    return lessons


# ---------------------------------------------------------------------------------------------
# Text


def pdf_paragraphs(pdf: Path) -> list[str]:
    """Text blocks of the PDF, without page headers, footers and separator lines."""
    import pymupdf

    paragraphs = []
    with pymupdf.open(pdf) as doc:
        for page in doc:
            for block in page.get_text("blocks", sort=True):
                text = re.sub(r"\s+", " ", block[4]).strip()
                if not text or is_boilerplate(text):
                    continue
                paragraphs.append(text)
    return paragraphs


SPLIT_WORD = re.compile(r"\b([A-Za-z]+)- ([a-z]+)\b")
COMPOUND_PREFIXES = {
    "ex", "self", "well", "non", "all", "half", "long", "short", "high", "low", "full", "part", "cross", "one",
    "two", "three", "four", "five", "six", "ten", "so", "ill", "first", "second", "third", "old", "new", "anti",
    "pre", "post", "mid", "semi", "multi", "great",
}
# Splits in the course PDFs that the rules below get wrong, read by hand.
SPLIT_OVERRIDES = {
    "defeat- ing": "defeating", "dis- honesty": "dishonesty", "inter- views": "interviews",
    "mini- lesson": "mini-lesson", "mini- story": "mini-story", "people- pleaser": "people-pleaser",
    "wood- working": "woodworking", "work- sheets": "worksheets", "in- class": "in-class",
    "Bookstore- owned": "Bookstore-owned", "life- we": "life- we", "jus- or": "jus- or",
    "researchers- accomplished": "researchers- accomplished",
}


def join_split_words(paragraphs: list[str], known: collections.Counter) -> list[str]:
    """Words a PDF split at a line end ("simi- lar") made whole again.

    Joined when the whole word is in [known] (Whisper spells words whole), kept as a compound
    ("ex-wife") when the compound is known or starts with a usual prefix, left alone when both
    parts are common words (a dash: "words- make"), joined otherwise.
    """
    def repl(m: re.Match) -> str:
        first, second = m.group(1), m.group(2)
        if m.group(0) in SPLIT_OVERRIDES:
            return SPLIT_OVERRIDES[m.group(0)]
        if known[(first + second).lower()]:
            return first + second
        if known[f"{first}-{second}".lower()] or first.lower() in COMPOUND_PREFIXES:
            return f"{first}-{second}"
        if known[first.lower()] > 2 and known[second.lower()] > 2:
            return m.group(0)
        return first + second
    return [SPLIT_WORD.sub(repl, p) for p in paragraphs]


def known_words(*texts: str) -> collections.Counter:
    seen: collections.Counter = collections.Counter()
    for text in texts:
        seen.update(re.findall(r"[a-z]+(?:-[a-z]+)*", text.lower()))
    return seen


def is_boilerplate(text: str) -> bool:
    t = text.lower()
    return (
        "www." in t
        or "copyright" in t
        or "©" in text
        or re.fullmatch(r"[\d\s*.\-–—]*", t) is not None
        or t in ("effortless english", "learn real english")
    )


def split_sentences(paragraph: str) -> list[str]:
    sentences: list[str] = []
    for piece in (s.strip() for s in SENTENCE_END.split(paragraph)):
        if not piece:
            continue
        if sentences and sentences[-1].split()[-1].lower() in ABBREVIATIONS:
            sentences[-1] += " " + piece
        else:
            sentences.append(piece)
    return sentences


def tokens(text: str) -> list[str]:
    return re.findall(r"[a-z0-9']+", text.lower().replace("’", "'"))


# ---------------------------------------------------------------------------------------------
# Whisper


class Transcriber:
    def __init__(self, model: str, cache: Path):
        self.model_name = model
        self.cache = cache
        self.model = None
        cache.mkdir(parents=True, exist_ok=True)

    def words(self, audio: Path, key: str) -> list[dict]:
        """[{w, s, e}] with times in seconds; cached per audio file and model."""
        path = self.cache / (hashlib.sha1(f"{self.model_name}|{key}".encode()).hexdigest() + ".json")
        if path.exists():
            return json.loads(path.read_text(encoding="utf-8"))["words"]
        if self.model is None:
            from faster_whisper import WhisperModel

            self.model = WhisperModel(self.model_name, device="cpu", compute_type="int8", cpu_threads=os.cpu_count())
        started = time.time()
        segments, info = self.model.transcribe(str(audio), language="en", word_timestamps=True, vad_filter=True)
        words = [
            {"w": w.word.strip(), "s": round(w.start, 3), "e": round(w.end, 3)}
            for seg in segments for w in seg.words if w.word.strip()
        ]
        took = time.time() - started
        print(f"      whisper {info.duration / 60:.1f} min audio in {took:.0f} s ({info.duration / max(took, 1):.1f}x)", flush=True)
        tmp = path.with_suffix(".tmp")
        tmp.write_text(json.dumps({"key": key, "model": self.model_name, "words": words}), encoding="utf-8")
        tmp.replace(path)
        return words


# ---------------------------------------------------------------------------------------------
# Timing


def align(paragraphs: list[str], words: list[dict]) -> list[dict]:
    """Times each PDF sentence by matching its tokens against Whisper's timed words."""
    sentences = []  # (text, paragraph start, token range)
    pdf_tokens: list[str] = []
    for paragraph in paragraphs:
        for i, sentence in enumerate(split_sentences(paragraph)):
            t = tokens(sentence)
            sentences.append((sentence, i == 0, len(pdf_tokens), len(pdf_tokens) + len(t)))
            pdf_tokens += t
    heard = [tokens(w["w"]) for w in words]
    heard_tokens, owner = [], []
    for wi, ts in enumerate(heard):
        for t in ts:
            heard_tokens.append(t)
            owner.append(wi)

    matched: dict[int, int] = {}
    matcher = difflib.SequenceMatcher(None, pdf_tokens, heard_tokens, autojunk=False)
    for block in matcher.get_matching_blocks():
        for k in range(block.size):
            matched[block.a + k] = owner[block.b + k]

    out = []
    for text, para, a, b in sentences:
        hits = [matched[i] for i in range(a, b) if i in matched]
        start = words[hits[0]]["s"] if hits else None
        end = words[hits[-1]]["e"] if hits else None
        out.append({"t": text, "p": para, "s": start, "e": end, "hits": len(hits), "n": b - a})
    fill_gaps(out, words[-1]["e"] if words else 0)
    coverage = sum(x.pop("hits") for x in out) / max(1, sum(x.pop("n") for x in out))
    return out, coverage


def fill_gaps(sentences: list[dict], total: float) -> None:
    """Untimed sentences share the time between their timed neighbours; times never go backwards."""
    i = 0
    while i < len(sentences):
        if sentences[i]["s"] is not None:
            i += 1
            continue
        j = i
        while j < len(sentences) and sentences[j]["s"] is None:
            j += 1
        lo = sentences[i - 1]["e"] if i > 0 else 0.0
        hi = sentences[j]["s"] if j < len(sentences) else total
        step = max(0.0, hi - lo) / (j - i)
        for k in range(i, j):
            sentences[k]["s"] = lo + step * (k - i)
            sentences[k]["e"] = lo + step * (k - i + 1)
        i = j
    previous = 0.0
    for s in sentences:
        s["s"] = max(s["s"], previous)
        s["e"] = max(s["e"], s["s"])
        previous = s["s"]


def whisper_sentences(words: list[dict]) -> list[dict]:
    """Sentences from Whisper alone: break at sentence punctuation, or at a pause of a second or more."""
    out: list[dict] = []
    current: list[dict] = []
    new_paragraph = True

    def flush(paragraph_next: bool) -> None:
        nonlocal current, new_paragraph
        if current:
            # Whisper splits "mini-story" into "mini" + "-story"; glue such pieces back on.
            text = re.sub(r" (?=[-'’,.?!%])", "", " ".join(w["w"] for w in current)).strip()
            out.append({"t": text, "p": new_paragraph, "s": current[0]["s"], "e": current[-1]["e"]})
            new_paragraph = paragraph_next
        current = []

    for i, w in enumerate(words):
        if current and w["s"] - current[-1]["e"] >= 2.5:
            flush(True)
        elif current and (w["s"] - current[-1]["e"] >= 1.0 or len(current) >= 40):
            flush(False)
        current.append(w)
        if re.search(r"[.!?]$", w["w"]) and len(current) >= 3:
            flush(False)
    flush(False)
    return out


# ---------------------------------------------------------------------------------------------
# Output


def pretty_lesson(name: str) -> str:
    name = re.sub(r"_lessons$", "", name).replace("_", " ")
    name = re.sub(r"\s+", " ", name).strip()
    return name[0].upper() + name[1:] if name and name == name.lower() else name


def pretty_course(name: str) -> str:
    return re.sub(r"^\d+\.\s*", "", name).strip()


def slug(name: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-") or "x"


def stem(p: Path) -> str:
    name = p.name
    while Path(name).suffix.lower() in AUDIO | {".pdf"}:  # "3_Adventure_MINI_STORY.mp3.mp3"
        name = name[: -len(Path(name).suffix)]
    return name.rstrip(".")


def natural_key(s: str):
    return [int(x) if x.isdigit() else x.lower() for x in re.split(r"(\d+)", s)]


def copy_if_changed(src: Path, dst: Path) -> None:
    if dst.exists() and dst.stat().st_size == src.stat().st_size:
        return
    tmp = dst.with_name(dst.name + ".part")
    shutil.copyfile(src, tmp)
    tmp.replace(dst)


def write_timing(path: Path, sentences: list[dict]) -> None:
    data = {
        "version": 1,
        "sentences": [
            {"s": int(s["s"] * 1000), "e": int(s["e"] * 1000), "t": s["t"], "p": bool(s["p"])} for s in sentences
        ],
    }
    path.write_text(json.dumps(data, ensure_ascii=False, indent=0), encoding="utf-8")


def write_text(path: Path, sentences: list[dict]) -> None:
    paragraphs: list[list[str]] = []
    for s in sentences:
        if s["p"] or not paragraphs:
            paragraphs.append([])
        paragraphs[-1].append(s["t"])
    path.write_text("\n\n".join(" ".join(p) for p in paragraphs) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("src", type=Path)
    parser.add_argument("out", type=Path)
    parser.add_argument("--only", help='glob on "Course/Lesson", e.g. "1. Original English/1.0*"')
    parser.add_argument("--model", default="small.en")
    parser.add_argument("--texts", type=Path, help="cleaned transcripts, used instead of Whisper's text or the PDF: TEXTS/<course>/<lesson>/<audio base>.txt")
    parser.add_argument("--no-whisper", action="store_true", help="classify and convert PDFs only")
    parser.add_argument("--dry-run", action="store_true", help="print the plan, write nothing")
    args = parser.parse_args()

    lessons = find_lessons(args.src, args.only)
    total = sum(len(lesson.tracks) for lesson in lessons)
    print(f"{len(lessons)} lessons, {total} tracks", flush=True)
    transcriber = Transcriber(args.model, args.out / ".whisper")
    manifest_path = args.out / "manifest.json"
    manifest = {"version": 1, "courses": []}
    if manifest_path.exists() and args.only:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    courses = {c["id"]: c for c in manifest["courses"]}

    done = 0
    for lesson in lessons:
        print(f"[{lesson.rel}]", flush=True)
        course_id = slug(lesson.course)
        course = courses.setdefault(course_id, {"id": course_id, "title": pretty_course(lesson.course), "lessons": []})
        entry = {"id": slug(lesson.folder.name), "title": pretty_lesson(lesson.folder.name), "tracks": []}
        out_dir = args.out / lesson.course / lesson.folder.name
        for track in lesson.tracks:
            done += 1
            base = stem(track.audio)
            rel_dir = f"{lesson.course}/{lesson.folder.name}"
            print(f"  ({done}/{total}) {track.kind:<13} {track.audio.name}" + (f"  <- {track.pdf.name}" if track.pdf else ""), flush=True)
            item = {"kind": track.kind, "title": base.replace("_", " "), "audio": f"{rel_dir}/{track.audio.name}"}
            entry["tracks"].append(item)
            if args.dry_run:
                continue
            out_dir.mkdir(parents=True, exist_ok=True)
            copy_if_changed(track.audio, out_dir / track.audio.name)
            paragraphs = pdf_paragraphs(track.pdf) if track.pdf else []
            cleaned = args.texts / rel_dir / f"{base}.txt" if args.texts else None
            if cleaned and cleaned.exists():
                # Whisper's text punctuated, or the PDF's text re-paragraphed; timed like a PDF.
                paragraphs = [p.strip() for p in cleaned.read_text(encoding="utf-8").split("\n\n") if p.strip()]
            if args.no_whisper:
                if paragraphs:
                    paragraphs = join_split_words(paragraphs, known_words(*paragraphs))
                    sentences = [{"t": s, "p": i == 0} for p in paragraphs for i, s in enumerate(split_sentences(p))]
                    write_text(out_dir / f"{base}.txt", sentences)
                    item["transcript"] = f"{rel_dir}/{base}.txt"
                continue
            words = transcriber.words(track.audio, f"{lesson.rel}/{track.audio.name}")
            if paragraphs:
                paragraphs = join_split_words(paragraphs, known_words(" ".join(w["w"] for w in words), *paragraphs))
                sentences, coverage = align(paragraphs, words)
                print(f"      aligned {len(sentences)} PDF sentences, {coverage:.0%} of words matched", flush=True)
                if coverage < 0.5:
                    print("      WARNING: PDF and audio differ a lot; using Whisper's text instead", flush=True)
                    sentences = whisper_sentences(words)
            else:
                sentences = whisper_sentences(words)
            if not sentences:
                continue
            write_text(out_dir / f"{base}.txt", sentences)
            write_timing(out_dir / f"{base}.timing.json", sentences)
            item["transcript"] = f"{rel_dir}/{base}.txt"
            item["timing"] = f"{rel_dir}/{base}.timing.json"
        lessons_by_id = {x["id"]: x for x in course["lessons"]}
        lessons_by_id[entry["id"]] = entry
        course["lessons"] = sorted(lessons_by_id.values(), key=lambda x: natural_key(x["id"]))
        if not args.dry_run:
            args.out.mkdir(parents=True, exist_ok=True)
            ordered = sorted(courses.values(), key=lambda c: natural_key(c["id"]))
            tmp = manifest_path.with_suffix(".tmp")
            tmp.write_text(json.dumps({"version": 1, "courses": ordered}, ensure_ascii=False, indent=1), encoding="utf-8")
            tmp.replace(manifest_path)
    print("done", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
