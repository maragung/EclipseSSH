#!/usr/bin/env python3
"""Compose release-test-report.md (and failure-report.json on failure) from the
run's own evidence: the instrumented-suite stdout, the APK validation, and the
logcat crash scan. Exit code 0 always - the report states the verdict, the
workflow already knows it.

Usage: generate-report.py <out-dir> <tag> <api-level> <commit> <repo>
                       <test-stdout> <validation-json> <crash-json|-> <passed:yes|no>
"""
import json
import os
import re
import sys


def parse_suite(stdout_text):
    """Parse AndroidJUnitRunner stdout into (passed, failed, failures)."""
    failures = []
    # Failure blocks look like: "1) testName(ClassName)" followed by the stack.
    block = re.split(r"^(\d+)\) ", stdout_text, flags=re.M)
    # re.split with a group yields [pre, n1, rest1, n2, rest2, ...]
    it = iter(block[1:])
    for _num, rest in zip(it, it):
        header = rest.splitlines()[0] if rest else ""
        stack = rest[:4000]
        failures.append({"test": header, "stack": stack.strip()})
    total = 0
    m = re.search(r"^OK \((\d+) tests?\)", stdout_text, flags=re.M)
    if m:
        total = int(m.group(1))
    return total, len(failures), failures


def affected_files(failures):
    """Top app frames from the stacks - the files a fix will most likely touch."""
    files = []
    for f in failures:
        for line in f.get("stack", "").splitlines():
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
    (out_dir, tag, api_level, commit, repo,
     stdout_path, validation_path, crash_path, passed) = sys.argv[1:10]

    stdout_text = open(stdout_path, errors="replace").read() if os.path.exists(stdout_path) else ""
    validation = json.load(open(validation_path)) if os.path.exists(validation_path) else {}
    crash = json.load(open(crash_path)) if crash_path != "-" and os.path.exists(crash_path) else {}

    ran, failed_count, failures = parse_suite(stdout_text)
    is_pass = passed == "yes"

    version = validation.get("apk", {}).get("versionName", "?")
    lines = []
    lines.append("# Release Test Report")
    lines.append("")
    lines.append(f"- Version: `{tag}` (versionName {version})")
    lines.append(f"- Build: API {api_level} emulator, x86_64, pixel_6")
    lines.append(f"- Commit: `{commit}`")
    lines.append(f"- Repository: {repo}")
    lines.append("")
    lines.append("## Result")
    lines.append("")
    lines.append("**PASS**" if is_pass else "**FAIL**")
    lines.append("")
    lines.append("## Tests")
    lines.append("")
    lines.append(f"- Ran: {ran}")
    lines.append(f"- Failed: {failed_count}")
    lines.append("")
    if failures:
        lines.append("### Failing tests")
        lines.append("")
        for f in failures:
            lines.append(f"- `{f['test']}`")
        lines.append("")
    lines.append("## Crashes / ANR")
    lines.append("")
    if crash:
        lines.append(f"- Status: {crash.get('status')} ({crash.get('signatureMatches', 0)} signature matches)")
        if crash.get("evidence"):
            lines.append("")
            lines.append("```")
            lines.append(crash["evidence"][:3000])
            lines.append("```")
    else:
        lines.append("- logcat scan not available")
    lines.append("")
    lines.append("## APK validation")
    lines.append("")
    lines.append("```json")
    lines.append(json.dumps(validation, indent=2))
    lines.append("```")
    lines.append("")
    lines.append("## Final Recommendation")
    lines.append("")
    lines.append("RELEASE" if is_pass else "DO NOT RELEASE")
    lines.append("")
    report_path = os.path.join(out_dir, "release-test-report.md")
    with open(report_path, "w") as f:
        f.write("\n".join(lines))

    if not is_pass:
        doc = {
            "summary": f"{failed_count} failing tests, {crash.get('signatureMatches', 0)} crash signatures on API {api_level}",
            "failingTests": failures,
            "crashScan": crash,
            "affectedFiles": affected_files(failures),
            "logcat": "logs/logcat.txt",
            "environment": {
                "tag": tag,
                "apiLevel": api_level,
                "commit": commit,
            },
        }
        with open(os.path.join(out_dir, "failure-report.json"), "w") as f:
            json.dump(doc, f, indent=2)

    print(report_path)


if __name__ == "__main__":
    main()
