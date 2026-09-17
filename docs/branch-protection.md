# Branch protection

The repository is protected so a merge to `main` cannot happen without
a green CI run and a code-owner approval. Settings are at
`github.com/<owner>/EclipseSSH/settings/branches`; the rule below is
copy-pasteable.

## Rule for `main`

| Setting | Value |
| --- | --- |
| Branch name pattern | `main` |
| Require a pull request before merging | yes |
| Required approvals | 1 |
| Dismiss stale pull request approvals when new commits are pushed | yes |
| Require review from Code Owners | yes |
| Require status checks to pass before merging | yes |
| Require branches to be up to date before merging | yes |
| Require linear history | yes |
| Do not allow force pushes | yes |
| Do not allow deletions | yes |
| Include administrators | yes |

### Required status checks

`ci.yml` and `instrumentation.yml` both run on every pull request and every
push to `main`. Their check names are the job `name:` values, which are
deliberately not the job keys — add these *names*, not the keys.

| Workflow | Check name (job key) | Covers |
| --- | --- | --- |
| `CI` | `Documentation figures` (`docs`) | `scripts/check-doc-figures.sh` — the figures the documents state against the files that pin them, the repository paths they name, and the check names in this table |
| `CI` | `FreeRDP native (4 ABIs)` (`native`) | The four-ABI native build that every other job restores from cache |
| `CI` | `Lint (release variant)` (`lint`) | `lintRelease` |
| `CI` | `Unit and integration tests` (`test`) | The JVM/Robolectric suite — debug variant only, because AGP 9 removed `testReleaseUnitTest` |
| `CI` | `APKs, AAB and signatures` (`assemble`) | Debug and release APKs, the AAB, and the signature checks |
| `CI` | `Boot the built APKs (crash-on-open gate)` (`smoke`) | Installs and launches the built APK on an emulator |
| `Instrumentation` | `connectedAndroidTest` (`instrumentation`) | The instrumented suite, on a hosted runner that boots its own headless AVD |

The six `CI` rows are the set `ci.yml` itself names in its job-layout comment.
The `Instrumentation` row is the one that has to be added deliberately, because
it is the most expensive check in the repository. It is a genuine candidate
rather than a formality: `instrumentation.yml` `runs-on: ubuntu-24.04` on the
hosted pool and boots its own `system-images;android-35;default;x86_64` AVD, so
it executes the suite instead of skipping it — it can fail, and it can therefore
gate. (Until the workflow was rewritten it declared a
`[self-hosted, android-emulator]` runner and was silently absent whenever no such
runner was online; that model is gone.)

`CI`'s `Long idle stress test` (`stress`) is **not** in the list: it is a single
90-minute job against a real OpenSSH server, meant to be watched rather than to
block a merge. It is also the one `ci.yml` job a plain `workflow_dispatch` does
not start — it needs the dispatch's `stress` input set, because a job that holds
a connection open for 30 minutes should not be the tail of every on-demand run.

The `Release build` workflow is **not** a required check for `main`, because it
is gated by the `release` environment (see below) and should not block a merge.
The `Tagged release` workflow only runs on tag pushes and is not on the `main`
path.

## Environment: `release`

`Release build` is pinned to an environment called `release`. To require a human
click before a release artifact is built and uploaded:

1. Settings -> Environments -> New environment -> name `release`.
2. "Required reviewers" -> add the maintainers who can green-light a
   release. One is enough for a single-maintainer repo.
3. "Wait timer" -> 0 minutes (the maintainer approves when ready).

**Open item.** `Tagged release` is *not* pinned to that environment — it is the
only other workflow that touches the signing secrets, and it publishes a GitHub
release from a tag without an approval step. Adding `environment: release` to its
build job would close that, at the cost of making `git push --tags` wait for a
click.

## Secrets

The two release workflows read these secrets:

| Secret | Purpose |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | `base64 -w0 keystore/eclipse-release.jks` — the upload key, no newlines |
| `RELEASE_KEYSTORE_PROPERTIES` | The `keystore.properties` file contents, with a trailing newline |

Both are written to `keystore.properties` and `keystore/eclipse-release.jks`
inside the runner and `chmod 600`. A step named "Shred signing material"
(`if: always()`, so it runs on failure too) then removes both — `rm -f
keystore.properties` and `rm -rf keystore`. It is `rm`, not `shred(1)`: the
material lives on an ephemeral hosted runner's disk, which is discarded with the
VM, and neither workflow caches the workspace.

Rotate by:

1. Replace the local keystore.
2. `base64 -w0 keystore/eclipse-release.jks > new-jks.b64`.
3. Settings -> Secrets and variables -> Actions -> Update
   `RELEASE_KEYSTORE_BASE64` to the new value.
4. Update `RELEASE_KEYSTORE_PROPERTIES` with the new
   `storePassword`, `keyAlias`, `keyPassword`.

The first release after a rotation must be built twice: once to
publish the v2+v3 signed APK (existing installs upgrade because
v3 carries a rotation proof), and once after a delay if the Google
Play key is in use (Play requires a separate API call to register
the new key).

## Where the emulator comes from

There is no self-hosted runner and no `android-emulator` label to provision.
Every workflow that needs a device boots an ephemeral emulator on the hosted
pool and keeps its own pins in the workflow itself, so the result does not
depend on what the runner image happens to ship that week. The four that boot
one for a matrix or the instrumented suite — `instrumentation.yml`,
`android-release-test.yml`, `universal-apk-test.yml`, `android-ubuntu-e2e.yml`
— declare them in an `env:` block (`BUILD_TOOLS`, `COMPILE_SDK`, `SYSTEM_IMAGE`,
`FREERDP_NDK_VERSION`, `FREERDP_CMAKE_VERSION`). `ci.yml`'s `smoke` job pins
only what it uses, inline: `cmdline-tools-version` and the
`system-images;android-35;default;x86_64` package it boots. That is why a
device-level check can be a required status check at all: it cannot be
"silently absent", only red, green, or queued.
