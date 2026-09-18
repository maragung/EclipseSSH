#!/usr/bin/env bash
#
# Fails when a document states a version figure the build no longer pins, or names a file that is not
# there. Run it from anywhere; it walks up to the repository root itself.
#
# The audit report's header claims its figures are "re-derivable rather than remembered". This is what
# re-derives them, so the claim stays true between passes instead of decaying into a number somebody
# typed once and nothing since has contradicted. It reads each value out of the file that owns it --
# app/build.gradle.kts, gradle/libs.versions.toml, gradle/wrapper/gradle-wrapper.properties,
# linux/build.gradle.kts -- and requires the documentation to agree.
#
# It is a check, never a fixer. A document that disagrees with the build is not always a stale
# document: sometimes the build is what changed by mistake, and a script that rewrote the prose would
# quietly hide that behind a corrected string. So it prints the disagreement and exits non-zero.
#
# Usage: scripts/check-doc-figures.sh
set -uo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root" || exit 2

failures=0
checked=0

fail() {
  printf 'FAIL %s\n' "$*" >&2
  failures=$((failures + 1))
}

# value <file> <sed-expression> -- first capture of the expression, or empty.
value() {
  sed -nE "$2" "$1" | head -1
}

catalog_version() { value gradle/libs.versions.toml "s/^$1[[:space:]]*=[[:space:]]*\"([^\"]+)\".*/\1/p"; }
android_value() { value app/build.gradle.kts "s/.*[^A-Za-z]$1[[:space:]]*=[[:space:]]*\"?([0-9][0-9A-Za-z.+-]*)\"?.*/\1/p"; }
wrapper_version() { value gradle/wrapper/gradle-wrapper.properties "s/.*gradle-([0-9][0-9.]*)-bin\.zip.*/\1/p"; }

# number <word-or-digit> -- 7 for both "7" and "seven". Small numbers only, which is all that occurs.
# Defined here with the other readers rather than beside its first caller, because the sections below
# run in file order and a function is only visible after the line that defines it.
number() {
  case "$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')" in
    1|one) echo 1 ;; 2|two) echo 2 ;; 3|three) echo 3 ;; 4|four) echo 4 ;; 5|five) echo 5 ;;
    6|six) echo 6 ;; 7|seven) echo 7 ;; 8|eight) echo 8 ;; 9|nine) echo 9 ;; 10|ten) echo 10 ;;
    *) echo "" ;;
  esac
}

# expect <file> <first-line> <last-line> <label> <wanted>
#
# Scoped to a line range on purpose. AUDIT-REPORT.md is a record of past passes, so "minSdk 28" appears
# in it dozens of times and most of those are quotations of what an older release said. Only the header
# block is a claim about the current build; the rest is history and is not checked.
expect() {
  local file="$1" from="$2" to="$3" label="$4" want="$5" got
  checked=$((checked + 1))
  got="$(sed -n "${from},${to}p" "$file" \
    | grep -oE "\\b${label}\\b[[:space:]]*[=:]?[[:space:]]*[0-9][0-9A-Za-z.+-]*" \
    | head -1 | grep -oE '[0-9][0-9A-Za-z.+-]*$' | sed -E 's/[.+-]+$//')"
  if [ -z "$got" ]; then
    fail "$file:$from-$to names no '$label' figure; the build pins $want"
  elif [ "$got" != "$want" ]; then
    fail "$file:$from-$to says '$label $got'; the build pins $want"
  fi
}

# expect_section <file> <heading-text> <label> <wanted>
#
# The same check as `expect`, scoped to a section rather than to a line range. A range is a claim
# about where a figure sits, and that is not what this check is about: `docs/THIRD-PARTY.md`'s range
# was 75-95, editing a paragraph above it moved SLF4J to line 96, and a correct document was reported
# as one that "names no 'SLF4J' figure". Scoping to the heading and the next `## ` keeps what the
# range was for — a figure quoted in a different section is a different claim — without pinning the
# document's line numbers, so a section may grow and shrink freely. A heading that no longer exists
# fails loudly and names itself, which is how a rename gets noticed.
expect_section() {
  local file="$1" heading="$2" label="$3" want="$4" got
  checked=$((checked + 1))
  got="$(awk -v h="$heading" '
    !seen && /^## / && index($0, h) { seen = 1; next }
    seen && /^## / { exit }
    seen' "$file" \
    | grep -oE "\\b${label}\\b[[:space:]]*[=:]?[[:space:]]*[0-9][0-9A-Za-z.+-]*" \
    | head -1 | grep -oE '[0-9][0-9A-Za-z.+-]*$' | sed -E 's/[.+-]+$//')"
  if [ -z "$got" ]; then
    fail "$file has no '$label' figure under a '$heading' heading; the build pins $want"
  elif [ "$got" != "$want" ]; then
    fail "$file says '$label $got' under a '$heading' heading; the build pins $want"
  fi
}

