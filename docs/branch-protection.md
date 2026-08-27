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

| Workflow | Job | Why |
| --- | --- | --- |
| `CI` | `verify` | Lint, both unit-test variants, instrumentation compile, both APKs, AAB, signature checks |
| `Instrumentation` | `instrumentation` | The connected-device suite. Skipped automatically when no `android-emulator`-labelled runner is online. |

The `Release build` workflow is **not** a required check for `main`,
because it is gated by the `release` environment (see below) and
should not block a merge. The `Tagged release` workflow only runs on
tag pushes and is not on the `main` path.

## Environment: `release`

The `Release build` and `Tagged release` workflows are pinned to an
environment called `release`. To require a human click before a
release artifact is built and uploaded:

1. Settings -> Environments -> New environment -> name `release`.
2. "Required reviewers" -> add the maintainers who can green-light a
   release. One is enough for a single-maintainer repo.
3. "Wait timer" -> 0 minutes (the maintainer approves when ready).

Without this environment, every push to `main` builds a signed APK
and uploads it as a workflow artifact, which is the behaviour the
brief calls out as the wrong default.

## Secrets

The `release` workflows read these secrets:

| Secret | Purpose |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | `base64 -w0 keystore/eclipse-release.jks` — the upload key, no newlines |
| `RELEASE_KEYSTORE_PROPERTIES` | The `keystore.properties` file contents, with a trailing newline |

Both are written to `keystore.properties` and `keystore/eclipse-release.jks`
inside the runner, `chmod 600`, and shredded on every exit path. A
`if: always()` step in each workflow runs the shred so a failed
release does not leave signing material in a cached workspace.

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

## Self-hosted runner for instrumentation

The `Instrumentation` workflow declares
`runs-on: [self-hosted, android-emulator]`. To make it run:

1. Provision a self-hosted runner with a working Android emulator
   (or attached device), install the Actions runner, and register it
   with the label `android-emulator`.
2. The workflow only runs where the label is present, so a public
   runner never picks it up. A workflow run on `main` while the
   runner is offline is silently absent from the status check.

The runner image is not pinned in this repository because the
operator owns the runner. The build itself pins the SDK and the
JDK so the result is independent of the host image.
