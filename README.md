<p align="center"><img src="docs/icon.png" width="96" alt=""></p>

# Lingleaf for Android

**Learn English by reading real books.** Tap any word to see its translation,
pronunciation and meaning, and highlight what you learn. Free and open source.

> 🇪🇸 **Aprende inglés leyendo libros de verdad.** Toca cualquier palabra para ver su
> traducción, pronunciación y significado, y subraya lo que aprendes. Gratis y open source.

iOS version: [lingleaf-ios](https://github.com/erobinsonc/lingleaf-ios) · Product spec: [SPEC.md](SPEC.md)

## Features

- **Tap a word**: base form, IPA, on-device translation, definition, and the sentence translated.
- **Learn / Learned**: words you're learning get a yellow underline in every book, inflected forms too.
- **Review** with spaced repetition (1, 3, 7, 14, 30 days).
- **Read aloud** a word, a sentence or a paragraph.
- **Thousands of free books** from Project Gutenberg, plus 3 original graded stories (A1–B1). Import your own `.txt`.
- **Daily goal and streak.**
- No accounts, no ads, no tracking.

## Build

Requirements: Android Studio (recent) with JDK 17.

```bash
./gradlew :app:installDebug          # build and install on a device/emulator
./gradlew -p core check koverVerify  # logic tests + coverage (no Android SDK needed)
./gradlew :app:testDebugUnitTest     # UI and accessibility tests (Robolectric)
./gradlew :app:ktlintCheck :app:lintDebug
```

## Architecture

- `core/` — pure Kotlin, no Android: text tools, base forms, spaced repetition,
  streak, Gutenberg catalog, dictionary client, library storage. ≥90% test coverage is enforced.
- `app/` — Jetpack Compose UI, ML Kit translation, system text-to-speech, HTTPS-only networking.

## Quality gates (every pull request)

| Gate | Tool |
|---|---|
| Logic tests + coverage ≥90% | JUnit, Kover |
| UI flow + accessibility tests | Compose UI tests on Robolectric |
| Code style | ktlint |
| Android Lint (accessibility issues are errors) | Android Lint |
| Secrets | gitleaks |
| Vulnerable dependencies | Dependabot, dependency review* |
| Static security analysis | CodeQL* |
| Build | debug APK uploaded as an artifact |

\* Runs automatically once the repository is public (free for open source).

## Privacy

Data stays on your device. Network requests go only to `gutendex.com` / `gutenberg.org`
(books) and `api.dictionaryapi.dev` (definitions), always over HTTPS. Translation
runs on the device with Google ML Kit.

## License

[MIT](LICENSE). Bundled stories in `app/src/main/assets/books` are original and
dedicated to the public domain (CC0). Project Gutenberg books are public domain in the US.
