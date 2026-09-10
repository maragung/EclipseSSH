# :freerdp — the RDP engine's native half

This Gradle library module is the RDP counterpart to the VNC engine in
`app/.../vnc/`. It owns:

- **`LibFreeRDP.java`** — an adapted copy of FreeRDP 3.31.1's own JNI wrapper
  (MPL-2.0, header preserved). The package name
  `com.freerdp.freerdpcore.services` is load-bearing: the native bridge's JNI
  symbol mangling and its `FindClass` lookups are compiled into
  `libfreerdp-android.so`. Upstream's application-class dependencies
  (GlobalApp/SessionState/BookmarkBase) are replaced by a per-instance listener
  registry.
- **The native build** — `buildFreerdpNative` runs the recipe the spike branch
  (`spike/freerdp-android`, runs 34515547889 and 34516696090) proved on GitHub's
  runner: CMake + Ninja against the NDK toolchain, `-S` pointing at the bridge
  project inside a pinned, SHA256-verified FreeRDP tarball fetched into
  `freerdp/build/freerdp-src/`. FreeRDP's superbuild cross-compiles OpenSSL,
  cJSON and uriparser per ABI; the eight resulting `.so` files per ABI are
  collected into `build/native/jniLibs/`, which AGP packages like any prebuilt
  native library.

The bridge C sources are **not** vendored: the build compiles the tarball's own
copies, so bridge and core are always siblings from the same release. The JNI
wrapper is the only adapted file.

Nothing from FreeRDP enters the repository or the app's dependency graph until
`:app` depends on this module (the RdpTunnel wiring); CI builds the module
standalone until then.

## Toolchain requirements (CI installs all of these)

- NDK `29.0.13113456` (AGP resolves it from `ndkVersion`; CI lists it as an SDK
  package)
- CMake `4.1.2` and Ninja on `PATH` (the SDK manager does not carry CMake 4.x)
- `tar`

## Third-party software built into the module's `.so` files

| Component | License | Source |
|---|---|---|
| FreeRDP 3.31.1 (core, client, winpr, Android bridge) | Apache-2.0 | pinned release tarball, SHA256-verified |
| LibFreeRDP.java (adapted) | MPL-2.0 | vendored in this module, header preserved |
| OpenSSL | Apache-2.0 | fetched by the superbuild, hash pinned by FreeRDP's DepVersions.cmake |
| cJSON | MIT | fetched by the superbuild |
| uriparser | BSD-3-Clause | fetched by the superbuild |
