# Effortless English — Android study app

Native Android app (Kotlin + Jetpack Compose) for studying the Effortless English courses:
a day-by-day lesson plan, a background player with a karaoke transcript and shadowing mode,
vocabulary flashcards with SM-2 spaced repetition, study reminders and stats.

The course audio and text are copyrighted and are **never committed** (see `.gitignore`).
The app reads them at runtime either from the owner's Google Drive (the package written by
`tools/prepare_content.py`, read-only OAuth) or from a folder the user picks (SAF).

## Project layout
- `android/` — Android app (Gradle Kotlin DSL, Compose, Room, Media3, Material 3).
  - `data/content/` — `LibraryScanner` turns a content folder into courses/lessons/tracks; `Transcript` parses text and timing files.
  - `data/db/` — Room entities and DAOs. Library tables are replaced on every scan; progress, cards and track state live in separate tables keyed by path-derived ids, so a rescan keeps them.
  - `player/` — `PlayerController` owns the single ExoPlayer (A-B loop, shadowing, listening time) and builds lesson playlists; `PlaybackService` (a `MediaLibraryService`) wraps it for background playback, the media notification and Android Auto.
  - `reminders/` — inexact daily alarms re-armed by the receiver, on boot and on time changes.
  - `drive/` — Google Drive source: `DriveAuth` (Play services AuthorizationClient, scope `drive.readonly`), `DriveApi` (Drive v3 over OkHttp), `DriveRepository` (sync of manifest/transcripts/timings into `getExternalFilesDir()/content`, per-lesson audio downloads via `DownloadWorker`, streaming fallback). Tracks always point at the local path; `DriveRepository.playableUri` swaps in the Drive URL while the file is not downloaded, and the player's `ResolvingDataSource` adds the token.
- `tools/prepare_content.py` — turns the course folders (Google Drive layout) into the app's content package: copies audio, converts PDFs to text, runs Whisper (cached, resumable), times PDF sentences against Whisper's words, writes `manifest.json`. Run it with a Python 3.12 venv holding `faster-whisper`, `pymupdf` and `av<18` (av 18+ breaks faster-whisper 1.2).

