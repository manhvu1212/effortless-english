# Effortless English — Android study app

Native Android app (Kotlin + Jetpack Compose) for studying the Effortless English courses:
a day-by-day lesson plan, a background player with a karaoke transcript and shadowing mode,
vocabulary flashcards with SM-2 spaced repetition, study reminders and stats.

The course audio and text are copyrighted and are **never committed** (see `.gitignore`).
The app reads them at runtime from a folder the user picks (SAF); downloading from the
owner's Google Drive is planned.

## Project layout
- `android/` — Android app (Gradle Kotlin DSL, Compose, Room, Media3, Material 3).
  - `data/content/` — `LibraryScanner` turns a content folder into courses/lessons/tracks; `Transcript` parses text and timing files.
  - `data/db/` — Room entities and DAOs. Library tables are replaced on every scan; progress, cards and track state live in separate tables keyed by path-derived ids, so a rescan keeps them.
  - `player/` — `PlayerController` owns the single ExoPlayer (A-B loop, shadowing, listening time); `PlaybackService` wraps it in a MediaSession for background playback.
  - `reminders/` — inexact daily alarms re-armed by the receiver, on boot and on time changes.
- `tools/` — PC-side scripts (planned): Whisper alignment that writes the timing files.

## Content folder
- Every folder holding audio files (mp3, m4a, wav…) is a lesson; its top-level folder under the picked root is the course.
- A folder whose audio files carry several leading numbers (`01 …`, `02 …`) is split into one lesson per number.
- Track kind comes from the file name: `vocab`, `mini`/`ms`, `pov`/`point of view`, `comment`; anything else is the main track.
- Transcript: `.txt` with the audio's base name (or the only text file of that kind). Karaoke: `<audio base>.timing.json`.
- A `manifest.json` at the root overrides all of the above (format: `Manifest` in `LibraryScanner.kt`).

Timing file: `{"version":1,"sentences":[{"s":<startMs>,"e":<endMs>,"t":"<text>","p":<paragraph start>}]}`.

## Toolchain
- AGP 9.2, Kotlin 2.3, Gradle 9.5, Java 17 target.
- minSdk 26, targetSdk 35, compileSdk 36 (Media3 1.11 needs it).
- Room via KSP, kotlinx.serialization for JSON.

## Build & test
- From `android/`: `gradlew.bat assembleDebug`, `gradlew.bat testDebugUnitTest`.
- Release signing reads `NMV_KEYSTORE` / `NMV_KEYSTORE_PASS` from the user-level `~/.gradle/gradle.properties`; the alias is `effortless`.

## Conventions
- Compose-first: no XML layouts beyond resources.
- ViewModels per screen (created through `appViewModel { … }` from the `AppContainer`), repository-backed flows via Room.
- The UI is Vietnamese only: strings live in `res/values/strings.xml`; plurals carry both `one` and `other`.
