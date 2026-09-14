#!/usr/bin/env python3
"""Compose the Ubuntu E2E report from the driver's phase results.

Two outputs, matching the release-test pipeline's shape on purpose:

  ubuntu-e2e-report.md - the stage table (APK install, download, verify,
      PRoot, shell, APT, DNS, HTTP, persistence, ...) with the final verdict,
      written into the job summary and the artifact.
  failure-report.json - only on failure, in the SAME schema
      testing/auto-fix.sh consumes ({summary, failingTests, crashScan,
      affectedFiles, logcat, environment}), so the autonomous repair loop
      works on this pipeline without a fork of the script.

Usage: summary.py <out-dir> <mode> <api-level> <commit> <repo>
"""

import json
import os
import re
import sys

# The stage line each phase of the driver maps to in the summary table.
PHASE_LABELS = {
    "preflight": "APK install + launch",
    "storage-gate": "Storage failure handling",
    "install": "Ubuntu install (UI flow)",
    "verify": "Ubuntu usable (shell, apt, DNS, HTTP)",
    "terminal-ui": "Terminal through the app",
    "persistence-restart": "Persistence after app restart",
    "interrupt-process": "Process-kill recovery",
    "interrupt-network": "Network-cut recovery",
}


def parse_suite(stdout_text):
    failures = []
    block = re.split(r"^(\d+)\) ", stdout_text, flags=re.M)
    it = iter(block[1:])
    for _num, rest in zip(it, it):
        header = rest.splitlines()[0] if rest else ""
        failures.append({"test": header, "stack": rest[:4000].strip()})
    return failures


def affected_files(failures):
    files = []
    for failure in failures:
        for line in failure.get("stack", "").splitlines():
            m = re.search(r"at dev\.eclipse\.ssh\.[\w.]+\(([A-Za-z0-9_]+\.kt:\d+)\)", line)
            if m:
                files.append(m.group(1))
    seen, out = set(), []
    for f in files:
        if f not in seen:
            seen.add(f)
            out.append(f)
    return out[:12]


def main():
    out_dir, mode, api_level, commit, repo = sys.argv[1:6]

    with open(os.path.join(out_dir, "phase-results.json")) as fh:
        doc = json.load(fh)
    phases = doc["phases"]

    # Instrumentation failures carry the finest-grained stage attribution.
    test_failures = []
    for phase in phases:
        for evidence in phase.get("evidence", []):
            if os.path.basename(evidence).startswith("instrument-") and os.path.isfile(evidence):
                with open(evidence) as fh:
                    test_failures.extend(parse_suite(fh.read()))

    crash_findings = [p for p in phases if "crash signatures:" in p.get("detail", "")]
    failed = [p for p in phases if p["status"] == "fail"]
    passed = all(p["status"] in ("pass", "skip") for p in phases) and not failed

    lines = []
    lines.append("# Ubuntu E2E Test")
    lines.append("")
    lines.append("Mode `%s` on API %s, commit `%s`." % (mode, api_level, commit[:10]))
    lines.append("")
    lines.append("| Stage | Result |")
    lines.append("|---|---|")
    for phase in phases:
        label = PHASE_LABELS.get(phase["phase"], phase["phase"])
        mark = {"pass": "PASS", "fail": "**FAIL**", "skip": "SKIP"}[phase["status"]]
        lines.append("| %s | %s |" % (label, mark))
    lines.append("")
    lines.append("**FINAL RESULT: %s**" % ("PASS" if passed else "FAIL"))
    lines.append("")

    if failed:
        lines.append("## Failed stage detail")
        lines.append("")
        for phase in failed:
            lines.append("### %s (stage: %s)" % (phase["phase"], phase.get("stage") or "-"))
            lines.append("")
            lines.append("```")
            lines.append(phase.get("detail") or "(no detail recorded)")
            lines.append("```")
            lines.append("")

    report_path = os.path.join(out_dir, "ubuntu-e2e-report.md")
    with open(report_path, "w") as fh:
        fh.write("\n".join(lines))
    print(report_path)

    if not passed:
        failing_tests = test_failures or [
            {"test": "%s (%s)" % (p["phase"], p.get("stage") or "?"),
             "stack": p.get("detail", "")} for p in failed]
        report_doc = {
            "summary": "%d failed phase(s), %d failing verification test(s), mode %s on API %s"
                       % (len(failed), len(test_failures), mode, api_level),
            "failingTests": failing_tests,
            "crashScan": {
                "signatureMatches": len(crash_findings),
                "phases": [p["phase"] for p in crash_findings],
            },
            "affectedFiles": affected_files(failing_tests),
            "logcat": "logcat-full.txt",
            "environment": {
                "pipeline": "android-ubuntu-e2e",
                "mode": mode,
                "apiLevel": api_level,
                "commit": commit,
                "repo": repo,
            },
        }
        with open(os.path.join(out_dir, "failure-report.json"), "w") as fh:
            json.dump(report_doc, fh, indent=2)

    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
