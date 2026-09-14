#!/usr/bin/env bash
# Scans a captured logcat for crash signatures: JVM fatals, ANRs, native
# crashes, and force-closes involving the app. Writes crash-report.json and
# exits 1 when anything is found - a suite can pass while a background
# service crashes, and that must still fail the release gate.
#
# Usage: scan-crashes.sh <logcat-file> <package> <out-json>
set -euo pipefail

LOGCAT="$1"
PACKAGE="$2"
OUT_JSON="$3"

# Every pattern names a real failure class; the report keeps the matching
# lines so the root cause is on the same page as the verdict.
declare -a labels patterns
labels=("jvm-fatal" "anr" "native-crash" "force-close")
patterns=(
  'FATAL EXCEPTION'
  'ANR in |Input dispatching timed out|executing service timed out'
  'Fatal signal [0-9]+|SIGSEGV|SIGABRT|crash_dump'
  "Force finishing activity $PACKAGE|Force stopping package $PACKAGE|Process $PACKAGE .* has died"
)

total=0
: > /tmp/scan-matches.txt
for i in "${!labels[@]}"; do
  count="$(grep -Ec "${patterns[$i]}" "$LOGCAT" || true)"
  if [ "$count" -gt 0 ]; then
    echo "### ${labels[$i]} ($count)" >> /tmp/scan-matches.txt
    grep -E "${patterns[$i]}" "$LOGCAT" | head -25 >> /tmp/scan-matches.txt
    echo >> /tmp/scan-matches.txt
    total=$((total + count))
  fi
done

STATUS="clean"
if [ "$total" -gt 0 ]; then
  STATUS="crashes-detected"
fi

python3 - "$OUT_JSON" "$STATUS" "$total" <<'PY'
import json, sys
out, status, total = sys.argv[1:4]
doc = {"status": status, "signatureMatches": int(total)}
if status != "clean":
    try:
        with open("/tmp/scan-matches.txt") as f:
            doc["evidence"] = f.read()
    except FileNotFoundError:
        pass
with open(out, "w") as f:
    json.dump(doc, f, indent=2)
    f.write("\n")
PY

cat "$OUT_JSON"
if [ "$STATUS" != "clean" ]; then
  echo "crash signatures detected in logcat ($total)" >&2
  exit 1
fi
echo "no crash signatures in logcat"
