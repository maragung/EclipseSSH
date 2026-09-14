#!/usr/bin/env python3
"""Scan a captured logcat for crashes, ANRs, freezes - independent of what
the explorer recorded, because a passing journey set can coexist with a
background service crash the UI never sees.

Enriches findings with context when the explorer's outputs are available:
the stack, the screen signature and previous action (from
crash-events.json / action-trace.json), and a screenshot reference.

Usage:
  scan-issues.py <logcat-file> <package> <out-json> \
      [crash-events.json] [action-trace.json]

Exit code: 1 when findings exist, 0 when the log is clean.
"""
import json
import os
import re
import sys

sys.path.insert(0, __file__.rsplit("/", 1)[0])
from adbutil import CRASH_PATTERNS  # noqa: E402

# Cap evidence per class: 25 lines a class is a report, 2500 is a paste.
MAX_LINES_PER_CLASS = 25
MAX_STACK_CHARS = 4000


def scan(logcat_text):
    """Group every crash-signature line by class; returns {label: [lines]}."""
    findings = {}
    lines = logcat_text.splitlines()
    for label, pattern in CRASH_PATTERNS:
        hits = [line for line in lines if pattern.search(line)]
        if hits:
            findings[label] = hits
    return findings


def stacks_for(logcat_lines, fatal_lines):
    """Attach the ~60 lines following each FATAL header (the stack) to the
    finding, so root cause and verdict live on the same page."""
    joined = "\n".join(logcat_lines)
    enriched = []
    for line in fatal_lines[:10]:
        entry = {"line": line.strip()}
        idx = joined.find(line)
        if idx >= 0:
            entry["stack"] = joined[idx:idx + MAX_STACK_CHARS]
        enriched.append(entry)
    return enriched


def main():
    if len(sys.argv) < 4:
        print(__doc__, file=sys.stderr)
        sys.exit(2)
    logcat_path, package, out_path = sys.argv[1:4]
    crash_events_path = sys.argv[4] if len(sys.argv) > 4 else ""
    trace_path = sys.argv[5] if len(sys.argv) > 5 else ""

    logcat_text = ""
    if os.path.exists(logcat_path):
        with open(logcat_path, errors="replace") as fh:
            logcat_text = fh.read()
    logcat_lines = logcat_text.splitlines()

    findings = scan(logcat_text)

    # Explorer context: crash events already carry screen + previous action
    # + screenshot; merge them under their class by matching the line.
    events_by_line = {}
    if crash_events_path and os.path.exists(crash_events_path):
        with open(crash_events_path) as fh:
            for event in json.load(fh):
                events_by_line[event.get("line", "")] = event

    report = {
        "package": package,
        "logcatLines": len(logcat_lines),
        "classes": {},
        "totalFindings": 0,
        "mentionsPackage": 0,
    }
    total = 0
    for label, hits in findings.items():
        capped = hits[:MAX_LINES_PER_CLASS]
        if label in ("jvm-fatal", "native-crash"):
            entries = stacks_for(logcat_lines, capped)
        else:
            entries = [{"line": line.strip()} for line in capped]
        for entry in entries:
            event = events_by_line.get(entry["line"])
            if event:
                entry["afterAction"] = event.get("afterAction")
                entry["screen"] = event.get("screen")
                entry["screenshot"] = event.get("screenshot")
        report["classes"][label] = {
            "count": len(hits),
            "evidence": entries,
        }
        total += len(hits)

    # How many findings name the app? Attribution, not filtering: everything
    # is reported, this count tells the reader how much is app-owned. The
    # FATAL header line itself rarely carries the package - the Process:
    # line and stack frames right after it do.
    mentioned = 0
    for hits in findings.values():
        for line in hits:
            if package in line:
                mentioned += 1
                continue
            idx = logcat_text.find(line)
            window = logcat_text[idx:idx + 4000] if idx >= 0 else ""
            if package in window:
                mentioned += 1
    report["mentionsPackage"] = mentioned
    report["totalFindings"] = total
    report["status"] = "clean" if total == 0 else "crashes-detected"

    # ANR traces reference: the collector copies /data/anr separately; the
    # report points at it when the ANR class is non-empty.
    if "anr" in findings:
        report["anrTracesRef"] = "logs/anr-traces.txt (see diagnostics)"

    with open(out_path, "w") as fh:
        json.dump(report, fh, indent=2)
        fh.write("\n")

    print(json.dumps({"status": report["status"],
                      "total": total,
                      "mentionsPackage": report["mentionsPackage"]},
                     indent=2))
    if total:
        print("crash signatures detected in logcat (%d)" % total,
              file=sys.stderr)
        sys.exit(1)
    print("no crash signatures in logcat")


if __name__ == "__main__":
    main()