## Study flow
- Follows the courses' "How To Use The Lessons" guides (`domain/StudyPlan.kt`): read the text, then vocabulary → mini-story → point of view → the main audio → commentary (optional); Learn Real English puts the conversation first. Targets: 3 listens for mini-story and main, 1 for commentary, 2 otherwise; daily goal 60 min by default (guides: at least an hour, two is best).
- Reading (`ReadScreen`): text only. Tapping a word opens the lesson glossary entry (`data/content/Glossary.kt`: Vietnamese meaning, the teacher's explanation in short, and the span of the vocabulary audio where he gives it); without a glossary file, sentences of the vocabulary lesson and mini-story that use the word (`data/content/Words.kt`), plus a hand-off to Google Translate's pop-up (no ML Kit: it would make the app collect diagnostics data). Under each paragraph, a Vietnamese meaning from `<audio base>.vi.json` when the package has one, hidden until tapped.
- A listen to the end is counted per track and day (`track_listen`); with auto-tick on (default) reaching the target ticks the step. The player's step bar shows "n/target" and "Xong, bước tiếp →" ticks the step and starts the next step's track. Playlists are in plan order.
- Practice modes in the player: ANSWER pauses briefly after each question (the mini-story method: answer out loud, fast), SHADOW after every sentence.
- When a track ends the player repeats it by default (`AfterTrack.REPEAT`); the user can switch to stop-at-end or go-to-next, remembered in settings.

## Android Auto
- Audio-only by design (no transcript in the car). `MediaTree` is the browse tree: tabs `@today` (current lesson's tracks) and `@courses` (course → lesson → tracks). Folder ids start with `@`; playable items use the plain track id, the same id the player uses.
- Choosing a track queues its whole lesson from where it was left; a voice query matches lesson then track titles; a blank query or an id-less item resumes (`PlayerController.resumption`).
- Whatever the car sets on the player, `PlayerController.syncPlaylist` mirrors into the phone's state, so listening time and steps are recorded the same way.
- Custom buttons (also in the notification): rewind/forward by the configured seconds, cycle speed, toggle shadowing. They are passed on connect as well as updated, since the car can connect before the first update.
- Tested by `CarMediaLibraryTest` (a `MediaBrowser` acting as the car). The emulator's Android Auto is a stub without Play sign-in, so the Desktop Head Unit has not been used yet.

## Content folder
- Every folder holding audio files (mp3, m4a, wav…) is a lesson; its top-level folder under the picked root is the course.
- A folder whose audio files carry several leading numbers (`01 …`, `02 …`) is split into one lesson per number.
- Track kind comes from the words a file name adds to its lesson folder's title: `vocab…`, `pov`/`point of view` (before MS: "POV-MS" is POV), `comm…`, `mini…`/`ms`/`msi`; otherwise Power English's leading `1_`–`4_`; anything else is the main track. `tools/prepare_content.py` uses the same rules.
- Transcript: `.txt` with the audio's base name (or the only text file of that kind). Karaoke: `<audio base>.timing.json`.
- A `manifest.json` at the root overrides all of the above (format: `Manifest` in `LibraryScanner.kt`).

Timing file: `{"version":1,"sentences":[{"s":<startMs>,"e":<endMs>,"t":"<text>","p":<paragraph start>}]}`.
Glossary (optional, vocabulary tracks): `<audio base>.glossary.json` = `{"version":1,"entries":[{"term","keys":[tapped words that open it],"vi","explain","startMs","endMs"}]}`.
Translation (optional): `<audio base>.vi.json` = `{"version":1,"paragraphs":["…"]}`, one entry per transcript paragraph; ignored when the count does not match.

## Google Drive setup (owner's Google Cloud project)
- Drive API enabled; OAuth consent screen External, scope `.../auth/drive.readonly`, published (unverified is fine for personal use; Testing mode would expire grants weekly).
- Android OAuth clients for `io.github.manhvu1212.effortless.debug` (debug keystore SHA-1) and, once a release key exists, `io.github.manhvu1212.effortless`. No client id goes into the app: Play services matches package + SHA-1.
- Without it, connecting fails with DEVELOPER_ERROR (shown as "Chưa cấu hình OAuth client").

## Toolchain
- AGP 9.2, Kotlin 2.3, Gradle 9.5, Java 17 target.
- minSdk 26, targetSdk 36, compileSdk 36.
- Room via KSP, kotlinx.serialization for JSON.

## Build & test
- From `android/`: `gradlew.bat assembleDebug`, `gradlew.bat testDebugUnitTest`.
- Instrumented (needs a device or emulator; replaces the app's library data and uninstalls afterwards): `gradlew.bat connectedDebugAndroidTest`.
- Release signing reads `NMV_KEYSTORE` / `NMV_KEYSTORE_PASS` from the user-level `~/.gradle/gradle.properties`; the alias is `effortless`.

## Google Play
- Listing text and phone screenshots: `android/fastlane/metadata/android/{vi-VN,en-US}` (fastlane supply layout); changelog per versionCode in `changelogs/<versionCode>.txt`.
- Icon 512 and feature graphic: `play-store-assets/generate.ps1 [-Title ...]` (UTF-8 with BOM: PowerShell 5 reads it as ANSI otherwise).
- Screenshots must be at most 2:1; emulator shots (1280x2856) are scaled to 2560 high and padded to 1280 wide.
- Play App Signing re-signs the app: Drive needs an extra Android OAuth client with the *app signing* SHA-1 from Play Console → App integrity.
- Bump `versionCode` for every upload.

## Conventions
- Compose-first: no XML layouts beyond resources.
- ViewModels per screen (created through `appViewModel { … }` from the `AppContainer`), repository-backed flows via Room.
- The UI is Vietnamese only: strings live in `res/values/strings.xml`; plurals carry both `one` and `other`.
- Theme (`ui/theme/Theme.kt`): light and dark schemes built around the logo blue #2E6BD9; Settings picks system/light/dark (`ThemeMode`) and optional wallpaper colours. On Android 12+ `MainActivity` also sets `UiModeManager.setApplicationNightMode`, so the launch window matches. No hard-coded colours in screens: use `MaterialTheme.colorScheme`.
