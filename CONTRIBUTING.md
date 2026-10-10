# Contributing

Thanks for helping! Code, graded stories, translations of the interface and bug reports are all welcome.

1. Fork and branch: `git checkout -b feat/my-change`.
2. Run the checks before opening a pull request:
   ```bash
   ./gradlew -p core check koverVerify
   ./gradlew :app:ktlintCheck :app:lintDebug :app:testDebugUnitTest
   ```
   `./gradlew -p core ktlintFormat` and `./gradlew :app:ktlintFormat` fix most style issues.
3. Behaviour changes update [SPEC.md](SPEC.md) here **and** in
   [lingleaf-ios](https://github.com/erobinsonc/lingleaf-ios), so both apps stay in sync.
4. Every user-facing string goes in `strings.xml` (English) and `values-es/strings.xml` (Spanish).
5. Open the pull request. CI must be green before merging.

## Principles

- Same behaviour on iOS and Android.
- No analytics, ads or tracking. Any new network host is documented in the README.
- Accessible by default: every button has a label, text scales.
