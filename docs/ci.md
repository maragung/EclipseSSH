# Continuous integration

Ten workflows, by purpose:

| Workflow | Trigger | What it does |
| --- | --- | --- |
| `ci.yml` | Push to `main`, every pull request, `workflow_dispatch` | The gate. Seven jobs: the documentation figure check, both native modules, release lint, the unit and integration suite, the instrumentation and AAB builds with their signature checks, an emulator crash-on-open smoke, and an idle stress matrix. |
| `fast-test.yml` | Push to `fast-test/**`, `workflow_dispatch` | Lint and the unit/integration suite as a two-entry matrix, each under a hard timeout. The gate a work-in-progress branch uses so it does not have to push to `main` first. |
| `focused-test.yml` | `workflow_dispatch` (`ref`, `filter`, `task`) | One Gradle task against one `--tests` filter, on any ref. How a single class is run without paying for the whole suite. |
| `instrumentation.yml` | Push to `main`, every pull request, `workflow_dispatch` | `connectedDebugAndroidTest` on a booted AVD, against the debug build. |
| `release.yml` | Push to `main`, `workflow_dispatch` | Signed `assembleRelease` and `bundleRelease`, both signatures verified, checksums computed. |
| `tagged-release.yml` | A `vX.Y.Z` (or `vX.Y.Z-…`) tag | The same build from the exact tag, then creates the GitHub release with every APK, the AAB and the checksums. |
| `android-release-test.yml` | `release: published`, `workflow_dispatch` | Takes a *published* release and exercises its APK on an emulator matrix, one job per API level. |
| `universal-apk-test.yml` | `workflow_dispatch` (`apk_source`) | The universal APK on an emulator matrix. Resolve → test → gate → `auto-fix`, which is the autonomous repair loop. |
| `android-ubuntu-e2e.yml` | `workflow_dispatch` (`mode`) | The Linux userspace end-to-end drive: `SMOKE`, `STANDARD`, or `FULL` (which adds failure injection and needs `adb root`). |
| `schema-dump.yml` | `workflow_dispatch`, and a path-filtered `pull_request` touching the data layer | `:app:kspDebugKotlin` and uploads `app/schemas`, for a pull request that changes the database layer. The path filter covers `data/local/**`, `data/model/Models.kt` and the workflow file itself. |

Only `tagged-release.yml` creates a public release. The three matrix workflows do write outside the
Actions tab: each has a `gate` job that collects every API level's report before deciding and opens
a GitHub issue when the run failed (`issues: write`), and each has an `auto-fix` job — *Autonomous
repair* — that then runs the repository's own repair loop in `testing/` over the failure evidence,
with `contents: write`, `actions: write` (it re-dispatches its own workflow) and an
`ANTHROPIC_AUTH_TOKEN`. That loop is bounded: `android-release-test.yml` and
`android-ubuntu-e2e.yml` read a `MAX_REPAIR_ATTEMPTS` repository variable (default 5, and 3 for the
E2E workflow), while `universal-apk-test.yml` takes the same cap as its own `max_repair_attempts`
dispatch input, default 5. It is the one part of this pipeline that reacts to a red run by trying to
change something rather than only reporting it.

Whether it runs by default is not the same in all three, and the difference is deliberate:
`android-release-test.yml` and `android-ubuntu-e2e.yml` take `run_repair: true` as their default and
so run unless told `run_repair=false`, while `universal-apk-test.yml` takes `false` and runs only
when asked. `gate` is `if: always()`, so it collects and reports on a green run too — it is the job
that decides what the run was worth. `auto-fix` is the one gated on `failure()`, so it only ever
wakes up after a red one.

## ci.yml

Seven jobs, so a failure lands on the thing that is actually broken instead of stopping a chain:

| Job | Command | What it protects |
| --- | --- | --- |
| `docs` | `bash scripts/check-doc-figures.sh` | That the figures the documents state are still the ones the build files pin, that the repository paths they name still resolve, and that the check names in `docs/branch-protection.md` are the ones the jobs report as. Needs no JDK, no Android SDK and no Gradle, so it is the first job listed and the first to go red. |
| `native` | `:freerdp:assembleDebug`, `:linux:assembleDebug` | That both native modules still build from their pinned sources and patches: `:freerdp` on all four ABIs, and `:linux` on the three Ubuntu Base publishes for (`arm64-v8a`, `armeabi-v7a`, `x86_64` — there is no i386 Ubuntu Base image, so no `x86`). |
| `lint` | `lintRelease` | Release-variant Android lint, including the manifest and resource checks that only run for `release`. |
| `test` | `testDebugUnitTest` | The whole JVM suite, Robolectric included, with an isolated OpenSSH sandbox started for the tests that dial a real server. |
| `assemble` | `assembleDebugAndroidTest`, `assembleDebug assembleRelease bundleRelease`, `:app:dependencies --configuration releaseRuntimeClasspath --write-locks` | That the `androidTest` sources still compile, that both APKs and the Play AAB build, that the dependency lockfiles are still satisfied, and that the release APK is signed. |
| `smoke` | Boots a headless AVD and launches both APKs | That the app starts and stays up. This is the crash-on-open gate — a window that dies in `onCreate` passes every JVM test there is. |
| `stress` | `ECLIPSE_STRESS=1 :app:testDebugUnitTest --tests '*RealOpenSshInteropRobolectricTest'`, under a 90-minute job cap | The idle matrix against a real OpenSSH server: connections kept open long enough to catch a keep-alive or NAT-rebinding regression. The step asserts the class ran with `skipped="0"`, so a leg that quietly skipped is a failure rather than a pass. |

