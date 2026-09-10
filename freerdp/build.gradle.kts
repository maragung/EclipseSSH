// The RDP engine's native half. This module owns exactly two things:
//
//  1. the Java JNI wrapper, `LibFreeRDP.java` - an adapted copy of upstream's
//     MPL-2.0 file, carrying the `com.freerdp.freerdpcore.services.LibFreeRDP`
//     package name the native bridge's JNI name mangling is compiled against, and
//  2. a Gradle-driven native build that cross-compiles FreeRDP's own Android
//     bridge and its dependency libraries for every ABI the app ships.
//
// The native build deliberately does NOT use AGP's `externalNativeBuild`. AGP
// would inject its own CMake discovery and flags - a different build from the
// one the spike proved (branch spike/freerdp-android, runs 34515547889 and
// 34516696090: all four ABIs green on GitHub's runner). The tasks below run the
// spike's exact recipe instead: the same tarball, the same `-S` directory inside
// it, the same lean feature set, the same NDK toolchain file. What CI verifies
// here is what the spike verified.
//
// The FreeRDP source never enters the repository. `fetchFreerdpSource` downloads
// the release tarball, checks it against the published SHA256 and extracts it
// under freerdp/build/. FreeRDP's superbuild fetches and cross-compiles OpenSSL,
// cJSON and uriparser per ABI into that tree, and the resulting .so files are
// copied into build/native/jniLibs/<abi>/ - a plain jniLibs source set, so AGP
// packages them like any prebuilt native library.
//
// The build needs `cmake`, `ninja` and `tar` on PATH (CI pins cmake 4.1.2, the
// version upstream's project pins; the SDK manager does not carry it), plus the
// NDK installed under the Android SDK's ndk/<version> directory - CI installs
// it via the SDK package list in .github/workflows/ci.yml.
plugins { alias(libs.plugins.android.library) }

// FreeRDP release pin. The sha256 is the one published beside the tarball on the
// release page; a mismatch aborts the build rather than compiling unknown source.
val freerdpVersion = "3.31.1"
val freerdpSha256 = "4a2629026896cb4e26fb8ed2d6ca6aa4ab89ca95528dfbae2550c2f6bc866991"

// Every ABI the app's universal APK ships. Built sequentially in one task: the
// superbuilds are independent (each has its own build dir and its own jniLibs
// install dir) and share only the pristine extracted source, which no ABI build
// writes into - the pattern the 4-ABI spike run proved.
val freerdpAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

// The .so files an RDP session needs at runtime, besides the bridge itself.
// Copied from an explicit list rather than a glob: the superbuild also installs
// an OpenSSL provider module (ossl-modules/legacy.so) that must not be packaged,
// and a renamed or renamed-away dependency should fail the build loudly instead
// of letting the APK ship without it and die at load time.
val freerdpRuntimeLibraries = listOf(
    "libfreerdp3",
    "libfreerdp-client3",
    "libwinpr3",
    "libcrypto",
    "libssl",
    "libcjson",
    "liburiparser",
)

val freerdpSrcDir = layout.buildDirectory.dir("freerdp-src").get().asFile
val freerdpTarball = File(freerdpSrcDir, "freerdp-$freerdpVersion.tar.gz")
// Kept next to the version pins so the two cannot drift apart.
val freerdpNdkVersion = "29.0.13113456"
val freerdpSourceRoot = File(freerdpSrcDir, "freerdp-$freerdpVersion")
// Upstream's own bridge CMake project, exactly where docs/README.android's
// standalone recipe points. Building their copy (not a vendored one) keeps the
// spike's guarantee that the bridge and the core are byte-identical siblings;
// the JNI wrapper is the only adapted file.
val freerdpBridgeCMakeDir =
    File(freerdpSourceRoot, "client/Android/Studio/freeRDPCore/src/main/cpp")
// Where the superbuild installs the dependency libraries per ABI. copyLibs (a
// build dependency of the bridge target) also flattens the lib/ subdir to the
// ABI root; the copy below accepts either location so it survives an upstream
// change to that flattening.
val freerdpDepsJniLibs =
    File(freerdpSourceRoot, "client/Android/Studio/freeRDPCore/src/main/jniLibs")

