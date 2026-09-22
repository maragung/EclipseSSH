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
   - [The percentage](#the-percentage)
4. [The runtime model](#the-runtime-model)
5. [ABI support](#abi-support)
6. [The filesystem contract](#the-filesystem-contract)
   - [Browsing it from Files](#browsing-it-from-files)
7. [Backing it up and restoring it](#backing-it-up-and-restoring-it)
8. [Lifecycle](#lifecycle)
   - [What Repair tries](#what-repair-tries)
9. [Security](#security)
10. [Troubleshooting](#troubleshooting)

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
   - the app's own supplementary Android group IDs named in `/etc/group` (`android_inet:x:3003:`
     and its siblings), so that `groups`, `id` and `ls -l` inside a session have a name for them
     instead of printing `cannot find name for group ID`. Soft: a rootfs that cannot be written
     here installs anyway, with a warning on the report,
   - `/home/ubuntu/workspace` created,
   - `/etc/resolv.conf` replaced with a real file (the base image ships a symlink into
     `/run/systemd/resolve`, which does not exist under proot),
   - `/etc/apt/sources.list` pointed at the archive matching the architecture —
     `archive.ubuntu.com` carries amd64 only; every phone architecture needs
     `ports.ubuntu.com/ubuntu-ports`,
   - `apt-get update`, then the base packages through apt itself — `apt-utils`,
     `bash-completion`, `ca-certificates`, `cron`, `curl`, `git`, `htop`, `openssh-client`,
     `sudo`, `unzip`, `wget`, `zip`. That list is the whole of what the install adds: a shell and
     the package manager, the TLS roots and fetch tools to use it with, and the handful of small
     utilities (an archive pair, a process viewer, cron) that recipes written for a real Ubuntu
     box assume are already present. It stays deliberately short: Python, Node.js, an editor and
     a compiler are the user's own call, one `apt-get install` away inside the terminal, so the
     install stays small, fast and away from registries outside the pinned Ubuntu archive. One
     caveat stated plainly — `cron` is installed but nothing starts it: this userspace has no
     init, so a crontab fires only if the user starts the daemon themselves.
5. **Health check** — a shell runs and prints a marker, `whoami` answers `root`, DNS resolves,
   `apt-get check` passes. Only then does the state machine reach Stopped.
6. **Workspace restore** — if a previous keep-workspace uninstall parked a snapshot, it is
   restored into the fresh rootfs before the first shell opens.

### The percentage

The Ubuntu window, the host-list line and the foreground notification all show the same number, and
it is one number rather than three because it is computed in one place: `LinuxInstallProgress`
turns the current `LinuxInstallStep` into a whole percent, and every renderer reads that. The bar
beside the label is drawn from the same value, so the two cannot disagree.

What the number is, precisely, is a **schedule refined by measurement**. An install is not one
measurable quantity: the download has a known byte total, extraction consumes a known tarball, the
two apt steps draw a bar of their own, and the rest are steps whose length nothing can know in
advance. So each phase is given a share of the whole from how long it actually takes on a phone —
setup the largest by a wide margin — and the phases that can measure themselves move *inside* their
share instead of jumping to its end. The download moves on bytes received, extraction on the
fraction of the tarball read, and `apt-get install` on apt's own `Progress: [ 45%]` line, which
counts completed package steps over the whole dpkg run. Reading it as a stopwatch would be wrong;
what it does promise is that it starts at 0, never walks backwards, and reaches 100 when the work
is done.

A repair over a rootfs that is already extracted skips the download half entirely, and the shares
are renormalized to match — otherwise a repair would open at 36% for work that is not happening. The
health check is the last phase and is measured in seconds, so it reads 100%: the work is finished,
and what remains is the verdict. A check that fails does not leave a 100% standing — the window
replaces the row with the failure.

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

The *groups* do move with it, and the rootfs had no name for any of them. `-0` fakes the identity a
program asks for — `getuid`, `geteuid`, `getgid`, `getegid` — and leaves `getgroups` alone, so a
session is really in the app's own Android groups: `AID_INET` (3003), `AID_EVERYBODY` (9997), and
the per-app cache and shared groups the platform derives from the app id (`AID_CACHE_GID_START`
20000 and `AID_SHARED_GID_START` 50000, each plus it). A stock Ubuntu Base `/etc/group` names none
of them, so `groups` printed `cannot find name for group ID 3003` on stderr once per ID — the
symptom this file's Troubleshooting table now answers. They are named in `/etc/group` instead, by
`AndroidGroupNames` (`android_inet:x:3003:`, `android_cache_504:x:20504:`, and `android_gid_<n>`
for an ID the platform's table does not know), written at setup and again at every start of the
userspace — which is what corrects an install made before the names existed, the first time its
terminal is opened.

Naming them is deliberately the fix, rather than hiding them from the session. Hiding is what the
pinned proot fork offers — a `getgroups`/`setgroups` handler whose own comment is this complaint
("Android is returning gids that our rootfs knows nothing about which is generating errors") — but
it is compiled out behind an `#ifdef USERLAND` that no build file in the fork defines, and enabling
that flag wholesale would also delete the `chown` emulation dpkg needs to unpack anything. A
userspace is also better off honest about this: those groups are enforced by the kernel on every
file the session opens, so an `id` that did not list them would be describing a process that does
not exist.

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
├── rootfs.import/     where an imported archive is unpacked before it is validated and swapped in
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

Because Android forbids the app to `chown`, every file under `filesDir` is owned by the app uid.
That is what the whole privilege story rests on, and it is worth separating from the identity the
session reports: the shell's writes succeed by DAC — it is the owner — while `whoami` answers `root`
because proot's fake identity says so and `/etc/passwd` has a `root` entry to put a name to it. Two
different questions, and only the first is about the filesystem. Nothing in the rootfs needs a
permission bit to be set for anyone, because the only process that ever opens them is the app.

### Browsing it from Files

The tree is also a Files-tab session of its own — the third backend beside SFTP and SAF, offered as an
**Ubuntu on this device** chip while the userspace is *running*. An installed rootfs that is stopped
does not earn one: a chip is not a fact about a directory, it is an entry in a list of places the app
can take you, and every other entry in that list is a machine that is up. The chip appears when the
userspace starts and goes when it stops, without a relaunch. What it shows is guest paths, not the
sandbox's: `/home/ubuntu/workspace/main.kt`, never `filesDir/linux/rootfs/home/ubuntu/…`.

That mapping is where the care is, because a rootfs is not a tree the host's own path semantics can
walk. Ubuntu Base is usrmerged — `/bin` is a symlink to `usr/bin`, and `lib` and `sbin` join it — and
every link in it is absolute and *guest*-rooted, so `File(rootfs, "/bin/sh")` resolves to the
**device's** `/bin/sh` and would browse the phone while drawing Ubuntu's paths. So every path component
is resolved inside the root and refused if it escapes: a link out of the root fails, `..` that climbs
above `/` fails, a link that points at itself stops at Linux' own `MAXSYMLINKS` rather than recursing,
and the device's `/dev`, `/proc` and `/sys` are hidden rather than offered as the guest's, because proot
binds the host's over them and the Files tab has "This device" for the real ones. Writes carry the same
guard, since one mapping decides both. The editor's `onlyIfUnmodifiedSince` check applies here as it
does on SFTP: a file the guest changed underneath the editor is a question for the user, not a silent
overwrite.

## Backing it up and restoring it

A userspace is a directory, so it can be copied out whole and put back later. **Export** on the
Ubuntu on this device screen writes one to a `.tar.gz` document the user picks; **Import** reads one
back in place of what is installed. What that buys is the case an install cannot cover — a phone
about to be replaced, a userspace broken past what Repair fixes, an experiment worth keeping before
it is tried, the same carefully built environment on a second device — each of which would otherwise
mean a fresh download and a fresh `apt-get install`.

The bytes are `app/src/main/java/dev/eclipse/ssh/linux/RootfsArchive.kt` and the ordering is
`app/src/main/java/dev/eclipse/ssh/linux/RootfsTransfer.kt`. The split is deliberate: the first
holds every property that makes an untrusted archive safe and has no Android type in it, which is
what lets those properties be tested on trees and streams, and the second is the only one that knows
what a lock and a state machine are.

### What an export contains

The guest filesystem *as the guest sees it* — the tree the Files tab browses, and nothing the host
lends it:

- the base rootfs and everything `apt` has added since, with the modes and symlinks they have. A
  usrmerged Ubuntu Base is half symlinks, and a `bin/sh` restored as a copy of `bin/bash` is a shell
  `apt` will never upgrade again;
- the workspace under `/home/ubuntu`. The user's own files are what a backup is *for*, and the
  Import row says so in as many words;
- `/dev`, `/proc` and `/sys` as **empty directories** — the same three names, and the same reason,
  as the Files tab's own rule: what a rootfs holds there are mount-point stubs that proot binds the
  device's own over, and archiving one phone's stubs into a file that claims to be somebody's
  userspace is exactly the mixture a restore must not produce;
- no `PROOT_TMP_DIR`, which is outside the rootfs by construction.

Hard links are written as duplicates. The cost is real and the trade is the right one: the rootfs
ships a few, a `node_modules`-shaped tree ships many, and a link that came back as a plain file is
invisible until something writes through one name and expects the other to change — a class of bug
no error message can explain to the user who finds it.

Both directions stream; a userspace is 90–250 MB and neither end holds it in memory. Progress
(bytes, entries, percentage) is a row under the buttons, and **Cancel** stops it. An abandoned
export leaves the half-written document *deleted* rather than on disk, because a file no extractor
will open is worse than no file at all.

### What an import does, in order

An imported archive is **untrusted input**: it may be the backup the user made, and it may be a file
from anywhere. So it goes through the same guards the pinned tarball does — the path-traversal and
link guards in `app/src/main/java/dev/eclipse/ssh/linux/TarSafety.kt`, and the same expansion budget
of ten times the archive's own size (a fixed ceiling when the picker will not report one) — plus one
check the pinned tarball does not need: the tree must carry one of the three names that make a
tarball a Linux system rather than a directory of files — a shell, `dpkg`, `apt-get`. A tarball of
photographs unpacks perfectly and is refused here, before anything is swapped, rather than five
minutes later as `apt` failing in a rootfs with no shell.

Then the order, which is the whole of the promise the confirmation dialog makes:

1. whatever is running is stopped: the tree about to be replaced is the one its sessions run on;
2. the archive is unpacked into `rootfs.import/`, a staging directory of its own — deliberately not
   the installer's `rootfs.staging/`, because *that* directory is what `RootfsInstaller.isExtracted()`
   reads, so an import through it would make the installed userspace report itself as not extracted
   for the length of the import, and a concurrent install would clear the tree out from under it;
3. the staged tree is checked for being a usable userspace of **this** device's release, so an
   archive of another Ubuntu series is refused before anything is swapped rather than becoming an
   install that cannot work and cannot say why;
4. only then is the installed rootfs renamed aside and the staged tree renamed into its place,
   parking the old one first and putting it back if the rename fails — the installer's own
   move-into-place ordering, mirrored rather than reused, because that method is private and its
   staging directory is not this one;
5. the setup pipeline runs over the result: `install()` when nothing was installed, `repair()` when
   something was. Neither downloads a byte (the tree is already extracted) and both end in the
   health probe, so an imported userspace counts as installed only once it has been shown to work.

So an import that fails — a traversing entry, a tarball that is not a userspace, one that expands
past the budget, a document the user cancelled — leaves the installed userspace byte-for-byte as it
was, and a failure *after* step 4 leaves a userspace that is installed but not yet healthy, which is
what NeedsRepair names and Repair fixes.

That last point is what a cancellation is arranged around. It is answered *before* the swap, where
nothing has been replaced, and refused after it, where the tree on disk is already the archive's and
the only honest ending is the pipeline running to completion; a cancel that arrives too late to
matter is reported as the import it turned out to be rather than as a failed one.

An archive carries the **exporting** device's app uid in `/etc/passwd`, its resolvers and its apt
mirror, and none of those belong on this one — step 5 is what rewrites all three, which is why an
import is not a file copy. It also discards any workspace snapshot a keep-workspace uninstall left
parked for the next install: the archive's workspace is the newer intent by definition, since the
user asked for it by importing it.

### One operation at a time

Both directions take `install.lock` for their whole length — the same lock an install and a repair
take — so a transfer and an install can never be in flight together. That is not decoration: a
repair rewriting the tree while an export reads it would produce an archive of a state nobody was
ever in, and an import landing under a running install is a race no one wins.

A transfer does *not* get the foreground service, and that is the one gap worth knowing: the service
is derived from the states that hold a process, and a copy is not one. What an app killed mid-way
leaves behind is in the Troubleshooting table below.

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

### What Repair tries

Repair is a ladder, cheapest rung first, and it stops at the first one that ends with a healthy
userspace. The state it was reported against — a rootfs that is *there* and wrong, so every rung of
the old two-step repair (`apt-get update`, `dpkg --configure -a && apt-get -f install`) failed with
the same `1 expected program not found in PATH or not executable` — is exactly the case the lower
rungs exist for.

| Rung | What it does | What it costs |
|---|---|---|
| Reclaim the staging tree | A crash mid-extraction leaves a whole rootfs *and* `rootfs.staging` beside it; `isExtracted()` is false because of the leftover alone, so the leftover goes before anything reads the install as missing | Nothing: the tree is what the failed run already abandoned |
| Free space | Empties apt's package cache, its lists, the binary index caches, `/tmp` and `/var/tmp`, rotated logs, a download fragment, and a `rootfs.old` parked by a crashed swap — and only when a rootfs is in place. Runs whenever the free space is short or unknown | Nothing the user owns: every byte is one apt regenerates |
| Set up again | The setup pipeline again: `apt-get update`, the dpkg prologue (which restores any of dpkg's own programs that are really gone, out of the pinned archive), the base packages | Time, and one archive fetch if the prologue needs it |
| Restore what the archive says is missing | One pass over the pinned tarball comparing presence, kind and the executable bit against the rootfs, then the absent members written back | One archive fetch (~30 MB, from the pin) |
| Rewrite the base system | Every member the pinned archive carries, written over whatever the rootfs holds at that name — the repair for damage no file-level scan can name (a library replaced by something that does not load) | The base system's own bytes; installed packages stay installed |
| Reinstall | The rootfs is thrown away and built from the pin again | The base system *and* the packages installed on top of it |

Nothing in the ladder writes `/home` (the workspace inside it), `/var/lib/dpkg` and the state trees
beside it, and the last rung parks the workspace before it replaces the rootfs and restores it
afterwards — so no rung costs the user a file they wrote or a package they installed, except the
reinstall, which costs the packages.

The ladder refuses a failure a rebuild cannot fix, and hands it back unchanged rather than rewriting
a working base system over it: no network, no DNS, an archive that no longer matches its pin, a
mirror that refuses or serves an unsigned index, a step that timed out, and a disk with nothing left
to free. On a metered connection a rebuild is the one repair that leaves the user worse off, and it
would not have worked anyway.

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
- **No escape from filesDir.** Every archive the app extracts — the rootfs, its own workspace
  snapshots, and an imported userspace backup the user picked from anywhere — goes through the same
  path-traversal guard, the same expansion budget and the same "is this actually a userspace" check.
  A backup is untrusted input by definition: that it was exported by this app is a fact about a
  filename, not about the bytes. The rootfs never writes outside `filesDir/linux`. proot does bind
  the host's `/dev`, `/proc` and `/sys` into the guest, so that `ps` reports something and device
  nodes resolve — but those are the host's directories shared in rather than private copies, and the
  argument vector asks for no read-only form of the bind: `ProotRuntime.commandArgv` passes a plain
  `-b /dev`, `-b /proc`, `-b /sys`.
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
| `dpkg: warning: 'rm' not found in PATH or not executable`, then `E: Sub-process /usr/bin/dpkg returned an error code (2)` | Two causes that read identically: the rootfs is genuinely missing one of dpkg's programs (`rm`, `tar`, `sh`), or the guest's own `sudo`, `su -` or login shell rebuilt a short `PATH` from files the app was not writing — `dpkg` is named as the broken sub-process either way | Verify first, and it says which: `missingPrograms` lists every program `command -v` cannot find and `loginPath` is the PATH a login shell actually ended up with. Repair restores anything that is really gone from the pinned, SHA-256-verified tarball *before* it asks dpkg anything, since dpkg cannot answer when dpkg is the broken thing, and re-writes all four PATH files (`/etc/environment`, `/etc/profile.d/00-eclipse-path.sh`, `login.defs`, the sudoers drop-in). A rootfs whose damage is not an absent file — a library replaced by something that does not load — is what the ladder's last two rungs are for (see [What Repair tries](#what-repair-tries)) |
| `whoami` is not `root` (a number, or `ubuntu`) | `/etc/passwd`'s root entry lost, or the shell did not get proot's `-0` — without it `apt install` and `su` cannot work | Repair |
| DNS does not resolve | Network changed since setup wrote `resolv.conf` | Repair rewrites it; the wiring layer passes the live resolvers |
| `groups: cannot find name for group ID 3003` (or 9997, 20504, 50504) | The names for the app's own Android groups are missing from the rootfs's `/etc/group` — an install made before the app wrote them | Nothing to repair: starting the userspace rewrites the file, so opening the terminal once after the update clears it. The IDs are the app's real groups (`inet`, `everybody`, and the cache and shared groups derived from its app id); `id` and `ls -l` print them by number until then |
| Download dies mid-install | Network drop; the verified-tarball resume only covers completed downloads | Retry install; nothing half-extracted is left behind |
| Install or Repair fails on space | The tarball plus the extracted rootfs plus apt's caches exceed what the volume has | Repair reclaims apt's caches, its lists, the temporary directories and the rotated logs before it does anything else, and reports how much it freed. Uninstalling with "delete the workspace" is the other half: the rootfs is the large item |
| Repair ran and the terminal still fails | The failure is environmental — no network, no DNS, a mirror refusing, an archive that no longer matches its pin, a step timing out, a disk with nothing left to free | Repair says so instead of rebuilding: it hands back the numbered failure the taxonomy names (see [What Repair tries](#what-repair-tries)), and the install log records which rung ran and what each one answered |
| Sessions die when app is backgrounded | The foreground service was stopped by the user or the system | Settings → Ubuntu on this device → Start; sessions cannot be revived (their ptys died) but the workspace is untouched |
| Huge `filesDir` after many installs | A kept backup plus a new rootfs | Settings shows storage used; uninstall deletes the rootfs, keep-workspace keeps only the snapshot |
| Export or Import stops with no result after the app is closed | Transfers are not covered by the foreground service — a copy holds no process, so a process death takes it with it | Nothing installed was lost. An interrupted export is a truncated document to delete (the app deletes its own if it was still running); an interrupted import leaves a `rootfs.import/` staging tree, which the next import deletes before it starts. Start the transfer again |
