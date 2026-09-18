# :linux — the Ubuntu Linux userspace's native half

This Gradle library module owns everything native the local Ubuntu
userspace needs:

- **`LinuxPty.kt`** — the JNI wrapper over `liblinuxpty.so`, a PTY bridge
  that forks a child with a controlling terminal (the same shape a terminal
  emulator uses) so interactive programs behave exactly like they do over
  SSH.
- **`src/main/cpp/talloc-config.h`** — the hand-written feature set that
  compiles talloc's single source file for bionic without talloc's waf
  build (waf wants to execute target test programs, which a cross-build
  cannot do).
- **The native build** — `buildLinuxNative` cross-compiles, for every
  supported ABI:
  1. **talloc 2.4.2** (pinned tarball, SHA256-verified) — one `talloc.c`,
     archived into `libtalloc.a`,
  2. **proot and its standalone loader** from a pinned fork tarball of
     [oonid/pr](https://github.com/oonid/pr) (proot v5.4.0 with Android
     patches), statically linked, through the fork's own GNUmakefile, and
  3. **the PTY bridge** with CMake + Ninja against the NDK toolchain.

  The artifacts land in `build/native/jniLibs/<abi>/` as `libproot.so`,
  `libproot-loader.so` and `liblinuxpty.so`; AGP packages them like any
  prebuilt native library. `libproot.so` and `libproot-loader.so` are
  executables renamed into the `lib*.so` namespace purely so they land in
  `nativeLibraryDir` — the one directory a targetSdk 29+ app may `execve`
  from.

## Why a proot fork, in one paragraph

The app targets SDK 35, where SELinux W^X denies `execve()` of every file
under `filesDir` — the rootfs included — and the Zygote's seccomp filter
blocks syscalls glibc programs need (`clone3`, `fchmodat`, `faccessat2`,
the `setuid` family, ...). Upstream proot v5.4.0 cannot work under those
rules. The pinned fork solves both: a SIGSYS handler set
(`src/proot/src/tracee/seccomp.c`) emulates or downgrades the blocked
syscalls in userspace, and the `PROOT_LOADER` environment variable lets
proot's loader live in `nativeLibraryDir` instead of extracting it into
`filesDir` (where it could never be exec'd). Rootfs binaries themselves are
never exec'd by the kernel: proot's loader `mmap`s them, and mmap-exec of
`app_data_file` is permitted. This is why `:app` sets
`useLegacyPackaging = true`.

## Runtime contract

The userspace runtime in `:app` is the only intended consumer. It:

- spawns `nativeLibraryDir/libproot.so` through `LinuxPty.spawn`,
- passes `PROOT_LOADER=<nativeLibraryDir>/libproot-loader.so`,
  `PROOT_TMP_DIR=<filesDir>/linux/tmp` and
  `PROOT_SIGSYS_LOG=<filesDir>/linux/sigsys-log.txt` in the environment,
- never relies on the embedded fallback loader (it would extract into
  `filesDir` and die with EACCES).

## proot-patches/

Fixes the fork needs but has not merged live in `proot-patches/` as
numbered unified diffs; `fetchLinuxSource` applies them (in name order)
to the extracted fork tree and records a fingerprint of the whole patch
set — every name and every byte — in a `.patches-applied` marker. The
tree on disk is only kept when that fingerprint matches, so a patch
rewritten after a build makes the next one re-extract and re-apply all
of them rather than stacking the rest onto a tree the rewrite no longer
describes.
See `proot-patches/README.md` and each patch's preamble for the failure
it fixes and the evidence. 0001 is load-bearing for amd64/x86_64
devices and emulators: without it, every plain `rename()` inside the
rootfs (apt, dpkg, coreutils `mv`) returns `ENOSYS` — the SIGSYS
handler decodes the trapped syscall as 0 on x86 because both `rax` and
`orig_rax` have already been consumed, and its `PR_rename →
PR_renameat` downgrade never matches.

## Toolchain requirements (CI installs all of these)

- NDK `29.0.13113456` via the SDK package list,
- `cmake` and `ninja` on PATH (same as `:freerdp`),
- `make`, `tar`, `readelf` and `awk` on PATH (standard on ubuntu runners;
  `readelf`+`awk` generate `loader-info.c` on aarch64),
- `patch` on PATH for `proot-patches/` (the stock ubuntu-24.04 runner image
  does not ship it; every CI workflow installs it explicitly).

## Supported ABIs

`arm64-v8a`, `armeabi-v7a`, `x86_64` — the three ABIs Ubuntu publishes a
Base rootfs for. `x86` is deliberately absent (no i386 Ubuntu Base exists);
on x86 devices the app's architecture check reports the userspace as
unsupported.

## Licensing

proot is GPL-2.0, talloc LGPL-3.0. Only proot is built as a standalone
executable that the app runs as a separate process and talks to over a PTY —
arm's-length aggregation, not linking. talloc is not a process and has no binary
of its own: the module compiles it to `libtalloc.a` and links proot against it
statically, so the talloc code is inside `libproot.so`, which the app never links
into itself. The source
offer is the pinned, SHA256-verified tarball URLs recorded in
`build.gradle.kts` together with the patches in `proot-patches/` that the build
applies to the extracted fork before compiling it; see `docs/THIRD-PARTY.md` for
the full notice.
