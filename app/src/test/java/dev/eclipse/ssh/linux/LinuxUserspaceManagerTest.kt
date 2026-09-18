package dev.eclipse.ssh.linux

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The userspace state machine, driven end to end over the real components: a real rootfs fixture
 * extracted by the real installer, a real setup pipeline running against a scripted proot, and the
 * real health probe deciding every transition.
 *
 * What these tests pin down is the feature's load-bearing promise: the state machine reaches
 * "installed" only through a passing health check, never presents a broken install as healthy, and
 * keeps the user's workspace across a keep-workspace uninstall. The host-list card trusts the
 * state; if these invariants slip, the card lies.
 */
class LinuxUserspaceManagerTest {

    private class Harness(
        distro: LinuxDistro,
        // Overridable because the stale-flag test needs the root to exist - with its state file
        // already written - before the manager is constructed: initialState() reads the flag at
        // construction, not on demand.
        val rootDir: File = Files.createTempDirectory("linux-userspace").toFile().apply { deleteOnExit() },
    ) {
        val spawner = ScriptedPtySpawner()
        var downloads = 0
        val runtime = ProotRuntime(rootDir, "/fake/native/lib", spawner)
        val installer =
            RootfsInstaller(rootDir, distro, downloader = { _, target, onChunk ->
                downloads++
                TestTarballs.serving(FIXTURE).download("https://fixtures.invalid/rootfs.tar.gz", target, onChunk)
            })
        val distribution = UbuntuDistributionManager(distro, runtime, appUid = 10150, appGid = 10150)
        val processes = LinuxProcessManager()
        val workspace = LinuxWorkspaceManager(runtime)
        val backupFile = File(rootDir.parentFile, "${rootDir.name}-workspace-backup.tar.gz")
        val manager = LinuxUserspaceManager(
            rootDir,
            distro,
            runtime,
            installer,
            distribution,
            processes,
            workspace,
            backupFile,
        )
    }

    companion object {
        private val FIXTURE: File by lazy {
            TestTarballs.writeRootfsFixture(
                Files.createTempDirectory("linux-userspace-fixture").toFile().resolve("rootfs.tar.gz"),
            )
        }

        private fun newHarness(): Harness =
            Harness(TestTarballs.fixtureDistro("https://fixtures.invalid/rootfs.tar.gz", TestTarballs.sha256(FIXTURE)))
    }

    @Test
    fun `install runs the whole pipeline and lands on Stopped`() = runTest {
        val harness = newHarness()
        // Observe the endpoints of the trajectory, not its middle: StateFlow conflates, so
        // intermediate Installing steps are not deterministically observable by a collector -
        // their content is pinned by RootfsInstallerTest's progress assertions instead. The first
        // value a fresh collector receives is the current one, which is NotInstalled here.
        val states = mutableListOf<LinuxUserspaceState>()
        val observer = launch { harness.manager.state.collect { states += it } }
        // Run the observer to its first suspension before install() starts. install suspends on
        // real IO, and the work runner starts queued coroutines while the body waits there - so
        // without this flush the observer's first dispatch happens after the state has already
        // advanced to Installing, and it never sees the NotInstalled it exists to pin.
        testScheduler.runCurrent()

        val report = harness.manager.install()
        testScheduler.advanceUntilIdle()

        assertThat(harness.manager.state.value).isEqualTo(LinuxUserspaceState.Stopped)
        assertThat(report.warnings).isEmpty()
        assertThat(harness.downloads).isEqualTo(1)

        // The account: the app uid registered as ubuntu, never root.
        val passwd = harness.installer.rootfsDir.resolve("etc/passwd").readText()
        assertThat(passwd).contains("ubuntu:x:10150:10150:Ubuntu:/home/ubuntu:/bin/bash")
        // The group and shadow entries replaced any shipped ubuntu line rather than duplicating it.
        val group = harness.installer.rootfsDir.resolve("etc/group").readText()
        assertThat(group.lines().count { it.startsWith("ubuntu:") }).isEqualTo(1)

        // The archive matches the architecture: arm64 gets the ports archive, not amd64's.
        val sources = harness.installer.rootfsDir.resolve("etc/apt/sources.list").readText()
        assertThat(sources).contains("ports.ubuntu.com/ubuntu-ports")

        // DNS is a real file, not the rootfs's dangling systemd symlink.
        val resolv = harness.installer.rootfsDir.resolve("etc/resolv.conf").readText()
        assertThat(resolv.lines().first()).isEqualTo("nameserver 1.1.1.1")

        assertThat(harness.installer.rootfsDir.resolve("home/ubuntu/workspace").isDirectory).isTrue()

        // The setup pipeline really ran through the scripted proot: package lists updated, the
        // base packages installed, and both ran with fake root (the "-0" proot sessions need for dpkg).
        // The happy path never leaves the ladder's first rung - the primary archive succeeds and no
        // mirror is fetched - which is what pins the flags every rung carries.
        val rootCommands = harness.spawner.commandsWith.filter { it.first }
        assertThat(rootCommands).isNotEmpty()
        assertThat(rootCommands.map { it.second }).contains(
            "apt-get update -o Acquire::Retries=3 -o Acquire::Languages=none -o Acquire::http::Timeout=30",
        )
        assertThat(
            rootCommands.map { it.second }.any { it.startsWith("apt-get install -y --no-install-recommends") },
        ).isTrue()

        // The probe is the only thing that runs without fake root, and it is what gated the state.
        val probeCommands = harness.spawner.commandsWith.filterNot { it.first }
        assertThat(probeCommands.map { it.second }).contains("whoami")

        assertThat(states.first()).isEqualTo(LinuxUserspaceState.NotInstalled)
        assertThat(states.last()).isEqualTo(LinuxUserspaceState.Stopped)

        observer.cancel()
    }