# Every workflow a document names must exist, because the usual way a workflow reference rots is a
# rename that leaves the prose pointing at a file nothing runs any more.
expect_workflows_exist() {
  local doc="$1" wf
  while read -r wf; do
    [ -n "$wf" ] || continue
    checked=$((checked + 1))
    [ -f ".github/workflows/$wf" ] || fail "$doc names .github/workflows/$wf, which does not exist"
  done < <(grep -ohE '[a-z0-9-]+\.ya?ml' "$doc" | sort -u)
}

# ---------------------------------------------------------------------------------------------
# The figures the build owns.
# ---------------------------------------------------------------------------------------------

v_version_code="$(android_value versionCode)"
v_version_name="$(android_value versionName)"
v_min_sdk="$(android_value minSdk)"
v_target_sdk="$(android_value targetSdk)"
v_compile_sdk="$(android_value compileSdk)"
v_kotlin="$(catalog_version kotlin)"
v_agp="$(catalog_version agp)"
v_ksp="$(catalog_version ksp)"
v_hilt="$(catalog_version hilt)"
v_room="$(catalog_version room)"
v_compose_bom="$(catalog_version composeBom)"
v_sshd="$(catalog_version sshd)"
v_bouncycastle="$(catalog_version bouncycastle)"
v_slf4j="$(catalog_version slf4j)"
v_gradle="$(wrapper_version)"

for pair in "versionCode=$v_version_code" "versionName=$v_version_name" "minSdk=$v_min_sdk" \
  "targetSdk=$v_target_sdk" "compileSdk=$v_compile_sdk" "kotlin=$v_kotlin" "agp=$v_agp" \
  "ksp=$v_ksp" "hilt=$v_hilt" "room=$v_room" "composeBom=$v_compose_bom" "sshd=$v_sshd" \
  "bouncycastle=$v_bouncycastle" "slf4j=$v_slf4j" "gradle=$v_gradle"; do
  [ -n "${pair#*=}" ] || fail "could not read ${pair%%=*} from the build files — this script is out of date"
done

# ---------------------------------------------------------------------------------------------
# AUDIT-REPORT.md, header block only (lines 1-8). Line 7 is the paragraph that promises this check.
# ---------------------------------------------------------------------------------------------

expect AUDIT-REPORT.md 1 8 versionCode         "$v_version_code"
expect AUDIT-REPORT.md 1 8 versionName         "$v_version_name"
expect AUDIT-REPORT.md 1 8 minSdk              "$v_min_sdk"
expect AUDIT-REPORT.md 1 8 targetSdk           "$v_target_sdk"
expect AUDIT-REPORT.md 1 8 compileSdk          "$v_compile_sdk"
expect AUDIT-REPORT.md 1 8 Kotlin              "$v_kotlin"
expect AUDIT-REPORT.md 1 8 AGP                 "$v_agp"
expect AUDIT-REPORT.md 1 8 Gradle              "$v_gradle"
expect AUDIT-REPORT.md 1 8 'Compose BOM'       "$v_compose_bom"
expect AUDIT-REPORT.md 1 8 Hilt                "$v_hilt"
expect AUDIT-REPORT.md 1 8 KSP                 "$v_ksp"
expect AUDIT-REPORT.md 1 8 Room                "$v_room"
expect AUDIT-REPORT.md 1 8 SSHD                "$v_sshd"
expect AUDIT-REPORT.md 1 8 BouncyCastle        "$v_bouncycastle"

