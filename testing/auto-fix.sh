#!/usr/bin/env bash
# Autonomous repair loop (the "auto-fix" stage of the release-test pipeline).
#
# Gate: this script does nothing unless ANTHROPIC_AUTH_TOKEN is set. The
# token is a repository secret the maintainer adds; without it the workflow
# still fails loudly with a filed issue, and repair stays in the hands of the
# maintainer's session - never a silent skip.
#
# Loop: read the failure report -> ask the agent for the smallest real fix ->
# commit -> push to an auto-fix branch -> dispatch the full release-test
# workflow on that branch (apk_source=build, so the rebuilt APK is what gets
# retested) -> on green, open a PR and stop; on red, feed the new failure
# report into the next attempt. A fix that makes things worse is reverted
# before the next attempt. Hard cap: MAX_REPAIR_ATTEMPTS (default 5).
#
# Usage: auto-fix.sh <failure-report.json> <base-ref> <run-dir>
set -euo pipefail

REPORT="$1"
BASE_REF="$2"
RUN_DIR="$3"
MAX_ATTEMPTS="${MAX_REPAIR_ATTEMPTS:-5}"

if [ -z "${ANTHROPIC_AUTH_TOKEN:-}" ]; then
  echo "ANTHROPIC_AUTH_TOKEN is not configured - the autonomous repair loop" >&2
  echo "is disabled. The failure issue carries the full report; repair is" >&2
  echo "driven by the maintainer's session." >&2
  exit 78  # EX_CONFIG: configuration is missing, not a code failure
fi

if ! command -v claude >/dev/null 2>&1; then
  npm install -g @anthropic-ai/claude-code
fi

BRANCH="auto-fix/${BASE_REF//\//-}-$(date +%s)"
git config user.name "EclipseSSH build"
git config user.email "build@eclipse.invalid"
git checkout -b "$BRANCH"

# The rules are part of the invocation, not a suggestion: the agent sees them
# on every attempt, and the loop below reverts anything that regresses.
read -r -d '' RULES <<'EOF' || true
You are repairing a real Android release-test failure. Rules, non-negotiable:
- Make the smallest reasonable change that fixes the verified defect.
- Preserve the architecture; no unrelated refactors.
- Add or extend a regression test for every bug you fix.
- NEVER disable a test, skip a feature, swallow an exception, add an empty
  catch, inflate a timeout to hide a hang, or special-case the test
  environment to make CI pass.
- Explain the root cause and the change in the commit message (English).
- Commit with "Co-Authored-By: Claude <noreply@anthropic.com>".
EOF

attempt=0
prev_failures="$(python3 -c "import json;print(len(json.load(open('$REPORT')).get('failingTests', [])))")"
while [ "$attempt" -lt "$MAX_ATTEMPTS" ]; do
  attempt=$((attempt + 1))
  echo "=== repair attempt $attempt/$MAX_ATTEMPTS ==="

  claude -p --dangerously-skip-permissions "$(cat <<PROMPT
$RULES

The release-test pipeline failed. The machine-readable failure report is in
$REPORT - read it, then read the affected files it names, trace the failure
to its actual root cause (do not guess from the exception message alone),
and implement the fix. The full evidence (logcat, test stdout) is under
$RUN_DIR. When done, commit the fix.
PROMPT
  )"

  if git diff --quiet HEAD; then
    echo "the agent produced no changes on attempt $attempt - stopping"
    exit 1
  fi

  git -c credential.helper= push origin "HEAD:$BRANCH"

  # Rebuild and retest the FIXED code: the workflow builds its own release
  # APK from this branch (apk_source=build), installs it on the emulator and
  # runs the same suite. RETEST_WORKFLOW/RETEST_ARGS let another pipeline
  # (the Ubuntu E2E) reuse this loop against its own workflow; the defaults
  # are the release-test pipeline this script was born in.
  RETEST_WORKFLOW="${RETEST_WORKFLOW:-android-release-test.yml}"
  RETEST_ARGS="${RETEST_ARGS:--f apk_source=build -f api_levels=35 -f run_repair=false}"
  RUN_URL="$(gh workflow run "$RETEST_WORKFLOW" \
    --ref "$BRANCH" \
    $RETEST_ARGS \
    && sleep 10 \
    && gh run list --workflow="$RETEST_WORKFLOW" --branch "$BRANCH" --limit 1 --json url --jq '.[0].url')"
  echo "retest: $RUN_URL"
  if gh run watch "$(basename "$RUN_URL")" --exit-status; then
    echo "the fix passed the full pipeline - opening a PR for review"
    gh pr create --head "$BRANCH" --base "$BASE_REF" \
      --title "Auto-fix: release-test failure on $BASE_REF" \
      --body "Autonomous repair from the release-test pipeline. Evidence and reasoning are in the commit messages and the linked workflow run: $RUN_URL" || true
    exit 0
  fi

  # A fix that increases the failure count is a wrong turn: revert it before
  # trying again, so attempts compose instead of stacking mistakes.
  gh run download "$(basename "$RUN_URL")" -n failure-report -D "$RUN_DIR/next" || true
  if [ -f "$RUN_DIR/next/failure-report.json" ]; then
    next_failures="$(python3 -c "import json;print(len(json.load(open('$RUN_DIR/next/failure-report.json')).get('failingTests', [])))")"
    if [ "$next_failures" -gt "$prev_failures" ]; then
      echo "attempt $attempt regressed ($prev_failures -> $next_failures failures); reverting"
      git revert --no-edit HEAD
      prev_failures="$next_failures"
      continue
    fi
    cp "$RUN_DIR/next/failure-report.json" "$REPORT"
    prev_failures="$next_failures"
  fi
done

echo "repair budget exhausted ($MAX_ATTEMPTS attempts) - the failure issue carries the last report"
exit 1
