# EclipseSSH

An SSH and SFTP client for Android: a full-screen VT/ANSI terminal, multiple concurrent sessions in
tabs, a two-pane SFTP browser with resumable transfers, and credentials kept in an Android
Keystore-backed vault — with VNC and RDP viewers, a real Ubuntu userland running on the device under
proot, and a text editor and an archive browser beside them.

Package `dev.eclipse.ssh` · `minSdk 28` (Android 9) · `compileSdk 37`, `targetSdk 35` · Kotlin 2.4.20 ·
Jetpack Compose (Material 3) · Hilt · Room · WorkManager · Apache MINA SSHD 2.19.0.

## What it does

**Connections.** Hostname or IP (IPv4, IPv6 literal, or name), port, username, password or private
key, passphrase-protected keys, per-host connect and auth timeouts, keep-alive interval, HTTP CONNECT
and SOCKS5 proxies, a jump host, strict or prompt-on-first-contact host-key verification, and legacy
algorithm compatibility — an app-wide switch, off by default, that a host can override in either
direction. Hosts can be grouped, tagged, favourited, searched, duplicated, and imported from
`~/.ssh/config`.

**Terminal.** Full-screen after login, VT/ANSI parsing with scrollback, selection and copy, an on-screen
key row (arrows, Ctrl, Tab, Esc, function keys) that follows the mode the remote program set, font size
and theme, snippets, and a session list in the Terminal tab for switching between open sessions. The
grid is centred in its window and framed by a margin of one percent of the screen, so the text fills
the display on a phone and a tablet alike.
Reconnect uses bounded exponential backoff; a foreground service keeps sessions alive in the
background.

**SFTP.** Browse local (via the Storage Access Framework) and remote side by side; upload, download,
pause, resume, cancel; scheduled and repeating transfers through WorkManager with progress
notifications; sync in either direction; server-to-server send; rename, delete, copy, move, mkdir,
chmod, properties; batch selection. An optional per-host **Auto Login SFTP** opens the browser as soon
as the session is ready.

**Remote desktop.** A host can carry a VNC and an RDP endpoint beside its shell (`V:5900`, `R:3389`),
and either opens in its own full-screen window riding the session's tunnel — so a machine that speaks
RFB or RDP is driven from the app that already holds its credentials.

**On-device Linux.** A real Ubuntu userland — bash, apt, git, curl, wget, sudo and an SSH client
— installed into the app's own sandbox and run under proot: no VM, no root, no ISO, and a local
shell that is the same terminal channel an SSH one is. Anything past that base (Python, Node.js,
an editor, a compiler) is one `apt-get install` away inside the terminal. Architecture and
operating manual: [`docs/linux-userspace.md`](docs/linux-userspace.md).

**Files.** A text editor for the files the app browses — opened from the explorer, a preview sheet or
the New File dialog — in its own opaque window rather than a panel over the workspace. An archive
browser opens an archive before extracting anything from it: entries are listed, previewed and
inspected, password-protected archives are unlocked on demand, and the extraction writes the entries
you pick to a Storage Access Framework destination.

**Keys and secrets.** Generate RSA (2048/4096) or ECDSA P-256 keys on device, import and export
them — Ed25519 keys generated elsewhere are read too — manage
known-hosts entries, lock the app with a PIN or biometrics, toggle `FLAG_SECURE`, and let a copied
password clear itself from the clipboard — a wipe that survives the process being killed. Passwords,
passphrases and private keys are encrypted with a hardware-backed key and are kept out of logs,
exceptions, vault backups and `adb backup`. A single-account export is the one deliberate exception:
it may carry the account's saved password and key passphrase inside its own passphrase-encrypted
envelope, so importing it on another device connects without re-entering them; private keys never
travel in an export.

**Extras.** Local, remote and dynamic (SOCKS) port forwarding; server stats; a quick-connect home-screen
widget and a Quick Settings tile; vault export/import.

**Settings, one window per subject.** Every Settings entry opens an Activity of its own rather than
a dialog over the list — key generation, PIN lock, auto-lock, known hosts, saved credentials,
keep-alive, clipboard auto-clear, font size, the shortcut bar, terminal width, the encrypted backup
export, reconnect delay, connection diagnostics, the Ubuntu userspace, and About — and Add Host /
Edit host opens the same kind of window. Each one edits a store the Settings list already reads, so
a change is visible when the list resumes without a result code being handed back. The one
deliberate exception is **No active forwards**, which stays a live status row carrying the running
forwards and their Stop buttons: a list of what is running right now is a status, not a screen.

The shared shell is `ui/settings/SettingsScaffold.kt` and the rows every screen draws are
`ui/settings/SettingsComponents.kt`. The five screens that are just a list of choices — keep-alive,
clipboard auto-clear, reconnect delay, auto-lock and terminal width — are five Activities inside
`ui/settings/SettingsDestinations.kt`, next to the base classes all of them extend. The ten remaining
screens have a file each beside it: the nine heavier ones (key generation, PIN lock, known hosts,
saved credentials, shortcut bar, font size, backup export, diagnostics, Ubuntu) and the host form.
About keeps its own home in `ui/about/`.

## Build

The toolchain is not vendored. You need JDK 17 and an Android SDK with build-tools 36.0.0; point
`local.properties` at the SDK (`sdk.dir=/path/to/android-sdk`) or set `ANDROID_HOME`.

