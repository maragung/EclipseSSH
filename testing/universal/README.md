# Universal APK testing, crash detection, debugging and auto-fix

A black-box, adb-driven test system for **any** Android APK - with or
without source, View-based or Compose, any package name. Everything the
system knows about the app it learns at runtime: identity and structure
from the APK (`aapt2`), behavior from a live emulator (`uiautomator` dumps,
`input` events, `am`, `pm`, logcat). No instrumentation, no Compose test
rules, no app-specific code. The one app-specific fact the pipeline needs -
the package and launchable activity - is read from the APK badging before
every launch; nothing is hardcoded.

Pipeline: `.github/workflows/universal-apk-test.yml`.

## Architecture

```
                aapt2 dump badging / xmltree
                        |
                 discover-app.py  -----> application-model.json
                        |                 (package, versions, SDKs,
                 observe first screen      permissions, components,
                        |                  deep links, ABIs, first screen)
                 explore.py
        dump -> classify -> act -> observe -> record
                        |
        action-trace.json  journeys.json  crash-events.json  screenshots/
                        |
                 scan-issues.py  <--- logcat (independent pass)
                        |
                 generate-report.py ----> release-test-report.md
                                          universal-test-result.json
                        |
                 gate (aggregate legs) -> auto-fix.sh (optional repair loop)
```

Shared plumbing lives in `adbutil.py`: the guarded `Adb` wrapper (every call
time-bounded, every failure a value - a dead emulator mid-journey degrades
into a recorded failure, never a lost report), the UI-dump parser, element
classification, screen signatures, and the policy tables (dangerous
permissions, destructive keywords, safe fuzz inputs). `verify-apk.sh`
gates the APK before it ever reaches the emulator.

## Modes and budgets

| Mode | Journeys | Wall clock | Actions |
|---|---|---|---|
| SMOKE | cold start, first screen, one rotation, background/foreground, crash scan | ~5 min | 40 |
| STANDARD | + navigation discovery, back-stack sanity, dialogs, basic form inputs, permission grant/revoke cycle | ~15 min | 150 |
| DEEP | + input fuzzing (full safe-value set), lifecycle chaos (rotation matrix, process death, force-stop, keyboard), seeded random exploration, state persistence | ~30 min | 400 |
| RELEASE | + network failure suite (airplane mode), both matrix levels mandatory, stricter APK gate (debuggable fails) | ~45 min | 600 |

Budgets are hard: the engine stops at the action cap or the deadline,
whichever comes first, and records the remaining journeys as
skipped-by-budget. Randomization is seeded (`--seed`, recorded in
`action-trace.json` meta) so a failure replays exactly: same seed, same
mode, same screens, same action sequence.

## How discovery works

- **Static**: `aapt2 dump badging` (package, versionCode/versionName,
  minSdk/targetSdk, permissions, launchable activity, native ABIs) and
  `aapt2 dump xmltree AndroidManifest.xml` (activities, services,
  receivers, providers, exported flags, intent filters; VIEW filters with
  data schemes become deep links).
- **Runtime**: after the first verified launch, the first screen is dumped
  and its classified elements are merged into the model.
- **Element addressing**: elements are picked from the dump by *kind* first
  (`KIND_PRIORITY`: tab, menu, button, link, image-button, picker, switch,
  radio, clickable, scrollable, text-field, webview) and then by a stable,
  bounds-free descriptor, so the same screen yields the same walk. A tap is
  always the element's `center` from its bounds; the human-readable name in
  the traces and reports is the first non-empty of text, content-desc,
  resource-id, class. Coordinates are never invented — every tap is inside a
  node the dump actually reported.
- **Screen identity**: a stable sha1 over sorted element descriptors
  (class, resource-id, content-desc, truncated text) - deliberately
  bounds-free so a rotation does not fork the visited-screen set. Compose
  apps are covered because Compose exposes semantics through
  accessibility, which `uiautomator dump` sees.

## Safe-interaction rules

- **Destructive flows are never confirmed.** Elements whose label
  (text/content-desc/resource-id) carries a destructive keyword (delete,
  remove, clear, buy, pay, checkout, send, share, logout, reset,
  uninstall, ...) are probed, not performed: open the flow, verify a
  confirmation UI appeared (when one exists), press CANCEL - never a
  positive button - and verify the app and screen survived.
- **No purchases, no irreversible external actions, no real recipients.**
- **Input fuzzing is correctness testing, not DoS**: values are bounded
  (empty, short, whitespace, numeric, negative, decimal, special chars,
  path-like, SQL-ish, unicode, emoji, max 2000 chars).
- **No positive-button taps inside dialogs reached from a destructive
  element**; unknown dialogs are dismissed with BACK.
- Tapping is limited to the app's own windows; system chrome is flagged,
  not touched.

## What a failure looks like