val nativeBuildRoot = layout.buildDirectory.dir("native").get().asFile
val nativeJniLibs = File(nativeBuildRoot, "jniLibs")

android {
    namespace = "dev.eclipse.ssh.freerdp"
    compileSdk = 35
    // The NDK upstream's freeRDPCore module builds with (their ndkVersion), and
    // the one the spike proved the build with. Do not bump one without the other.
    ndkVersion = freerdpNdkVersion

    defaultConfig {
        // The .so files are built for android-28; the library must not claim a
        // lower minSdk than the native code supports (nor a higher one than :app).
        minSdk = 28
        // The native bridge resolves the wrapper class and its static callbacks
        // by name through JNI, so R8 must not rename or strip them when the app
        // ships; the consumer rules travel with the module for exactly that.
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets.getByName("main") {
        // Populated by buildFreerdpNative. Because every :freerdp compilation
        // hangs off preBuild, which depends on the native task, a clean checkout
        // cannot package (or compile against) a half-built module: the .so files
        // are either built or the build has already failed.
        jniLibs.srcDir(nativeJniLibs)
    }
}

// Only the SDK *path* is resolved at configuration time, never the NDK itself.
// AGP resolves (and downloads) an NDK when a native build needs one; this
// module's native build is driven by buildFreerdpNative below, which derives
// and checks the toolchain path at execution time. That keeps Gradle
// invocations that never touch native code (schema dumps, app-only tasks)
// from demanding a multi-gigabyte NDK during configuration.
val androidSdkRoot = android.sdkDirectory

val fetchFreerdpSource =
    tasks.register("fetchFreerdpSource") {
        group = "freerdp"
        description =
            "Downloads, SHA256-verifies and extracts the pinned FreeRDP source tarball."
        outputs.file(freerdpTarball)
        outputs.dir(freerdpSourceRoot)
        doLast {
            if (!freerdpSourceRoot.isDirectory) {
                freerdpSrcDir.mkdirs()
                if (!freerdpTarball.isFile) {
                    logger.lifecycle("Downloading FreeRDP $freerdpVersion ...")
                    download(
                        "https://github.com/FreeRDP/FreeRDP/releases/download/" +
                            "$freerdpVersion/freerdp-$freerdpVersion.tar.gz",
                        freerdpTarball,
                    )
                }
                verifySha256(freerdpTarball, freerdpSha256)
                logger.lifecycle("Extracting FreeRDP $freerdpVersion ...")
                runProcess(
                    logger,
                    "tar",
                    "xzf",
                    freerdpTarball.absolutePath,
                    "-C",
                    freerdpSrcDir.absolutePath,
                )
            }
        }
    }

val buildFreerdpNative =
    tasks.register("buildFreerdpNative") {
        group = "freerdp"
        description =
            "Cross-compiles the FreeRDP JNI bridge and dependencies for every ABI " +
                "(the spike recipe) and collects the .so files into the jniLibs source set."
        dependsOn(fetchFreerdpSource)
        // The pins are inputs so editing any of them reruns the task; the build
        // trees are deliberately NOT inputs - after a CI cache restore the task
        // reruns, CMake skips configure (build.ninja exists) and Ninja rebuilds
        // nothing, so a warm build costs seconds.
        inputs.property("freerdpVersion", freerdpVersion)
        inputs.property("freerdpSha256", freerdpSha256)
        inputs.property("abis", freerdpAbis.joinToString(","))
        outputs.dir(nativeJniLibs)
        doLast {
            val toolchain =
                File(File(androidSdkRoot, "ndk/$freerdpNdkVersion"), "build/cmake/android.toolchain.cmake")
            check(toolchain.isFile) {
                "The NDK $freerdpNdkVersion toolchain is missing at $toolchain - install it " +
                    "with: sdkmanager \"ndk;$freerdpNdkVersion\""
            }
            check(freerdpBridgeCMakeDir.isDirectory) {
                "The extracted FreeRDP tree has no bridge project at $freerdpBridgeCMakeDir"
            }

            for (abi in freerdpAbis) {
                val cmakeDir = File(nativeBuildRoot, "cmake/$abi")
                if (!File(cmakeDir, "build.ninja").isFile) {
                    // docs/README.android's standalone recipe with the lean
                    // feature set of upstream's `qa` build type: OpenSSL (the one
                    // dependency RDP cannot connect without - TLS/NLA) and cJSON
                    // on, every optional codec off. android-28, not the README's
                    // android-23: the .so must not require less than the app's
                    // minSdk, and must not promise more than it.
                    runProcess(
                        logger,
                        "cmake",
                        "-S", freerdpBridgeCMakeDir.absolutePath,
                        "-B", cmakeDir.absolutePath,
                        "--toolchain", toolchain.absolutePath,
                        "-DANDROID_ABI=$abi",
                        "-DANDROID_PLATFORM=android-28",
                        "-DCMAKE_BUILD_TYPE=Release",
                        "-DWITH_FFMPEG=OFF",
                        "-DWITH_OPENH264=OFF",
                        "-DWITH_OPUS=OFF",
                        "-DWITH_WEBP=OFF",
                        "-DWITH_JPEG=OFF",
                        "-DWITH_PNG=OFF",
                        "-DWITH_CJSON=ON",
                        "-DWITH_OPENSSL=ON",
                        "-GNinja",
                    )
                }
                runProcess(logger, "cmake", "--build", cmakeDir.absolutePath)

                val outDir = File(nativeJniLibs, abi)
                outDir.mkdirs()

                val bridge = File(cmakeDir, "libfreerdp-android.so")
                check(bridge.isFile) { "the bridge library was not built for $abi: $bridge" }
                bridge.copyTo(File(outDir, bridge.name), overwrite = true)

                for (lib in freerdpRuntimeLibraries) {
                    val abiDir = File(freerdpDepsJniLibs, abi)
                    val flattened = File(abiDir, "$lib.so")
                    val inLibDir = File(abiDir, "lib/$lib.so")
                    val installed = if (flattened.isFile) flattened else inLibDir
                    check(installed.isFile) {
                        "$lib was not installed for $abi (looked in $abiDir and its lib/ subdir)"
                    }
                    installed.copyTo(File(outDir, installed.name), overwrite = true)
                }
                logger.lifecycle(
                    "packaged $abi: libfreerdp-android.so + ${freerdpRuntimeLibraries.size} dependencies",
                )
            }
        }
    }

// Every variant compilation of :freerdp goes through preBuild, so the native
// libraries are built by lint, test and assemble alike. That is the point: a
// module whose Java wrapper loads `freerdp-android` must never compile green
// while the .so it needs is missing.
tasks.named("preBuild") { dependsOn(buildFreerdpNative) }

/** Runs a command, streaming its output, and fails the build on a nonzero exit. */
fun runProcess(logger: org.gradle.api.logging.Logger, vararg command: String) {
    logger.lifecycle("exec: ${command.joinToString(" ")}")
    val process =
        ProcessBuilder(*command)
            .redirectErrorStream(true)
            .start()
    // The CMake/Ninja output is the only trace of a native failure; print all of it.
    process.inputStream.bufferedReader().forEachLine { line -> println(line) }
    val exitCode = process.waitFor()
    check(exitCode == 0) {
        "command failed with exit code $exitCode: ${command.joinToString(" ")}"
    }
}

/** Downloads [url] to [target], following redirects (GitHub releases redirect to a CDN). */
fun download(url: String, target: File) {
    val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
    connection.connectTimeout = 30_000
    connection.readTimeout = 300_000
    connection.instanceFollowRedirects = true
    try {
        val code = connection.responseCode
        check(code in 200..299) { "downloading $url failed: HTTP $code" }
        connection.inputStream.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
    } finally {
        connection.disconnect()
    }
}

/** Verifies [file] against [expected] (lowercase hex) before anything is built from it. */
fun verifySha256(file: File, expected: String) {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    val actual =
        digest.digest().joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }
    check(actual == expected) {
        "sha256 mismatch for ${file.name}: expected $expected, got $actual"
    }
}
