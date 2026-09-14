# Release APK testing, validation, and autonomous repair

This directory is the test-side of the release pipeline in
[`.github/workflows/android-release-test.yml`](../.github/workflows/android-release-test.yml).
Its contract with that workflow is one sentence: **the APK that ships is the APK
that is tested.** Not a debug build, not a Gradle-installed artifact — the actual
release asset from the GitHub release (per-ABI, release-key signed, R8-minified),
installed on a hosted emulator and exercised by the full instrumented suite.

## Why the released APK specifically

Every prior automated signal ran on the debug build. The one release-only crash
this app has actually shipped — v1.1.12 through v1.1.16 crashed on open because
a library's class-initialization path resolved differently through R8 than it
did in debug — was invisible to all of it. The release APK carries three
properties no debug run reproduces: R8 minification and shrinking, the release
key's signing identity, and resource shrinking.

## Pipeline stages

1. **Resolve** — the release tag (release event, input, or latest), the APK
   source, and the API-level matrix.
2. **Test** (matrix, default API 35 + 30, x86_64, pixel_6) —
   checkout the tag → JDK/SDK → build `:app:assembleReleaseAndroidTest` at the
   same tag → download the release asset (`app-x86_64-release.apk`, universal
   as fallback) or assemble a release APK when validating a branch
   (`apk_source=build`) → **verify** → boot the emulator → **install the
   release APK** → smoke-launch `MainActivity` → pre-grant
   `POST_NOTIFICATIONS` → **signature-gate** the test APK against the app →
   run the suite via `am instrument` under `timeout` (the hang detector) →
   **scan logcat** for crashes/ANRs the suite cannot see → collect
   diagnostics → generate the report → upload evidence.
3. **Gate** — all legs green: comment on the release. Any leg red: pull the
   release back to **draft**, file a GitHub issue with the full report, and
   fail the workflow. A release is only valid when it is green.
4. **Auto-fix** — gated on the `ANTHROPIC_AUTH_TOKEN` repository secret. See
   below.

## The files

| File | Role |
|---|---|
| `feature-manifest.json` | Machine-readable feature inventory: components, permissions, every user-facing feature with its priority and the suite that covers it. The report's feature list comes from here — keep it current when screens or flows change. |
| `verify-release-apk.sh` | Distribution-artifact verification: package name, versionCode/versionName against the checked-out source, x86_64 ABI, no debuggable flag, valid v2+v3 signature. Writes `release-validation.json`; any mismatch fails before install. |
| `scan-crashes.sh` | Post-suite logcat scan for JVM fatals, ANRs, native crashes, force-closes. A passing suite with a crashed background service still fails the gate. Writes `crash-report.json`. |
| `collect-diagnostics.sh` | Deterministic evidence collection (always, pass or fail): logcat, dumpsys activity/package/meminfo/gfxinfo, ANR traces, screenshot, environment record. |
| `generate-report.py` | Composes `release-test-report.md` (verdict, tests, crashes, APK validation, RELEASE / DO NOT RELEASE recommendation) and, on failure, `failure-report.json` with failing tests, stacks, and the app frames a fix will most likely touch. |
| `auto-fix.sh` | The autonomous repair loop. See below. |

## Autonomous repair loop

`auto-fix.sh` runs only when the `ANTHROPIC_AUTH_TOKEN` secret is configured;
without it the loop is a no-op (exit 78) and repair stays with the maintainers —
the failure issue already carries the complete report, so nothing is silently
skipped.

Each attempt: read `failure-report.json` → the agent traces the failure to a
root cause (never the exception message alone) and makes the **smallest
reasonable fix** with a regression test → commit to an `auto-fix/*` branch →
dispatch this same workflow on that branch (`apk_source=build`, so the
**rebuilt** APK is retested) → green: open a PR and stop; red: feed the new
report into the next attempt. An attempt that increases the failure count is
**reverted** before the next try. Hard cap: `MAX_REPAIR_ATTEMPTS` (repository
variable, default 5).

The agent's rules are pinned in the script and enforced by the loop:
no disabling tests, no swallowing exceptions, no timeout inflation to hide
hangs, no test-environment special cases, no unrelated refactors. The loop
never merges anything — a human (or the maintainer's session) reviews the PR.

## What the pipeline does not pretend to cover

Honest limits, stated rather than hidden:

- **Live SSH/SFTP/RDP/Linux-install journeys** need reachable servers and a
  ~600 MB download; they are covered by the JVM suites against embedded
  servers and by the maintainer's on-device loop. The manifest records this
  per feature.
- **API 30 is the oldest leg** — one step above minSdk 28. Add levels via the
  `api_levels` dispatch input; each leg is a full emulator suite, so the
  default matrix is deliberately two.
- **Video recording** is not wired; screenshots and full logcat are. The
  collector's layout leaves room for `videos/` when a runner-side encoder is
  justified.

## Dispatching manually

```
gh workflow run android-release-test.yml \
  -f tag=v1.1.17            # or omit for the latest release
  -f api_levels=35,30
  -f apk_source=release     # release asset | build from the current ref
  -f run_repair=true
```

Pre-release validation of a branch: dispatch with `apk_source=build` from that
branch — the pipeline assembles and signs its own release APK and runs the same
gate, without publishing anything.
