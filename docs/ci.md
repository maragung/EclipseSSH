# Continuous integration

Eleven workflows, by purpose:

| Workflow | Trigger | What it does |
| --- | --- | --- |
| `ci.yml` | Push to `main`, every pull request, `workflow_dispatch` | The gate. Eight jobs: the documentation figure check, both native modules, release lint, the unit and integration suite, the instrumentation and AAB builds with their signature checks, an emulator crash-on-open smoke, an idle stress matrix that only a dispatch which asks for it runs, and the closer for the CI window. |
| `fast-test.yml` | Push to `fast-test/**`, `workflow_dispatch` | Lint and the unit/integration suite as a two-entry matrix, each under a hard timeout. The gate a work-in-progress branch uses so it does not have to push to `main` first. |
| `focused-test.yml` | `workflow_dispatch` (`ref`, `filter`, `task`) | One Gradle task against one `--tests` filter, on any ref. How a single class is run without paying for the whole suite. |
| `instrumentation.yml` | Push to `main`, every pull request, `workflow_dispatch` | `connectedDebugAndroidTest` on a booted AVD, against the debug build. |
| `release.yml` | Push to `main`, `workflow_dispatch` | Signed `assembleRelease` and `bundleRelease`, both signatures verified, checksums computed. |
| `tagged-release.yml` | A `vX.Y.Z` (or `vX.Y.Z-…`) tag | The same build from the exact tag, then creates the GitHub release with every APK, the AAB and the checksums. |
| `android-release-test.yml` | `release: published`, `workflow_dispatch` | Takes a *published* release and exercises its APK on an emulator matrix, one job per API level. |
| `universal-apk-test.yml` | `workflow_dispatch` (`apk_source`) | The universal APK on an emulator matrix. Resolve → test → gate → `auto-fix`, which is the autonomous repair loop. |
| `android-ubuntu-e2e.yml` | `workflow_dispatch` (`mode`) | The Linux userspace end-to-end drive: `SMOKE`, `STANDARD`, or `FULL` (which adds failure injection and needs `adb root`). |
| `schema-dump.yml` | `workflow_dispatch`, and a path-filtered `pull_request` touching the data layer | `:app:kspDebugKotlin` and uploads `app/schemas`, for a pull request that changes the database layer. The path filter covers `data/local/**`, `data/model/Models.kt` and the workflow file itself. |
| `close-ci-window.yml` | `workflow_call`, from `ci.yml` and `release.yml` | Flips the repository back to private at the end of a run that started while it was public. Skipped — and so free, and secret-free — whenever the repository is private, which is every ordinary run. See [The CI window](#the-ci-window). |

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

Eight jobs, so a failure lands on the thing that is actually broken instead of stopping a chain:

| Job | Command | What it protects |
| --- | --- | --- |
| `docs` | `bash scripts/check-doc-figures.sh` | That the figures the documents state are still the ones the build files pin, that the repository paths they name still resolve, and that the check names in `docs/branch-protection.md` are the ones the jobs report as. Needs no JDK, no Android SDK and no Gradle, so it is the first job listed and the first to go red. |
| `native` | `:freerdp:assembleDebug`, `:linux:assembleDebug` | That both native modules still build from their pinned sources and patches: `:freerdp` on all four ABIs, and `:linux` on the three Ubuntu Base publishes for (`arm64-v8a`, `armeabi-v7a`, `x86_64` — there is no i386 Ubuntu Base image, so no `x86`). |
| `lint` | `lintRelease` | Release-variant Android lint, including the manifest and resource checks that only run for `release`. |
| `test` | `testDebugUnitTest` | The whole JVM suite, Robolectric included, with an isolated OpenSSH sandbox started for the tests that dial a real server. |
| `assemble` | `:app:dependencies --configuration releaseRuntimeClasspath`, `assembleDebugAndroidTest`, `assembleDebug assembleRelease bundleRelease`, `assembleRelease` again on its own | That the release classpath still resolves against `app/gradle.lockfile` (the report is read, and a `FAILED` row fails the job) before anything is compiled, that the `androidTest` sources still compile, that both APKs and the Play AAB build, that the per-ABI splits build, and that the release APK is signed. The second `assembleRelease` is the one that produces the splits: `splits.abi` stands down inside the first invocation because a `bundle` task shares it, so a run that stopped there would build no per-ABI APK at all — and the per-ABI APKs are what a GitHub release serves. |
| `smoke` | Boots a headless AVD and launches both APKs | That the app starts and stays up. This is the crash-on-open gate — a window that dies in `onCreate` passes every JVM test there is. |
| `stress` | `ECLIPSE_STRESS=1 :app:testDebugUnitTest --tests '*RealOpenSshInteropRobolectricTest'`, under a 90-minute job cap | The idle matrix against a real OpenSSH server: connections kept open long enough to catch a keep-alive or NAT-rebinding regression. The step asserts the class ran with `skipped="0"`, so a leg that quietly skipped is a failure rather than a pass. Runs only when a `workflow_dispatch` sets the `stress` input, which defaults to false — a dispatch that does not ask for it finishes with the other seven. |
| `close-window` | `gh api -X PATCH repos/… -F private=true` | Nothing the repository builds. It closes a CI window, and it only exists in a run that started while the repository was public; every ordinary run reports it as `skipped`, so it spends no runner and needs no secret to be configured. See [The CI window](#the-ci-window). |

The `assemble` job asserts, before it compiles them, that the `androidTest` sources contain at least
one test, because a suite that compiles to nothing is indistinguishable from a suite that passes.

Its first Gradle step is the dependency-lockfile check. `app/gradle.lockfile` records the *resolved*
version of every coordinate on `releaseRuntimeClasspath`, and a lock state is enforced as a
`strictly` constraint, so a catalog bump that the lockfile does not carry — which is every dependency
bot's bump, since a bot can edit `gradle/libs.versions.toml` and cannot run `--write-locks` — leaves
that classpath unresolvable. It is first because of where that failure otherwise lands: the assemble
steps below are what break, and they break with a message about a navigation resource file that
cannot be serialized into the configuration cache, while the sentence that names the lockfile turns up
in the `lint` job's log instead (issue #130).

The refusal is a `grep`, and that is not decoration. `:app:dependencies` is a report task: it resolves
leniently, prints `FAILED` beside the coordinate that will not resolve, and exits 0, so the report on
its own cannot fail a build. The step reads that report and fails on the `FAILED` row, and it checks
Gradle's own exit status first — otherwise a build script error, a daemon OOM or a lost network would
leave a report that was never written, no `FAILED` row to find, and a step that printed the sentence
saying the classpath resolved. Running the same command with `--write-locks`, which is what the step
used to do, rewrote the lockfile to match whatever it had just resolved — the drift the step is named
for was the one outcome it could not produce. The remedy the failing step prints is that command, on a
runner, with the file committed.

### What the `docs` job checks, and what it does not

`scripts/check-doc-figures.sh` compares checkable claims, not prose. It reads each number out of the
file that owns it — the SDK levels and version names from `app/build.gradle.kts`, the library
versions from `gradle/libs.versions.toml`, the Gradle version from the wrapper properties, the proot
commit from `linux/build.gradle.kts`, the FreeRDP pin from `freerdp/build.gradle.kts` — and fails
when a document states a different one. It also recounts the README's test totals from the sources —
the `@Test` methods in `app/src/test` and `app/src/androidTest`, and the files that hold them, which
are a different number and which the README therefore names as files — and checks the README's one
claim about classes rather than files, that `ChoiceActivitiesRobolectricTest.kt` declares six of them,
against that file. It checks every library version the About screen shows, checks that every workflow
a document names exists, checks that every repository path a document names resolves (from the root or
from that document's own directory), and checks that each row of the branch-protection table names a
check the job actually reports as. One of its checks is about the tree rather than a document: no Kotlin
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
own version**. The narrative and the version number advance independently — §36 is *Releasing 1.1.4*
and §37 is *Releasing 1.1.18*, with the substantive §33 and §35 between them — so "the newest
section" is not "the section for this release": when the filter was added, the unfiltered newest
section was thirteen releases old and was being published as the summary of the one being released.
A tag no section mentions gets no summary line, but the release still links the report rather than
going out blank.

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

- `gradle/actions/setup-gradle@v6.3.0` handles the Gradle user home (dependencies, wrapper, build
  cache). It is read-only off `main`, so a pull request cannot poison the cache the branch builds
  from.
- `actions/cache@v6.1.0` for the two native build trees, `freerdp/build` and `linux/build`, keyed on
  the module's own build file — and, for `:linux`, on `linux/proot-patches/*.patch` as well, since
  the patches are part of what is compiled.
- `actions/cache@v6.1.0` for `~/.m2/repository/org/robolectric`, keyed on `gradle/libs.versions.toml`
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

`testing/verify-release-signer.sh` is what turns "signed with the release key" from a description of
the step above into a checkable claim. It asserts that every certificate `apksigner` reports for each
APK is `0c69794b…`, the one v1.1.20 through v1.4.0 carry, and that a v2 signature is present;
`tagged-release.yml` runs it over the five APKs before the release is created. When the secrets were
present a mismatch fails the release; when they were not, the same output is reported as a warning, so
a fork's deliberate debug-signed build is still allowed to be built while a published release says
which key it carries. Rotating the release key therefore means editing that constant in its own
commit, with the reinstall the rotation costs stated in the release notes. See AUDIT-REPORT.md §57.

## The CI window

Actions minutes are free on a public repository and billed on a private one. This repository is
private, so its runs are metered — and when the account's minutes run out, every job dies about four
seconds after it starts with no steps executed and an empty `runner_name`, which is
[worth recognising on sight](#reading-the-signature-check) because it looks exactly like a real red
build. The way out is to run the build while the repository is public. That is the CI window:
`scripts/ci-window.sh` opens it, and three separate things are able to close it.

**The first flip cannot be a job.** A GitHub-hosted runner is precisely what the account cannot
afford, so no workflow in this repository can run the command that would make the minutes free. That
one step has to come from outside GitHub, which is why the opener is a script and not a workflow.
Everything after it can be a job, and the closer is.

```sh
scripts/ci-window.sh status                       # visibility, and whether a window is open
scripts/ci-window.sh open --yes                   # release.yml on main, 120-minute deadline
scripts/ci-window.sh open --workflow ci.yml --ref some-branch --minutes 60 --yes
scripts/ci-window.sh open --dry-run               # dispatch and wait, flip nothing
scripts/ci-window.sh close                        # close it now
```

**The three closers, and what each one covers.** The opener installs its close as an `EXIT` trap, so
an interrupt or a failure in the script does not leave the door open. That covers the script; it does
not cover the machine the script runs on. `close-ci-window.yml` covers a run that outlives its opener
— it is a job in `ci.yml` and `release.yml`, guarded so that it is `skipped` and costs nothing on
every ordinary run, and it closes the window in seconds rather than at the next tick. `scripts/ci-window-watchdog.sh`
covers the rest: the opener died before it dispatched anything, so there is no run to close the
window and no trap left to fire. It runs from cron, every five minutes, and it acts only when the
repository is public *and* carries a deadline that has passed. A repository someone made public by
hand has no deadline, and the watchdog leaves it alone — that is a decision, and the script does not
overrule decisions.

**The closer's guard reads the trigger event, not the repository.** The job is conditioned on
`github.event.repository.private == false`, and that is the visibility recorded in the event payload
when the run was created — not a live read at job time. Two consequences, both observed. A run
created while the repository was private keeps `private: true` in its payload after a window opens,
so its closer is `skipped` even though the repository is public by then: `release.yml` run
`35699061371`, a re-run of a push from 07:19:53Z, reported `Close the CI window / Close the CI
window: completed/skipped` while the window opened at 07:25:48Z was still open. And a window that
spans more than one run is closed by whichever of them settles first — which is why the opener's trap
and the deadline exist as well, and why a release, which is a branch push, a merge and a tag, is
opened by hand with a long deadline and closed with `close` rather than by `open`'s dispatch-and-wait.
A job that has already started keeps its runner when the window closes underneath it; what a close
does affect is any job that had not started yet, which is billed from that moment on.

**The deadline is the honest part of the design.** The opener records `CI_WINDOW_DEADLINE` as a
repository variable *before* it flips anything, so a crash between the two leaves a public repository
that the watchdog knows to close, rather than a public repository nobody has a record of. The
watchdog closes and *then* deletes the marker, so a failed flip leaves it in place to retry against.
And the watchdog never clears a deadline it sees while the repository is private: the opener records
the deadline and only then flips, so clearing it in the gap between those two calls would strip the
backstop off a window that was about to open, and that race is real at a five-minute interval.

**Opening a window is a one-way decision, and the script makes you say so.** Making the repository
public publishes its entire history — every commit, branch and tag — to everyone, permanently. Forks
and third-party archives copy it within minutes and flipping back to private does not recall those
copies. So `open` prints that and exits 2 without `--yes`. The audit run on 2026-09-22 found no
credential anywhere in the history: the one `ghp_` occurrence is the literal placeholder
`ghp_notarealtokenvalue` in a test, every `storePassword=` is the workflow's own `sed` or a `printf`
format string, every `PRIVATE KEY` hit is a PEM header constant or an assertion about one, no
keystore, `.jks` or `keystore.properties` has ever been tracked, no workflow echoes a secret into a
log, there is no `pull_request_target` or `workflow_run` anywhere, and no artifact has ever been named
for a key or a signature. What a window exposes is therefore the source code — which is the point of
running a build in public — and nothing else.

**Two things need the maintainer, not the repository.** The closer and the watchdog both need a token
that can administer the repository; `GITHUB_TOKEN` cannot, at any permission level, because
repository visibility is an administrative operation and the default token has no `administration`
permission to grant. The scripts read the token from `~/.netrc` by the same rule as everything else
here, and the workflow reads it from the `REPO_ADMIN_TOKEN` secret — which is optional by design: the
job it feeds is skipped in every ordinary run, so a missing secret can never turn a green build red,
while a *public* repository with no secret configured fails loudly instead of leaving the door open.
The watchdog is installed once:

```sh
scripts/ci-window-watchdog.sh --install-cron
```

**A window also decides whether `main` is protected at all.** Branch protection is a paid feature on a
private repository for this account and a free one on a public repository, so the rule is possible
only while a window is open — and a close does not suspend it, it deletes it. That was measured in
that order: a pull request carrying a red check read `mergeStateStatus: BLOCKED` while the window was
open, read `UNSTABLE` with the same red check still present minutes after `close`, and a later window
found the endpoint answering `404 Branch not protected`. So a window opened without re-applying the
rule leaves `main` guarded by nothing for its own duration as well as after it — and the whole point
of running a release this way is that the merge and the tag happen inside the window. The `PUT` that
re-applies it is on [Branch protection](branch-protection.md); it is by hand today, and folding it
into `open` is the open item that page names.

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
artifact, under `if: always()` — so a red suite still hands over its HTML and XML. The one other
job that uploads any of them is `stress`, under its own `stress-reports` name and only on a
dispatch that asked for it. `lint` uploads none of them: its only record is the log line that says
the HTML and SARIF reports were written, so a lint count read off a run's artifact is not
available.
The emulator jobs cannot be reproduced this way — they need a booted device, which is what the five
workflows above that boot one exist to provide. `focused-test.yml` is not among them: it runs a JVM
task against a `--tests` filter and has no emulator in it.
