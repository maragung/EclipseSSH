#!/usr/bin/env python3
"""Compose release-test-report.md (and failure-report.json on failure) from the
run's own evidence: the instrumented-suite stdout, the APK validation, and the
logcat crash scan. Exit code 0 always - the report states the verdict, the
workflow already knows it.

Usage: generate-report.py <out-dir> <label> <api-level> <commit> <repo>
                       <test-stdout> <validation-json> <crash-json|-> <passed:yes|no>

`label` names what was under test, and the caller decides what that is: the
release tag for a release event or an `apk_source=release` dispatch, or
`<ref>@<commit>` for the `apk_source=build` dispatch that validates a branch
without publishing anything. It is NOT always a tag - passing the tag there
left the line below reading "Version: ``" on every build-source run, and issue
#124 was filed from one (run 35343449935) with an empty title to match.
"""
import json
import os
import re
import sys


def parse_suite(stdout_text):
    """Parse AndroidJUnitRunner stdout into (ran, failed, failures).

    `ran` is None when the runner never printed a summary, which is a different
    statement from zero and has to be reported as one: the report's "Ran:" line
    is read by a repair attempt deciding how much of the suite it is looking at.

    The runner has two summaries and only one of them is unconditional. A green
    run ends with `OK (47 tests)`. A red one ends with `FAILURES!!!` followed by
    `Tests run: 47,  Failures: 1` - the count carries two spaces after the comma.
    Reading only the green form, which is what this did until 2026-09-18, made
    **every failing leg report `Ran: 0`**: the report v1.2.0's gate produced (run
    35338666049) diagnosed a 47-test suite as a zero-test one, and a killed run
    cannot print either form.
    """
    total = None
    m = re.search(r"^OK \((\d+) tests?\)", stdout_text, flags=re.M)
    if m:
        total = int(m.group(1))
    else:
        m = re.search(r"^Tests run: (\d+),", stdout_text, flags=re.M)
        if m:
            total = int(m.group(1))

    failures = []
    # Failure blocks look like: "1) testName(ClassName)" followed by the stack.
    block = re.split(r"^(\d+)\) ", stdout_text, flags=re.M)
    # re.split with a group yields [pre, n1, rest1, n2, rest2, ...]
    it = iter(block[1:])
    for _num, rest in zip(it, it):
        header = rest.splitlines()[0] if rest else ""
        stack = rest[:4000]
        failures.append({"test": header, "stack": stack.strip()})
    if not failures:
        # That numbered list is printed with the summary at the very end, so a run
        # the workflow's `timeout` killed before reaching it has none - while the
        # runner has already echoed each failure as it happened, as
        # "Error in <method>(<class>):" over the stack. Reading those back is the
        # difference between a killed run listing the tests it died on and listing
        # nothing beside a FAIL verdict. Only consulted when the numbered list is
        # absent, because when both are present they are the same failures and
        # counting both would double every one of them.
        echoed = re.split(r"^Error in (\S+\([^)]*\)):\s*$", stdout_text, flags=re.M)
        it = iter(echoed[1:])
        seen = set()
        for header, rest in zip(it, it):
            if header in seen:
                continue
            seen.add(header)
            # The stack runs to the blank line that ends the echo.
            stack = rest.strip().split("\n\n")[0]
            failures.append({"test": header, "stack": stack[:4000]})
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
    (out_dir, label, api_level, commit, repo,
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
    lines.append(f"- Under test: `{label}`")
    lines.append(f"- Version: `{version}` (versionName the APK's manifest declares)")
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
    # None is not zero: a killed suite has no count, and printing 0 would claim
    # the suite it died in the middle of never ran anything.
    lines.append(
        "- Ran: " + (str(ran) if ran is not None
                     else "unknown (the suite printed no summary - killed or aborted)")
    )
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
                "label": label,
                "apiLevel": api_level,
                "commit": commit,
            },
        }
        with open(os.path.join(out_dir, "failure-report.json"), "w") as f:
            json.dump(doc, f, indent=2)

    print(report_path)


if __name__ == "__main__":
    main()