# ---------------------------------------------------------------------------------------------
# README.md: the same claims, in the block a reader meets first.
# ---------------------------------------------------------------------------------------------

expect README.md 1 12 minSdk    "$v_min_sdk"
expect README.md 1 12 compileSdk "$v_compile_sdk"
expect README.md 1 12 targetSdk "$v_target_sdk"
expect README.md 1 12 Kotlin    "$v_kotlin"
expect README.md 1 12 SSHD      "$v_sshd"

# ---------------------------------------------------------------------------------------------
# README.md's test counts. These are figures the *sources* own: counted the same way the README
# describes them, one `@Test` annotation per method, and a class is a file that declares one.
# A class renamed, split or deleted moves the count, and the count is what a reader sizes the suite
# by, so it is worth a check rather than a periodic re-reading.
#
# Read with `--text` and with the NUL scan below, because the way this figure went wrong before is
# worth not repeating: one test file carried a literal NUL byte inside a string literal, which makes
# grep call the file binary and report a single match for the whole file instead of one per method.
# The suite therefore looked 18 methods and one class smaller than it is, and that is the number the
# README stated.
# ---------------------------------------------------------------------------------------------

test_methods() { grep -rhaE '^[[:space:]]*@Test(\(|$)' "$1" --include='*.kt' | wc -l | tr -d ' '; }
# `grep -l` lists files, so this is a FILE count and it is named one. It used to be called
# test_classes, and that name is the whole defect: the README said "152 classes" because this counted
# 152 files, and a check that reads the same wrong noun off both sides cannot notice that the noun is
# wrong -- it only notices when the two numbers disagree. One Kotlin file can declare several test
# classes (ChoiceActivitiesRobolectricTest.kt declares six), so the count this produces is a file
# count, and the README now says so. The classes-per-file case is checked on its own below.
test_files() { grep -rlaE '^[[:space:]]*@Test(\(|$)' "$1" --include='*.kt' | wc -l | tr -d ' '; }

jvm_methods="$(test_methods app/src/test)"
jvm_files="$(test_files app/src/test)"
android_methods="$(test_methods app/src/androidTest)"
android_files="$(test_files app/src/androidTest)"

# A Kotlin source file that is not text: every line-oriented tool degrades on it, and the counts
# above are only trustworthy because none of them is. One check for the whole tree rather than one per
# file -- the claim is "the sources are text", and a per-file tally would drown the figure count.
#
# Detected by byte count rather than by `file(1)`, which would have to be installed on the runner for
# this check to mean anything -- and a check that quietly does nothing when a tool is missing is worse
# than no check, because it still reports a pass. `tr` and `wc` are coreutils: a NUL byte is the one
# byte `tr -d '\000'` removes and nothing else does, so a file whose byte count changes under it
# contains one.
checked=$((checked + 1))
scanned=0
while IFS= read -r file; do
  [ -n "$file" ] || continue
  scanned=$((scanned + 1))
  before="$(wc -c < "$file")"
  after="$(tr -d '\000' < "$file" | wc -c)"
  [ "$before" = "$after" ] || \
    fail "$file contains a NUL byte, so it is binary to grep, diff and every editor"
done < <(find app/src -type f -name '*.kt')
if [ "$scanned" -eq 0 ]; then
  fail "the NUL-byte scan found no Kotlin sources under app/src — this check would be reporting a pass it did not earn"
fi

# The two sentences, each read from its own line. The JVM figure is the first number on its line; the
# file count is the number that precedes the word "files", allowing one lowercase word between them,
# because one line says "152 test files" and the other says "6 files"; and each line is found by a
# phrase unique to it, so a reordering of the README cannot silently pair a figure with the wrong count.
first_number() { printf '%s\n' "$1" | grep -oE '[0-9][0-9,]*' | head -1 | tr -d ','; }
count_before_files() { printf '%s\n' "$1" | grep -oE '[0-9][0-9,]*([[:space:]][a-z]+)?[[:space:]]files' | head -1 | grep -oE '^[0-9,]+' | tr -d ','; }

jvm_line="$(grep -F 'JVM/Robolectric test methods in' README.md | head -1)"
android_line="$(grep -F 'holds the instrumentation tests' README.md | head -1)"

