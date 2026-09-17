# Branch protection

**In force since 2026-09-17.** `GET
/repos/maragung/EclipseSSH/branches/main/protection` answers with the rule below
as stored, and that read-back is the evidence — a `PUT` returning `200` is a
claim, not a state. Until that date the same endpoint answered `Branch not
protected`, and the six pull requests before it — every one authored and merged
by the same account — are what that looked like in practice: the discipline was
the author's, not GitHub's. Settings are at
`github.com/maragung/EclipseSSH/settings/branches`.

## Rule for `main`

| Setting | Value |
| --- | --- |
| Branch name pattern | `main` |
| Require a pull request before merging | yes |
| Required approvals | 0 — see below |
| Dismiss stale pull request approvals when new commits are pushed | yes |
| Require review from Code Owners | no — see below |
| Require status checks to pass before merging | yes |
| Require branches to be up to date before merging | yes |
| Require linear history | no — see below |
| Do not allow force pushes | yes |
| Do not allow deletions | yes |
| Include administrators | yes |

### The three rows that are not what a template would say

Each of these was `yes` (or `1`) when this document was a recipe, and each is
now written as it is actually stored, because a recipe mistaken for a safeguard
is worse than no recipe — which is the defect this file carried until
2026-09-17. All three were found by reading the repository rather than the
template, and none of them was left unset by accident.

| Row as a template has it | Stored as | Why |
| --- | --- | --- |
| Required approvals `1` | `0` | `maragung` is the only collaborator, and it authors every pull request. GitHub does not let an author approve their own pull request, so one required approval on a one-account repository makes `main` permanently unmergeable — it would have blocked the very pull request that applied this rule. The requirement that does the work here is the status checks, and `enforce_admins: true` is what makes them bind the administrator too. Raise this to `1` in the same breath as adding a second collaborator who can review. |
| Require review from Code Owners `yes` | `no` | `.github/CODEOWNERS` exists and names `@maragung` on every pattern. Requiring code-owner review is the row above with a stricter resolver, so on one account it is the same deadlock and not a second safeguard. |
| Require linear history `yes` | `no` | `main` carries 85 merge commits and its convention is `Merge pull request #NN from …`. Linear history forbids merge commits outright, so the setting does not distinguish a good merge from a bad one — it forbids the repository's own history, and any release merged the way every release has been merged. If the convention ever changes to squash, this row follows it; the convention does not follow this row. |

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
