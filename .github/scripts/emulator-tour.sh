#!/usr/bin/env bash
# Runs inside the Android emulator: installs the APK and runs the Maestro
# tour in light and dark mode. Screenshots end up in ./screenshots.
# Keeps going after a failed step so every screen that can be captured is.
set -u
adb install -r app/build/outputs/apk/debug/app-debug.apk
mkdir -p screenshots
status=0
for mode in light dark; do
  if [ "$mode" = dark ]; then adb shell cmd uimode night yes; else adb shell cmd uimode night no; fi
  work="$(mktemp -d)"
  (cd "$work" && maestro test "$GITHUB_WORKSPACE/.maestro" --test-output-dir="$work/output") || status=1
  # Maestro writes named screenshots to the working directory and a
  # screenshot of the failing step under the output directory.
  find "$work" -name '*.png' | while read -r png; do
    cp "$png" "screenshots/$mode-$(basename "$png")"
  done
done
ls -l screenshots
exit $status
