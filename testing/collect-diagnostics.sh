#!/usr/bin/env bash
# Deterministic diagnostics collector. Runs after every test outcome - pass
# or fail - so a failure always ships with its evidence and a pass costs one
# mostly-empty artifact. Everything lands under the directory given as $1.
#
# Usage: collect-diagnostics.sh <out-dir> <package> [screenshot-name]
set -uo pipefail

OUT_DIR="$1"
PACKAGE="$2"
SHOT="${3:-diagnostic}"

mkdir -p "$OUT_DIR/logs" "$OUT_DIR/screenshots" "$OUT_DIR/dumpsys"

# Every adb call is guarded: the emulator can be gone by the time a fatal
# crash lands, and the collector's job is to preserve what exists, not to
# add its own failure to the pile.
adb_cmd() {
  ADB="${ANDROID_HOME:-}/platform-tools/adb"
  if [ -x "$ADB" ]; then
    "$ADB" "$@"
  else
    adb "$@"
  fi
}

if adb_cmd get-state >/dev/null 2>&1; then
  adb_cmd logcat -d -v time > "$OUT_DIR/logs/logcat.txt" 2>&1 || true
  adb_cmd shell dumpsys activity > "$OUT_DIR/dumpsys/activity.txt" 2>&1 || true
  adb_cmd shell dumpsys package "$PACKAGE" > "$OUT_DIR/dumpsys/package.txt" 2>&1 || true
  adb_cmd shell dumpsys meminfo "$PACKAGE" > "$OUT_DIR/dumpsys/meminfo.txt" 2>&1 || true
  adb_cmd shell dumpsys gfxinfo "$PACKAGE" > "$OUT_DIR/dumpsys/gfxinfo.txt" 2>&1 || true
  # ANR traces: readable on emulator images via run-as-free path only
  # sometimes; a permission denial is recorded, not fatal.
  adb_cmd shell "ls /data/anr 2>/dev/null && cat /data/anr/* 2>/dev/null" \
    > "$OUT_DIR/logs/anr-traces.txt" 2>&1 || true
  adb_cmd exec-out screencap -p > "$OUT_DIR/screenshots/$SHOT.png" 2>/dev/null || true
else
  echo "device is gone - no live diagnostics available" > "$OUT_DIR/logs/logcat.txt"
fi

# The environment record: which device, which build, which ABI - the facts a
# report needs to be reproducible.
{
  echo "date: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "package: $PACKAGE"
  adb_cmd shell getprop ro.build.version.sdk 2>/dev/null | sed 's/^/api_level: /' || true
  adb_cmd shell getprop ro.product.cpu.abi 2>/dev/null | sed 's/^/abi: /' || true
  adb_cmd shell getprop ro.product.model 2>/dev/null | sed 's/^/device: /' || true
  adb_cmd shell dumpsys package "$PACKAGE" 2>/dev/null \
    | grep -m1 'versionName' | sed 's/^ *//' || true
} > "$OUT_DIR/environment.txt" 2>&1

echo "diagnostics in $OUT_DIR:"
find "$OUT_DIR" -type f -printf '  %p (%s bytes)\n' | sort
