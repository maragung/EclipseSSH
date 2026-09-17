# Continuous integration

Ten workflows, by purpose:

| Workflow | Trigger | What it does |
| --- | --- | --- |
| `ci.yml` | Push to `main`, every pull request, `workflow_dispatch` | The gate. Six jobs: both native modules, release lint, the unit and integration suite, the instrumentation and AAB builds with their signature checks, an emulator crash-on-open smoke, and an idle stress matrix. |
| `fast-test.yml` | Push to `fast-test/**`, `workflow_dispatch` | Lint and the unit/integration suite as a two-entry matrix, each under a hard timeout. The gate a work-in-progress branch uses so it does not have to push to `main` first. |
| `focused-test.yml` | `workflow_dispatch` (`ref`, `filter`, `task`) | One Gradle task against one `--tests` filter, on any ref. How a single class is run without paying for the whole suite. |
| `instrumentation.yml` | Push to `main`, every pull request, `workflow_dispatch` | `connectedDebugAndroidTest` on a booted AVD, against the debug build. |
| `release.yml` | Push to `main`, `workflow_dispatch` | Signed `assembleRelease` and `bundleRelease`, both signatures verified, checksums computed. |
| `tagged-release.yml` | A `vX.Y.Z` (or `vX.Y.Z-…`) tag | The same build from the exact tag, then creates the GitHub release with every APK, the AAB and the checksums. |
| `android-release-test.yml` | `release: published`, `workflow_dispatch` | Takes a *published* release and exercises its APK on an emulator matrix, one job per API level. |
| `universal-apk-test.yml` | `workflow_dispatch` (`apk_source`) | The universal APK on an emulator matrix. Resolve → test → gate → `auto-fix`, which is the autonomous repair loop. |
| `android-ubuntu-e2e.yml` | `workflow_dispatch` (`mode`) | The Linux userspace end-to-end drive: `SMOKE`, `STANDARD`, or `FULL` (which adds failure injection and needs `adb root`). |
| `schema-dump.yml` | `workflow_dispatch` | `:app:kspDebugKotlin` and uploads `app/schemas`, for a pull request that changes the database layer. |

Only `tagged-release.yml` creates a public release. The three matrix workflows do write outside the
Actions tab: each has a `gate` job that collects every API level's report before deciding, and opens
a GitHub issue when the run failed (`issues: write`), and each has an `auto-fix` job — *Autonomous
repair* — that then runs the repository's own repair loop in `testing/` over the failure evidence,
with `contents: write` and an `ANTHROPIC_AUTH_TOKEN`. That loop is bounded (a `max_repair_attempts`
input, default 5) and can be turned off (`run_repair: false`); it is the one part of this pipeline
that reacts to a red run by trying to change something rather than only reporting it.

## ci.yml

Six jobs, so a failure lands on the thing that is actually broken instead of stopping a chain:

| Job | Command | What it protects |
| --- | --- | --- |
| `native` | `:freerdp:assembleDebug`, `:linux:assembleDebug` | That both native modules still build from their pinned sources and patches, on all four ABIs. |
| `lint` | `lintRelease` | Release-variant Android lint, including the manifest and resource checks that only run for `release`. |
| `test` | `testDebugUnitTest` | The whole JVM suite, Robolectric included, with an isolated OpenSSH sandbox started for the tests that dial a real server. |
| `assemble` | `assembleDebugAndroidTest`, `assembleDebug assembleRelease bundleRelease`, `:app:dependencies --write-locks` | That the `androidTest` sources still compile, that both APKs and the Play AAB build, that the dependency lockfiles are still satisfied, and that the release APK is signed. |
| `smoke` | Boots a headless AVD and launches both APKs | That the app starts and stays up. This is the crash-on-open gate — a window that dies in `onCreate` passes every JVM test there is. |
| `stress` | A filtered `testDebugUnitTest` under `timeout --signal=QUIT` | The idle matrix against a real OpenSSH server: connections kept open long enough to catch a keep-alive or NAT-rebinding regression. |

The `assemble` job's first step asserts that the `androidTest` sources contain at least one test,
because a suite that compiles to nothing is indistinguishable from a suite that passes.

`testReleaseUnitTest` is a real task and worth running locally, but no workflow runs it: the
release variant's resource shrinking is exercised by `assemble` and the smoke job instead.

## fast-test.yml

One matrix job, `verify`, with two entries — `lintRelease` and `testDebugUnitTest` — each run
through `timeout --signal=QUIT --kill-after=60s` so a hung test is killed and reported rather than
sitting until the six-hour job limit. It fires on `fast-test/**` branches, which is what makes it
the gate for work that is not ready for `main`.

## release.yml and tagged-release.yml