# The README's own answer to "a file count is not a class count": it names one file and says how many
# test classes that file declares, with the number spelled as a word. It is read out of the clause that
# names the file rather than as the first "<word> test classes" on the line, because the same sentence
# says "several test classes" a few words earlier, about the general case.
#
# `^(...)*class` rather than `^class`, so a class declared `internal` or `open` is still counted -- the
# point of the check is to agree with what a run executes, and a run does not care about the modifier.
classes_file_line="$(grep -F 'declares' README.md | grep -F 'test classes' | grep -F 'RobolectricTest.kt' | head -1)"
# `[a-z0-9]` and not `[a-z]`: `number()` reads the digit form as readily as the word, and a README that
# writes "declares 6 test classes" states the same claim. Rejecting it would be a check failing a
# correct document, which is the kind of noise that gets a check turned off.
declared_classes="$(number "$(printf '%s\n' "$classes_file_line" | sed -n 's/.*declares \([a-z0-9]*\) test classes.*/\1/p')")"
classes_src="$(find app/src/test -name 'ChoiceActivitiesRobolectricTest.kt' -print -quit 2>/dev/null)"

check_count() {
  local what="$1" got="$2" want="$3"
  checked=$((checked + 1))
  if [ -z "$want" ]; then
    fail "README.md no longer states the $what count in the shape this script reads — this script is out of date"
  elif [ "$got" != "$want" ]; then
    fail "README.md says $want $what; the sources hold $got"
  fi
}

check_count "JVM test methods"              "$jvm_methods"     "$(first_number "$jvm_line")"
check_count "JVM test files"                "$jvm_files"       "$(count_before_files "$jvm_line")"
check_count "instrumentation test methods"  "$android_methods" "$(first_number "$android_line")"
check_count "instrumentation test files"    "$android_files"   "$(count_before_files "$android_line")"

# The classes-per-file claim. The file is located rather than hardcoded, so moving it is not a failure;
# only deleting it is, and that is failed explicitly rather than left to grep, whose `-c` on an empty
# path reads stdin and would hang the job instead of reporting anything.
checked=$((checked + 1))
if [ -z "$classes_src" ]; then
  fail "app/src/test holds no ChoiceActivitiesRobolectricTest.kt, the file README.md names as the example of one file declaring several test classes"
elif [ -z "$declared_classes" ]; then
  fail "README.md no longer states how many test classes ChoiceActivitiesRobolectricTest.kt declares in the shape this script reads — this script is out of date"
else
  actual_classes="$(grep -cE '^([a-z]+ )*class [A-Za-z]' "$classes_src" | tr -d ' ')"
  if [ "$actual_classes" != "$declared_classes" ]; then
    fail "README.md says ChoiceActivitiesRobolectricTest.kt declares $declared_classes test classes; it declares $actual_classes"
  fi
fi

# ---------------------------------------------------------------------------------------------
# README's "Settings, one window per subject" paragraph. It is a map of the UI, and a map is the
# kind of prose that rots fastest: a screen added to `ui/settings/` makes its counts wrong without
# touching a single word of the sentence. Both counts are read from the tree the same way a reader
# would check them -- the concrete screens living inside SettingsDestinations.kt, and the files in
# that package that declare an Activity class.
# ---------------------------------------------------------------------------------------------

settings_dir=app/src/main/java/dev/eclipse/ssh/ui/settings
destinations="$settings_dir/SettingsDestinations.kt"

# The paragraph is wrapped, so a phrase it states can straddle a line break ("The ten remaining
# screens"). Read it with its newlines folded away, so the check reads what the sentence says rather
# than what the line width happens to be.
readme_flat="$(tr '\n' ' ' < README.md | tr -s ' ')"

checked=$((checked + 1))
if [ ! -f "$destinations" ]; then
  fail "README.md points at $destinations, which does not exist"