    @Test
    fun `start verifies health, stop closes every session`() = runTest {
        val harness = newHarness()
        harness.manager.install()

        // A live session: a real local channel over a scripted pty, as the terminal tab opens one.
        val process = FakePtyProcess()
        val channel = LocalTerminalChannel(process)
        harness.processes.register("session-1", channel)
        assertThat(harness.manager.sessionCount.value).isEqualTo(1)

        harness.manager.start()
        assertThat(harness.manager.state.value).isInstanceOf(LinuxUserspaceState.Running::class.java)

        harness.manager.stop()
        assertThat(harness.manager.state.value).isEqualTo(LinuxUserspaceState.Stopped)
        // Stop closed the session - the one path where ending a terminal is logout, because the
        // user asked for the whole userspace to stop.
        assertThat(harness.manager.sessionCount.value).isEqualTo(0)
        assertThat(channel.hasEnded).isTrue()
    }

    @Test
    fun `an unhealthy probe lands on NeedsRepair instead of Running`() = runTest {
        val harness = newHarness()
        harness.manager.install()

        // Break the one thing the probe trusts: whoami answers root, which would mean the
        // never-root-by-default contract is broken even though every command still exits 0.
        // Everything else stays healthy, so NeedsRepair names the account and nothing else.
        val healthyRespond = harness.spawner.respond
        harness.spawner.respond = { command ->
            if (command == "whoami") 0 to "root\n" else healthyRespond(command)
        }

        harness.manager.start()
        assertThat(harness.manager.state.value)
            .isInstanceOf(LinuxUserspaceState.NeedsRepair::class.java)
        assertThat((harness.manager.state.value as LinuxUserspaceState.NeedsRepair).detail)
            .contains("'root'")

        // Repair runs the pipeline again over the extracted rootfs - without re-downloading it.
        harness.spawner.respond = healthyRespond
        harness.manager.repair()
        assertThat(harness.manager.state.value).isEqualTo(LinuxUserspaceState.Stopped)
        assertThat(harness.downloads).isEqualTo(1)

        // And with health restored, start now reaches Running.
        harness.manager.start()
        assertThat(harness.manager.state.value).isInstanceOf(LinuxUserspaceState.Running::class.java)
    }

    @Test
    fun `a keep-workspace uninstall snapshots and the next install restores it`() = runTest {
        val harness = newHarness()
        harness.manager.install()
        val projects = harness.workspace.workspaceDir.resolve("projects")
        projects.mkdirs()
        projects.resolve("site.txt").writeText("the user's work\n")

        harness.manager.uninstall(keepWorkspace = true)

        assertThat(harness.manager.state.value).isEqualTo(LinuxUserspaceState.NotInstalled)
        assertThat(harness.installer.rootfsDir.exists()).isFalse()
        // The backup lives outside the userspace root, or the uninstall would destroy the thing it
        // was keeping.
        assertThat(harness.manager.hasPendingWorkspaceBackup()).isTrue()

        harness.manager.install()

        assertThat(harness.manager.state.value).isEqualTo(LinuxUserspaceState.Stopped)
        assertThat(harness.workspace.workspaceDir.resolve("projects/site.txt").readText())
            .isEqualTo("the user's work\n")
        // The backup is consumed by the restore, not left to shadow newer work later.
        assertThat(harness.manager.hasPendingWorkspaceBackup()).isFalse()
    }

