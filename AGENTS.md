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
  - `player/` — `PlayerController` owns the single ExoPlayer (A-B loop, shadowing, listening time) and builds lesson playlists; `PlaybackService` (a `MediaLibraryService`) wraps it for background playback, the media notification and Android Auto.
  - `reminders/` — inexact daily alarms re-armed by the receiver, on boot and on time changes.
- `tools/` — PC-side scripts (planned): Whisper alignment that writes the timing files.

## Android Auto
- Audio-only by design (no transcript in the car). `MediaTree` is the browse tree: tabs `@today` (current lesson's tracks) and `@courses` (course → lesson → tracks). Folder ids start with `@`; playable items use the plain track id, the same id the player uses.
- Choosing a track queues its whole lesson from where it was left; a voice query matches lesson then track titles; a blank query or an id-less item resumes (`PlayerController.resumption`).
- Whatever the car sets on the player, `PlayerController.syncPlaylist` mirrors into the phone's state, so listening time and steps are recorded the same way.
- Custom buttons (also in the notification): rewind/forward by the configured seconds, cycle speed, toggle shadowing. They are passed on connect as well as updated, since the car can connect before the first update.
- Tested by `CarMediaLibraryTest` (a `MediaBrowser` acting as the car). The emulator's Android Auto is a stub without Play sign-in, so the Desktop Head Unit has not been used yet.

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
- Instrumented (needs a device or emulator; replaces the app's library data and uninstalls afterwards): `gradlew.bat connectedDebugAndroidTest`.
- Release signing reads `NMV_KEYSTORE` / `NMV_KEYSTORE_PASS` from the user-level `~/.gradle/gradle.properties`; the alias is `effortless`.

## Conventions
- Compose-first: no XML layouts beyond resources.
- ViewModels per screen (created through `appViewModel { … }` from the `AppContainer`), repository-backed flows via Room.
- The UI is Vietnamese only: strings live in `res/values/strings.xml`; plurals carry both `one` and `other`.
