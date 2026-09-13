// The Ubuntu Linux userspace's native half. This module owns three things:
//
//  1. `LinuxPty.kt` - the JNI wrapper over liblinuxpty.so, the tiny PTY
//     bridge the app uses to fork proot with a controlling terminal,
//  2. a Gradle-driven cross-build of proot and its standalone loader, and
//  3. the packaging of both as prebuilt "libraries" in the APK.
//
// Why proot at all, and why this proot: the userspace runs a real Ubuntu
// Base rootfs with apt, node, python and friends, without root and without a
// VM. proot implements that with ptrace translation. Vanilla upstream proot
// v5.4.0 cannot do this on a targetSdk 29+ app: Android's Zygote seccomp
// filter blocks syscalls glibc programs issue (clone3, fchmodat, faccessat2,
// openat2, the setuid family, ...), and the SELinux W^X rule denies execve()
// of every file under filesDir - including the rootfs AND proot's own loader
// when it extracts it to a temp dir. The pinned fork below (github.com/oonid/pr,
// src/proot/) is proot v5.4.0 with those two problems solved: a SIGSYS
// handler set in src/tracee/seccomp.c that emulates or downgrades the blocked
// syscalls in userspace, and a runtime PROOT_LOADER environment variable that
// lets the loader live in nativeLibraryDir, which the system labels
// apk_data_file and keeps executable.
//
// The exec model that survives W^X (proven end to end on a host build before
// any of this entered CI, and in production by the fork's own app):
//
//   app process --fork+execve--> nativeLibraryDir/libproot.so   (apk_data_file: allowed)
//   proot -----execve---------> nativeLibraryDir/libproot-loader.so (PROOT_LOADER)
//   loader ----mmap--------->  filesDir/linux/rootfs/bin/bash  (mmap-exec of
//                              app_data_file is permitted; only execve is denied)
//
// Which is also why :app sets useLegacyPackaging = true: without it the
// libraries stay inside the APK zip and there is no on-disk file to exec.
//
// The proot and talloc sources never enter this repository. fetchLinuxSource
// downloads the two pinned tarballs, checks them against published SHA256
// sums and extracts them under linux/build/. buildLinuxNative then compiles,
// per ABI: talloc (a single talloc.c with a hand-written config header - see
// src/main/cpp/talloc-config.h), proot itself through its GNUmakefile, and
// the PTY bridge with CMake. The artifacts are copied into
// build/native/jniLibs/<abi>/ as libproot.so, libproot-loader.so and
// liblinuxpty.so - a plain jniLibs source set, so AGP packages them like any
// prebuilt native library. libproot.so and libproot-loader.so are
// executables, not shared objects; that is deliberate and matches how the
// fork's own app ships them.
//
// The build needs `make`, `tar`, `readelf`, `awk`, `cmake` and `ninja` on
// PATH plus the NDK under the SDK's ndk/<version> directory - the same
// environment :freerdp already requires, except that freerdp does not need
// make/readelf/awk (all standard on CI's ubuntu runners).
//
// Licensing: proot is GPL-2.0 (talloc LGPL-3.0, statically linked into it).
// Both are built as standalone executables and run in separate processes
// that the app talks to over a PTY - arm's-length aggregation, not linking -
// so the app's license does not change. The source offer is the pinned,
// SHA-verified tarball URL plus the upstream projects; see docs/THIRD-PARTY.
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

plugins { alias(libs.plugins.android.library) }

// The proot fork pin. The commit is the fork's tree that the runtime design
// above was validated against; the sha256 is of the codeload tarball for
// that exact commit, so a mismatch aborts the build rather than compiling
// unknown source.
val prootForkCommit = "754583c96e686a07be47bd02a8ba4fdfb2d7069f"
val prootForkSha256 = "db6f10b7834a87c7b75b51242e54d64983bf9f04acea270cd1ff2b427308a50b"

// talloc, proot's only dependency. The sha256 is the one published beside
// the tarball on samba.org.
val tallocVersion = "2.4.2"
val tallocSha256 = "85ecf9e465e20f98f9950a52e9a411e14320bc555fa257d87697b7e7a9b1d8a6"

// The NDK the freerdp module builds with; do not bump one without the other.
val linuxNdkVersion = "29.0.13113456"