    @Test
    fun `a delete-workspace uninstall removes the backup too`() = runTest {
        val harness = newHarness()
        harness.manager.install()
        val projects = harness.workspace.workspaceDir.resolve("projects")
        projects.mkdirs()
        projects.resolve("site.txt").writeText("disposable\n")

        harness.manager.uninstall(keepWorkspace = false)

        assertThat(harness.manager.state.value).isEqualTo(LinuxUserspaceState.NotInstalled)
        assertThat(harness.manager.hasPendingWorkspaceBackup()).isFalse()
        assertThat(harness.installer.rootfsDir.exists()).isFalse()
    }

    @Test
    fun `a fresh harness starts NotInstalled, and a stale flag cannot fake an install`() = runTest {
        val harness = newHarness()
        assertThat(harness.manager.state.value).isEqualTo(LinuxUserspaceState.NotInstalled)

        // Crash recovery's dark twin: the state file says installed, the filesystem says otherwise
        // (here: never extracted at all). The manager must not present that as a healthy install -
        // NeedsRepair is what the settings screen turns into a Repair button. The root and its
        // flag exist before the harness, because the classification runs at construction.
        val staleRoot = Files.createTempDirectory("linux-userspace-stale").toFile().apply { deleteOnExit() }
        staleRoot.resolve("state.properties").writeText("installed=true\ndistroId=ubuntu-22.04\n")
        val stale = Harness(
            TestTarballs.fixtureDistro("https://fixtures.invalid/rootfs.tar.gz", TestTarballs.sha256(FIXTURE)),
            staleRoot,
        )
        assertThat(stale.manager.state.value).isInstanceOf(LinuxUserspaceState.NeedsRepair::class.java)
    }

    @Test
    fun `an interrupted install comes back as NeedsRepair and the retry reclaims its staging`() = runTest {
        // The crash shape: the installing marker was written, then the process died
        // mid-extraction - a junk staging tree, no rootfs. Construction classifies it as
        // repairable (never as a healthy install), and the install that follows reclaims the
        // staging tree and completes.
        val interruptedRoot =
            Files.createTempDirectory("linux-userspace-interrupted").toFile().apply { deleteOnExit() }
        interruptedRoot.resolve("state.properties")
            .writeText("installing=true\ndistroId=ubuntu-22.04\nphase=download\nstartedAtMs=1\n")
        interruptedRoot.resolve("rootfs.staging/etc").mkdirs()
        interruptedRoot.resolve("rootfs.staging/etc/half-written.conf").writeText("truncated by the crash\n")
        val harness = Harness(
            TestTarballs.fixtureDistro("https://fixtures.invalid/rootfs.tar.gz", TestTarballs.sha256(FIXTURE)),
            interruptedRoot,
        )

        val state = harness.manager.state.value
        assertThat(state).isInstanceOf(LinuxUserspaceState.NeedsRepair::class.java)
        assertThat((state as LinuxUserspaceState.NeedsRepair).detail).contains("interrupted")

        harness.manager.install()

        assertThat(harness.manager.state.value).isEqualTo(LinuxUserspaceState.Stopped)
        assertThat(harness.installer.rootfsDir.resolve("bin/bash").isFile).isTrue()
        // The junk tree is gone - reclaimed by the real extraction - and the run's bookkeeping
        // cleaned itself up: no in-flight marker left for the next construction to misread, no
        // lock left to refuse the next install.
        assertThat(interruptedRoot.resolve("rootfs.staging").exists()).isFalse()
        assertThat(interruptedRoot.resolve("state.properties").readText()).doesNotContain("installing=true")
        assertThat(interruptedRoot.resolve("install.lock").exists()).isFalse()
    }

