#!/usr/bin/env bash
# Runs inside the Android emulator: installs the APK and runs the Maestro
# tour in light and dark mode. Screenshots end up in ./screenshots.
# Keeps going after a failed step so every screen that can be captured is.
set -u
adb install -r app/build/outputs/apk/minified/app-minified.apk
# A slow CI emulator often shows "Pixel Launcher isn't responding" over the app;
# hide system error dialogs and give the launcher time to settle.
adb shell settings put global hide_error_dialogs 1
sleep 20
mkdir -p screenshots
status=0
for mode in light dark; do
  if [ "$mode" = dark ]; then adb shell cmd uimode night yes; else adb shell cmd uimode night no; fi
  work="$(mktemp -d)"
  run=0
  (cd "$work" && maestro test "$GITHUB_WORKSPACE/.maestro" --test-output-dir="$work/output") || run=1
  [ "$run" = 0 ] || status=1
  # Maestro writes named screenshots to the working directory, and step
  # screenshots (also for skipped optional steps) under the output directory;
  # step screenshots are kept only when the flow failed.
  find "$work" -name '*.png' | while read -r png; do
    case "$(basename "$png")" in
      *step-*) [ "$run" = 1 ] || continue ;;
    esac
    cp "$png" "screenshots/$mode-$(basename "$png")"
  done
done
# On a failure, the app's own log (and any crash) goes with the screenshots.
if [ "$status" != 0 ]; then
  adb logcat -d -v brief Lingleaf:V AndroidRuntime:E '*:S' > screenshots/logcat.txt || true
  echo "::group::App log"; cat screenshots/logcat.txt; echo "::endgroup::"
fi
ls -l screenshots
exit $status