else
  # The screens that are nothing but a list of choices: each one is a concrete Activity extending the
  # abstract ChoiceDestinationActivity, so the count is the number of those declarations.
  choice_screens="$(grep -cE '^class [A-Za-z][A-Za-z0-9]*Activity : ChoiceDestinationActivity' "$destinations")"
  readme_choices="$(number "$(printf '%s\n' "$readme_flat" \
    | grep -oE '[A-Za-z0-9]+ screens that are just a list of choices' | head -1 | awk '{print $1}')")"
  checked=$((checked + 1))
  if [ -z "$readme_choices" ]; then
    fail "README.md no longer says how many settings screens are a list of choices — this script is out of date"
  elif [ "$readme_choices" != "$choice_screens" ]; then
    fail "README.md says $readme_choices settings screens are a list of choices; SettingsDestinations.kt declares $choice_screens"
  fi

  # Every other screen has a file of its own. "A file of its own" is checked by what it means: a file
  # in the package that declares an Activity class, other than the two shared files and the one that
  # holds the choice screens.
  screen_files="$(grep -lE '^class [A-Za-z][A-Za-z0-9]*Activity' "$settings_dir"/*.kt \
    | grep -vE '/(SettingsDestinations|SettingsScaffold|SettingsComponents)\.kt$' | wc -l | tr -d ' ')"
  readme_files="$(number "$(printf '%s\n' "$readme_flat" \
    | grep -oE '[A-Za-z0-9]+ remaining screens' | head -1 | awk '{print $1}')")"
  checked=$((checked + 1))
  if [ -z "$readme_files" ]; then
    fail "README.md no longer says how many settings screens have a file of their own — this script is out of date"
  elif [ "$readme_files" != "$screen_files" ]; then
    fail "README.md says $readme_files settings screens have a file of their own; $settings_dir has $screen_files"
  fi

  checked=$((checked + 1))
  [ -f app/src/main/java/dev/eclipse/ssh/ui/about/AboutActivity.kt ] || \
    fail "README.md says About lives in ui/about/, which holds no AboutActivity.kt"
fi

# ---------------------------------------------------------------------------------------------
# docs/THIRD-PARTY.md: the licences section names the libraries that carry obligations, and the
# proot pin it quotes has to be the pin the build actually verifies against.
# ---------------------------------------------------------------------------------------------

expect_section docs/THIRD-PARTY.md 'What ships inside the APK' SSHD  "$v_sshd"
expect_section docs/THIRD-PARTY.md 'What ships inside the APK' SLF4J "$v_slf4j"

# Bouncy Castle is the one inventory entry expect_section cannot read: its version sits after a code
# span rather than after the name ("**Bouncy Castle Licence** - `bcprov-jdk18on` 1.86"), and
# expect_section wants the digits to follow the label with nothing between them. Read the line whole.
# This figure went stale in exactly the way the check exists to prevent - a version bump updated the
# About screen's copy and left this one behind - so it is worth the second reader.
bcprov_doc="$(grep -oE '`bcprov-jdk18on` [0-9][0-9A-Za-z.+-]*' docs/THIRD-PARTY.md \
  | head -1 | grep -oE '[0-9][0-9A-Za-z.+-]*$' | sed -E 's/[.+-]+$//')"
checked=$((checked + 1))
if [ -z "$v_bouncycastle" ]; then
  fail "gradle/libs.versions.toml no longer declares bouncycastle - this script is out of date"
elif [ "$bcprov_doc" != "$v_bouncycastle" ]; then
  fail "docs/THIRD-PARTY.md lists bcprov-jdk18on at ${bcprov_doc:-nothing}; gradle/libs.versions.toml pins $v_bouncycastle"
fi

proot_pin_build="$(value linux/build.gradle.kts "s/^val prootForkCommit = \"([0-9a-f]{40})\".*/\1/p")"
proot_pin_doc="$(grep -oE '`[0-9a-f]{40}`' docs/THIRD-PARTY.md | tr -d '`' | head -1)"
checked=$((checked + 1))
if [ -z "$proot_pin_build" ]; then
  fail "linux/build.gradle.kts no longer declares prootForkCommit — this script is out of date"
elif [ "$proot_pin_doc" != "$proot_pin_build" ]; then
  fail "docs/THIRD-PARTY.md pins proot at ${proot_pin_doc:-nothing}; linux/build.gradle.kts pins $proot_pin_build"
fi