    @Test
    fun `every proot spawn carries the loader, the tmp dir and the binds`() = runTest {
        val harness = newHarness()
        harness.manager.install()
        harness.runtime.spawnSession(40, 120)

        val spawns = harness.spawner.spawns
        assertThat(spawns).isNotEmpty()
        for (spawn in spawns) {
            // The exec model, pinned per spawn: the binary and loader come from nativeLibraryDir
            // (the only execve-able directory for this app), the rootfs is the shared path, and
            // /dev, /proc and /sys are bound because the Ubuntu Base rootfs ships them empty.
            assertThat(spawn.argv.first()).isEqualTo("/fake/native/lib/libproot.so")
            assertThat(spawn.argv)
                .contains("--rootfs=${harness.rootDir.resolve("rootfs").absolutePath}")
            for (bind in listOf("/dev", "/proc", "/sys")) {
                val index = spawn.argv.indexOf(bind)
                assertThat(spawn.argv[index - 1]).isEqualTo("-b")
            }
            // PROOT_LOADER must point into nativeLibraryDir, or proot extracts its embedded
            // loader into PROOT_TMP_DIR - under filesDir, never executable - and dies with EACCES.
            // PROOT_TMP_DIR names the directory RuntimeStorageManager creates and probes.
            assertThat(spawn.envp).contains("PROOT_LOADER=/fake/native/lib/libproot-loader.so")
            assertThat(spawn.envp)
                .contains("PROOT_TMP_DIR=${harness.rootDir.resolve("tmp").absolutePath}")
            // Blocked-syscall diagnostics land in a file this app can write (the fork's default
            // points at the fork's own app's cache — see linux/proot-patches/0002).
            assertThat(spawn.envp)
                .contains("PROOT_SIGSYS_LOG=${harness.rootDir.resolve("sigsys-log.txt").absolutePath}")
        }
        // Sessions never run fake root; the setup pipeline's scripted commands do (dpkg's chowns).
        assertThat(spawns.last().argv.contains("-0")).isFalse()
        assertThat(spawns.any { it.argv.contains("-0") }).isTrue()
    }

    @Test
    fun `a command that never finishes is closed at its timeout, not leaked`() = runBlocking {
        val root = Files.createTempDirectory("proot-timeout").toFile().apply { deleteOnExit() }
        val process = BlockingPtyProcess()
        val runtime = ProotRuntime(root, "/fake/native/lib", spawner = { _, _, _, _, _ -> process })

        val result = runtime.runCommand(listOf("proot"), timeoutMs = 200)

        // The timeout fired, the child was SIGHUPed, and — the part the old blocking read loop
        // could not do — the blocked read actually ended, so the call returned instead of hanging
        // the install with the pty slot held.
        assertThat(result).isNull()
        assertThat(process.closed).isTrue()
        assertThat(process.readReturned).isTrue()
    }

    @Test
    fun `a reader that no close can wake is abandoned, not awaited forever`() = runBlocking {
        val root = Files.createTempDirectory("proot-abandon").toFile().apply { deleteOnExit() }
        val process = UnwakeablePtyProcess()
        val runtime = ProotRuntime(
            root,
            "/fake/native/lib",
            spawner = { _, _, _, _, _ -> process },
            readerDrainTimeoutMs = 200,
        )

        // The kernel-truth worst case: the timeout closed the pty but the parked read never
        // returned — the 2026-09 install hang's exact shape, a child that died before opening
        // the slave so no hangup ever came. The call must still come back (bounded failure)
        // instead of holding the install on "Configuring DNS". The parked reader thread leaks
        // for the rest of the JVM — accepted in production (a log line, not a hang) and
        // harmless in a test.
        val result = kotlinx.coroutines.withTimeout(10_000) {
            runtime.runCommand(listOf("proot"), timeoutMs = 200)
        }

        assertThat(result).isNull()
        assertThat(process.closed).isTrue()
    }

    @Test
    fun `scripted commands are visible and killable while in flight`() = runBlocking {
        val root = Files.createTempDirectory("proot-kill").toFile().apply { deleteOnExit() }
        val process = BlockingPtyProcess()
        val runtime = ProotRuntime(root, "/fake/native/lib", spawner = { _, _, _, _, _ -> process })

        val command = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            runtime.runCommand(listOf("proot"), timeoutMs = 60_000)
        }
        // Wait until the child is genuinely parked in its read, not just spawned.
        assertThat(process.readEntered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue()
        assertThat(runtime.hasLiveScriptedProcesses()).isTrue()

        runtime.killScriptedProcesses()

        kotlinx.coroutines.withTimeout(5_000) { command.join() }
        assertThat(process.closed).isTrue()
        assertThat(runtime.hasLiveScriptedProcesses()).isFalse()
    }
}

