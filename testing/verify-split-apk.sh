#!/usr/bin/env bash
# Verifies that a per-ABI release split is what it claims to be, from the
# outside: the ABI it carries and no other, the native libraries the app links
# and execs, and that each of those libraries is an ELF of the architecture it
# is filed under.
#
# Why this exists next to verify-release-apk.sh: that script answers "is this
# the right app, signed, at the right version" - questions whose answers are the
# same for every ABI. This one answers "is this the *arm64-v8a* artifact", which
# is the question nobody was asking. The splits are what users download, and
# before this script the only thing CI checked about one was its signature.
#
# The caller names the ABI, the ELF signature and the libraries, for the same
# reason verify-release-apk.sh's caller names the ABI: a checker that assumes
# cannot check the artifact it was handed. It also means the same script checks
# whichever split a caller cares about, rather than there being one hard-coded
# blessed ABI and four unexamined ones.
#
# Usage: verify-split-apk.sh <apk> <expected-abi> <expected-elf-signature> <out-json> <lib> [<lib>...]
#   e.g. verify-split-apk.sh app-arm64-v8a-release.apk arm64-v8a 'ARM aarch64' \
#          out/split.json libproot libproot-loader liblinuxpty libfreerdp-android ...
set -euo pipefail

APK="$1"
EXPECTED_ABI="$2"
EXPECTED_ELF="$3"
OUT_JSON="$4"
shift 4
LIBS=("$@")

: "${ANDROID_HOME:?ANDROID_HOME must be set}"
: "${BUILD_TOOLS:?BUILD_TOOLS must be set}"
AAPT2="$ANDROID_HOME/build-tools/$BUILD_TOOLS/aapt2"

errors=()

if [ ! -f "$APK" ]; then
  echo "verify-split-apk.sh: no such APK: $APK" >&2
  exit 1
fi
if [ "${#LIBS[@]}" -eq 0 ]; then
  # A caller that names no libraries would otherwise pass every loop below
  # vacuously - the check would report success having asserted nothing.
  echo "verify-split-apk.sh: no libraries named, so there is nothing to verify" >&2
  exit 1
fi

# ------------------------------------------------------------------ purity
#
# A split must carry exactly its own ABI. Two ABI directories means the split
# is really a universal APK wearing a per-ABI name - which ships every user the
# native payload of three architectures they cannot run, and is exactly the
# regression the size promise of splitting is for. None means the ABI's native
# code was dropped, which is the failure this whole script exists to catch.
mapfile -t abi_dirs < <(
  unzip -Z1 "$APK" 'lib/*' 2>/dev/null | sed -n 's#^lib/\([^/]*\)/.*#\1#p' | sort -u
)
if [ "${#abi_dirs[@]}" -ne 1 ] || [ "${abi_dirs[0]:-}" != "$EXPECTED_ABI" ]; then
  errors+=("the split is not a pure $EXPECTED_ABI APK - lib/ carries: ${abi_dirs[*]:-nothing}")
fi

# ------------------------------------------------------------- the libraries
#
# Each named library must be present under lib/<abi>/ and must actually be an
# ELF for that architecture. Presence alone is not enough: a packaging mistake
# that filed one ABI's binary under another's directory would install cleanly
# and then die at exec or dlopen, which is the class of bug that only ever
# reproduces on a real device.
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
present=()
for LIB in "${LIBS[@]}"; do
  entry="lib/${EXPECTED_ABI}/${LIB}.so"
  if [ -z "$(unzip -Z1 "$APK" "$entry" 2>/dev/null)" ]; then
    errors+=("$entry is missing from the split")
    continue
  fi
  present+=("$LIB")
  unzip -p "$APK" "$entry" > "$tmp/${LIB}.so"
  desc="$(file -b "$tmp/${LIB}.so")"
  case "$desc" in
    *"$EXPECTED_ELF"*) ;;
    *) errors+=("$entry is not a ${EXPECTED_ELF} ELF: $desc") ;;
  esac
done

# ------------------------------------------------------- extractNativeLibs
#
# The property that makes the userspace runnable at all. proot and its loader
# are execve'd out of nativeLibraryDir, which is the one directory a targetSdk
# 29+ app may execute from - and a file only exists there if the platform
# extracted it at install, which it only does when extractNativeLibs is true.
# With it false the libraries stay inside the APK, mmap-able but not
# executable, and every userspace start dies with EACCES. app/build.gradle.kts
# sets it via packaging.jniLibs.useLegacyPackaging; this asserts the setting
# survived to the artifact, where it is the only place it actually matters.
#
# aapt2 failing is an error in its own right rather than a reason to exit.
# Under `set -e` a bare assignment would kill the script the moment aapt2 could
# not read the file - reporting nothing at all, from the one script whose job is
# to say what is wrong. A truncated or malformed APK is a real possibility in a
# release pipeline and deserves the diagnostic, not silence.
if manifest="$("$AAPT2" dump xmltree --file AndroidManifest.xml "$APK" 2>&1)"; then
  extract_native_libs="$(printf '%s\n' "$manifest" | sed -n 's/.*:extractNativeLibs([^)]*)=\([^ ]*\).*/\1/p' | head -1)"
  if [ "$extract_native_libs" != "true" ]; then
    errors+=("extractNativeLibs is ${extract_native_libs:-absent}, not true: the userspace execs proot out of nativeLibraryDir, and nothing is extracted there without it - every start would die with EACCES")
  fi
else
  errors+=("aapt2 could not read this APK's manifest, so it is not a well-formed APK: $(printf '%s' "$manifest" | head -2 | tr '\n' ' ')")
fi

apk_sha256="$(sha256sum "$APK" | cut -d' ' -f1)"
apk_bytes="$(stat -c%s "$APK")"

STATUS="pass"
if [ "${#errors[@]}" -gt 0 ]; then
  STATUS="fail"
fi

# JSON via python so escaping is never a hand-rolled bug.
python3 - "$OUT_JSON" "$STATUS" "$EXPECTED_ABI" "$EXPECTED_ELF" "$apk_sha256" "$apk_bytes" \
  "${#present[@]}" "${errors[@]+"${errors[@]}"}" <<'PY'
import json, sys
out, status, abi, elf, sha, size, lib_count = sys.argv[1:8]
errors = sys.argv[8:]
doc = {
    "status": status,
    "split": {
        "expectedAbi": abi,
        "expectedElf": elf,
        "librariesVerified": int(lib_count),
        "sha256": sha,
        "bytes": int(size),
    },
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
    echo "split APK verification FAILED: $e" >&2
  done
  exit 1
fi
echo "split APK verification passed: $EXPECTED_ABI, ${#present[@]} libraries, extractNativeLibs=true"
