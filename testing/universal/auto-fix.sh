#!/usr/bin/env bash
# Autonomous repair loop for the universal APK test pipeline.
#
# Gates, in order, each exit 78 (EX_CONFIG) loudly - never a silent skip:
#   1. apk_source must be build-from-source: the repair branch is cut from
#      the ref the workflow was dispatched on, which is the only ref that
#      both carries this workflow file (retest dispatch needs it) and is the
#      code that was actually tested. An artifact or URL run has no such
#      ref, and the report already states that source-level repair was not
#      attempted.
#   2. ANTHROPIC_AUTH_TOKEN must be configured.
#
# Loop: read universal-test-result.json -> agent produces the smallest real
# fix plus a regression test -> commit -> push to auto-fix/universal-* ->
# dispatch THIS workflow on that branch (apk_source=build-from-source, so
# the rebuilt APK is retested, not the old artifact) -> on green, open a PR
# and stop; on red, feed the new result into the next attempt. A fix that
# increases the failure count is reverted before the next attempt.
# Hard cap: MAX_REPAIR_ATTEMPTS (default 5).
#
# Usage: auto-fix.sh <universal-test-result.json> <base-ref> <run-dir> \
#           <apk-source> <api-level> <mode>
set -euo pipefail

RESULT="$1"
BASE_REF="$2"
RUN_DIR="$3"
APK_SOURCE="$4"
API_LEVEL="${5:-35}"
MODE="${6:-STANDARD}"
MAX_ATTEMPTS="${MAX_REPAIR_ATTEMPTS:-5}"

if [ "$APK_SOURCE" != "build-from-source" ]; then
  echo "auto-fix is not possible for apk_source=$APK_SOURCE: the repair" >&2
  echo "branch must be cut from the ref under test, which only a" >&2
  echo "build-from-source run has (and the retest dispatch needs this" >&2
  echo "workflow file on the branch). The failure issue carries the full" >&2
  echo "report; re-dispatch with apk_source=build-from-source on the" >&2
  echo "fixing branch to enable the loop." >&2
  exit 78  # EX_CONFIG: configuration is missing, not a code failure
fi

if [ -z "${ANTHROPIC_AUTH_TOKEN:-}" ]; then
  echo "ANTHROPIC_AUTH_TOKEN is not configured - the autonomous repair" >&2
  echo "loop is disabled. The failure issue carries the full report;" >&2
  echo "repair is driven by the maintainer's session." >&2
  exit 78
fi

if ! command -v claude >/dev/null 2>&1; then
  npm install -g @anthropic-ai/claude-code
fi

# The failure metric for regression detection: gate blockers plus crash and
# ANR counts. A fix that makes this number go up is a wrong turn.
failure_metric() {
  python3 -c "
import json
d = json.load(open('$1'))
crashes = d.get('crashes', {})
print(len(d.get('blockers', [])) + crashes.get('jvm', 0) + \
      crashes.get('native', 0) + crashes.get('anr', 0))
"
}

BRANCH="auto-fix/universal-${BASE_REF//\//-}-$(date +%s)"
git config user.name "EclipseSSH build"
git config user.email "build@eclipse.invalid"
git checkout -b "$BRANCH"

read -r -d '' RULES <<'EOF' || true
You are repairing a real Android app failure found by a black-box
exploration test pipeline. Rules, non-negotiable:
- Make the smallest reasonable change that fixes the verified defect.
- Preserve the architecture; no unrelated refactors.
- Add or extend a regression test for every bug you fix.
- NEVER disable a test, skip a feature, swallow an exception, add an empty
  catch, inflate a timeout to hide a hang, or special-case the test
  environment to make CI pass.
- The evidence is black-box (screens, actions, logcat stacks): trace the
  stack to the source; do not guess from the exception message alone.
- Explain the root cause and the change in the commit message (English).
- Commit with "Co-Authored-By: Claude <noreply@anthropic.com>".
EOF

attempt=0
prev_metric="$(failure_metric "$RESULT")"
while [ "$attempt" -lt "$MAX_ATTEMPTS" ]; do
  attempt=$((attempt + 1))
  echo "=== repair attempt $attempt/$MAX_ATTEMPTS ==="

  claude -p --dangerously-skip-permissions "$(cat <<PROMPT
$RULES

The universal APK test pipeline failed. The machine-readable verdict with
gate blockers, failed journeys, crash/ANR counts and the replay seed is in
$RESULT - read it, then read the files it implicates. The full evidence
(logcat, action trace, screenshots, report) is under $RUN_DIR. Fix the root
cause and commit.
PROMPT
  )"

  if git diff --quiet HEAD; then
    echo "the agent produced no changes on attempt $attempt - stopping"
    exit 1
  fi

  git -c credential.helper= push origin "HEAD:$BRANCH"

  # Rebuild and retest the FIXED code: the dispatched run builds its own
  # APK from the branch and runs the same journeys.
  RUN_URL="$(gh workflow run universal-apk-test.yml \
    --ref "$BRANCH" \
    -f apk_source=build-from-source \
    -f api_levels="$API_LEVEL" \
    -f mode="$MODE" \
    -f run_repair=false \
    && sleep 10 \
    && gh run list --workflow=universal-apk-test.yml --branch "$BRANCH" \
      --limit 1 --json url --jq '.[0].url')"
  echo "retest: $RUN_URL"
  if gh run watch "$(basename "$RUN_URL")" --exit-status; then
    echo "the fix passed the full pipeline - opening a PR for review"
    gh pr create --head "$BRANCH" --base "$BASE_REF" \
      --title "Auto-fix: universal APK test failure on $BASE_REF" \
      --body "Autonomous repair from the universal APK test pipeline. Evidence and reasoning are in the commit messages and the linked workflow run: $RUN_URL" || true
    exit 0
  fi

  # A fix that increases the failure metric is a wrong turn: revert it
  # before trying again, so attempts compose instead of stacking mistakes.
  rm -rf "$RUN_DIR/next"
  gh run download "$(basename "$RUN_URL")" \
    -n "universal-test-api$API_LEVEL" -D "$RUN_DIR/next" || true
  NEXT="$(find "$RUN_DIR/next" -name universal-test-result.json | head -1)"
  if [ -n "$NEXT" ]; then
    next_metric="$(failure_metric "$NEXT")"
    if [ "$next_metric" -gt "$prev_metric" ]; then
      echo "attempt $attempt regressed ($prev_metric -> $next_metric failures); reverting"
      git revert --no-edit HEAD
      prev_metric="$next_metric"
      continue
    fi
    cp "$NEXT" "$RESULT"
    prev_metric="$next_metric"
  fi
done

echo "repair budget exhausted ($MAX_ATTEMPTS attempts) - the failure issue carries the last report"
exit 1