/**
 * A [PtySpawner] that never forks: every proot command the runtime would run is answered from a
 * script, and recorded with whether it asked for fake root.
 *
 * The recording is the test's window into the exec model: scripted setup commands must carry `-0`
 * (dpkg's chowns need proot's fake root) while the health probe must not (the user-facing session
 * path is never fake root).
 */
internal class ScriptedPtySpawner : PtySpawner {
    /** Every spawn, as (ranWithFakeRoot, command). */
    val commandsWith = mutableListOf<Pair<Boolean, String>>()

    /** One spawn's full argument vector and environment. */
    data class SpawnRecord(val argv: List<String>, val envp: List<String>)

    /** Every spawn's full argv and environment — the exec-model invariant's evidence. */
    val spawns = mutableListOf<SpawnRecord>()

    /**
     * How to answer a command: exit code to output. Replace per test to break things.
     *
     * The default answers every marker the real pipeline asks: the distribution manager's runtime
     * smoke (`echo eclipse-runtime-ok`, the first proot execution in setup) and the health probe
     * (`whoami`, `echo eclipse-probe-ok`). A harness that never overrides respond therefore gets
     * a userspace that installs and probes clean — the shape every end-to-end test here wants.
     */
    var respond: (String) -> Pair<Int, String> = { command ->
        if (command == "whoami") {
            0 to "ubuntu\n"
        } else if (command == "echo eclipse-runtime-ok") {
            0 to "eclipse-runtime-ok\n"
        } else if (command.startsWith("echo eclipse-probe-ok")) {
            0 to "eclipse-probe-ok\n"
        } else {
            0 to ""
        }
    }

    override fun spawn(
        argv: List<String>,
        envp: List<String>,
        cwd: String,
        rows: Int,
        columns: Int,
    ): PtyProcess {
        spawns += SpawnRecord(argv, envp)
        val command = argv.lastOrNull() ?: ""
        val asRoot = argv.contains("-0")
        commandsWith += asRoot to command
        val (exit, output) = respond(command)
        return ScriptedPtyProcess(exit, output.toByteArray(Charsets.UTF_8))
    }
}

/** One scripted proot run: its output is handed out once, then the stream ends. */
private class ScriptedPtyProcess(
    private val exitCode: Int,
    private val output: ByteArray,
) : PtyProcess {
    private var served = false

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (served) return -1
        served = true
        if (output.isEmpty()) return -1
        val n = minOf(length, output.size)
        System.arraycopy(output, 0, buffer, offset, n)
        return n
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int): Int = length

    override fun resize(rows: Int, columns: Int) = Unit

    override fun awaitExit(): Int = exitCode

    override fun close() = Unit
}

/**
 * The wedged-command worst case: a child whose output never arrives, parked in a blocking read
 * that only a [PtyProcess.close] can wake — which is exactly what [ProotRuntime.runCommand]'s
 * timeout and [ProotRuntime.killScriptedProcesses] are supposed to do.
 */
internal class BlockingPtyProcess : PtyProcess {
    /** Counted down once read() is genuinely parked, so a test can wait out the spawn race. */
    val readEntered = CountDownLatch(1)
    private val wake = CountDownLatch(1)

    var closed = false
        private set

    /** True once the blocked read actually returned — the proof the timeout was real. */
    var readReturned = false
        private set

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        readEntered.countDown()
        wake.await()
        readReturned = true
        return -1
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int): Int = length

    override fun resize(rows: Int, columns: Int) = Unit

    override fun awaitExit(): Int = 0

    override fun close() {
        closed = true
        wake.countDown()
    }
}

/**
 * The wedge even a well-behaved close cannot fix: a child that died before opening the slave
 * generates no master-side hangup, so the parked read stays parked forever. Unlike
 * [BlockingPtyProcess], close() marks itself but never wakes the read — the kernel's own
 * behavior when the slave end was never opened.
 */
private class UnwakeablePtyProcess : PtyProcess {
    private val never = CountDownLatch(1)

    var closed = false
        private set

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        // No wake exists. The reader's only exits are the runtime abandoning it or the JVM dying.
        never.await()
        return -1
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int): Int = length

    override fun resize(rows: Int, columns: Int) = Unit

    override fun awaitExit(): Int = 0

    override fun close() {
        closed = true
    }
}