The `assemble` job asserts, before it compiles them, that the `androidTest` sources contain at least
one test, because a suite that compiles to nothing is indistinguishable from a suite that passes.

### What the `docs` job checks, and what it does not

`scripts/check-doc-figures.sh` compares checkable claims, not prose. It reads each number out of the
file that owns it — the SDK levels and version names from `app/build.gradle.kts`, the library
versions from `gradle/libs.versions.toml`, the Gradle version from the wrapper properties, the proot
commit from `linux/build.gradle.kts`, the FreeRDP pin from `freerdp/build.gradle.kts` — and fails
when a document states a different one. It also recounts the test totals in the README from the
sources, checks every library version the About screen shows, checks that every workflow a document
names exists, checks that every repository path a document names resolves (from the root or from
that document's own directory), and checks that each row of the branch-protection table names a check
the job actually reports as. One of its checks is about the tree rather than a document: no Kotlin
source under `app/src` may contain a NUL byte, because such a file is binary to `grep` and every
count taken from it is silently short.

It cannot tell whether a sentence is *true*, only whether a claim is current, and it deliberately
never rewrites one: a document that disagrees with the build is not always a stale document, and a
script that edited the prose would hide the case where the build is what changed by mistake.

There is no release unit-test variant to run: AGP 9 removed `testReleaseUnitTest`, so
`testDebugUnitTest` is the only JVM suite task (ci.yml's `test` job says so where it invokes it).
The release side keeps its coverage through `lintRelease` and the assembled release artifacts
instead.

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
notes are composed from the newest `## <n>.` section of `AUDIT-REPORT.md` **that names this tag's
own version**, because the narrative stopped at §36 (*Releasing 1.1.4*) while the version kept
moving: the unfiltered "latest section" was thirteen releases old and was being published as the
summary of the one being released. A tag no section mentions gets no summary line, but the release
still links the report rather than going out blank.

The release is always created as a **draft**, so publishing it is a deliberate second step. The
tag's own shape is not lost — a pre-release suffix (e.g. `v1.2.0-rc.1`) sets the release's
`prerelease` flag from the same metadata step — but it does not make the draft unconditional or
conditional in either direction.

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
every one creates a **headless** AVD — there is no window to attach to. The framebuffer is still
readable, and three of the five read it: the three matrix workflows each capture a first-launch
image of their own and then run `testing/collect-diagnostics.sh`, which takes a screenshot and a
full logcat into the run's artifact. `instrumentation.yml` and `ci.yml`'s `smoke` job keep the log
and leave the screen alone.

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

Three caches, because they expire on different things:

- `gradle/actions/setup-gradle@v4` handles the Gradle user home (dependencies, wrapper, build cache).
  It is read-only off `main`, so a pull request cannot poison the cache the branch builds from.
- `actions/cache@v4` for the two native build trees, `freerdp/build` and `linux/build`, keyed on the
  module's own build file — and, for `:linux`, on `linux/proot-patches/*.patch` as well, since the
  patches are part of what is compiled.
- `actions/cache@v4` for `~/.m2/repository/org/robolectric`, keyed on `gradle/libs.versions.toml`
  and the `COMPILE_SDK` pin. Robolectric downloads its `android-all` jars (~100 MB per SDK level)
  from Maven Central at *test* time, not resolve time, so Gradle's own cache never contains them.

## Signing

The release APK is signed with the real upload key only if both secrets exist:

- `RELEASE_KEYSTORE_BASE64` — `base64 -w0 keystore/eclipse-release.jks`
- `RELEASE_KEYSTORE_PROPERTIES` — the contents of `keystore.properties`

The step writes them to `keystore/eclipse-release.jks` and `keystore.properties`, `chmod 600`, never
echoes either, and a final `if: always()` step removes both — `rm -f keystore.properties` and
`rm -rf keystore`; the step is named "Shred signing material", but it is `rm` on an ephemeral
runner's disk, not `shred(1)`. Nothing survives into an artifact or a cache. Without the secrets the
build still succeeds: `app/build.gradle.kts` falls back to the debug key and logs a loud warning,
and the signature check then only asserts the APK is signed at all. That fallback is deliberate — a
fork should be able to build the app — but a debug-signed APK is not publishable and cannot upgrade
an existing install.

## Reproducing a CI failure locally

The documentation check needs nothing but `bash` and the checkout, so it is the one gate that is
always reproducible here:

```sh
bash scripts/check-doc-figures.sh
```

Everything else needs the toolchain:

```sh
./gradlew lintRelease testDebugUnitTest assembleDebugAndroidTest \
          assembleDebug assembleRelease bundleRelease
```

Reports land in `app/build/reports/`. It is the `test` job that uploads them, as the `reports`
artifact, under `if: always()` — so a red suite still hands over its HTML and XML. No other job
uploads them, and `lint` in particular does not: its only record is the log line that says the HTML
and SARIF reports were written, so a lint count read off a run's artifact is not available.
The emulator jobs cannot be reproduced this way — they need a booted device, which is what the five
workflows above that boot one exist to provide. `focused-test.yml` is not among them: it runs a JVM
task against a `--tests` filter and has no emulator in it.
