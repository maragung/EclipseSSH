package dev.eclipse.ssh.linux

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.eclipse.ssh.di.LinuxUserspaceGraphProvider
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The deep-verification half of the Ubuntu E2E pipeline (android-ubuntu-e2e.yml): the install
 * itself is driven through the real UI by the adb driver, and THEN this test opens the very graph
 * the app uses — no Hilt wiring, no mocks, the same [LinuxUserspaceGraphProvider] the controller
 * reads — and proves the installed Ubuntu actually works by executing commands in it.
 *
 * Every command here runs through [ProotRuntime.runCommand] with the real session argv — the
 * exact code path the terminal UI's sessions take — so a passing test means the environment a
 * user gets is genuinely usable: shell, account, DNS, the package database, HTTP. This is the
 * difference the pipeline exists to enforce: "the UI said Installation completed" is not
 * evidence, a shell that answered is.
 *
 * ## Why an instrumentation arg gates every test
 *
 * The androidTest APK also carries the ordinary release suite, which runs on every PR and every
 * release validation. This test requires a userspace installed by the E2E driver first (its
 * phase A); in any other context it would fail on a fresh device for no defect at all. So the
 * runner skips it unless `-e ubuntuE2e true` is passed — a skipped test in the normal suite, a
 * full participant in the E2E pipeline only.
 *
 * ## Phases
 *
 * `-e e2ePhase write` (default): the deep verification plus writing the persistence markers.
 * `-e e2ePhase read`: run AFTER `am force-stop` + relaunch — reads the markers back, proving
 * the rootfs and workspace survived the app process dying, which is the persistence acceptance
 * criterion (a reboot of the *device* is the same on-disk fact and needs no separate test).
 */
@RunWith(AndroidJUnit4::class)
class UbuntuE2eVerificationTest {

    private val arguments get() = InstrumentationRegistry.getArguments()
    private val graphOrNull by lazy {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        LinuxUserspaceGraphProvider(context).graph
    }

    /**
     * A null graph means no ABI this device reports maps to an Ubuntu rootfs - a pipeline
     * misconfiguration, not a device defect.
     *
     * The message names the device's own ABIs rather than presuming the emulator's. It used to
     * assert the E2E emulator "must run an x86_64 APK", which was true of the only leg that
     * existed and would have been actively misleading on the arm64 leg the same catalog serves:
     * what is wrong is the pairing of APK and device, and only the device can say which ABIs it
     * offered. `versionsFor` is handed the whole list, so the whole list is what belongs here.
     */
    private val graph get() = checkNotNull(graphOrNull) {
        val deviceAbis = Build.SUPPORTED_ABIS.joinToString(", ")
        "GRAPH stage: no userspace graph for this device - it reports the ABIs [$deviceAbis] and " +
            "LinuxDistroCatalog maps none of them to a rootfs. The pipeline must install an APK " +
            "whose ABI the catalog carries (the per-ABI split for this device), not a build that " +
            "resolved to some other ABI"
    }

    @Before
    fun onlyInsideTheE2ePipeline() {
        assumeTrue("true" == arguments.getString("ubuntuE2e"))
    }

    private fun assumeWritePhase() {
        assumeTrue("read" != arguments.getString("e2ePhase"))
    }

    private fun assumeReadPhase() {
        assumeTrue("read" == arguments.getString("e2ePhase"))
    }

    /**
     * One real session command, run to completion with a per-command timeout. The message names
     * the stage so the pipeline's failure report can attribute the break to a phase, not just a
     * stack trace — and carries whatever the command printed before going silent, because "no
     * output" and "started printing then died" are different diagnoses (proot errors, shell
     * errors and DNS messages all live in that partial output).
     */
    private fun session(command: String, timeoutMs: Long = COMMAND_TIMEOUT_MS): ProotCommandResult {
        val runtime = graph.runtime
        val partial = StringBuilder()
        val result = runBlocking {
            runtime.runCommand(runtime.sessionArgv(command), env = runtime.baseEnv(),
                timeoutMs = timeoutMs) { chunk ->
                synchronized(partial) { partial.append(chunk.toString(Charsets.UTF_8)) }
            }
        }
        checkNotNull(result) {
            val printed = synchronized(partial) { partial.toString() }
            "UBUNTU SHELL stage: '$command' did not answer within ${timeoutMs / 1000}s" +
                (if (printed.isBlank()) " and printed nothing at all"
                else " but printed this before going silent: ${printed.take(2000)}")
        }
        return result
    }