// The three ABIs Ubuntu publishes a Base rootfs for (arm64, armhf, amd64).
// x86 is deliberately absent: there is no i386 Ubuntu Base image, so an x86
// device simply gets no userspace and the app's architecture check reports
// it as unsupported.
val linuxAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")
val linuxTriples =
    mapOf(
        "arm64-v8a" to "aarch64-linux-android",
        "armeabi-v7a" to "armv7a-linux-androideabi",
        "x86_64" to "x86_64-linux-android",
    )

val linuxSrcDir = layout.buildDirectory.dir("linux-src").get().asFile
val oonidTarball = File(linuxSrcDir, "oonid-pr-$prootForkCommit.tar.gz")
val tallocTarball = File(linuxSrcDir, "talloc-$tallocVersion.tar.gz")
// Root directory names the two tarballs extract to (codeload names them
// <repo>-<ref> and the release tarball talloc-<version>).
val prootSrcRoot = File(linuxSrcDir, "pr-$prootForkCommit")
val tallocSrcRoot = File(linuxSrcDir, "talloc-$tallocVersion")

val nativeBuildRoot = layout.buildDirectory.dir("native").get().asFile
val nativeJniLibs = File(nativeBuildRoot, "jniLibs")

// The hand-written feature set that compiles talloc.c for bionic without
// waf (waf wants to execute target test programs). Lives in the repo, not a
// download, because every define in it is a claim about bionic that this
// build is responsible for.
val tallocConfigHeader = File(projectDir, "src/main/cpp/talloc-config.h")
val ptyCMakeDir = File(projectDir, "src/main/cpp")

android {
    namespace = "dev.eclipse.ssh.linux"
    compileSdk = 37
    ndkVersion = linuxNdkVersion

    defaultConfig {
        // The .so files are built for android-28; must not claim less than
        // the native code supports, nor more than :app's minSdk.
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Populated by buildLinuxNative. Every :linux compilation hangs off preBuild,
// which depends on the native task, so a clean checkout cannot package (or
// compile against) a half-built module. Registered through the variant
// sources API for the same reason as :freerdp's jniLibs (AGP 9 cut the old
// sourceSets typing; addStaticSourceDirectory is the supported form).
androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(nativeJniLibs.absolutePath)
    }
}

// Only the SDK path resolves at configuration time - same rationale as
// :freerdp: no multi-gigabyte NDK demand from gradle invocations that never
// touch native code.
val androidSdkRoot = androidComponents.sdkComponents.sdkDirectory.get().asFile

val fetchLinuxSource =
    tasks.register("fetchLinuxSource") {
        group = "linux"
        description =
            "Downloads, SHA256-verifies and extracts the pinned proot fork and talloc tarballs."
        // Same rebind-to-locals rule as :freerdp's fetch task: a task action
        // reading a script-level val captures the script class itself, which
        // the configuration cache refuses to serialize - and a refused store
        // fails the whole build.
        val prootCommit = prootForkCommit
        val prootSha256 = prootForkSha256
        val tallocVer = tallocVersion
        val tallocSha = tallocSha256
        val srcDir = linuxSrcDir
        val oonidTgz = oonidTarball
        val tallocTgz = tallocTarball
        val prootRoot = prootSrcRoot
        val tallocRoot = tallocSrcRoot
        outputs.file(oonidTgz)
        outputs.file(tallocTgz)
        outputs.dir(prootRoot)
        outputs.dir(tallocRoot)
        doLast {
            fun run(vararg command: String) {
                logger.lifecycle("exec: ${command.joinToString(" ")}")
                val process =
                    ProcessBuilder(*command)
                        .redirectErrorStream(true)
                        .start()
                process.inputStream.bufferedReader().forEachLine { line -> println(line) }
                val exitCode = process.waitFor()
                check(exitCode == 0) {
                    "command failed with exit code $exitCode: ${command.joinToString(" ")}"
                }
            }

            fun download(url: String, target: File) {
                val connection = URL(url).openConnection() as HttpURLConnection
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

            fun verifySha256(file: File, expected: String) {
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                    }
                }
                val actual =
                    digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
                check(actual == expected) {
                    "sha256 mismatch for ${file.name}: expected $expected, got $actual"
                }
            }

            // Extraction guards are real content, not the declared output
            // roots (Gradle pre-creates those): the proot GNUmakefile and
            // talloc.c only ever get there by a real extraction.
            val prootMakefile = File(prootRoot, "src/proot/src/GNUmakefile")
            if (!prootMakefile.isFile) {
                srcDir.mkdirs()
                if (!oonidTgz.isFile) {
                    logger.lifecycle("Downloading the proot fork @ $prootCommit ...")
                    download(
                        "https://codeload.github.com/oonid/pr/tar.gz/$prootCommit",
                        oonidTgz,
                    )
                }
                verifySha256(oonidTgz, prootSha256)
                logger.lifecycle("Extracting the proot fork ...")
                run("tar", "xzf", oonidTgz.absolutePath, "-C", srcDir.absolutePath)
                check(prootMakefile.isFile) {
                    "the extracted fork has no proot makefile at $prootMakefile"
                }
            }
            if (!File(tallocRoot, "talloc.c").isFile) {
                srcDir.mkdirs()
                if (!tallocTgz.isFile) {
                    logger.lifecycle("Downloading talloc $tallocVer ...")
                    download(
                        "https://www.samba.org/ftp/talloc/talloc-$tallocVer.tar.gz",
                        tallocTgz,
                    )
                }
                verifySha256(tallocTgz, tallocSha)
                logger.lifecycle("Extracting talloc $tallocVer ...")
                run("tar", "xzf", tallocTgz.absolutePath, "-C", srcDir.absolutePath)
                check(File(tallocRoot, "talloc.c").isFile) {
                    "the extracted talloc tree has no talloc.c at $tallocRoot"
                }
            }
        }
    }

