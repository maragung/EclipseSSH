package dev.eclipse.ssh.linux

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import org.junit.Test

/**
 * The exec model's one non-negotiable flag: every proot run carries `-0`, sessions included.
 *
 * This is the regression test for a device report that took a userspace that *installed* and made
 * it unusable the moment the user typed in it: `apt install zip` ended in `dpkg: error: requested
 * operation requires superuser privilege` and `su - root` in `su: System error`. Both are one
 * fact — the shell was not uid 0. dpkg refuses to unpack as anyone else, and proot's fake identity
 * is all-or-nothing per process tree, so a session without `-0` has no way to ascend: the fix is
 * the flag on the session's own argv, not a smarter command.
 *
 * No test caught it because nothing asserted the argv a session is spawned with. The setup
 * pipeline's commands had `-0` (and were asserted), the health probe went through the session argv
 * and asked `whoami` — but the answer it got, `ubuntu`, was the *expected* one at the time, since
 * the app's own Android uid is registered under that name. The suite agreed with the bug. So what
 * is pinned here is the flag itself, on the argv, read back from the spawn seam.
 *
 * The `whoami` half of the same claim lives in `LinuxUserspaceManagerTest` (the probe now expects
 * `root`, and a userspace that answers anything else is NeedsRepair) and in the E2E pipeline,
 * which runs the real thing on a real emulator.
 */
class ProotRuntimeExecModelTest {

    private val spawner = ScriptedPtySpawner()

    private fun root(): File =
        Files.createTempDirectory("proot-exec-model").toFile().apply { deleteOnExit() }

    private fun runtime(): ProotRuntime = ProotRuntime(root(), "/fake/native/lib", spawner)

    /**
     * An interactive session — the argv the terminal tab actually forks — is fake root.
     *
     * Asserted on the recorded spawn rather than on `sessionArgv()` alone, so the whole path is
     * covered: the builder, the storage gate in front of it, and the spawner's own arguments.
     */
    @Test
    fun `an interactive session is spawned with fake root`() {
        val runtime = runtime()

        runtime.spawnSession(rows = 24, columns = 80)

        val argv = spawner.spawns.single().argv
        assertThat(argv).contains("-0")
        // Order matters to proot: the flag belongs before the bindings, which belong before the
        // shell. A `-0` that drifted after `/bin/bash` would be passed to bash instead.
        assertThat(argv.indexOf("-0")).isLessThan(argv.indexOf("-b"))
        assertThat(argv).containsExactly(
            "/fake/native/lib/libproot.so",
            "--rootfs=${runtime.rootfsDir.absolutePath}",
            "-0",
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-w", "/home/ubuntu",
            "/bin/bash", "--login",
        ).inOrder()
    }

    /**
     * A scripted command is fake root too, and rides as `bash --login -c`.
     *
     * One builder serves both paths, which is the point: the pipeline needed `-0` for dpkg's
     * ownership changes long before the session did, and the two disagreeing is what produced a
     * terminal that could not install anything.
     */
    @Test
    fun `a scripted command is spawned with fake root and runs through bash -c`() {
        val runtime = runtime()

        val argv = runtime.commandArgv("whoami")

        assertThat(argv).contains("-0")
        assertThat(argv.takeLast(2)).containsExactly("-c", "whoami").inOrder()
        assertThat(argv).containsExactlyElementsIn(runtime.sessionArgv("whoami")).inOrder()
    }

    /**
     * The session's home is `/home/ubuntu` even though its identity is root.
     *
     * The two are deliberately not the same account: the workspace, the editor and SFTP all live
     * under `/home/ubuntu`, so a shell that started in root's own home would be standing nowhere
     * near the user's files. Both halves are asserted — the `-w` proot starts the shell with, and
     * the `HOME` the environment carries — because a login shell reading `/etc/passwd` would
     * otherwise resolve it back to `/root`.
     */
    @Test
    fun `the session starts in the ubuntu home with HOME pinned to it`() {
        val runtime = runtime()

        runtime.spawnSession(rows = 24, columns = 80)

        val argv = spawner.spawns.single().argv
        assertThat(argv[argv.indexOf("-w") + 1]).isEqualTo("/home/ubuntu")
        assertThat(runtime.homePath).isEqualTo("/home/ubuntu")
        assertThat(runtime.workspacePath).isEqualTo("/home/ubuntu/workspace")
        assertThat(spawner.spawns.single().envp).contains("HOME=/home/ubuntu")
    }
}
