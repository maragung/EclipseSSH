# Ubuntu E2E — the in-app Linux userspace, installed for real and proven usable

This pipeline answers one question: **does the Ubuntu environment this app
installs actually work?** Not "did the UI say Installation completed" — that is
where this pipeline *starts* asking. The answer must come from Ubuntu itself:
a shell that answers, `/etc/os-release` that says Ubuntu, DNS that resolves,
`apt` that is consistent, HTTP that reaches the internet, and files that
survive the app dying.

Nothing here is mocked. The app downloads the pinned Ubuntu Base rootfs over
the network, verifies its SHA-256, extracts it under proot, configures apt
inside it and installs the toolchain — the driver only drives and watches.

## Pieces

| Piece | Role |
|---|---|
| `.github/workflows/android-ubuntu-e2e.yml` | CI: builds the release x86_64 APK + androidTest APK, boots a hosted emulator, runs the driver, scans for crashes, reports, gates, and can run the autonomous repair loop. |
| `driver.py` | The orchestrator: drives the real UI (Settings → Linux userspace → Install → confirm), watches the install, fires the lifecycle exercises and (FULL mode) the failure injections, runs the verification instrumentation, records per-phase results. |
| `summary.py` | Renders `ubuntu-e2e-report.md` (the stage table) and, on failure, `failure-report.json` in the schema `testing/auto-fix.sh` consumes. |
| `scripts/e2e-ubuntu.sh` | The local entry point: builds and installs a debug APK/test pair, then runs the driver. Its `--ci` flag skips that build and expects `$APK`/`$TEST_APK` already installed — no workflow uses it, because `android-ubuntu-e2e.yml` builds, signs and installs its own release pair and calls `driver.py` directly. |
| `app/src/androidTest/.../UbuntuE2eVerificationTest.kt` | The deep verification, inside the app process: executes commands through the app's own session argv against the real installed userspace. Gated on `-e ubuntuE2e true`, so the ordinary release suite skips it. |

The driver reuses `testing/universal/adbutil.py` (guarded adb + UI dump/tap)
— the same layer the universal black-box explorer runs on.

## Modes

| Mode | Phases | Rough cost |
|---|---|---|
| `SMOKE` | preflight → UI install → install-log → deep verification → terminal through the UI | ~20–30 min |
| `STANDARD` (default) | SMOKE + mid-install lifecycle exercises (background, rotation, screen off) + persistence across force-stop + relaunch | ~35–45 min |
| `FULL` | STANDARD + full-disk refusal + process-kill mid-download recovery + airplane-mode mid-download recovery (each a fresh install cycle; needs `adb root`) | ~2–3 h |

## What "pass" means

Every executed phase green **and** no crash/ANR signature in the device log:
a passing table with a crashed background service is a failed run.

The install phase passing means the settings row reports *Installed and
verified* — which the app itself only reaches after its own health probe (a
real shell, a real `getent`, a real `apt-get check`) passes. The verification
phase then re-proves all of that independently, plus HTTP and the persistence
markers, through the app's real session path.

## Local run

```bash
./scripts/e2e-ubuntu.sh                  # SMOKE against a booted emulator
./scripts/e2e-ubuntu.sh --mode STANDARD
./scripts/e2e-ubuntu.sh --mode FULL      # needs an adb-rootable emulator image
```

The script refuses with a clear message if no emulator is booted. `--keep-data`
skips the initial `pm clear` for re-runs; `--collect-diagnostics` runs the
shared `testing/collect-diagnostics.sh` at the end.

## Failure reports

A failed run produces, under the artifact:

- `phase-results.json` — per-phase pass/fail with stage attribution and evidence paths
- `ubuntu-e2e-report.md` — the stage table + final verdict
- `failure-report.json` — the machine-readable report (failing tests, affected
  files, crash scan) that `auto-fix.sh`'s repair loop consumes
- `screenshots/`, `instrument-*.txt`, `logcat-full.txt`, diagnostics

With `ANTHROPIC_AUTH_TOKEN` configured as a repository secret, the workflow's
`auto-fix` job picks that report up and runs the repair loop: diagnose from
the evidence, fix, re-dispatch *this* pipeline, and open a PR only when the
fix survives the same install-and-use E2E.
