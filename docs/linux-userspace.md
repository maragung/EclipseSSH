# The Ubuntu Linux userspace

This document is the feature's architecture and operating manual: what runs where, where every
byte comes from, and why each layer is shaped the way it is. The native half has its own doc at
[`../linux/README.md`](../linux/README.md); third-party licensing is in
[`THIRD-PARTY.md`](THIRD-PARTY.md).

The feature in one sentence: a real Ubuntu userland — whichever LTS series you pick, 20.04 through
26.04 — bash, apt, git, curl, wget, sudo and an SSH client — running on the phone itself, inside
the app's sandbox, with no VM, no root and no ISO.

## Table of contents

1. [Architecture](#architecture)
2. [The rootfs source](#the-rootfs-source)
3. [The install process](#the-install-process)
4. [The runtime model](#the-runtime-model)
5. [ABI support](#abi-support)
6. [The filesystem contract](#the-filesystem-contract)
7. [Lifecycle](#lifecycle)
8. [Security](#security)
9. [Troubleshooting](#troubleshooting)

## Architecture

```
┌───────────────────────────────────────────────┐
│ UI: host card, terminal tab, Settings screen  │
│        (LocalLinuxHost — synthesized,          │
│         never persisted as a host)             │
├───────────────────────────────────────────────┤
│ MainViewModel connect() local branch          │
│   └─ LinuxProcessManager (session table)      │
│        └─ LocalTerminalChannel                │
│             = TerminalChannel, same contract  │
│               the SSH channel implements      │
├───────────────────────────────────────────────┤
│ LinuxUserspaceManager (state machine)         │
│   ├─ RootfsInstaller (download/verify/extract)│
│   ├─ UbuntuDistributionManager (setup/probe)  │
│   └─ LinuxWorkspaceManager (snapshot/restore) │
├───────────────────────────────────────────────┤
│ ProotRuntime (argv + environment recipe)      │
│   └─ PtySpawner ──► LinuxPty (JNI)            │
│                      └─ liblinuxpty.so        │
│                      └─ fork + execve         │
│                          libproot.so          │
│                          └─ proot translates  │
│                             paths & syscalls  │
│                          └─ Ubuntu binaries   │
│                             (mmap-exec from   │
│                              filesDir)        │
└───────────────────────────────────────────────┘
```

The seam that makes the whole feature cheap is `TerminalChannel`: the interface the SSH shell
channel already satisfied. A local shell is a channel whose far side is a pty master fd instead of
a socket, so the terminal tab, the output collector and the terminal buffer are shared code, not
parallel implementations — a local shell renders, scrolls, resizes and reports its ending through
the exact path a remote one does.

## The rootfs source

Every root filesystem on offer is an **Ubuntu Base** release, pinned per architecture to an exact
tarball plus SHA256 from the release's own `SHA256SUMS`
(`cdimage.ubuntu.com/ubuntu-base/releases/<series>/release`). The catalogue carries all four current
LTS series — 20.04, 22.04, 24.04 and 26.04, each with its own per-architecture tarball and hash — so
22.04.5 is the default starting point rather than the only choice. Ubuntu Base is the only officially
published Ubuntu rootfs that fits the constraints:

- ~30 MB compressed (the desktop image is ~4 GB),
- no installer, no systemd, no kernel expectation — everything a proot environment cannot provide
  is already absent,
- published by the distribution itself, not a third-party rebuild.

The pin is the supply-chain boundary: the installer verifies the download's SHA256 **before
extracting a single byte**, so the rootfs on the device is the rootfs this build was tested with.
A hash mismatch aborts the install with nothing extracted.

## The install process

`LinuxUserspaceManager.install()` runs the whole pipeline and only reports success after the final
health check passes — "installed" and "works" are the same fact:

1. **Download** — HTTPS, streamed to a `.part` file. The `.part` file is never a resume point: an
   interrupted download starts again from the first byte. What is reused is a tarball that already
   arrived *and* verified — reinstalling over a complete download does not fetch it again.
2. **Verify** — SHA256 against the pinned hash; a mismatch extracts nothing.
3. **Extract** — into `rootfs.staging/`, then renamed into place. A rootfs that exists is always a
   complete one; an interrupted install leaves no half-extracted tree under the real name. Every
   entry is resolved through a path-traversal guard (`resolveInsideRoot`) that refuses `../` and
   absolute paths; device nodes and fifos are skipped (proot binds the host's `/dev`).
4. **Set up the distribution** (`UbuntuDistributionManager.setup()`):
   - the app's uid registered as the `ubuntu` account in `/etc/passwd`, `/etc/group`, `/etc/shadow`
     — home `/home/ubuntu`, shell `/bin/bash`, password `*` (locked; the account is entered by
     process identity, never by password),
   - `/home/ubuntu/workspace` created,
   - `/etc/resolv.conf` replaced with a real file (the base image ships a symlink into
     `/run/systemd/resolve`, which does not exist under proot),
   - `/etc/apt/sources.list` pointed at the archive matching the architecture —
     `archive.ubuntu.com` carries amd64 only; every phone architecture needs
     `ports.ubuntu.com/ubuntu-ports`,
   - `apt-get update`, then the base packages through apt itself — `bash-completion`,
     `ca-certificates`, `curl`, `git`, `openssh-client`, `sudo`, `wget`. That list is the whole
     of what the install adds, and it is minimal by decision rather than by omission: Python,
     Node.js, an editor and a compiler are the user's own call, one `apt-get install` away inside
     the terminal, so the install stays small, fast and away from registries outside the pinned
     Ubuntu archive.
5. **Health check** — a shell runs and prints a marker, `whoami` answers `root`, DNS resolves,
   `apt-get check` passes. Only then does the state machine reach Stopped.
6. **Workspace restore** — if a previous keep-workspace uninstall parked a snapshot, it is
   restored into the fresh rootfs before the first shell opens.

## The runtime model

proot has no daemon. Every shell, every scripted command and every health probe is a fresh
`fork + execve` of `libproot.so` (from `nativeLibraryDir` — the only directory a targetSdk 29+ app
may `execve` from), which `mmap`s the Ubuntu binaries out of the rootfs under `filesDir` and
translates their path arguments and syscalls at the ptrace boundary. `PROOT_LOADER` must point at
the standalone `libproot-loader.so` in the same directory, or proot extracts an embedded loader
into `PROOT_TMP_DIR` (inside `filesDir`, never executable) and dies with EACCES.

**Every proot run carries `-0`, proot's fake root — interactive sessions and scripted commands
alike.** Inside the rootfs the process is uid 0; at the kernel level it is still the app's own uid,
so nothing escapes proot's sandbox or the app's SELinux domain. Fake root is not a privilege
escalation here — it is the only identity under which the userspace is usable at all:

- `dpkg` refuses to unpack anything unless `getuid() == 0`, so `apt install` cannot work without
  it. dpkg also `chown`s what it unpacks to `root:root`, and SELinux answers a real `chown` with
  ENOENT (a masqueraded EPERM) — which dpkg treats as fatal where it ignores EPERM. The proot fork
  answers `chown`/`lchown` with a faked `getuid` instead; the kernel-level owner stays the app uid
  either way, because nothing here can `chown` for real.
- `su` and `sudo` have nowhere to go from a non-root shell: proot's fake identity is all-or-nothing
  per process tree, so a session that is not fake root can never become root, and one that is,
  already is. `su`'s PAM stack (`pam_rootok`) lets an effective uid of 0 through without a
  password, so no password is ever asked for or needed — and the `setuid`/`setgid`/`setgroups`
  calls it makes are trapped by the zygote's seccomp filter and answered by the fork's SIGSYS
  handler.

What a session presents is therefore `root`: `whoami` says `root`, the prompt is `root@localhost`.
Two things deliberately do not move with it. `HOME` stays `/home/ubuntu` — the workspace, the
editor and SFTP all live there, and a shell that started in root's own home would be standing
nowhere near the user's files — and `/etc/passwd` keeps its `ubuntu` entry for the app's own
Android uid, which is who really owns every file in the rootfs and the account `su - ubuntu` drops
to. The `ubuntu` shadow entry stays `*` (locked): neither account is entered by password.

The history is worth keeping, because the shape of the mistake is instructive: sessions used to run
*without* `-0`, on the theory that the terminal account should never be root. That worked for
everything the app itself did and broke the moment a user typed `apt install zip` — "requested
operation requires superuser privilege" — or `su - root` — "System error". The pipeline's own
commands already carried `-0` and had done for as long as dpkg had been involved; the session was
the one path that did not.

`/dev`, `/proc` and `/sys` are bind-mounted into every session (the base rootfs ships empty mount
points for them), and the environment is **replaced**, not extended: an Android environment inside
Ubuntu is a set of lies (`HOME` pointing into the outer filesDir, `PATH` full of Android tooling),
and env vars cross the execve boundary untouched.

## ABI support

| Android ABI | Ubuntu arch | Toolchain |
|---|---|---|
| `arm64-v8a` | `arm64` | NDK `aarch64-linux-android` |
| `armeabi-v7a` | `armhf` | NDK `armv7a-linux-androideabi` |
| `x86_64` | `amd64` | NDK `x86_64-linux-android` |

`x86` is deliberately unsupported: Ubuntu publishes no i386 Base image, so an x86 device gets "not
supported" rather than an install that cannot finish. The mapping lives in `LinuxDistroCatalog`,
which is also the gate — a device that maps to no distro is never offered an install.

## The filesystem contract

Everything the userspace owns lives under `filesDir/linux`:

```
filesDir/linux/
├── rootfs/            the extracted Ubuntu (deleted on uninstall)
│   └── home/ubuntu/workspace/   ← the persisted workspace
├── rootfs.staging/    where a tarball is unpacked before it is validated and moved into place
├── downloads/         the tarballs, before verification
├── tmp/               PROOT_TMP_DIR (proot's own scratch)
├── install.lock       the cross-process install lock
└── state.properties   the persisted lifecycle facts

filesDir/linux-workspace-backup.tar.gz   (outside the root!)
    a keep-workspace uninstall's snapshot, restored by the next install
```

The workspace is a plain directory inside the rootfs — that is what makes every Ubuntu tool see it
as the user's home. Uninstall with "keep workspace" snapshots it (a plain gzipped tar, openable on
a desktop) to a path the uninstall does not delete, and the next install restores it before the
first shell opens. Uninstall without it deletes the snapshot too. **Stop and Restart never touch
the workspace.**

Because Android forbids the app to `chown`, every file under `filesDir` is owned by the app uid —
which is why the rootfs needs no privilege model of its own: the shell's writes succeed by DAC, and
the account identity comes from `/etc/passwd`, not from file ownership.

## Lifecycle

```
NotInstalled ──install──► Installing ──health ok──► Stopped
NeedsRepair ──install/repair──► …                   │  ▲
     ▲                                             start│stop
     └── health failed ◄── start ───┘                 ▼  │
                                                 Starting │
                                                     │    │
                 Stopping ◄──── stop ──────── Running ────┘
                     │            (closing the last terminal is NOT stop)
                     └────────────► Stopped
```

The rule the whole design turns on: **Running means "held open", not "a process exists"**. Closing
the last terminal does not stop the userspace, backgrounding the app does not stop it, and only an
explicit Stop (or the app's process dying, which takes the pty masters — and with them every
session — along) returns it to Stopped. While the machine holds a process — Running, but also
Installing, Starting and Stopping, so backgrounding the app cannot interrupt an install halfway —
the foreground service keeps the app's process alive so backgrounded sessions survive.

That service is `LinuxUserspaceService`, and its existence is derived: the userspace controller
starts it when the state machine enters a state that holds a process and stops it when none does, and
the service itself watches the same state and stops itself if it is ever alive without one. Both
sides read one predicate for that — `LinuxUserspaceState.holdsProcess()` in
`LinuxUserspaceController.kt` — so the binding and the service cannot disagree about what it covers;
a Running-only rule would demote an install the binding deliberately promotes. Its notification
offers the two gestures that matter away from the app — Open (back to
the terminal) and Stop Ubuntu (the same lifecycle verb as Stop in the Ubuntu on this device
window) — and its Android 15
six-hour dataSync budget is shared with the SSH session service: when the budget runs out both post
the same "background paused" text, under two notification ids of their own, so the second alert
replaces nothing.

Crash recovery is the constructor: the persisted `state.properties` is re-checked against the
files actually on disk, and a disagreement yields NeedsRepair rather than a state the UI would
render as healthy. A stale "installed" flag can never present as a working install.

## Security

- **No root, anywhere.** The app never elevates: it holds no Android privilege it did not already
  have, and proot's fake root is a fiction that stays inside the rootfs — the kernel still sees
  the app's own uid for every process in that tree. What fake root buys is `dpkg`'s uid check and
  somewhere for `su` to go; it grants nothing the app's sandbox did not already allow. Neither
  account has a password to attack (`*` in shadow for both), and `su` never asks for one.
- **Pinned supply chain.** The rootfs tarball is verified against a hash pinned in the source
  before extraction; the proot/talloc sources are pinned tarballs with SHA256s in
  `linux/build.gradle.kts`; every package the install adds comes from the distribution's own
  archive, through apt.
- **No escape from filesDir.** Every archive the app extracts — the rootfs and its own workspace
  snapshots — goes through the same path-traversal guard. The rootfs never writes outside
  `filesDir/linux`. proot does bind the host's `/dev`, `/proc` and `/sys` into the guest, so that
  `ps` reports something and device nodes resolve — but those are the host's directories shared in
  rather than private copies, and the argument vector asks for no read-only form of the bind:
  `ProotRuntime.commandArgv` passes a plain `-b /dev`, `-b /proc`, `-b /sys`.
- **No secrets in source.** No credentials, keys or tokens are needed by any of this — the
  environment is entered by process identity, the downloads are public.
- **Sandboxed by construction.** The userspace runs under the app's Android uid: it has the app's
  permissions, the app's sandbox, and nothing more. It cannot reach another app's data any more
  than the app itself can.

## Troubleshooting

| Symptom | Likely cause | Way out |
|---|---|---|
| Card never appears | Health probe failing — the probe's field-by-field report is the Health check row of the Ubuntu on this device window (Settings → Ubuntu on this device → Verify) | Repair |
| "proot: cannot execute" at shell start | `nativeLibraryDir` mismatch after an app update changed the ABI | Restart the app: the directory is read once, when the userspace graph is built, so a Stop and Start inside the same process reads the same stale path. Reinstall if it persists |
| `apt-get` fails with hash/404 errors | Stale archive pin or interrupted update | Repair (re-runs `apt-get update`); check DNS in the probe report |
| `whoami` is not `root` (a number, or `ubuntu`) | `/etc/passwd`'s root entry lost, or the shell did not get proot's `-0` — without it `apt install` and `su` cannot work | Repair |
| DNS does not resolve | Network changed since setup wrote `resolv.conf` | Repair rewrites it; the wiring layer passes the live resolvers |
| Download dies mid-install | Network drop; the verified-tarball resume only covers completed downloads | Retry install; nothing half-extracted is left behind |
| Sessions die when app is backgrounded | The foreground service was stopped by the user or the system | Settings → Ubuntu on this device → Start; sessions cannot be revived (their ptys died) but the workspace is untouched |
| Huge `filesDir` after many installs | A kept backup plus a new rootfs | Settings shows storage used; uninstall deletes the rootfs, keep-workspace keeps only the snapshot |
