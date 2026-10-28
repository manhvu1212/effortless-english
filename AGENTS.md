# Effortless English — Android study app

Native Android app (Kotlin + Jetpack Compose) for studying the Effortless English courses:
a day-by-day lesson plan, a background player with a karaoke transcript, vocabulary
flashcards with spaced repetition, and study reminders.

The course audio and text are copyrighted and are **never committed** (see `.gitignore`).
The app downloads them from the owner's private storage at runtime.

## Project layout
- `android/` — Android app (Gradle Kotlin DSL, Compose, Room, Material 3).
- `tools/` — PC-side scripts: Whisper alignment that produces per-sentence timestamps for the transcript.

## Toolchain
- AGP 9.2, Kotlin 2.3, Gradle 9.5, Java 17 target.
- minSdk 26, targetSdk/compileSdk 35.
- Room via KSP.

## Build
- From `android/`: `gradlew.bat assembleDebug`.
- Release signing reads `NMV_KEYSTORE` / `NMV_KEYSTORE_PASS` from the user-level `~/.gradle/gradle.properties`; the alias is `effortless`.

## Conventions
- Compose-first: no XML layouts beyond resources.
- ViewModels per screen, repository-backed flows via Room.
- Strings in `res/values/strings.xml` (English) and `res/values-vi/strings.xml`.
