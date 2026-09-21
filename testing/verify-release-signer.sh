#!/usr/bin/env bash
# Fails when an APK about to be published was not signed by the release key.
#
# Why this exists: `tagged-release.yml` reports "Signed with the release key"
# whenever a keystore and a `keystore.properties` were present on the runner.
# That is a check that *a* key signed the build, never that *the* key did - and
# an APK signed with the wrong key installs fine on a fresh device while
# refusing to install over anything, which is not a failure any build step sees.
# v1.5.0 shipped that way: signed by a keystore regenerated on 2026-08-15 that
# replaced the canonical one in the checkout, and every existing install answered
# INSTALL_FAILED_UPDATE_INCOMPATIBLE. See AUDIT-REPORT.md section 56.
#
# The expected certificate is the one every release from v1.1.20 through v1.4.0
# carries. Rotating the release key is a deliberate act that has to edit this
# file in its own commit, with the reinstall that rotation costs stated in the
# release notes - which is the point: the value is a tripwire, not a default.
#
# Usage: testing/verify-release-signer.sh <apk> [<apk> ...]
# Reads ANDROID_HOME (or ANDROID_SDK_ROOT) and BUILD_TOOLS, as the SDK's own
# shell wrappers do.
set -euo pipefail

EXPECTED="0c69794b7934452bdf1b2f1f345314e13b86ef43f0d17bec9ff260dd30a443be"

if [ "$#" -eq 0 ]; then
  echo "usage: $0 <apk> [<apk> ...]" >&2
  exit 2
fi

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then
  echo "neither ANDROID_HOME nor ANDROID_SDK_ROOT is set" >&2
  exit 2
fi
BUILD_TOOLS="${BUILD_TOOLS:-36.0.0}"
APKSIGNER="$SDK/build-tools/$BUILD_TOOLS/apksigner"
if [ ! -x "$APKSIGNER" ]; then
  echo "no apksigner at $APKSIGNER" >&2
  exit 2
fi

fail=0
for apk in "$@"; do
  if [ ! -f "$apk" ]; then
    echo "FAIL $apk: no such file" >&2
    fail=1
    continue
  fi

  # `apksigner verify` fails outright on an unsigned or corrupt APK, so its exit
  # status is checked before the certificate output is parsed - a mangled APK
  # must not read as a signature mismatch.
  if ! verify_out="$("$APKSIGNER" verify --print-certs "$apk" 2>&1)"; then
    echo "FAIL $apk: apksigner could not verify it"
    printf '%s\n' "$verify_out" | sed 's/^/    /'
    fail=1
    continue
  fi

  # Every certificate apksigner reports, which includes any past signer a v3
  # rotation lineage carries. All of them have to be the expected one: a lineage
  # that ends at the right key is still an APK whose history is not this app's.
  digests="$(printf '%s\n' "$verify_out" | sed -n 's/.*certificate SHA-256 digest: //p')"
  if [ -z "$digests" ]; then
    echo "FAIL $apk: apksigner reported no certificate digest"
    fail=1
    continue
  fi

  apk_ok=1
  while IFS= read -r got; do
    if [ "$got" != "$EXPECTED" ]; then
      apk_ok=0
      echo "FAIL $apk: signed by $got"
      echo "            expected  $EXPECTED"
    fi
  done <<< "$digests"

  if [ "$apk_ok" = "1" ]; then
    dn="$(printf '%s\n' "$verify_out" | sed -n 's/.*certificate DN: //p' | head -1)"
    schemes="$(printf '%s\n' "$("$APKSIGNER" verify --verbose "$apk" 2>/dev/null | sed -n 's/^Verified using \(v[0-9]*\) scheme.*/\1/p' | tr '\n' ' ')" | sed 's/ *$//')"
    echo "ok   $apk: $dn (schemes: ${schemes:-none})"
    case " $schemes " in
      *" v2 "*) ;;
      *) echo "     no v2 signature, which is the scheme every install since API 24 uses"; fail=1;;
    esac
  else
    fail=1
  fi
done

if [ "$fail" != "0" ]; then
  echo
  echo "At least one artifact is not signed by the canonical release key. Do not publish it:"
  echo "an APK signed by a different key cannot be installed over any existing install."
  exit 1
fi

echo
echo "All artifacts are signed by the canonical release key."
