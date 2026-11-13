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
# The shrunk build must still load the bundled irregular-verb table: the app
# logs "loaded (N forms)" or "refused" at start-up instead of crashing, and a
# failed background job instead of stopping.
applog="$(adb logcat -d -v brief Lingleaf:V '*:S' || true)"
if ! grep -q "Irregular-verb table loaded ([1-9][0-9]* forms)" <<<"$applog"; then
  echo "::error::The minified build did not log the irregular-verb table as loaded"
  status=1
fi
if grep -q "Irregular-verb table refused" <<<"$applog"; then
  echo "::error::The irregular-verb table was refused in the minified build"
  status=1
fi
if grep -q "Background work failed" <<<"$applog"; then
  echo "::error::Background work failed in the minified build"
  status=1
fi
# On a failure, the app's own log (and any crash) goes with the screenshots.
if [ "$status" != 0 ]; then
  adb logcat -d -v brief Lingleaf:V AndroidRuntime:E '*:S' > screenshots/logcat.txt || true
  echo "::group::App log"; cat screenshots/logcat.txt; echo "::endgroup::"
fi
ls -l screenshots
exit $status
