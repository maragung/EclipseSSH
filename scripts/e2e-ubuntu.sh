#!/usr/bin/env bash
# Ubuntu-installation E2E, as a command a developer can run locally and CI runs
# on a hosted emulator. The heavy lifting is testing/ubuntu-e2e/driver.py; this
# wrapper owns the parts that are environment, not test: where the APK comes
# from, whether an emulator is reachable, and what "clean" means.
#
# Usage:
#   ./scripts/e2e-ubuntu.sh                      # build the debug APK, run SMOKE
#   ./scripts/e2e-ubuntu.sh --mode STANDARD      # + lifecycle + persistence
#   ./scripts/e2e-ubuntu.sh --mode FULL          # + failure injections (rooted emu)
#   ./scripts/e2e-ubuntu.sh --ci                 # CI mode: APKs prebuilt in $APK
#
# Options:
#   --ci                   CI mode: expects $APK (app) and $TEST_APK installed
#                          already, and an emulator booted; never builds.
#   --apk <path>           install this app APK instead of building one
#   --mode <m>             SMOKE | STANDARD | FULL (driver semantics)
#   --timeout <minutes>    per-install-cycle timeout (default 30)
#   --keep-data            skip the initial pm clear (local re-runs)
#   --collect-diagnostics  run testing/collect-diagnostics.sh at the end
#   --verbose              pass through to the driver
#
# Local requirements: a booted emulator visible to adb (the script refuses with
# a clear message otherwise), and JAVA/Android SDK for the build steps. CI has
# none of those needs - it drives this with --ci after its own build+boot steps.
set -euo pipefail

cd "$(dirname "$0")/.."

MODE=SMOKE
TIMEOUT=30
EXTRA=()
APK_OVERRIDE=""
CI=no
KEEP_DATA=no
COLLECT_DIAG=no

while [ $# -gt 0 ]; do
  case "$1" in
    --ci) CI=yes ;;
    --apk) APK_OVERRIDE="$2"; shift ;;
    --mode) MODE="$2"; shift ;;
    --timeout) TIMEOUT="$2"; shift ;;
    --keep-data) KEEP_DATA=yes ;;
    --collect-diagnostics) COLLECT_DIAG=yes ;;
    --verbose) EXTRA+=(--verbose) ;;
    *) echo "unknown option: $1" >&2; exit 64 ;;
  esac
  shift
done

ADB="${ADB:-adb}"
OUT=out/e2e-ubuntu
APP_PACKAGE=dev.eclipse.ssh
TEST_PACKAGE=dev.eclipse.ssh.test

mkdir -p "$OUT"

if [ "$CI" = no ]; then
  # A developer machine must meet the pipeline halfway: an emulator that is
  # actually booted. Waiting for one silently is how a local run turns into a
  # twenty-minute hang that ends in confusion.
  if ! "$ADB" shell getprop sys.boot_completed 2>/dev/null | grep -q '^1'; then
    echo "no booted emulator visible to adb." >&2
    echo "start one first, e.g.: \$ANDROID_HOME/emulator/emulator -avd <name>" >&2
    exit 66
  fi
  if [ -n "$APK_OVERRIDE" ]; then
    APK="$APK_OVERRIDE"
  else
    # The debug build type carries the .debug applicationId suffix - the driver
    # must target that package or every adb call silently misses the app.
    APP_PACKAGE=dev.eclipse.ssh.debug
    TEST_PACKAGE=dev.eclipse.ssh.debug.test
    ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
    APK="$(find app/build/outputs/apk/debug -name '*x86_64*.apk' | head -1)"
    [ -n "$APK" ] || APK="$(find app/build/outputs/apk/debug -name '*.apk' | head -1)"
    TEST_APK="$(find app/build/outputs/apk/androidTest/debug -name '*.apk' | head -1)"
  fi
  [ -n "$APK" ] && [ -f "$APK" ] || { echo "no app APK to test"; exit 66; }
  "$ADB" install -r "$APK"
  # The deep-verification phases run through the instrumented test APK.
  if [ -n "${TEST_APK:-}" ] && [ -f "$TEST_APK" ]; then
    "$ADB" install -r "$TEST_APK"
  else
    echo "no androidTest APK found - the verify phases will fail" >&2
  fi
else
  # CI mode: the workflow built, signed and installed everything already; the
  # driver only needs the device and the test APK present.
  : "${APK:?CI mode needs \$APK set}"
  : "${TEST_APK:?CI mode needs \$TEST_APK set}"
fi

[ "$KEEP_DATA" = yes ] && EXTRA+=(--keep-data)

RC=0
python3 testing/ubuntu-e2e/driver.py \
  --package "$APP_PACKAGE" --test-package "$TEST_PACKAGE" \
  --out "$OUT" --mode "$MODE" --install-timeout "$TIMEOUT" "${EXTRA[@]}" || RC=$?

if [ "$COLLECT_DIAG" = yes ] && [ -x testing/collect-diagnostics.sh ]; then
  bash testing/collect-diagnostics.sh "$OUT" "$APP_PACKAGE" post-e2e || true
fi

exit "$RC"