# ---------------------------------------------------------------------------------------------
# AboutLicenses.kt is documentation too: it is the list of third-party versions the user is shown,
# and it is the page a licence question gets answered from. It was four versions stale when this
# check was written, which is the argument for checking it rather than reading it occasionally.
#
# Each entry is `name = "..."` followed by `version = "..."`, so the version is read as the line
# after its name. The first number in the version string is the one compared: entries that carry a
# parenthetical ("3.31.1 (adapted copy)") or a product prefix ("Compose BOM 2025.04.01") still lead
# with the figure that matters.
# ---------------------------------------------------------------------------------------------

about="app/src/main/java/dev/eclipse/ssh/feature/about/AboutLicenses.kt"

license_version() {
  grep -A1 -F "name = \"$1\"," "$about" 2>/dev/null \
    | grep -m1 -oE 'version = "[^"]*"' | sed -E 's/^version = "(.*)"$/\1/'
}

# nth_number <text> <n> -- the nth version-shaped token in the text, "" if there is no nth.
nth_number() { printf '%s\n' "$1" | grep -oE '[0-9][0-9A-Za-z.+-]*' | sed -n "${2}p"; }

# check_license <name as AboutLicenses spells it> <pinned version> [which number in the string]
check_license() {
  local name="$1" want="$2" nth="${3:-1}" got
  got="$(nth_number "$(license_version "$name")" "$nth")"
  checked=$((checked + 1))
  if [ -z "$want" ]; then
    fail "could not read the pinned version for '$name' — this script is out of date"
  elif [ "$got" != "$want" ]; then
    fail "AboutLicenses.kt lists $name at ${got:-nothing}; the build pins $want"
  fi
}