```bash
./gradlew assembleDebug            # debug APK
./gradlew testDebugUnitTest        # JVM + Robolectric suite
./gradlew lintRelease              # Android lint
./gradlew assembleRelease          # release APK
```

`app/build/outputs/apk/release/app-universal-release.apk` is the release artifact, built alongside the
four per-ABI splits (`app-arm64-v8a-release.apk`, `app-armeabi-v7a-release.apk`, `app-x86-release.apk`,
`app-x86_64-release.apk`) that `splits.abi` produces. Prebuilt APKs are attached to the
[releases](../../releases) rather than committed.

### Signing

`keystore.properties` and the keystore itself are git-ignored and are **not** in this repository. Create
`keystore.properties` in the project root to sign a real release:

```properties
storeFile=keystore/your-release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

Without that file `assembleRelease` still succeeds — it falls back to the debug key and prints a loud
warning — but the result is not publishable and cannot upgrade an existing install. Release builds are
signed with APK signature schemes v2 and v3 (v3 carries the rotation proof; v1 is off because `minSdk`
is 28).

## Tests

The suite is 1,748 JVM/Robolectric test methods in 152 test files and contacts nothing off the
machine: the SSH and SFTP integration tests start a real Apache MINA SSHD server on a loopback port
inside the test JVM, and a second class dials a real OpenSSH `sshd` that the `test` job starts on
loopback first (`tools/local-sshd.sh`, because interop bugs live in the gap an in-JVM server cannot
reproduce). No public or shared SSH account is involved either way. That figure is the
count of `@Test` methods in `app/src/test` — a few of them sit behind `assumeTrue` and report as
skipped wherever their precondition cannot hold, which is why a green CI run reports a slightly
smaller number than this. A file count is not a class count: one Kotlin file may declare several test
classes, and `ChoiceActivitiesRobolectricTest.kt` declares six test classes, so a run executes more
classes than there are files here. `scripts/check-doc-figures.sh` recounts both, so neither can drift
silently again: it read 1,731 across 151 until that check existed, because one test file carried a
literal NUL byte inside a string and every `grep`-based count of the suite stopped counting that
file's methods.

`app/src/test/resources/keys/` holds throwaway Ed25519 fixtures (`plain_ed25519`,
`encrypted_ed25519`) used only to exercise key parsing against that in-process server. They are test
data, they authorise nothing anywhere, and they must never be reused as real credentials.

`app/src/androidTest/` holds the instrumentation tests — 47 test methods in 7 files — which
need a device or emulator.

## Continuous integration

Ten GitHub Actions workflows in all; [`docs/ci.md`](docs/ci.md) holds the full table. Three of them
are what a release passes through:

- [`.github/workflows/ci.yml`](.github/workflows/ci.yml) — the gate that runs on every push to
  `main` and every pull request. Seven jobs, so a failure lands on the thing that is actually broken:
  a documentation figure check, both native modules, release lint, the unit and integration suite
  (the debug variant only, since AGP 9 removed `testReleaseUnitTest`), the instrumentation and AAB
  builds with their signature checks, and an emulator crash-on-open smoke — with the idle stress
  matrix as a seventh job that runs only when a `workflow_dispatch` sets its `stress` input. A
  push-triggered idle test would burn the runner's metered time for a regression the rest of the
  suite cannot see anyway, and a 30-minute idle test behind every on-demand run would put a
  75-minute tail on checks that otherwise answer in 35. The
  debug and release APKs are uploaded as build artifacts, and the test reports are uploaded even when
  a step fails.
- [`.github/workflows/release.yml`](.github/workflows/release.yml) — the release build, run on
  demand or on every push to `main`. `bundleRelease` first and then `assembleRelease` (that order,
  because per-ABI splits left behind by the first crash a later bundle), the APK signature and the
  AAB signature each verified — the AAB is not an APK and `apksigner` cannot read it as one — and the
  APKs, the AAB and their SHA-256 sums uploaded as a single artifact.
- [`.github/workflows/tagged-release.yml`](.github/workflows/tagged-release.yml) — the
  publication path. A `git tag vX.Y.Z && git push --tags` triggers a clean `assembleRelease`
  from the tag, asserts the source matches the tag (`git describe --exact-match`), verifies the
  signature, computes checksums, and creates a GitHub release with every APK, the AAB and the
  `SHA256SUMS.txt` attached — as a **draft**, so publishing stays a deliberate second step.

Two more are worth knowing from here: `instrumentation.yml` runs `connectedAndroidTest` on a hosted
runner that boots its own headless AVD, so the emulator suite is executed rather than only compiled;
and `fast-test.yml` is the lint-and-suite gate a work-in-progress branch uses without pushing to
`main` first.

Signing in CI is optional. Set the repository secrets `RELEASE_KEYSTORE_BASE64` and
`RELEASE_KEYSTORE_PROPERTIES` to sign for real; without them the release APK is built with the
debug key and says so. [`docs/ci.md`](docs/ci.md) covers the workflows step by step, including how
to read the signature output.

## Report

[`AUDIT-REPORT.md`](AUDIT-REPORT.md) is the full audit: the root cause of each bug fixed, the security
review, the deliberate behaviour changes, the edge cases exercised, and the build/test results.
