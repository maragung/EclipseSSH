#!/usr/bin/env bash
# Verifies every per-ABI release split in a build's output directory.
#
# This is the ABI-to-library mapping, in one place. The mapping is not uniform -
# :freerdp builds four ABIs and :linux three - so it cannot be inferred from a
# directory listing, and a copy of it per workflow would drift the first time an
# ABI is added or dropped. Both ci.yml (on every PR) and tagged-release.yml (on
# the artifacts that actually ship) call this, so the two can never disagree
# about what a correct split is.
#
# Each split is passed to verify-split-apk.sh, which checks the ABI it carries
# and no other, the libraries, that each is an ELF of the architecture it is
# filed under, and extractNativeLibs. Fails on the first bad split.
#
# Usage: verify-release-splits.sh <apk-dir> <out-dir>
set -euo pipefail

APK_DIR="${1:?the directory holding the per-ABI release APKs must be named}"
OUT_DIR="${2:?the directory for the per-ABI verdict JSON must be named}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

declare -A ELF_SIGNATURE=(
  [arm64-v8a]='ARM aarch64'
  [armeabi-v7a]='ARM, EABI5'
  [x86_64]='x86-64'
  [x86]='Intel 80386'
)
FREERDP_LIBS=(libfreerdp-android libfreerdp3 libfreerdp-client3 libwinpr3
              libcrypto libssl libcjson liburiparser)
LINUX_LIBS=(libproot libproot-loader liblinuxpty)

mkdir -p "$OUT_DIR"
for ABI in arm64-v8a armeabi-v7a x86_64 x86; do
  LIBS=("${FREERDP_LIBS[@]}")
  if [ "$ABI" != "x86" ]; then
    # :linux builds no x86 toolchain, so the x86 split legitimately carries
    # FreeRDP's eight and none of the userspace's three. Asserting eleven there
    # would fail on a correct build; asserting eight everywhere would stop
    # noticing a dropped proot on the three ABIs that must have it.
    LIBS+=("${LINUX_LIBS[@]}")
  fi
  "$HERE/verify-split-apk.sh" \
    "$APK_DIR/app-${ABI}-release.apk" \
    "$ABI" "${ELF_SIGNATURE[${ABI}]}" \
    "$OUT_DIR/${ABI}.json" "${LIBS[@]}"
done

echo "all per-ABI release splits verified"
