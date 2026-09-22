package dev.eclipse.ssh.linux

import java.io.File
import java.nio.file.Files

/**
 * A stand-in for the APK's extracted native library directory: the two files the app's own runtime
 * execs, both present and both executable.
 *
 * Every test that lets [ProotRuntime] fork needs a directory like this one. The runtime refuses to
 * spawn when a component it is about to exec is missing or not executable — a fact about the
 * *installation*, not about the userspace (see [ProotRuntime.missingNativeComponents]) — so a
 * fixture naming a directory that holds nothing would have every fork in the suite fail with
 * `NativeRuntimeMissing` before the test's own subject was ever reached. A scripted spawner
 * replaces the forking; it does not replace the installation the forking checks, so these files
 * have to be real ones.
 *
 * Created once per JVM and shared: no test reads or writes the contents, so they are deliberately
 * empty — only the file mode matters, and it is asserted at creation rather than left to a
 * filesystem that might quietly ignore the bit.
 */
internal fun fakeNativeLibraryDir(): String = FakeNativeRuntime.dir

private object FakeNativeRuntime {

    val dir: String by lazy {
        val dir = Files.createTempDirectory("fake-native-runtime").toFile()
        // Registered before the files it holds: the JVM deletes registered paths in reverse order,
        // so the directory is removed last, after it is empty.
        dir.deleteOnExit()
        for (name in listOf("libproot.so", "libproot-loader.so")) {
            val component = File(dir, name)
            component.writeText("")
            component.deleteOnExit()
            check(component.setExecutable(true)) {
                "the test fixture could not mark ${component.path} executable"
            }
        }
        dir.path
    }
}
