#!/usr/bin/env bash
# Verifies that the APK under test is the real distribution artifact, not a
# lookalike: package name, versionCode/versionName against the checked-out
# source, the native ABI the caller expects, no debuggable flag, and a valid
# v2+v3 signature. Writes a machine-readable release-validation.json and exits
# non-zero on any mismatch, so the workflow fails before a bad APK is ever
# installed on the emulator.
#
# The expected ABI is an argument rather than a constant. It was x86_64 once,
# because the only caller tested the emulator leg - but x86_64 is the ABI *no
# user runs*: the release splits that reach a phone are arm64-v8a first, and a
# verifier that could only name the emulator's ABI could never check them.
#
# Usage: verify-release-apk.sh <apk> <expected-version-name> <expected-version-code> <out-json> <expected-abi>
set -euo pipefail

APK="$1"
EXPECTED_VERSION_NAME="$2"
EXPECTED_VERSION_CODE="$3"
OUT_JSON="$4"
EXPECTED_ABI="${5:?the expected ABI must be named (e.g. arm64-v8a, x86_64) - \
a verifier that guesses it cannot check the split it was handed}"

: "${ANDROID_HOME:?ANDROID_HOME must be set}"
: "${BUILD_TOOLS:?BUILD_TOOLS must be set}"
AAPT2="$ANDROID_HOME/build-tools/$BUILD_TOOLS/aapt2"
APKSIGNER="$ANDROID_HOME/build-tools/$BUILD_TOOLS/apksigner"

errors=()

badging="$("$AAPT2" dump badging "$APK")"
package_line="$(printf '%s\n' "$badging" | sed -n "s/^package: name='\([^']*\)' versionCode='\([0-9]*\)' versionName='\([^']*\)'.*$/\1 \2 \3/p" | head -1)"
read -r actual_package actual_version_code actual_version_name <<<"$package_line"

if [ "$actual_package" != "dev.eclipse.ssh" ]; then
  errors+=("package name is '$actual_package', expected dev.eclipse.ssh")
fi
if [ -n "$EXPECTED_VERSION_NAME" ] && [ "$actual_version_name" != "$EXPECTED_VERSION_NAME" ]; then
  errors+=("versionName is '$actual_version_name', the checked-out source says '$EXPECTED_VERSION_NAME'")
fi
if [ -n "$EXPECTED_VERSION_CODE" ] && [ "$actual_version_code" != "$EXPECTED_VERSION_CODE" ]; then
  errors+=("versionCode is '$actual_version_code', the checked-out source says '$EXPECTED_VERSION_CODE'")
fi

# The caller names the ABI it means to be testing, and the APK must carry it.
# Naming it is what makes a per-ABI split checkable at all: the same script
# now verifies the arm64-v8a split a phone downloads and the x86_64 split the
# emulator runs, without either caller inheriting the other's assumption.
if ! printf '%s\n' "$badging" | grep -q "native-code: '$EXPECTED_ABI'"; then
  actual_abis="$(printf '%s\n' "$badging" | sed -n "s/^native-code: \(.*\)$/\1/p" | head -1)"
  errors+=("APK does not carry the $EXPECTED_ABI native code the caller expects (it carries: ${actual_abis:-none})")
fi

# A debuggable "release" APK is not a distribution artifact.
if printf '%s\n' "$badging" | grep -q '^application-debuggable'; then
  errors+=("APK is debuggable - this is not a release build")
fi

# v2+v3 signatures, per the signing config in app/build.gradle.kts.
if ! "$APKSIGNER" verify "$APK" 2>/dev/null; then
  errors+=("apksigner verify failed - signature is invalid")
fi
cert_digest="$("$APKSIGNER" verify --print-certs "$APK" 2>/dev/null | sed -n "s/^Signer #1 certificate SHA-256 digest: \(.*\)$/\1/p" | head -1)"
if [ -z "$cert_digest" ]; then
  errors+=("could not read the signer certificate digest")
fi

apk_sha256="$(sha256sum "$APK" | cut -d' ' -f1)"
apk_bytes="$(stat -c%s "$APK")"

STATUS="pass"
if [ "${#errors[@]}" -gt 0 ]; then
  STATUS="fail"
fi

# JSON via python so escaping is never a hand-rolled bug.
python3 - "$OUT_JSON" "$STATUS" "$actual_package" "$actual_version_code" "$actual_version_name" \
  "$cert_digest" "$apk_sha256" "$apk_bytes" "$EXPECTED_ABI" "${errors[@]+"${errors[@]}"}" <<'PY'
import json, sys
out, status, pkg, code, name, digest, sha, size, abi = sys.argv[1:10]
errors = sys.argv[10:]
doc = {
    "status": status,
    "apk": {
        "package": pkg,
        "versionCode": code,
        "versionName": name,
        "signerSha256": digest,
        "sha256": sha,
        "bytes": int(size),
    },
    # Which ABI this artifact was checked to carry - the whole point of the
    # split, and the field a reader needs to tell an arm64 run from an
    # emulator run in the evidence.
    "expectedAbi": abi,
}
if errors:
    doc["errors"] = errors
with open(out, "w") as f:
    json.dump(doc, f, indent=2)
    f.write("\n")
PY

cat "$OUT_JSON"
if [ "$STATUS" != "pass" ]; then
  for e in "${errors[@]}"; do
    echo "release APK verification FAILED: $e" >&2
  done
  exit 1
fi
echo "release APK verification passed"
