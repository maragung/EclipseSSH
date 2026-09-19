# Third-party notices

EclipseSSH's own code is Apache-2.0. The items below ship in its release
artifacts or build outputs and carry their own terms.

One of them carries obligations beyond attribution: proot is GPL-2.0, and its
section below is the one that has to be read rather than skimmed. Everything
else here is notice.

## Proot (GPL-2.0) and talloc (LGPL-3.0) — the Linux userspace

The `:linux` module cross-compiles [proot][proot] (GPL-2.0-or-later) and its
dependency [talloc][talloc] (LGPL-3.0) from source. Only proot ships as an
executable: the release APK carries `libproot.so` and `libproot-loader.so` in
`nativeLibraryDir` (executables renamed into the `lib*.so` namespace only so
Android's packaging puts them on disk). talloc is compiled to `libtalloc.a` and
linked statically into proot, so it has no binary of its own in the APK — the
module links proot with `-static`, which is why `libproot.so` carries no
`DT_NEEDED` entries at all.

The build uses [oonid/pr][oonid-pr], a fork of proot v5.4.0 with Android
targetSdk 29+ fixes, pinned to an exact commit in `linux/build.gradle.kts`:

- proot fork source: `https://codeload.github.com/oonid/pr/tar.gz/<commit>`
  (commit `754583c96e686a07be47bd02a8ba4fdfb2d7069f`, SHA256-verified by the
  build; the fork's patches are derived from upstream
  `https://github.com/proot-me/proot`, GPL-2.0)
- talloc source: `https://www.samba.org/ftp/talloc/talloc-2.4.2.tar.gz`
  (LGPL-3.0, SHA256-verified by the build)

**Corresponding source**: the two tarballs above, at the versions and hashes
pinned in `linux/build.gradle.kts`, **together with the four patches in
`linux/proot-patches/`**. The tarballs are inputs, not the whole source:
`fetchLinuxSource` extracts the fork, applies `0001`–`0004` in filename order,
and only then does the module compile — so `libproot.so` is built from the fork
*as patched*, and for a GPL-2.0 binary those patches are part of the
corresponding source. Checking out this repository and running the `:linux`
build downloads the fork, hash-verifies it, applies those four patches and
compiles exactly those bytes. (The build records what it applied in a
`.patches-applied` fingerprint over every patch name and hash, so a tree that
was built from different patches cannot pass as this one.)

**Why the app's license is unaffected**: neither proot nor talloc is linked into
EclipseSSH. proot is a standalone executable, and talloc is statically linked
into *it* rather than into the app — which keeps the LGPL code inside the GPL-2.0
program it was already part of, and out of EclipseSSH's own binary. The app
starts that executable as a separate process (fork + execve via the
`liblinuxpty.so` PTY bridge) and communicates over a pseudo-terminal; no GPL or
LGPL code is linked into, derived from, or combined with the app's code at build
or run time. This is arm's-length aggregation, same as running a GPL program on
the same machine.

## FreeRDP (Apache-2.0) — the `:freerdp` module

Compiled from the pinned upstream tarball recorded in
`freerdp/build.gradle.kts`. Apache-2.0 is compatible with the app's own
license; no additional obligation beyond notice, which this section provides.

The module ships eight `.so` files per ABI: `libfreerdp-android.so` (the JNI
bridge) plus the seven in the build's runtime list — `libfreerdp3`,
`libfreerdp-client3`, `libwinpr3`, `libcrypto`, `libssl`, `libcjson`,
`liburiparser`. `:app` depends on the module, so all of them are in the APKs.
Three further libraries ship as `.so` files of their own rather than being
compiled into the FreeRDP ones — they are the last four of the eight above, not
a fifth set alongside them — and their terms therefore travel with the APKs:

- **OpenSSL 4.0.1** — Apache-2.0 — TLS and NLA for the RDP client
  (`libcrypto`, `libssl`)
- **cJSON 1.7.19** — MIT — JSON handling inside the client (`libcjson`)
- **uriparser 1.0.2** — BSD-3-Clause — URI handling inside the client
  (`liburiparser`)

Versions are the FreeRDP tarball's own pins (`cmake/DepVersions.cmake`). That
file names further dependencies — WebP, PNG, JPEG, Opus, OpenH264, FFmpeg —
none of which is in this module's runtime library list, so none of them ships.

**Modified file.** `freerdp/src/main/java/com/freerdp/freerdpcore/services/LibFreeRDP.java`
is a copy of FreeRDP's MPL-2.0 JNI wrapper, adapted for this app: its header
says so ("Adapted for EclipseSSH from FreeRDP 3.31.1"), and the per-instance
listener registry replaces upstream's global application state. MPL-2.0
requires notice that a covered file has been modified, and this paragraph is
that notice. The file keeps its package name because the JNI symbols are
mangled from it.

## What ships inside the APK

The release APK's runtime classpath, by license:

- **Apache-2.0** — Apache MINA SSHD 2.19.0 (`sshd-core`, `sshd-common`,
  `sshd-sftp`), Apache Commons Compress 1.28.0 (tar and zip reading, and the
  Linux workspace snapshot), Kotlin and Kotlin Coroutines, Hilt/Dagger, and
  the AndroidX family the UI is built from: Compose and Material 3, Activity,
  Lifecycle, Navigation, Room, DataStore, WorkManager, Biometric and
  `core-splashscreen`.
- **Bouncy Castle Licence** — `bcprov-jdk18on` 1.86.
- **MIT** — [vernacular-vnc][vernacular-vnc] (commit `f39cbe2`, the VNC
  engine) and SLF4J 2.0.19.
- **CC0-1.0 (public domain)** — `net.i2p.crypto:eddsa` 0.3.0, Ed25519 key
  support.
- **Public domain** — `org.tukaani:xz` 1.12, the `.tar.xz` codec for the
  archive reader.

**Bundled font.** `app/src/main/res/font/jetbrains_mono_regular.ttf` and
`jetbrains_mono_bold.ttf` are [JetBrains Mono][jbmono], SIL Open Font
License 1.1 — the terminal's typeface, redistributed with the app.

The itemised list, with versions, is also in the app itself:
`app/src/main/java/dev/eclipse/ssh/feature/about/AboutLicenses.kt`, which the
About screen renders. `scripts/check-doc-figures.sh` compares that file's
versions against the pins in `gradle/libs.versions.toml` and the module build
files, so a dependency bump that misses it fails CI; this document's own list
is still kept in step by hand.

**Open item.** No `LICENSE` file is committed at the repository root — the
Apache-2.0 statement at the top of this document is the project's own
declaration rather than a file a distributor can point at. Add one before
shipping the app outside this repository.

[proot]: https://github.com/proot-me/proot
[talloc]: https://talloc.samba.org/
[oonid-pr]: https://github.com/oonid/pr
[vernacular-vnc]: https://github.com/maragung/vernacular-vnc
[jbmono]: https://github.com/JetBrains/JetBrainsMono