Both do `assembleRelease` **and** `bundleRelease`, verify the APK signature and the AAB signature
separately (the AAB is not an APK and `apksigner` cannot read it as one), and compute checksums.

The difference is the source of the build. `release.yml` builds whatever the branch holds and
uploads the artifacts. `tagged-release.yml` asserts `git describe --exact-match` first — the build
must be the tag, not a branch that resembles it — and then creates the GitHub release. Release
notes are composed from the latest section heading of `AUDIT-REPORT.md`, and the step fails if that
file is missing or the section cannot be found, rather than publishing a blank release.

The release is created as a **draft** when the tag carries a pre-release suffix (e.g. `v1.2.0-rc.1`)
and as a normal release otherwise.

Cutting a release:

```sh
git tag v1.2.3
git push origin v1.2.3
```

## The emulator jobs

Five workflows boot an AVD: `instrumentation.yml` (to run `connectedDebugAndroidTest`), `ci.yml`'s
`smoke` job (to launch both APKs and assert they stay up), and the three matrix workflows
(`android-release-test.yml`, `universal-apk-test.yml`, `android-ubuntu-e2e.yml`), which each
resolve a matrix, run one job per API level, and gate on the collected reports.

Every one of them enables KVM device permissions on the runner before starting the emulator, and
every one creates a **headless** AVD — there is no window to attach to, and no screenshot to take.
The failure evidence is the device log, uploaded as an artifact.

The three matrix workflows share a shape worth knowing when one of them goes red: a `resolve` job
turns the dispatch inputs into a matrix, `test` runs per API level, and `gate` collects every
report before deciding — so a single failing API level fails the run with the others' results still
available. `universal-apk-test.yml` and the other two then run `auto-fix`, which is a repair loop
that reads the failure report and the run evidence from the artifacts.

## Reading the signature check

`apksigner verify --verbose` prints `Verified using v2 scheme: false` for this APK, and that is not a
missing signature. `minSdk` is 28, v3 covers 28 and up, and the verifier stops at the highest scheme
that covers the whole range rather than falling back — so the v2 result stays `false` even though the
block is there. Two ways to see it:

```sh
apksigner verify --verbose --min-sdk-version 24 --max-sdk-version 27 app-release.apk   # v2: true
```

or by listing the APK Signing Block IDs, where `0x7109871a` (v2) and `0xf05368c0` (v3) are both
present. v1 is off deliberately and v4 needs an `.idsig` nothing in this pipeline consumes; see the
`signingConfigs` comment in `app/build.gradle.kts`.

A CI run without the signing secrets prints the mirror image — `v2: true, v3: false` — for a different
reason. That APK is signed with the debug key, and the explicit `enableV3Signing = true` lives on the
release `signingConfig`, so the fallback carries AGP's defaults instead. It installs on everything the
app supports (v2 covers API 24 up, `minSdk` is 28) and the debug key is never rotated, so v3 buys it
nothing. Both readings are why the workflows only assert the v2 *and* v3 blocks when
`steps.signing.outputs.signed` is `true`: with a real key their absence is a defect worth failing on,
and without one it is expected.

## Caching

Two caches, because they expire on different things:

- `gradle/actions/setup-gradle@v4` handles the Gradle user home (dependencies, wrapper, build cache).
  It is read-only off `main`, so a pull request cannot poison the cache the branch builds from.
- `actions/cache@v4` for `~/.m2/repository/org/robolectric`, keyed on `gradle/libs.versions.toml` and
  `app/build.gradle.kts`. Robolectric downloads its `android-all` jars (~100 MB per SDK level) from
  Maven Central at *test* time, not resolve time, so Gradle's own cache never contains them.

## Signing

The release APK is signed with the real upload key only if both secrets exist:

- `RELEASE_KEYSTORE_BASE64` — `base64 -w0 keystore/eclipse-release.jks`
- `RELEASE_KEYSTORE_PROPERTIES` — the contents of `keystore.properties`

The step writes them to `keystore/eclipse-release.jks` and `keystore.properties`, `chmod 600`, never
echoes either, and a final `if: always()` step shreds both so nothing survives into an artifact or a
cache. Without the secrets the build still succeeds: `app/build.gradle.kts` falls back to the debug
key and logs a loud warning, and the signature check then only asserts the APK is signed at all. That
fallback is deliberate — a fork should be able to build the app — but a debug-signed APK is not
publishable and cannot upgrade an existing install.

## Reproducing a CI failure locally

```sh
./gradlew lintRelease testDebugUnitTest assembleDebugAndroidTest \
          assembleDebug assembleRelease bundleRelease
```

Reports land in `app/build/reports/`; that whole directory is what the `reports` artifact contains.
The emulator jobs cannot be reproduced this way — they need a booted device, which is what
`focused-test.yml` and the matrix workflows exist to provide.
