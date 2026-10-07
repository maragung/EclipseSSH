#!/usr/bin/env bash
#
# The test for scripts/check-instrumentation-keeps.sh. Run it from anywhere; it walks up to the
# repository root itself and writes its fixtures into a temporary directory it removes again.
#
# Why it exists. That checker is the guard between an androidTest source that names a class in the
# release APK's minified dex and a release that dies on an emulator with NoClassDefFoundError, so a
# check that *passes for a reason of its own* is worse than no check: it retires the question. Release
# CI run 37587054967 is that failure - the step reported `androidx.compose.ui.semantics.SemanticsActions`
# as not kept, on a keep file that carries it on line 384, and the same tree had passed the same step
# on the pull request minutes earlier. The cause was `printf ... | grep -qxF`, where `grep -q` exits
# before the writer finishes and `pipefail` turns the writer's SIGPIPE into "not kept".
#
# The cases below, and what each one can fail for on its own:
#
#   green on the keep list as it stands        - the checker works on the sources it guards
#   green on a keep list padded past a pipe    - the race above, reachable because the padded list is
#     buffer                                     longer than the writer's buffer; the piped spelling
#                                                fails this one every time, not sometimes
#   red, naming the class, when an import's    - the import pass still reports a real miss
#     rule is dropped
#   red, naming the nested class, when its     - the nested pass still reports a real miss
#     rule is dropped
#   refuses a keep file that is not there      - a missing input is an error, not an empty pass
#
# What it does not do: run Gradle, or read the release APK. The checker is static by design and so is
# this.
set -uo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
checker="$root/scripts/check-instrumentation-keeps.sh"
keep="$root/app/proguard-instrumentation.pro"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

passed=0
failures=0
ok() { printf 'ok   %s\n' "$*"; passed=$((passed + 1)); }
bad() { printf 'FAIL %s\n' "$*" >&2; failures=$((failures + 1)); }

# Runs the checker against $1 and captures stdout+stderr in $out and the status in $status.
out=""
status=0
run_checker() {
  out="$("$checker" "$1" 2>&1)"
  status=$?
}

# 1. The keep list as the repository has it.
run_checker "$keep"
if [ "$status" -eq 0 ]; then
  ok "the keep list as it stands passes"
else
  bad "the keep list as it stands failed (exit $status): $out"
fi

# 2. The race. The real keep list, with enough padding appended that the writer cannot finish before
#    a reader that matches early has gone - which is what `printf ... | grep -q` needs to die. 40,000
#    rules is ~1.6 MB against a 64 KiB pipe.
padded="$work/padded.pro"
{
  cat "$keep"
  seq -f '-keep class zzt.pad.Pad%05.0f { *; }' 1 40000
} > "$padded"
run_checker "$padded"
if [ "$status" -eq 0 ]; then
  ok "a keep list padded to $(wc -c < "$padded") bytes passes"
else
  bad "a keep list padded to $(wc -c < "$padded") bytes failed (exit $status): $out"
fi

# 3. An import whose rule is gone. The import pass must still see it.
no_outer="$work/no-outer.pro"
grep -vxF -- '-keep class androidx.compose.ui.semantics.SemanticsActions { *; }' "$keep" > "$no_outer"
run_checker "$no_outer"
if [ "$status" -ne 0 ] && case "$out" in *"androidx.compose.ui.semantics.SemanticsActions is referenced"*) true ;; *) false ;; esac; then
  ok "a dropped import rule is reported by name"
else
  bad "a dropped import rule was not reported (exit $status): $out"
fi

# 4. The nested class the same file reaches through its outer name, with only its own rule gone. The
#    outer class stays kept, so this is the pass that reads `SemanticsActions.OnClick` in the sources.
no_nested="$work/no-nested.pro"
grep -vxF -- '-keep class androidx.compose.ui.semantics.SemanticsActions$OnClick { *; }' "$keep" > "$no_nested"
run_checker "$no_nested"
if [ "$status" -ne 0 ] && case "$out" in *'SemanticsActions$OnClick'*) true ;; *) false ;; esac; then
  ok "a dropped nested-class rule is reported by name"
else
  bad "a dropped nested-class rule was not reported (exit $status): $out"
fi

# 5. No keep file at all.
run_checker "$work/does-not-exist.pro"
if [ "$status" -eq 2 ]; then
  ok "a missing keep file is refused with exit 2"
else
  bad "a missing keep file gave exit $status instead of 2: $out"
fi

printf '%s\n' "$passed case(s) passed, $failures failed"
[ "$failures" -eq 0 ]
