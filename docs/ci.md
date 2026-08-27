# Continuous integration

Three workflows, by purpose:

| Workflow | Trigger | Job |
| --- | --- | --- |
| `ci.yml` | Every push to `main`, every pull request, `workflow_dispatch` | Lint, both unit-test variants, instrumentation compile, both APKs, signature check, artifacts |
| `release.yml` | Push to `main`, `workflow_dispatch` | `assembleRelease` + signature check, uploads the APKs as a downloadable artifact |
| `tagged-release.yml` | A `vX.Y.Z` (or `vX.Y.Z-…`) tag | The same as `release.yml`, then creates a GitHub release, attaches every APK and the checksums file, and writes release notes drawn from `AUDIT-REPORT.md` |

The CI gate (`ci.yml`) is the one that has to stay fast. The other two exist so a release can be
rebuilt at any time without rerunning lint and the full test suite, and so a `git tag` is the only
path that creates a published release.

## ci.yml

`.github/workflows/ci.yml` runs on every push to `main`, on every pull request, and on demand
(`workflow_dispatch`). One job, `verify`, does the whole chain in order, so a failure stops at the
first thing that is actually broken:

| Step | Command | What it protects |
| --- | --- | --- |
| Lint | `./gradlew lintRelease` | Release-variant Android lint, including the manifest and resource checks that only run for `release`. |
| Unit tests | `./gradlew testDebugUnitTest testReleaseUnitTest` | Both variants. `release` is not a duplicate: it compiles against the minified/shrunk resource set and different `BuildConfig`, and has caught variant-only breakage before. |
| Instrumentation compile | `./gradlew assembleDebugAndroidTest` | The `androidTest` sources cannot run here (see below) but they must still compile, or they rot silently. |
| Build | `./gradlew assembleDebug assembleRelease` | Both APKs, R8/resource shrinking included. |
| Signature | `apksigner verify --verbose` | That the release APK is signed. See the note below on reading its output. |
| Artifacts | `actions/upload-artifact` | `eclipse-ssh-debug-apk`, `eclipse-ssh-release-apk`, and `reports` (lint + test HTML, kept 14 days, uploaded even on failure). |

## release.yml

`.github/workflows/release.yml` runs on every push to `main` and on demand. It does
`assembleRelease`, verifies the APKs, computes a `SHA256SUMS.txt`, and uploads everything as the
`eclipse-ssh-release-apk` artifact. There is no test run and no OpenSSH sandbox - the CI gate
already covers that, and a release rebuild should not pay for it twice.

## tagged-release.yml

`.github/workflows/tagged-release.yml` is what the brief calls the "tagged release" path. It runs
on any `v*` tag pushed to the repository (matching `v[0-9]+.[0-9]+.[0-9]+`, optionally with a
`-…` pre-release suffix). It does `clean assembleRelease` from the tag exactly
(`git describe --exact-match` is asserted), verifies the signature, computes checksums, and uses
`softprops/action-gh-release` to create a GitHub release. The release title and the
`versionName`/`versionCode` it reports are read out of the built APK with `aapt2 dump badging` so
the published metadata cannot drift from the artifact.

The release is created as a **draft** when the tag carries a pre-release suffix (e.g. `v1.2.0-rc.1`),
and as a normal release otherwise. Release notes are composed from the latest section heading of
`AUDIT-REPORT.md`; if that file is missing or the section cannot be found the step fails rather
than producing a blank release.

Cutting a release:

```sh
git tag v1.2.3
git push origin v1.2.3
```

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
nothing. Both readings are why the workflow only asserts the v2 *and* v3 blocks when
`steps.signing.outputs.signed` is `true`: with a real key their absence is a defect worth failing on,
and without one it is expected.

## Why there is no emulator step

`connectedAndroidTest` needs a device. GitHub's `ubuntu-latest` runners are nested VMs without KVM,
so an x86 system image runs under full software emulation; it boots, then the guest's watchdog kills
`system_server` before the test run starts. Reactor-style AVD actions work around this on runners
that do expose KVM, which these do not. The instrumentation suite is therefore compiled in CI and run
on a real device, and everything that *can* be expressed as a JVM test is: `NavigationRobolectricTest`
drives the real `MainActivity` through Robolectric at SDK 35, and `SessionStabilityTest` runs a real
Apache MINA SSHD server in-process and asserts on kernel socket options.

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
./gradlew lintRelease testDebugUnitTest testReleaseUnitTest assembleDebugAndroidTest \
          assembleDebug assembleRelease
```

Reports land in `app/build/reports/`; that whole directory is what the `reports` artifact contains.