check_license 'Apache MINA SSHD'        "$v_sshd"
check_license 'SLF4J'                   "$v_slf4j"
check_license 'Kotlin / Kotlin Coroutines' "$v_kotlin" 1
check_license 'Kotlin / Kotlin Coroutines' "$(catalog_version coroutines)" 2
check_license 'AndroidX'                "$v_compose_bom"
check_license 'Hilt'                    "$v_hilt"
check_license 'Room'                    "$v_room"
check_license 'DataStore'               "$(catalog_version datastore)"
check_license 'WorkManager'             "$(catalog_version work)"
check_license 'Biometric'               "$(catalog_version biometric)"
check_license 'Apache Commons Compress' "$(catalog_version commonsCompress)"
check_license 'XZ for Java'             "$(catalog_version xz)"
check_license 'Bouncy Castle (bcprov-jdk18on)' "$v_bouncycastle"
check_license 'ed25519-java'            "$(catalog_version eddsa)"
check_license 'JUnit 4'                 "$(catalog_version junit)"
check_license 'Truth'                   "$(catalog_version truth)"
check_license 'Turbine'                 "$(catalog_version turbine)"
check_license 'Robolectric'             "$(value gradle/libs.versions.toml 's/^robolectric = \{ module = "[^"]*", version = "([^"]+)".*/\1/p')"
check_license 'FreeRDP'                 "$(value freerdp/build.gradle.kts 's/^val freerdpVersion = "([^"]+)".*/\1/p')"

# vernacular-vnc is pinned to a commit, and AboutLicenses shows the seven-character form of it. Read
# whole rather than through nth_number, because the commit begins with a letter ("f39cbe2") and a
# version-shaped token has to begin with a digit.
vnc_pin="$(catalog_version vernacular-vnc)"
vnc_doc="$(license_version 'vernacular-vnc' | sed -E 's/[[:space:]]*\(.*\)$//')"
checked=$((checked + 1))
if [ -z "$vnc_pin" ]; then
  fail "gradle/libs.versions.toml no longer pins vernacular-vnc — this script is out of date"
elif [ "$vnc_doc" != "${vnc_pin:0:7}" ]; then
  fail "AboutLicenses.kt lists vernacular-vnc at ${vnc_doc:-nothing}; libs.versions.toml pins ${vnc_pin:0:7}"
fi

# ---------------------------------------------------------------------------------------------
# The CI job set. Two documents describe it in prose -- README's list of the gate's jobs and ci.yml's
# own job-layout comment naming the check names branch protection must require -- and both go stale
# the moment a job is added, which is precisely when getting it wrong matters: a job that exists but
# is not in the comment is a job nobody makes required.
# ---------------------------------------------------------------------------------------------

ci_jobs="$(awk '/^jobs:/{f=1;next} f&&/^[^ ]/{exit} f&&/^  [a-z][a-z0-9_-]*:[[:space:]]*$/{n++} END{print n+0}' \
  .github/workflows/ci.yml)"

readme_jobs="$(number "$(grep -oE '\*\*?[A-Za-z0-9]+ jobs|(One|Two|Three|Four|Five|Six|Seven|Eight|Nine|Ten) jobs' README.md \
  | head -1 | grep -oE '[A-Za-z0-9]+' | head -1)")"
checked=$((checked + 1))
if [ -z "$readme_jobs" ]; then
  fail "README.md no longer states how many jobs ci.yml has — this script is out of date"
elif [ "$readme_jobs" != "$ci_jobs" ]; then
  fail "README.md says ci.yml has $readme_jobs jobs; it has $ci_jobs"
fi

# docs/ci.md states the same number twice: once in the ci.yml row of the workflow table and once in
# the sentence that opens the ci.yml section.
stated_jobs() { printf '%s\n' "$1" | grep -oE '[A-Za-z0-9]+ jobs' | head -1 | awk '{print $1}'; }
for place in "docs/ci.md's ci.yml row:$(grep -m1 '^| `ci.yml` |' docs/ci.md)" \
             "docs/ci.md's ci.yml section:$(grep -m1 'jobs, so a failure lands' docs/ci.md)"; do
  where="${place%%:*}"
  stated="$(number "$(stated_jobs "${place#*:}")")"
  checked=$((checked + 1))
  if [ -z "$stated" ]; then
    fail "$where no longer states how many jobs ci.yml has — this script is out of date"
  elif [ "$stated" != "$ci_jobs" ]; then
    fail "$where says ci.yml has $stated jobs; it has $ci_jobs"
  fi
done

# ci.yml's comment: "must require the six check names below (docs, native, lint, test, assemble,
# smoke)". It wraps over three commented lines, so the three are joined before parsing. The stated
# number and the list have to agree with each other, and every name has to be a real job.
layout_comment="$(awk '/Branch protection must require the/{f=1} f{print; if(++n==3) exit}' \
  .github/workflows/ci.yml | tr '\n' ' ' | sed -E 's/#//g; s/[[:space:]]+/ /g')"
claimed="$(number "$(printf '%s\n' "$layout_comment" | grep -oE 'the [A-Za-z0-9]+ check names' | awk '{print $2}')")"
named="$(printf '%s\n' "$layout_comment" | sed -nE 's/.*\((.*)\).*/\1/p' | tr -d ' ')"
named_count="$(printf '%s' "$named" | awk -F, 'NF{print NF}')"
checked=$((checked + 2))
if [ -z "$claimed" ] || [ -z "$named_count" ]; then
  fail "ci.yml's job-layout comment no longer names its required checks in the shape this script reads"
else
  [ "$claimed" = "$named_count" ] || \
    fail "ci.yml's comment says it names $claimed required checks but lists $named_count ($named)"
  while IFS= read -r job; do
    [ -n "$job" ] || continue
    grep -qE "^  $job:[[:space:]]*$" .github/workflows/ci.yml || \
      fail "ci.yml's comment names the check '$job', which is not a job in this workflow"
  done < <(printf '%s' "$named" | tr ',' '\n')
fi

# ---------------------------------------------------------------------------------------------
# The branch-protection table. It is the one table in the repository that someone copies into a
# settings page by hand, so a check name that drifted from the job's own `name:` is not a typo — it
# is a required check that can never be satisfied, or worse, one that is never required at all.
# ---------------------------------------------------------------------------------------------

workflow_for_display_name() {
  local f
  for f in .github/workflows/*.yml; do
    if [ "$(sed -nE 's/^name:[[:space:]]*(.*)$/\1/p' "$f" | head -1)" = "$1" ]; then
      basename "$f"
      return
    fi
  done
}

job_display_name() {  # <workflow file> <job key>
  awk -v k="$2" '
    $0 ~ "^  " k ":[[:space:]]*$" { f = 1; next }
    f && /^  [a-z]/ { exit }
    f && /^    name:[[:space:]]*/ { sub(/^    name:[[:space:]]*/, ""); print; exit }
  ' ".github/workflows/$1"
}

checked=$((checked + 1))
if ! grep -q '^| Workflow | Check name (job key) |' docs/branch-protection.md; then
  fail "docs/branch-protection.md no longer has its required-check table in the shape this script reads"
else
  while IFS='|' read -r _ display keyed _; do
    display="$(printf '%s' "$display" | tr -d ' `')"
    key="$(printf '%s' "$keyed" | sed -nE 's/.*\(`([a-z0-9_-]+)`\).*/\1/p')"
    [ -n "$display" ] && [ -n "$key" ] || continue
    checked=$((checked + 1))
    wf="$(workflow_for_display_name "$display")"
    if [ -z "$wf" ]; then
      fail "docs/branch-protection.md requires a check from a workflow named '$display', which does not exist"
      continue
    fi
    actual="$(job_display_name "$wf" "$key")"
    documented="$(printf '%s' "$keyed" | sed -E 's/[[:space:]]*\(`[a-z0-9_-]+`\)[[:space:]]*//' \
      | tr -d '`' | sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//')"
    if [ -z "$actual" ]; then
      fail "docs/branch-protection.md requires '$key' in $wf, which has no such job"
    elif [ "$actual" != "$documented" ]; then
      fail "docs/branch-protection.md calls $wf's '$key' check '$documented'; the job names itself '$actual'"
    fi
  done < <(grep -E '^\| `[A-Za-z0-9 .-]+` \| ' docs/branch-protection.md)
fi

# ---------------------------------------------------------------------------------------------
# Cross-references: a workflow name that no longer resolves. Every document that names workflows.
# ---------------------------------------------------------------------------------------------

# The documents whose claims are checked. One list, so a document cannot be added to one check and
# forgotten by the other.
documented_files=(README.md AUDIT-REPORT.md docs/ci.md docs/branch-protection.md docs/THIRD-PARTY.md
  docs/linux-userspace.md freerdp/README.md linux/README.md linux/proot-patches/README.md
  testing/README.md testing/ubuntu-e2e/README.md testing/universal/README.md)

for doc in "${documented_files[@]}"; do
  expect_workflows_exist "$doc"
done

# ---------------------------------------------------------------------------------------------
# The repository paths those documents name. A path is the cheapest claim a document can make and the
# first to rot: a file moves, the sentence pointing at it does not, and nothing notices until a reader
# follows it. `MainActivity.kt` moved out of `presentation/` and the audit report went on citing the
# old directory.
#
# A citation resolves two ways, because documents cite both ways: from the repository root, which is
# how prose names a file, and from the citing document's own directory, which is how a markdown link
# does it. Either is a real reference and neither is preferred here.
# ---------------------------------------------------------------------------------------------

cited_paths() {
  # Tokens are taken whole, so the leading `<filesDir>/` of a device-side path stays attached to it
  # and can be recognised. Two shapes are then dropped: an elided path ("app/src/.../X.kt", which is
  # prose shorthand rather than a reference) and anything carrying `<...>`, which is a path inside the
  # app's sandbox at run time and not a file in this repository.
  grep -ohE '[A-Za-z0-9_./<>-]*(app|docs|freerdp|linux|scripts|testing)/[A-Za-z0-9_./-]+\.(kt|kts|sh|yml|yaml|md|json|xml|toml|pro|properties|txt)' "$1" \
    | grep -v -e '\.\.\.' -e '<'
}

for doc in "${documented_files[@]}"; do
  docdir="$(dirname "$doc")"
  while IFS= read -r cited; do
    [ -n "$cited" ] || continue
    checked=$((checked + 1))
    [ -e "$cited" ] || [ -e "$docdir/$cited" ] || \
      fail "$doc names '$cited', which is neither at the repository root nor beside that document"
  done < <(cited_paths "$doc" | sort -u)
done

# ---------------------------------------------------------------------------------------------

if [ "$failures" -gt 0 ]; then
  printf '\n%d of %d documentation claims disagree with the build.\n' "$failures" "$checked" >&2
  exit 1
fi
printf 'All %d documentation claims agree with the build.\n' "$checked"
