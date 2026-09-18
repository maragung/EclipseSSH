#!/usr/bin/env bash
#
# Fails when an androidTest source references, by name, a class that lives in the release APK's
# minified dex and is not in proguard-instrumentation.pro's keep list. Run it from anywhere; it walks
# up to the repository root itself.
#
# Why this exists. Release-test run 35338666049 (2026-09-18) failed both legs with
#   NoClassDefFoundError: Landroidx/core/view/WindowCompat;
# under SystemBarAppearanceTest. WindowCompat is an app dependency, so it ships in the release APK
# and nothing carries it into the test APK's own dex - the test resolves it out of the minified app
# dex, by name. R8 renamed it, the name was not kept, the reference died. The fix is two lines in the
# keep file; this is what keeps the next such reference from reaching a release.
#
# The check is source-side and static on purpose. It cannot be done against the built APK: R8 renames
# the very classes this guard is about, so a scan of the offending v1.2.0 dex finds no
# Landroidx/core/view/WindowCompat; at all - the rule "an androidTest import that resolves into the
# app dex must be kept" would have been silent on the failure it is named for. What is stable across
# builds is which namespaces the release APK carries versus which travel in the test APK, so the
# scope below is written in those terms.
#
# It is a check, never a fixer. Adding a name the suite genuinely needs is a one-line edit to the
# keep file, and that edit is a decision about what R8 may shrink - not one a script should make.
#
# What it does not cover, so that a green run is not read as more than it is. The scan reads
# *imports*, and two shapes of reference need none:
#
#   - A class named from the test's own package. The suite lives in dev.eclipse.ssh.linux, so
#     UbuntuE2eVerificationTest's `state is LinuxUserspaceState.Stopped` resolves without an import
#     and this script never sees the name. The two LinuxUserspaceState keeps in the keep file are a
#     manual entry for exactly that reason, and nothing here reddens if a future same-package
#     reference is not kept.
#   - The application's own namespace (dev.eclipse.ssh.*), which is left out on purpose rather than
#     by omission. Most classes there are already protected by other rules - Hilt's generated
#     components, the manifest's components, AGP's defaults - so requiring an explicit keep for each
#     would produce a list of names that do not need keeping, and a guard that cries wolf is worse
#     than one with a stated limit.
#
# Usage: scripts/check-instrumentation-keeps.sh [keep-file]
set -uo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root" || exit 2

keep_file="${1:-app/proguard-instrumentation.pro}"
test_dir="app/src/androidTest"

failures=0
checked=0

fail() {
  printf 'FAIL %s\n' "$*" >&2
  failures=$((failures + 1))
}

[ -f "$keep_file" ] || { echo "No keep file at $keep_file" >&2; exit 2; }
[ -d "$test_dir" ] || { echo "No androidTest sources at $test_dir" >&2; exit 2; }

# The kept names, one per line, dotted. `-keep class X { *; }` and the same with `$`-nested classes.
kept=$(
  sed -nE 's/^-keep class ([^ ]+) \{.*/\1/p' "$keep_file"
)
kept_packages=$(
  printf '%s\n' "$kept" | sed -E 's/\.[A-Za-z0-9_$]+$//' | sort -u
)

# Namespaces the release APK carries and the test APK therefore borrows by name. Every import under
# one of these must be kept. A new app dependency that the suite touches belongs here as well.
in_scope_re='^androidx\.(core|lifecycle|room|activity|collection|savedstate|compose\.runtime|compose\.ui)\.|^kotlin\.|^kotlinx\.coroutines\.'

# Namespaces that travel in the test APK's own dex, so a name there survives without a keep entry.
# They are excluded because they are not borrowed: androidx.test and org.junit are the runner and the
# assertion libraries; compose-ui-test and coroutines-test are androidTestImplementation artifacts
# whose classes are packaged into the test APK. Verified against the current sources - the only
# imports under them are androidx/compose/ui/test's assertions and rules and kotlinx/coroutines/test's
# runTest, none of which appears in the keep list, and the suite compiles and runs.
out_of_scope_re='^androidx\.test\.|^androidx\.compose\.ui\.test\.|^kotlin\.test\.|^kotlinx\.coroutines\.test\.|^org\.junit\.|^com\.google\.common\.|^junit\.|^android\.|^java\.|^javax\.|^dalvik\.|^org\.hamcrest\.|^org\.mockito\.|^okhttp3\.|^okio\.'

# Matched with grep rather than `case`, so these stay the EREs they are written as: a case pattern is
# a glob, where the `(a|b)` above would be a literal paren and match nothing.
out_of_scope() { printf '%s\n' "$1" | grep -qE "$out_of_scope_re"; }
in_scope() { printf '%s\n' "$1" | grep -qE "$in_scope_re"; }

while IFS= read -r import_name; do
  out_of_scope "$import_name" && continue
  in_scope "$import_name" || continue
  checked=$((checked + 1))

  # A class-shaped import (last segment capitalised) must be in the keep list exactly. A function or
  # property import (last segment lowercase) names no class of its own - its owner is a file facade
  # like FlowKt that the import does not spell - so the strongest honest check is that a facade in the
  # same package is kept. That is where kotlinx.coroutines.flow.first is answered by the
  # kotlinx.coroutines.flow.FlowKt entry beside it.
  last="${import_name##*.}"
  case "$last" in
    [A-Z]*)
      if ! printf '%s\n' "$kept" | grep -qxF "$import_name"; then
        fail "$import_name is referenced by the instrumentation sources but not kept, and it ships in the release APK's minified dex - R8 may rename or remove it. Add: -keep class $import_name { *; }"
      fi
      ;;
    *)
      package="${import_name%.*}"
      if ! printf '%s\n' "$kept_packages" | grep -qxF "$package"; then
        fail "$import_name is a top-level function or property from $package, whose classes all ship in the release APK's minified dex, and no class in that package is kept. Keep the facade that owns it."
      fi
      ;;
  esac
done < <(
  # Every import in the instrumentation sources, without the `as` alias, without duplicates, and
  # without the star imports the loop below refuses separately.
  grep -rhoE '^import [A-Za-z0-9_.]+' "$test_dir" --include='*.kt' |
    sed -E 's/^import //' | grep -vE '\.$' | sort -u
)

# A star import under an in-scope namespace is unverifiable by construction: it names no class, so
# neither this script nor the keep list can be checked against it. Refuse rather than pass quietly.
while IFS= read -r star; do
  out_of_scope "$star" && continue
  if in_scope "$star"; then
    fail "star import $star in the instrumentation sources names no class, so this check cannot see what it needs. Import the class explicitly."
  fi
done < <(
  grep -rhoE '^import [A-Za-z0-9_.]+\.\*' "$test_dir" --include='*.kt' |
    sed -E 's/^import //' | sort -u
)

if [ "$failures" -gt 0 ]; then
  printf '%s\n' "$failures reference(s) the keep file must answer; $checked in-scope import(s) checked." >&2
  exit 1
fi
echo "Instrumentation keeps: $checked in-scope import(s) checked against $(printf '%s\n' "$kept" | wc -l) kept classes, all answered."
