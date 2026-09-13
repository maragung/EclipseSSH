# Third-party notices

EclipseSSH's own code is Apache-2.0. The items below ship in its release
artifacts or build outputs and carry their own terms.

## Proot (GPL-2.0) and talloc (LGPL-3.0) — the Linux userspace

The `:linux` module cross-compiles [proot][proot] (GPL-2.0-or-later) and its
dependency [talloc][talloc] (LGPL-3.0) from source, and the release APKs
ship both as standalone executables (`libproot.so`, `libproot-loader.so` in
`nativeLibraryDir` — executables renamed into the `lib*.so` namespace only so
Android's packaging puts them on disk).

The build uses [oonid/pr][oonid-pr], a fork of proot v5.4.0 with Android
targetSdk 29+ fixes, pinned to an exact commit in `linux/build.gradle.kts`:

- proot fork source: `https://codeload.github.com/oonid/pr/tar.gz/<commit>`
  (commit `754583c96e686a07be47bd02a8ba4fdfb2d7069f`, SHA256-verified by the
  build; the fork's patches are derived from upstream
  `https://github.com/proot-me/proot`, GPL-2.0)
- talloc source: `https://www.samba.org/ftp/talloc/talloc-2.4.2.tar.gz`
  (LGPL-3.0, SHA256-verified by the build)

**Corresponding source**: the two URLs above, at the versions and hashes
pinned in `linux/build.gradle.kts`. Both are exact, reproducible tarballs —
checking out this repository and running the `:linux` build downloads,
hash-verifies and compiles exactly those bytes.

**Why the app's license is unaffected**: proot and talloc are compiled into
standalone executables, not linked into EclipseSSH. The app starts them as
separate processes (fork + execve via the `liblinuxpty.so` PTY bridge) and
communicates over a pseudo-terminal; no GPL code is linked into, derived
from, or combined with the app's code at build or run time. This is
arm's-length aggregation, same as running a GPL program on the same machine.

## FreeRDP (Apache-2.0) — the `:freerdp` module

Compiled from the pinned upstream tarball recorded in
`freerdp/build.gradle.kts`. Apache-2.0 is compatible with the app's own
license; no additional obligation beyond notice, which this section provides.

[proot]: https://github.com/proot-me/proot
[talloc]: https://talloc.samba.org/
[oonid-pr]: https://github.com/oonid/pr
