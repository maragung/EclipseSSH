# EclipseSSH

An SSH and SFTP client for Android: a full-screen VT/ANSI terminal, multiple concurrent sessions in
tabs, a two-pane SFTP browser with resumable transfers, and credentials kept in an Android
Keystore-backed vault.

Package `dev.eclipse.ssh` · `minSdk 28` (Android 9) · `compileSdk`/`targetSdk 35` · Kotlin 2.1.20 ·
Jetpack Compose (Material 3) · Hilt · Room · WorkManager · Apache MINA SSHD 2.14.0.

## What it does

**Connections.** Hostname or IP (IPv4, IPv6 literal, or name), port, username, password or private
key, passphrase-protected keys, per-host connect/KEX/auth timeouts, keep-alive interval, HTTP CONNECT
and SOCKS5 proxies, a jump host, strict or prompt-on-first-contact host-key verification, and legacy
algorithm compatibility as an explicit per-host opt-in rather than a global default. Hosts can be
grouped, tagged, favourited, searched, duplicated, and imported from `~/.ssh/config`.

**Terminal.** Full-screen after login, VT/ANSI parsing with scrollback, selection and copy, an on-screen
key row (arrows, Ctrl, Tab, Esc, function keys) that follows the mode the remote program set, font size
and theme, snippets, and a session list in the Terminal tab for switching between open sessions.
Reconnect uses bounded exponential backoff; a foreground service keeps sessions alive in the
background.

**SFTP.** Browse local (via the Storage Access Framework) and remote side by side; upload, download,
pause, resume, cancel; scheduled and repeating transfers through WorkManager with progress
notifications; sync in either direction; server-to-server send; rename, delete, copy, move, mkdir,
chmod, properties; batch selection. An optional per-host **Auto Login SFTP** opens the browser as soon
as the session is ready.

**Keys and secrets.** Generate RSA (2048/4096) or ECDSA P-256 keys on device, import and export
them — Ed25519 keys generated elsewhere are read too — manage
known-hosts entries, lock the app with a PIN or biometrics, toggle `FLAG_SECURE`, and let a copied
password clear itself from the clipboard — a wipe that survives the process being killed. Passwords,
passphrases and private keys are encrypted with a hardware-backed key and are kept out of logs,
exceptions, backups and `adb backup`.

**Extras.** Local, remote and dynamic (SOCKS) port forwarding; server stats; a quick-connect home-screen
widget; vault export/import.

## Build

The toolchain is not vendored. You need JDK 17 and an Android SDK with build-tools 35.0.0; point
`local.properties` at the SDK (`sdk.dir=/path/to/android-sdk`) or set `ANDROID_HOME`.

```bash
./gradlew assembleDebug            # debug APK
./gradlew testDebugUnitTest        # JVM + Robolectric suite
./gradlew lintRelease              # Android lint
./gradlew assembleRelease          # release APK
```

`app/build/outputs/apk/release/app-release.apk` is the release artifact. Prebuilt APKs are attached to
the [releases](../../releases) rather than committed.

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

The suite is 603 JVM/Robolectric tests and runs offline: the SSH and SFTP integration tests start a
real Apache MINA SSHD server on a loopback port inside the test JVM, so nothing external is contacted
and no public or shared SSH account is involved.

`app/src/test/resources/keys/` holds throwaway Ed25519 fixtures (`plain_ed25519`,
`encrypted_ed25519`) used only to exercise key parsing against that in-process server. They are test
data, they authorise nothing anywhere, and they must never be reused as real credentials.

`app/src/androidTest/` holds instrumentation tests, which need a device or emulator.

## Continuous integration

Every push and pull request runs [`.github/workflows/ci.yml`](.github/workflows/ci.yml): release lint,
both unit-test variants, a compile of the instrumentation sources, both APKs, and a signature check.
The debug and release APKs are uploaded as build artifacts, and the test and lint reports are uploaded
even when a step fails, because that is what a red run is diagnosed from.

Signing in CI is optional. Set the repository secrets `RELEASE_KEYSTORE_BASE64` and
`RELEASE_KEYSTORE_PROPERTIES` to sign for real; without them the release APK is built with the debug
key and says so. [`docs/ci.md`](docs/ci.md) covers the workflow step by step, including why the
emulator suite is compiled but not executed and how to read the signature output.

## Report

[`AUDIT-REPORT.md`](AUDIT-REPORT.md) is the full audit: the root cause of each bug fixed, the security
review, the deliberate behaviour changes, the edge cases exercised, and the build/test results.