Journeys return pass / fail / warning / skip. `scan-issues.py` scans the
captured logcat independently for JVM fatals, native crashes (SIGSEGV,
SIGABRT, crash_dump), ANRs ("Input dispatching timed out", "executing
service timed out") and force-closes, attaching stacks and the previous
action. `generate-report.py` computes the verdict from gate blockers -
startup failure, JVM/native crash, ANR, invalid APK, failed journey -
and writes the recommendation (RELEASE / DO NOT RELEASE). The gate job
aggregates matrix legs and files an issue on failure; a leg that dies
before producing a result (boot failure, install failure, invalid
signing) counts as failed, never green.

## Artifacts (per API-level leg)

```
apk/        the APK under test
logs/       full logcat, diagnostics log
screenshots/ first launch + every new screen + crash moments
traces/     action-trace.json (every action, target, screen, ms), journeys.json
crashes/    crash-events.json (with stack + previous action + screenshot), universal-crash-report.json
anr/        ANR traces, when the image allows reading /data/anr
reports/    release-test-report.md, universal-test-result.json,
            application-model.json, apk-validation.json
```

## Dispatch

GitHub Actions -> "Universal APK test" -> Run workflow:

- `apk_source`: `build-from-source` (build and test this ref),
  `artifact-from-release` (download a published release asset), `url`
  (fetch an arbitrary APK).
- `apk_url`: the direct APK URL — required when `apk_source=url`.
- `tag`: the release tag to download from — used when
  `apk_source=artifact-from-release`, and empty means the latest release.
- `mode`: SMOKE / STANDARD / DEEP / RELEASE (see table above).
- `api_levels`: emulator matrix, default `35,30`.
- `seed`: default `20260914`; keep it fixed to compare runs, change it to
  explore different paths.
- `max_actions`, `max_test_minutes`: per-run overrides of the mode's action
  and time budgets; empty means the mode's own numbers.
- `max_repair_attempts`: the repair loop's cap, default 5.
- `run_repair`: `true` lets the repair loop run **only** when
  `apk_source=build-from-source` and `ANTHROPIC_AUTH_TOKEN` is configured.

CLI equivalent:

```sh
gh workflow run universal-apk-test.yml \
  -f apk_source=build-from-source -f mode=DEEP -f api_levels=35,30
```

### The auto-fix loop

On failure (with `run_repair=true`, source build, token configured):
read the verdict -> the claude CLI produces the smallest real fix plus a
regression test -> push `auto-fix/universal-*` branch -> re-dispatch this
same workflow on that branch (`apk_source=build-from-source`, so the
rebuilt APK is what gets retested) -> on green, open a PR; on red, feed
the new verdict into the next attempt. A fix that increases the failure
metric (blockers + crashes + ANRs) is reverted before the next attempt.
Cap: `max_repair_attempts` (default 5).

### Apk-only runs (Section 30)

When `apk_source` is `url` or `artifact-from-release`, everything
black-box still runs - install, launch, discovery, UI, lifecycle,
permission, navigation, input and crash/ANR testing, diagnostics, report.
Source-level auto-fix is **skipped and stated as skipped** in the report;
it is never pretended. The repair loop itself exits 78 with the reason:
the repair branch must be cut from the ref under test, which only a
source build has (and the retest dispatch needs the workflow file on the
branch).

## What this system honestly does not cover

- **Deep links are discovered but not exercised** - following a
  `scheme://` URI needs per-scheme handling the engine cannot guess.
- **Content providers are listed but not queried** - the correct URIs and
  expected shapes are app-specific.
- **Persistence reverts are warnings, not failures** - a black-box engine
  cannot distinguish a session-scoped control from a persistence bug.
- **Clean exit on BACK from the top screen is recorded, not failed** -
  Android sanctions both behaviors; only crashes and blank screens fail.
- **Visual checks are structural** (blank-screen detection via UI-dump
  emptiness, dialog coverage) - no AI vision, no pixel diffing.
- **A dialog-open screen can defeat `uiautomator dump`** on some images
  ("could not get idle state"); the engine retries a bounded number of
  times and then records a dump failure rather than hanging.
- **`am kill` only reaches background processes**; the process-death
  journey backgrounds the app first. Nothing checks that either step
  happened — `home()` and `am_kill()` return a value no caller reads, and
  the journey suppresses `force-close` signatures precisely because it
  expects the process to die — so an `am kill` that reached nothing is
  recorded as a pass. What the journey establishes is that the app is alive
  and unblanked after the trip, not that the trip killed it. Until those
  two return values are checked, read its process-death rows as unverified.
- **Network testing is airplane-mode cycling**, not a proxy/latency fault
  matrix - no MITM, no DNS poisoning.
- **No coverage claim**: the engine visits what it can discover and
  reach; unvisited code is invisible to it. A pass means "what was
  exercised did not break", not "everything works".

## Local scripts (not just CI)

All Python here is stdlib-only and runs on the GitHub runner as-is. With
`ANDROID_HOME` set (build-tools for `aapt2`, platform-tools for `adb`) the
pieces work standalone against any emulator:

```sh
python3 testing/universal/discover-app.py --apk app.apk \
  --aapt2 "$ANDROID_HOME/build-tools/36.0.0/aapt2" --out model.json
python3 testing/universal/explore.py --model model.json \
  --out artifacts --mode DEEP --seed 20260914
```
