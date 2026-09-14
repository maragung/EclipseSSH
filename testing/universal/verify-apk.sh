#!/usr/bin/env bash
# Universal pre-install APK verification. Unlike the app-specific
# testing/verify-release-apk.sh, nothing about the app is known in advance:
# aapt2 states what the APK is, and this script checks that it is installable
# and testable on an x86_64 emulator - a readable package, a launchable
# activity (the engine needs one to start), a valid v2+ signature, and either
# no native code or native code that includes x86_64. Writes a
# machine-readable JSON and exits non-zero on any error, so a bad APK never
# reaches the emulator.
#
# Usage: verify-apk.sh <apk> <out-json> [strict]
#   strict (any value, typically the RELEASE mode) also fails on a
#   debuggable flag; non-strict records it as a warning.
set -euo pipefail

APK="$1"
OUT_JSON="$2"
STRICT="${3:-}"

: "${ANDROID_HOME:?ANDROID_HOME must be set}"
: "${BUILD_TOOLS:?BUILD_TOOLS must be set}"
AAPT2="$ANDROID_HOME/build-tools/$BUILD_TOOLS/aapt2"
APKSIGNER="$ANDROID_HOME/build-tools/$BUILD_TOOLS/apksigner"

errors=()
warnings=()

badging="$("$AAPT2" dump badging "$APK")"
package_line="$(printf '%s\n' "$badging" | sed -n "s/^package: name='\([^']*\)' versionCode='\([0-9]*\)' versionName='\([^']*\)'.*$/\1 \2 \3/p" | head -1)"
read -r actual_package actual_version_code actual_version_name <<<"$package_line"

if [ -z "$actual_package" ]; then
  errors+=("aapt2 could not read a package name - the APK is not installable")
fi

launchable="$(printf '%s\n' "$badging" | sed -n "s/^launchable-activity: name='\([^']*\)'.*$/\1/p" | head -1)"
if [ -z "$launchable" ]; then
  errors+=("no launchable activity - the engine cannot start the app")
fi

# The emulator is x86_64: native code without that ABI cannot run; no native
# code at all (a pure-Java/Kotlin app) is fine and expected.
if printf '%s\n' "$badging" | grep -q '^native-code:'; then
  if ! printf '%s\n' "$badging" | grep -q "native-code:.*'x86_64'"; then
    errors+=("APK ships native code but not the x86_64 ABI the emulator runs")
  fi
fi

# A debuggable APK is not a distribution artifact; only the strict (RELEASE)
# gate treats it as an error, everything else records the fact.
if printf '%s\n' "$badging" | grep -q '^application-debuggable'; then
  if [ -n "$STRICT" ]; then
    errors+=("APK is debuggable - not a release artifact (strict gate)")
  else
    warnings+=("APK is debuggable (non-strict gate: recorded, not failed)")
  fi
fi

# v2+v3 signature: unsigned or tampered APKs fail here, never on the device.
if ! "$APKSIGNER" verify "$APK" 2>/dev/null; then
  errors+=("apksigner verify failed - signature is invalid")
fi
cert_digest="$("$APKSIGNER" verify --print-certs "$APK" 2>/dev/null | sed -n "s/^Signer #1 certificate SHA-256 digest: \(.*\)$/\1/p" | head -1)"
if [ -z "$cert_digest" ]; then
  errors+=("could not read the signer certificate digest")
fi

min_sdk="$(printf '%s\n' "$badging" | sed -n "s/^sdkVersion:'\([0-9]*\)'.*$/\1/p" | head -1)"
target_sdk="$(printf '%s\n' "$badging" | sed -n "s/^targetSdkVersion:'\([0-9]*\)'.*$/\1/p" | head -1)"

apk_sha256="$(sha256sum "$APK" | cut -d' ' -f1)"
apk_bytes="$(stat -c%s "$APK")"

STATUS="pass"
if [ "${#errors[@]}" -gt 0 ]; then
  STATUS="fail"
fi

# JSON via python so escaping is never a hand-rolled bug. The warning count
# is an explicit argument so the two lists never blur together.
python3 - "$OUT_JSON" "$STATUS" "$actual_package" "$actual_version_code" \
  "$actual_version_name" "$launchable" "$min_sdk" "$target_sdk" \
  "$cert_digest" "$apk_sha256" "$apk_bytes" "${#warnings[@]}" \
  "${warnings[@]+"${warnings[@]}"}" "${errors[@]+"${errors[@]}"}" <<'PY'
import json, sys
(out, status, pkg, code, name, launchable, minsdk, targetsdk,
 digest, sha, size, nwarn) = sys.argv[1:13]
rest = sys.argv[13:]
nwarn = int(nwarn)
warnings, errors = rest[:nwarn], rest[nwarn:]
doc = {
    "status": status,
    "apk": {
        "package": pkg,
        "versionCode": code,
        "versionName": name,
        "launchableActivity": launchable,
        "minSdk": minsdk,
        "targetSdk": targetsdk,
        "signerSha256": digest,
        "sha256": sha,
        "bytes": int(size),
    },
}
if warnings:
    doc["warnings"] = warnings
if errors:
    doc["errors"] = errors
with open(out, "w") as f:
    json.dump(doc, f, indent=2)
    f.write("\n")
PY

cat "$OUT_JSON"
if [ "$STATUS" != "pass" ]; then
  for e in "${errors[@]}"; do
    echo "APK verification FAILED: $e" >&2
  done
  exit 1
fi
echo "APK verification passed (package=$actual_package version=$actual_version_name launchable=$launchable)"