val buildLinuxNative =
    tasks.register("buildLinuxNative") {
        group = "linux"
        description =
            "Cross-compiles talloc, proot (with its loader) and the PTY bridge for every " +
                "supported ABI and collects them into the jniLibs source set."
        dependsOn(fetchLinuxSource)
        // Same rebind rule: the action below reads only these locals.
        val sdkRoot = androidSdkRoot
        val ndkVersion = linuxNdkVersion
        val prootRoot = prootSrcRoot
        val tallocRoot = tallocSrcRoot
        val configHeader = tallocConfigHeader
        val ptySources = ptyCMakeDir
        val buildRoot = nativeBuildRoot
        val jniLibsOut = nativeJniLibs
        val abis = linuxAbis
        val triples = linuxTriples
        val prootCommit = prootForkCommit
        inputs.property("prootForkCommit", prootForkCommit)
        inputs.property("prootForkSha256", prootForkSha256)
        inputs.property("tallocVersion", tallocVersion)
        inputs.property("abis", linuxAbis.joinToString(","))
        // The in-repo sources the compile reads. Without them an edit to the
        // config header or the PTY bridge would be masked by an UP-TO-DATE
        // verdict on a warm build tree - the artifact would silently keep the
        // stale bytes.
        inputs.file(tallocConfigHeader)
        inputs.file(File(ptyCMakeDir, "linuxpty.c"))
        inputs.file(File(ptyCMakeDir, "CMakeLists.txt"))
        outputs.dir(jniLibsOut)
        doLast {
            fun run(vararg command: String) {
                logger.lifecycle("exec: ${command.joinToString(" ")}")
                val process =
                    ProcessBuilder(*command)
                        .redirectErrorStream(true)
                        .start()
                process.inputStream.bufferedReader().forEachLine { line -> println(line) }
                val exitCode = process.waitFor()
                check(exitCode == 0) {
                    "command failed with exit code $exitCode: ${command.joinToString(" ")}"
                }
            }

            fun runEnv(
                env: Map<String, String>,
                vararg command: String,
            ) {
                logger.lifecycle("exec: ${command.joinToString(" ")}")
                // The environment belongs to the builder: `environment()` is
                // a ProcessBuilder method, and setting it after start() would
                // be too late for the child either way.
                val builder = ProcessBuilder(*command).redirectErrorStream(true)
                builder.environment().putAll(env)
                val process = builder.start()
                process.inputStream.bufferedReader().forEachLine { line -> println(line) }
                val exitCode = process.waitFor()
                check(exitCode == 0) {
                    "command failed with exit code $exitCode: ${command.joinToString(" ")}"
                }
            }

            /**
             * Reads a little-endian integer of [size] bytes at [at] from [bytes]. All three
             * supported ABIs are little-endian; this build would rather refuse than patch a
             * big-endian ELF it does not understand.
             */
            fun readLe(
                bytes: ByteArray,
                at: Int,
                size: Int,
            ): Long {
                var value = 0L
                for (i in 0 until size) {
                    value = value or ((bytes[at + i].toLong() and 0xff) shl (8 * i))
                }
                return value
            }

            fun writeLe(
                bytes: ByteArray,
                at: Int,
                value: Long,
                size: Int,
            ) {
                for (i in 0 until size) {
                    bytes[at + i] = ((value shr (8 * i)) and 0xff).toByte()
                }
            }

            /**
             * Raises the PT_TLS program header's alignment to 64 when it is smaller.
             * bionic's TLS resolver requires 64-byte alignment for static executables'
             * TLS segments; the NDK toolchain still emits smaller alignments in some
             * configurations, and a too-small p_align crashes at startup on Android 15+.
             * This is the same post-build fix the fork's own build script applies (there
             * via a Python snippet; here in Kotlin so the build needs no Python).
             */
            fun enforceTlsAlignment(file: File) {
                val bytes = file.readBytes()
                check(bytes.size > 64 && bytes[0] == 0x7f.toByte() && bytes[1] == 'E'.code.toByte()) {
                    "${file.name} is not an ELF file"
                }
                val is64 = bytes[4].toLong() == 2L
                val phoff = if (is64) readLe(bytes, 16, 8) else readLe(bytes, 28, 4)
                val phentsize = readLe(bytes, if (is64) 54 else 42, 2).toInt()
                val phnum = readLe(bytes, if (is64) 56 else 44, 2).toInt()
                check(phentsize > 0 && phnum in 1..64) {
                    "${file.name} has a program header table this patcher refuses " +
                        "(phentsize=$phentsize phnum=$phnum)"
                }
                var patched = 0
                for (i in 0 until phnum) {
                    val ph = (phoff + i.toLong() * phentsize).toInt()
                    if (readLe(bytes, ph, 4) != 7L) continue // PT_TLS
                    val alignAt = ph + (if (is64) 48 else 28)
                    val current = if (is64) readLe(bytes, alignAt, 8) else readLe(bytes, alignAt, 4)
                    if (current in 1 until 64) {
                        writeLe(bytes, alignAt, 64, if (is64) 8 else 4)
                        patched++
                    }
                }
                if (patched > 0) {
                    file.writeBytes(bytes)
                    logger.lifecycle("raised PT_TLS alignment to 64 in ${file.name} ($patched header(s))")
                }
            }

            val ndkHostBin =
                File(
                    File(File(sdkRoot, "ndk/$ndkVersion"), "toolchains/llvm/prebuilt"),
                    "linux-x86_64/bin",
                )
            check(ndkHostBin.isDirectory) {
                "The NDK $ndkVersion toolchain is missing at $ndkHostBin - install it " +
                    "with: sdkmanager \"ndk;$ndkVersion\""
            }
            val prootSrc = File(prootRoot, "src/proot/src")
            check(File(prootSrc, "GNUmakefile").isFile) {
                "The extracted proot fork has no source tree at $prootSrc"
            }
            check(File(tallocRoot, "talloc.c").isFile) {
                "The extracted talloc tree has no talloc.c at $tallocRoot"
            }
            check(configHeader.isFile) { "missing $configHeader" }
            val toolchain =
                File(File(sdkRoot, "ndk/$ndkVersion"), "build/cmake/android.toolchain.cmake")
            check(toolchain.isFile) { "The NDK $ndkVersion CMake toolchain is missing at $toolchain" }

            for (abi in abis) {
                val triple = triples.getValue(abi)
                val work = File(buildRoot, abi)
                work.mkdirs()

                // 1) talloc: one source file, compiled against the hand-written
                // config header, archived for static linking into proot.
                val configDir = File(work, "config")
                configDir.mkdirs()
                configHeader.copyTo(File(configDir, "config.h"), overwrite = true)
                val tallocObj = File(work, "talloc.o")
                val tallocLib = File(work, "libtalloc.a")
                run(
                    File(ndkHostBin, "${triple}28-clang").absolutePath,
                    "-c", File(tallocRoot, "talloc.c").absolutePath,
                    "-I${configDir.absolutePath}",
                    "-I${tallocRoot.absolutePath}",
                    "-I${File(tallocRoot, "lib/replace").absolutePath}",
                    "-O2", "-Wall",
                    "-o", tallocObj.absolutePath,
                )
                tallocLib.delete()
                run(
                    File(ndkHostBin, "llvm-ar").absolutePath,
                    "rcs", tallocLib.absolutePath, tallocObj.absolutePath,
                )

                // 2) proot + its standalone loader, through the fork's own
                // GNUmakefile. The makefile appends `-ltalloc
                // -Wl,-z,noexecstack` to LDFLAGS itself and links
                // `$(LD) -o $@ $^ $(LDFLAGS)`, so the archive only needs to be
                // on the -L path. Everything is built static: a static proot
                // has no DT_NEEDED entries and therefore no loader-path
                // surprises when exec'd from nativeLibraryDir.
                //
                // `make clean` before every ABI: the makefile compiles into
                // the shared source tree, so objects from a previous ABI would
                // poison the link. That makes the ABI loop inherently
                // sequential, which is fine - each build is well under a
                // minute on CI hardware.
                run("make", "-C", prootSrc.absolutePath, "clean")
                runEnv(
                    mapOf(
                        "CC" to File(ndkHostBin, "${triple}28-clang").absolutePath,
                        "STRIP" to File(ndkHostBin, "llvm-strip").absolutePath,
                        "OBJCOPY" to File(ndkHostBin, "llvm-objcopy").absolutePath,
                        "OBJDUMP" to File(ndkHostBin, "llvm-objdump").absolutePath,
                        // `true` shadows git so CHECK_VERSION degrades instead of
                        // describing the *app's* tags (the repo has no proot git
                        // history at all - it is a tarball).
                        "GIT" to "true",
                        "CPPFLAGS" to
                            "-I${configDir.absolutePath} " +
                                "-I${tallocRoot.absolutePath} " +
                                "-I${File(tallocRoot, "lib/replace").absolutePath} " +
                                "-I${work.absolutePath}",
                        "LDFLAGS" to
                            "-static -L${work.absolutePath} -Wl,-z,max-page-size=16384",
                    ),
                    "make", "-C", prootSrc.absolutePath, "-j2",
                )
                val prootBin = File(prootSrc, "proot")
                val loaderBin = File(prootSrc, "loader/loader")
                check(prootBin.isFile) { "proot was not built for $abi at $prootBin" }
                check(loaderBin.isFile) { "the standalone loader was not built for $abi at $loaderBin" }
                enforceTlsAlignment(prootBin)
                enforceTlsAlignment(loaderBin)

                // 3) the PTY bridge.
                val cmakeDir = File(work, "cmake")
                run(
                    "cmake",
                    "-S", ptySources.absolutePath,
                    "-B", cmakeDir.absolutePath,
                    "--toolchain", toolchain.absolutePath,
                    "-DANDROID_ABI=$abi",
                    "-DANDROID_PLATFORM=android-28",
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-GNinja",
                )
                run("cmake", "--build", cmakeDir.absolutePath)
                val ptyLib = File(cmakeDir, "liblinuxpty.so")
                check(ptyLib.isFile) { "the PTY bridge was not built for $abi at $ptyLib" }

                // 4) collect as prebuilt jniLibs. The executable proot and
                // loader are named lib*.so purely because that is the name
                // space AGP packages into nativeLibraryDir; their ELF types
                // stay ET_EXEC/whatever the fork's makefile emits, which is
                // exactly how the fork's own app ships them.
                val outDir = File(jniLibsOut, abi)
                outDir.mkdirs()
                prootBin.copyTo(File(outDir, "libproot.so"), overwrite = true)
                loaderBin.copyTo(File(outDir, "libproot-loader.so"), overwrite = true)
                ptyLib.copyTo(File(outDir, "liblinuxpty.so"), overwrite = true)
                logger.lifecycle(
                    "packaged $abi: libproot.so + libproot-loader.so + liblinuxpty.so " +
                        "(proot fork @ $prootCommit)",
                )
            }
        }
    }

// Same contract as :freerdp: every variant compilation goes through preBuild,
// so lint, test and assemble all fail loudly when the native artifacts are
// missing rather than packaging an APK whose userspace cannot start.
tasks.named("preBuild") { dependsOn(buildLinuxNative) }
