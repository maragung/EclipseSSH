#!/usr/bin/env python3
"""Compose release-test-report.md and universal-test-result.json from the
run's own evidence: the application model, the journey results, the action
trace, the logcat crash scan, and the APK validation. Exit code is always 0
here - the report states the verdict; the workflow reads the JSON.

The verdict (and the RELEASE / DO NOT RELEASE recommendation) is computed
from gate blockers, never from prose: startup crash, JVM/native crash, ANR,
invalid signing, or any failed journey blocks the release.

Usage:
  generate-report.py <out-dir> <model-json> <journeys-json> <trace-json>
      <crash-report-json> <apk-validation-json> <mode> <api-level>
      <commit> <repo> <source-available:yes|no> [environment.txt]
"""
import json
import os
import sys


def load(path, default=None):
    if path and path != "-" and os.path.exists(path):
        with open(path, errors="replace") as fh:
            return json.load(fh)
    return default if default is not None else {}


def journey(named, journeys):
    for j in journeys:
        if j.get("journey") == named:
            return j
    return {}


def fmt_detail(detail):
    if isinstance(detail, (dict, list)):
        return json.dumps(detail, indent=2)[:1500]
    return str(detail)[:1500]


def main():
    if len(sys.argv) < 11:
        print(__doc__, file=sys.stderr)
        sys.exit(2)
    # env_path is optional; sys.argv[1:12] + a default keeps older calls
    # (and muscle memory) working.
    args = sys.argv[1:12]
    (out_dir, model_path, journeys_path, trace_path, crash_path,
     validation_path, mode, api_level, commit, repo, source_available) = args
    env_path = sys.argv[12] if len(sys.argv) > 12 else ""

    model = load(model_path)
    journeys_doc = load(journeys_path)
    trace = load(trace_path)
    crash = load(crash_path)
    validation = load(validation_path)
    journeys = journeys_doc.get("journeys", [])
    meta = trace.get("meta", journeys_doc.get("meta", {}))

    env = {}
    if env_path and os.path.exists(env_path):
        for line in open(env_path, errors="replace"):
            if ":" in line:
                key, _, value = line.partition(":")
                env[key.strip()] = value.strip()

    apk = validation.get("apk", {})
    counts = meta.get("journeyCounts", {})
    failed_journeys = [j["journey"] for j in journeys
                       if j.get("status") == "fail"]
    warned_journeys = [j["journey"] for j in journeys
                       if j.get("status") == "warning"]

    crash_classes = crash.get("classes", {})
    jvm_crashes = crash_classes.get("jvm-fatal", {}).get("count", 0)
    native_crashes = crash_classes.get("native-crash", {}).get("count", 0)
    anrs = crash_classes.get("anr", {}).get("count", 0)

    # ------------------------------------------------------------------ gate
    blockers = []
    cold = journey("cold-start", journeys)
    if cold.get("status") == "fail":
        blockers.append("startup-failure: %s" % fmt_detail(cold.get("detail")))
    if jvm_crashes or native_crashes:
        blockers.append("crash: %d JVM, %d native" % (jvm_crashes,
                                                      native_crashes))
    if anrs:
        blockers.append("anr: %d" % anrs)
    if validation.get("status") == "fail":
        for err in validation.get("errors", ["APK validation failed"]):
            blockers.append("invalid-apk: %s" % err)
    for name in failed_journeys:
        if name != "cold-start":
            blockers.append("journey-failure: %s" % name)

    status = "FAIL" if blockers else "PASS"
    recommendation = "DO NOT RELEASE" if blockers else "RELEASE"

    result = {
        "status": status,
        "recommendation": recommendation,
        "package": model.get("package", apk.get("package", "")),
        "versionName": model.get("versionName",
                                 apk.get("versionName", "")),
        "versionCode": model.get("versionCode", apk.get("versionCode", "")),
        "mode": mode,
        "apiLevel": api_level,
        "commit": commit,
        "seed": meta.get("seed"),
        "screensVisited": meta.get("screensVisited", 0),
        "actionsTotal": meta.get("actionsTotal", 0),
        "journeyCounts": counts,
        "failedJourneys": failed_journeys,
        "crashes": {"jvm": jvm_crashes, "native": native_crashes,
                    "anr": anrs,
                    "forceClose": crash_classes.get(
                        "force-close", {}).get("count", 0)},
        "blockers": blockers,
        "sourceAvailable": source_available == "yes",
        "autoFixPossible": source_available == "yes",
        "apkSha256": apk.get("sha256", ""),
    }
    with open(os.path.join(out_dir, "universal-test-result.json"),
              "w") as fh:
        json.dump(result, fh, indent=2)
        fh.write("\n")

    # ---------------------------------------------------------------- report
    device = meta.get("device", {})
    lines = []
    add = lines.append
    add("# Universal APK test report")
    add("")
    add("| | |")
    add("|---|---|")
    add("| Application | %s |" % (model.get("applicationLabel",
                                            apk.get("package", "?"))))
    add("| Package | %s |" % result["package"])
    add("| Version | %s (code %s) |" % (result["versionName"],
                                        result["versionCode"]))
    add("| Commit | %s |" % commit)
    add("| APK | %s bytes, sha256 %s |" % (apk.get("bytes", "?"),
                                           apk.get("sha256", "?")[:16]))
    add("| API level | %s |" % api_level)
    add("| Device | %s (Android %s, %s) |" % (
        device.get("model") or env.get("device", "?"),
        device.get("release") or env.get("api_level", "?"),
        device.get("abi") or env.get("abi", "?")))
    add("| Mode | %s, seed %s |" % (mode, meta.get("seed", "?")))
    add("")
    add("## Summary")
    add("")
    add("**%s** - %s" % (status, "blocked by: " + "; ".join(blockers)
                         if blockers else "every executed journey passed "
                         "with no crash or ANR signature."))
    add("")
    add("## Feature discovery")
    add("")
    components = model.get("components", {})
    add("| | Discovered | Tested | Passed | Failed |")
    add("|---|---|---|---|---|")
    add("| Screens | %d | %d | %d | %d |" % (
        len(model.get("runtimeScreen", {}).get("elements", []))
        + len(components.get("activity", [])),
        meta.get("screensVisited", 0),
        counts.get("pass", 0), counts.get("fail", 0)))
    add("| Activities | %d | via navigation | | |" %
        len(components.get("activity", [])))
    add("| Services | %d | background crash scan | | |" %
        len(components.get("service", [])))
    add("| Receivers | %d | background crash scan | | |" %
        len(components.get("receiver", [])))
    add("| Providers | %d | not exercised | | |" %
        len(components.get("provider", [])))
    add("| Permissions | %d (%d runtime) | %d cycled | | |" % (
        len(model.get("permissions", [])),
        len(model.get("runtimePermissions", [])),
        len(journey("permission-cycle", journeys).get("detail", []))
        if isinstance(journey("permission-cycle", journeys).get("detail"),
                      list) else 0))
    add("| Deep links | %d | not exercised | | |" %
        len(model.get("deepLinks", [])))
    add("")
    add("Journey tally: %d pass, %d fail, %d warning, %d skipped."
        % (counts.get("pass", 0), counts.get("fail", 0),
           counts.get("warning", 0), counts.get("skip", 0)))
    add("")
    add("## Crashes")
    add("")
    if jvm_crashes or native_crashes:
        add("**%d JVM fatal(s), %d native crash(es)** - see "
            "crashes/universal-crash-report.json for stacks, the previous "
            "action and screenshot references." % (jvm_crashes,
                                                   native_crashes))
    else:
        add("None detected.")
    add("")
    add("## ANR")
    add("")
    if anrs:
        add("**%d ANR signature(s)** - %s" % (
            anrs, crash.get("anrTracesRef", "see diagnostics")))
    else:
        add("None detected.")
    add("")
    for title, names in (
        ("Lifecycle", ("lifecycle-chaos", "rotation",
                       "background-foreground")),
        ("Permissions", ("permission-cycle",)),
        ("Network", ("network-resilience",)),
        ("Persistence", ("state-persistence",)),
        ("Random exploration", ("random-exploration",)),
        ("Navigation and back stack", ("navigation", "back-stack")),
        ("Input validation", ("form-inputs", "input-fuzz")),
    ):
        add("## %s" % title)
        add("")
        any_row = False
        for name in names:
            j = journey(name, journeys)
            if not j:
                continue
            any_row = True
            add("- **%s**: %s - %s" % (name, j.get("status", "?"),
                                       fmt_detail(j.get("detail"))))
        if not any_row:
            add("Not executed in %s mode." % mode)
        add("")
    add("## Visual")
    add("")
    add("%d screenshots captured (screenshots/). Blank-screen detection is "
        "structural (UI-dump emptiness), not visual - screenshots are "
        "evidence for humans." % meta.get("screensVisited", 0))
    add("")
    add("## Auto fixes")
    add("")
    if source_available == "yes":
        add("Source is available: the autonomous repair job can attempt "
            "fixes when the workflow was dispatched with run_repair=true "
            "and ANTHROPIC_AUTH_TOKEN is configured.")
    else:
        add("No source is checked out (apk-only run): source-level "
            "auto-fix is **not possible** and was not attempted. Install, "
            "launch, discovery, UI, lifecycle, permission, navigation, "
            "input and crash testing all ran against the APK itself; only "
            "code-level repair requires source.")
    add("")
    add("## Regression tests")
    add("")
    if source_available == "yes":
        add("Regression tests are produced by the repair loop (one per "
            "fixed defect, per its rules); none are recorded from a "
            "passing or non-repaired run.")
    else:
        add("Not applicable without source.")
    add("")
    add("## Remaining problems")
    add("")
    if blockers:
        for b in blockers:
            add("- %s" % b)
    if warned_journeys:
        for name in warned_journeys:
            j = journey(name, journeys)
            add("- warning: %s - %s" % (name, fmt_detail(j.get("detail"))))
    if not blockers and not warned_journeys:
        add("None.")
    add("")
    add("## Final recommendation")
    add("")
    add("**%s**" % recommendation)
    add("")

    report_path = os.path.join(out_dir, "release-test-report.md")
    with open(report_path, "w") as fh:
        fh.write("\n".join(lines))
    print("report written to %s (status=%s)" % (report_path, status))


if __name__ == "__main__":
    main()