    private fun sessionSucceeds(command: String, timeoutMs: Long = COMMAND_TIMEOUT_MS): String {
        val result = session(command, timeoutMs)
        check(result.exitCode == 0) {
            "UBUNTU SHELL stage: '$command' exited ${result.exitCode}: ${result.outputText().take(2000)}"
        }
        return result.outputText()
    }

    @Test
    fun installedStateIsVerifiedStopped() {
        assumeWritePhase()
        val manager = graph.manager
        val state = manager.state.value
        check(state is LinuxUserspaceState.Stopped) {
            "STATE stage: expected Stopped (installed and health-probed), was $state"
        }
    }

    /**
     * The A/B for a wedged pty path: the very same libproot.so, rootfs and environment the
     * sessions use, but exec'd straight through [ProcessBuilder] — no pty pair, no fork of our
     * own, nothing of [LinuxPty] in the process. When pty-backed commands hang but this passes,
     * the break is in the pty bridge's child (the fork-side code path); when this hangs too,
     * proot itself does not run on this ABI/emulator combination. Either verdict names the next
     * fix precisely, which is why it lives here rather than in a one-off manual probe.
     */
    @Test
    fun prootExecsOutsideThePtyBridge() {
        assumeWritePhase()
        val runtime = graph.runtime
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir)
        val argv = listOf(
            File(nativeLibraryDir, "libproot.so").absolutePath,
            "--rootfs=${runtime.rootfsDir.absolutePath}",
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-w", runtime.homePath,
            "/bin/bash", "--login", "-c", "echo DIRECT_EXEC_OK",
        )
        val process = ProcessBuilder(argv).apply {
            environment().clear()
            environment()["PROOT_LOADER"] = File(nativeLibraryDir, "libproot-loader.so").absolutePath
            environment()["PROOT_TMP_DIR"] = runtime.tmpDir.absolutePath
            environment()["HOME"] = runtime.homePath
            environment()["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
            environment()["TERM"] = "xterm-256color"
            environment()["LANG"] = "C.UTF-8"
            // directory() returns ProcessBuilder (builder pattern), so Kotlin exposes no
            // `directory` property - assignment does not compile, the call does.
            directory(runtime.spawnCwd)
            redirectErrorStream(true)
        }.start()
        val finished = process.waitFor(DIRECT_EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            // Kill first: reading the output stream of a live process blocks until it exits,
            // and a wedged proot would turn the diagnostic itself into the hang it diagnoses.
            process.destroy()
            process.waitFor(5, TimeUnit.SECONDS)
            check(false) {
                "DIRECT-EXEC A/B stage: proot exec'd without the pty bridge also failed to answer " +
                    "within ${DIRECT_EXEC_TIMEOUT_SECONDS}s - the defect is in proot on this " +
                    "ABI/emulator, not in the pty bridge"
            }
        }
        // Plain java.io reads, not readBytes(): the minified app APK carries only the
        // kotlin.io symbols the app itself uses, and the instrumentation APK resolves
        // against it at runtime - readBytes() was stripped once already (NoClassDefFoundError:
        // kotlin/io/ByteStreamsKt) and took the A/B verdict down with it.
        val collected = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            val n = process.inputStream.read(chunk)
            if (n < 0) break
            collected.write(chunk, 0, n)
        }
        val output = String(collected.toByteArray(), Charsets.UTF_8)
        check(process.exitValue() == 0 && "DIRECT_EXEC_OK" in output) {
            "DIRECT-EXEC A/B stage: proot exec'd without the pty bridge exited " +
                "${process.exitValue()} without the marker (output: ${output.take(2000)})"
        }
    }

    @Test
    fun healthProbePasses() {
        assumeWritePhase()
        val health = runBlocking { graph.manager.refreshHealth() }
        check(health.healthy) {
            "HEALTH stage: ${health.describe()}"
        }
    }

    @Test
    fun shellExecutesAMarkerCommand() {
        assumeWritePhase()
        val output = sessionSucceeds("echo TEST_OK")
        check("TEST_OK" in output) { "SHELL stage: echo did not print TEST_OK, got: ${output.take(500)}" }
    }

    @Test
    fun osReleaseIsUbuntu() {
        assumeWritePhase()
        val output = sessionSucceeds("cat /etc/os-release")
        // Not pinned to a codename: the version chooser offers every LTS, so the contract is
        // "an Ubuntu", whichever series the driver let the UI pick.
        check("ID=ubuntu" in output && "Ubuntu" in output) {
            "OS-RELEASE stage: /etc/os-release is not Ubuntu: ${output.take(500)}"
        }
    }

    @Test
    fun sessionRunsAsTheUbuntuAccount() {
        assumeWritePhase()
        val whoami = sessionSucceeds("whoami").trim()
        check(whoami == "ubuntu") { "ACCOUNT stage: whoami is '$whoami', expected 'ubuntu' (never root)" }
    }

    @Test
    fun workingDirectoryIsTheUbuntuHome() {
        assumeWritePhase()
        val pwd = sessionSucceeds("pwd").trim()
        check(pwd == "/home/ubuntu") { "WORKDIR stage: pwd is '$pwd', expected /home/ubuntu" }
    }

    @Test
    fun kernelReportsLinuxOnTheDeviceArchitecture() {
        assumeWritePhase()
        val output = sessionSucceeds("uname -a")
        check("Linux" in output) { "KERNEL stage: uname did not report Linux: ${output.take(500)}" }
    }

    @Test
    fun dnsResolvesFromInsideTheRootfs() {
        assumeWritePhase()
        sessionSucceeds("getent hosts ubuntu.com", DNS_TIMEOUT_MS)
    }

    @Test
    fun packageManagerDatabaseIsConsistent() {
        assumeWritePhase()
        sessionSucceeds("apt-get check", APT_TIMEOUT_MS)
    }

    @Test
    fun httpReachesTheInternet() {
        // curl is a base package the setup pipeline installs, so this also proves the base-package
        // install really happened rather than the setup merely claiming it did.
        assumeWritePhase()
        sessionSucceeds("curl -I https://example.com", HTTP_TIMEOUT_MS)
    }

    @Test
    fun hardLinksShareOneInode() {
        assumeWritePhase()
        // Patch 0004's contract, probed the way shadow(1) probes it: hard-link two names together
        // and ask each how many links it has. Android's app seccomp filter refuses link(2), so the
        // fork emulates it by copying the bytes — two inodes of one link each — and every tool that
        // takes a lock with a hard link (adduser, groupadd, passwd: they link <db> to <db>.lock and
        // verify by counting links) reads that copy as a lock already held. Two names, one inode,
        // two links is what a real link looks like, and it is what let openssh-client's postinst
        // lock /etc/group at all.
        //
        // ONE session, not four. The patch's record of the pair lives in the proot process that made
        // the link, so a stat(2) served by the *next* proot invocation finds an empty table and
        // answers with the kernel's copy — one link each. Splitting these commands into separate
        // sessions is how this test first failed (run 35100526297: "test a -ef b exited 1"), and the
        // failure was the test's, not the patch's: shadow takes its lock and verifies it inside one
        // process, which is the case the patch is built for and the only one it claims.
        //
        // `test -ef` and `stat -c '%h %i'` are deliberately both asked: -ef goes through the shell's
        // own stat (glibc's newfstatat) and coreutils' stat goes through statx, so the two syscall
        // paths the patch corrects are both exercised.
        val probe = sessionSucceeds(
            "rm -rf /tmp/link-probe && mkdir -p /tmp/link-probe && printf x > /tmp/link-probe/a && " +
                "ln /tmp/link-probe/a /tmp/link-probe/b && " +
                // Not chained with &&: a 'no' answer must still reach the stat below, so the failure
                // message can carry what the filesystem actually said instead of an empty output.
                "{ test /tmp/link-probe/a -ef /tmp/link-probe/b && echo same-file=yes || echo same-file=no; } && " +
                "stat -c 'links=%h inode=%i' /tmp/link-probe/a /tmp/link-probe/b",
        )
        check(probe.contains("same-file=yes")) {
            "LINK stage: the shell does not see the two names as one file, so shadow(1) would read " +
                "its own lock as held: ${probe.take(500)}"
        }
        // Printed as well as asserted: the assertion is the verdict, but the device's own numbers
        // belong in the artifact, where a reader can check them without trusting this test. The
        // instrumentation transcript (instrument-write.txt) is the only surviving output of a run.
        println("LINK PROBE: ${probe.trim()}")

        // Plain String operations, not Regex(...).find(...) + MatchResult.groupValues: the test APK
        // resolves its Kotlin stdlib against the MINIFIED app APK, which carries only the members
        // the app itself reaches (proguard-instrumentation.pro lists exactly which), and
        // MatchResult.getGroupValues was not among them - every app call site had been devirtualized,
        // so R8 removed it from the interface. The call threw NoSuchMethodError on run 35106576845,
        // at the line below, AFTER the probe above had already succeeded; the same class of strip
        // the readBytes() note further up this file records.
        val rows = probe.lines()
            .map { it.trim() }
            .filter { it.startsWith("links=") }
        check(rows.size == 2) {
            "LINK stage: expected 'stat' to report two 'links=<n> inode=<n>' rows, got: ${probe.take(500)}"
        }
        // The numbers stay strings - only equality is asserted (same inode, two links each), and an
        // inode is not obliged to fit in an Int on every filesystem. toLongOrNull is just the
        // shape check: a row that is not a number must fail here, not compare unequal later.
        val links = rows.map { it.removePrefix("links=").substringBefore(' ') }
        val inodes = rows.map { it.substringAfter("inode=") }
        check(links.all { it.toLongOrNull() != null } && inodes.all { it.toLongOrNull() != null }) {
            "LINK stage: 'stat' printed rows this probe cannot read: ${probe.take(500)}"
        }
        check(links[0] == "2" && links[1] == "2") {
            "LINK stage: the two names report ${links[0]} and ${links[1]} links, expected 2 each — " +
                "the link was emulated as a byte copy, so shadow(1) would read its own lock as held: ${probe.take(500)}"
        }
        check(inodes[0] == inodes[1]) {
            "LINK stage: the two names report inodes ${inodes[0]} and ${inodes[1]}, expected one inode: ${probe.take(500)}"
        }
    }

    @Test
    fun persistenceMarkersAreWritten() {
        assumeWritePhase()
        // Two markers on purpose: /tmp proves the rootfs tree itself persists across an app
        // restart, the workspace proves the directory the UI promises survives ("kept by Stop
        // and Restart") keeps its bytes too.
        sessionSucceeds("mkdir -p /tmp/e2e-test && echo persistent-test > /tmp/e2e-test/result.txt")
        sessionSucceeds("echo workspace-test > /home/ubuntu/workspace/e2e-marker.txt")
        check(sessionSucceeds("cat /tmp/e2e-test/result.txt").contains("persistent-test")) {
            "PERSISTENCE stage: the marker was not readable right after writing it"
        }
    }

    @Test
    fun persistenceMarkersSurviveAppRestart() {
        assumeReadPhase()
        val tmp = sessionSucceeds("cat /tmp/e2e-test/result.txt")
        check("persistent-test" in tmp) {
            "PERSISTENCE stage: /tmp/e2e-test/result.txt did not survive the app restart: ${tmp.take(500)}"
        }
        val workspace = sessionSucceeds("cat /home/ubuntu/workspace/e2e-marker.txt")
        check("workspace-test" in workspace) {
            "PERSISTENCE stage: the workspace marker did not survive the app restart: ${workspace.take(500)}"
        }
    }

    companion object {
        private val COMMAND_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(120)
        private val DNS_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(60)
        private val APT_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(180)
        private val HTTP_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(90)
        private const val DIRECT_EXEC_TIMEOUT_SECONDS = 30L
    }
}
