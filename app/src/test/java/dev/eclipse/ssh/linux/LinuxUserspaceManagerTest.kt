package dev.eclipse.ssh.linux

import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.ssh.SessionEnd
import java.io.File
import java.io.IOException
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
        supplementaryGids: () -> IntArray = { IntArray(0) },
    ) {
        val spawner = ScriptedPtySpawner()
        var downloads = 0

        /**
         * Makes every later download fail the way a device with no network fails one. Set after the
         * install, it is how a test drives the repair ladder into the one failure no rebuild can
         * fix — the archive itself — without also making the install impossible.
         */
        var downloadFails = false
        val runtime = ProotRuntime(rootDir, fakeNativeLibraryDir(), spawner)
        val installer =
            RootfsInstaller(rootDir, distro, downloader = { _, target, onChunk ->
                downloads++
                // An IOException, because that is what an HTTP fetch raises and what `download()`
                // types as PinnedArchiveUnavailable — the failure the ladder's gate reads.
                if (downloadFails) throw IOException("the network is down")
                TestTarballs.serving(FIXTURE).download("https://fixtures.invalid/rootfs.tar.gz", target, onChunk)
            })
        val distribution = UbuntuDistributionManager(
            distro,
            runtime,
            appUid = 10150,
            appGid = 10150,
            supplementaryGids = supplementaryGids,
            // Wired here as the graph wires it (LinuxUserspaceGraphProvider), because the setup
            // pipeline's prologue restores any of dpkg's own programs the rootfs has lost out of
            // the same pinned archive — and that restore is the repair the reported
            // "1 expected program not found in PATH" failure needs. A harness without it exercises
            // a distribution that can only warn about a missing `rm`, which is not the app.
            installer = installer,
            // Nothing listens on port 1, so the mirror feed is unreachable in a millisecond and the
            // apt-update ladder is the three archives [builtinMirrorUrls] names and no more. Only
            // the tests that drive an *exhausted* update ever read it, and they have to be hermetic:
            // the default endpoint is a real host, and a unit test that waited on it would be a test
            // of the build machine's network.
            mirrorListUrl = UNREACHABLE_MIRROR_FEED,
        )
        val processes = LinuxProcessManager()
        val workspace = LinuxWorkspaceManager(runtime)
        val backupFile = File(rootDir.parentFile, "${rootDir.name}-workspace-backup.tar.gz")

        /**
         * A device with room to spare, said out loud rather than measured — because the JVM's
         * unmocked `StatFs` answers 0, and 0 means *unknown*, and the ladder's disk rung runs on an
         * unknown reading exactly as it does on a short one. A harness that took the default probe
         * would therefore have every repair begin by emptying apt's lists and the packages already
         * downloaded, which is the behaviour these tests exist to pin down the *absence* of: the
         * rungs below are only testable when the trees they argue about are still there when they
         * get to them. `RuntimeStorageManager.freeBytes`'s own doc says 0 is "do not gate on this",
         * and the disk rung's gate is what this is holding open.
         */
        val storage = RuntimeStorageManager(rootDir, freeBytesProbe = { ROOM_TO_SPARE })

        val manager = LinuxUserspaceManager(
            rootDir,
            distro,
            runtime,
            installer,
            distribution,
            processes,
            workspace,
            backupFile,
            storage = storage,
        )
    }

    companion object {
        /**
         * The repair fixture, not the bare one: an installed userspace has a package database and the
         * preserved trees, and two of these tests are about exactly what the ladder does with a
         * database that is there but unreadable. The `tmp` and `run` the local rung recreates are
         * deliberately absent here — a real install has them, and their absence is what makes the
         * rung's own work observable.
         */
        private val FIXTURE: File by lazy {
            TestTarballs.writeRepairFixture(
                Files.createTempDirectory("linux-userspace-fixture").toFile().resolve("rootfs.tar.gz"),
            )
        }

        internal const val UNREACHABLE_MIRROR_FEED = "http://127.0.0.1:1/mirrors.txt"

        /**
         * Far more than any rung of the ladder needs, so the disk rung's gate stays shut: see the
         * harness's own note on why the probe has to be answered rather than left to `StatFs`.
         */
        private const val ROOM_TO_SPARE = 64L * 1024 * 1024 * 1024

        private fun newHarness(supplementaryGids: () -> IntArray = { IntArray(0) }): Harness =
            Harness(
                TestTarballs.fixtureDistro("https://fixtures.invalid/rootfs.tar.gz", TestTarballs.sha256(FIXTURE)),
                supplementaryGids = supplementaryGids,
            )
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

        // The account entry is the app uid's own name, not the identity a session runs as: the
        // session is proot's fake root (see ProotRuntimeExecModelTest), and this line is what makes
        // `su - ubuntu` land back on the uid that really owns every file in the rootfs.
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
        // base packages installed, both with proot's `-0` — the identity dpkg insists on before it
        // will unpack anything.
        // The happy path never leaves the ladder's first rung - the primary archive succeeds and no
        // mirror is fetched - which is what pins the flags every rung carries.
        val commands = harness.spawner.commands
        assertThat(commands).contains(
            "apt-get update -o Acquire::Retries=3 -o Acquire::Languages=none -o Acquire::http::Timeout=30",
        )
        assertThat(
            commands.any { it.startsWith("apt-get install -y --no-install-recommends") },
        ).isTrue()

        // The probe ran too, and it is the user-facing path.
        assertThat(commands).contains("whoami")

        // And every proot run carried `-0`, the probe's included: a session that is not fake root
        // reports a non-zero uid, which is the state the user's own device was in when
        // `apt install` answered "requested operation requires superuser privilege" and `su` died
        // with a system error. Asserted over the raw argument vectors, so a builder that dropped
        // the flag for one caller cannot pass by leaving the summary looking right.
        assertThat(harness.spawner.spawns).isNotEmpty()
        assertThat(harness.spawner.spawns.filterNot { it.argv.contains("-0") }.map { it.argv }).isEmpty()

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

        // Break the one thing the probe trusts: whoami answers `ubuntu`, the app's own Android uid
        // rather than proot's fake root — a userspace whose sessions are not uid 0, where dpkg and
        // su cannot work, even though every command still exits 0. Everything else stays healthy,
        // so NeedsRepair names the identity and nothing else.
        val healthyRespond = harness.spawner.respond
        harness.spawner.respond = { command ->
            if (command == "whoami") 0 to "ubuntu\n" else healthyRespond(command)
        }

        harness.manager.start()
        assertThat(harness.manager.state.value)
            .isInstanceOf(LinuxUserspaceState.NeedsRepair::class.java)
        assertThat((harness.manager.state.value as LinuxUserspaceState.NeedsRepair).detail)
            .contains("'ubuntu'")

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
    fun `repair puts a program dpkg needs back, out of the pinned archive`() = runTest {
        val harness = newHarness()
        harness.manager.install()
        val rm = harness.installer.rootfsDir.resolve("usr/bin/rm")
        assertThat(rm.readText()).isEqualTo("fake rm\n")
        // The reported failure, in one deleted file: while `rm` is gone, every command in the setup
        // pipeline answers `dpkg: error: 1 expected program not found in PATH or not executable`,
        // and until the ladder existed that pipeline was the only repair the app had — so the one
        // state Repair was reported against was the one it could not clear. The program itself has
        // to come back, out of the archive the install came from, and the pipeline then runs over
        // the rootfs it just healed.
        rm.delete()
        // Something the user installed on top, which a rebuild would take with it. This repair is
        // not one, and this file is how the test says so.
        val userPackage = harness.installer.rootfsDir.resolve("usr/bin/user-package")
        userPackage.writeText("installed by the user\n")
        val downloadsBeforeRepair = harness.downloads

        harness.manager.repair()

        assertThat(harness.manager.state.value).isEqualTo(LinuxUserspaceState.Stopped)
        // Back, byte for byte and executable: the archive's own copy, not a placeholder.
        assertThat(rm.readText()).isEqualTo("fake rm\n")
        assertThat(rm.canExecute()).isTrue()
        assertThat(userPackage.readText()).isEqualTo("installed by the user\n")
        // And it cost the network nothing at all. This used to be one download, because the install
        // deleted the tarball once it was unpacked ("the tarball has served its purpose") and
        // putting one file back therefore meant fetching thirty-four megabytes for it. The archive
        // is kept for exactly this, and the assertion is +0 rather than +1 so that a future change
        // which starts fetching again fails here rather than at the user's data plan.
        assertThat(harness.downloads).isEqualTo(downloadsBeforeRepair)
    }

    @Test
    fun `repair restores a base file the pipeline does not look for, from the archive`() = runTest {
        val harness = newHarness()
        harness.manager.install()
        // A member of the archive that nothing in the pipeline asks about: the prologue stats the
        // fourteen programs dpkg runs, and a base system is a few thousand files. The rest is this
        // rung's - it compares the archive with what is on disk and writes back what is absent.
        val pad = harness.installer.rootfsDir.resolve("var/lib/rootfs-fixture.pad")
        assertThat(pad.isFile).isTrue()
        pad.delete()
        // The probe fails while the file is gone, which is the only way the ladder can be made to
        // climb past its first rung here: the pipeline itself succeeds whatever the filesystem
        // holds, because the proot behind it is scripted.
        val healthyRespond = harness.spawner.respond
        harness.spawner.respond = { command ->
            if (command == "whoami" && !pad.exists()) 0 to "ubuntu\n" else healthyRespond(command)
        }
        val downloadsBeforeRepair = harness.downloads

        harness.manager.repair()

        assertThat(harness.manager.state.value).isEqualTo(LinuxUserspaceState.Stopped)
        // Back, and the whole 3 MB of it: the archive's own bytes over the hole, not an empty file
        // standing in for one.
        assertThat(pad.length()).isEqualTo(3L * 1024 * 1024)
        // Written out of the archive the install kept, so the repair fetched nothing: see the test
        // above for why this is +0 and not +1.
        assertThat(harness.downloads).isEqualTo(downloadsBeforeRepair)
    }

    @Test
    fun `repair reclaims an interrupted extraction and keeps the rootfs that is there`() = runTest {
        val harness = newHarness()
        harness.manager.install()
        // The crash shape the ladder opens on: a whole rootfs, and a staging tree its extraction
        // never finished with. `isExtracted()` is false because of the leftover alone, so every
        // rung below reads the install as missing - the leftover goes first, and nothing else does.
        val staging = harness.rootDir.resolve("rootfs.staging")
        staging.resolve("etc").mkdirs()
        staging.resolve("etc/half-written.conf").writeText("truncated by the crash\n")
        val userPackage = harness.installer.rootfsDir.resolve("usr/bin/user-package")
        userPackage.writeText("installed by the user\n")
        val downloadsBeforeRepair = harness.downloads

        harness.manager.repair()

        assertThat(harness.manager.state.value).isEqualTo(LinuxUserspaceState.Stopped)
        assertThat(staging.exists()).isFalse()
        assertThat(harness.installer.rootfsDir.resolve("bin/bash").isFile).isTrue()
        assertThat(userPackage.readText()).isEqualTo("installed by the user\n")
        // The rootfs was already there and already whole: no archive was fetched, so no download.
        assertThat(harness.downloads).isEqualTo(downloadsBeforeRepair)
    }

    @Test
    fun `repair refuses to rebuild over a failure a rebuild cannot fix`() = runTest {
        val harness = newHarness()
        harness.manager.install()
        // A rootfs the probe calls broken (its `whoami` answers the app's own uid) and no network
        // to fetch the archive the deeper rungs read. Every one of them would hit that same dead
        // network, and the deepest would rewrite a working base system on the way to the same
        // failure - so the ladder has to stop, and hand back the typed failure it was given.
        val healthyRespond = harness.spawner.respond
        harness.spawner.respond = { command ->
            if (command == "whoami") 0 to "ubuntu\n" else healthyRespond(command)
        }
        val userPackage = harness.installer.rootfsDir.resolve("usr/bin/user-package")
        userPackage.writeText("installed by the user\n")
        // The archive has to go first, and that is the whole point of this test's premise rather
        // than a detail of it: an install *keeps* its verified tarball precisely so the deeper rungs
        // can write base bytes without the network, which means a device with nothing on disk to
        // rebuild from is the only one left to refuse. The sibling test below — "repair climbs to a
        // rebuild when nothing cheaper clears the fault, and keeps the work" — is the other side of
        // that line: it reaches the deepest rung and rebuilds out of the archive, fetching nothing.
        assertThat(harness.installer.releasePinnedArchive()).isGreaterThan(0L)
        harness.downloadFails = true

        val failure = runCatching { harness.manager.repair() }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(failure?.message).contains("the network is down")
        // Untouched: nothing re-extracted, nothing rewritten, and the packages the user installed
        // on top of the base system are still installed.
        assertThat(userPackage.readText()).isEqualTo("installed by the user\n")
        assertThat(harness.manager.state.value).isInstanceOf(LinuxUserspaceState.NeedsRepair::class.java)
    }

    @Test
    fun `repair climbs to a rebuild when nothing cheaper clears the fault, and keeps the work`() = runTest {
        val harness = newHarness()
        harness.manager.install()
        val projects = harness.workspace.workspaceDir.resolve("projects")
        projects.mkdirs()
        projects.resolve("site.txt").writeText("the user's work\n")
        // A fault only a rebuild removes: a file the archive never carried, standing in the rootfs
        // and keeping the probe failing. No cheaper rung can touch it - the essentials are all
        // present, so the prologue finds nothing to put back, and the overlay writes the archive's
        // own members over theirs without clearing anything the archive does not name. So the
        // ladder reaches its last rung, which is the one that costs the user something.
        val poison = harness.installer.rootfsDir.resolve("usr/bin/poison")
        poison.writeText("only a rebuild removes this\n")
        val healthyRespond = harness.spawner.respond
        harness.spawner.respond = { command ->
            if (command == "whoami" && poison.exists()) 0 to "ubuntu\n" else healthyRespond(command)
        }

        harness.manager.repair()

        assertThat(harness.manager.state.value).isEqualTo(LinuxUserspaceState.Stopped)
        // The rootfs is not the one that was there, and the fault went with it.
        assertThat(poison.exists()).isFalse()
        assertThat(harness.installer.rootfsDir.resolve("bin/bash").isFile).isTrue()
        // And the price was the base system alone: the work was parked before the rebuild and put
        // back after it, which is the one thing this rung must never cost.
        assertThat(harness.workspace.workspaceDir.resolve("projects/site.txt").readText())
            .isEqualTo("the user's work\n")
        assertThat(harness.manager.hasPendingWorkspaceBackup()).isFalse()
    }

    @Test
    fun `a stale package lock is cleared before the first package step, and the repair stays local`() = runTest {
        val harness = newHarness()
        harness.manager.install()
        val lock = harness.installer.rootfsDir.resolve("var/lib/dpkg/lock-frontend")
        lock.parentFile?.mkdirs()
        lock.writeText("")
        // Something the user installed on top of the base system: a rebuild would take it with it, and
        // this repair is not one.
        val userPackage = harness.installer.rootfsDir.resolve("usr/bin/user-package")
        userPackage.writeText("installed by the user\n")
        // And apt's own trees, at the size a real device has them: an index apt has already fetched
        // and a package it has already downloaded. This repair has nothing to do with either, and it
        // is the repair that used to take both — a full re-index plus every cached `.deb`, on the
        // user's connection, to answer a marker on disk.
        val index = harness.installer.rootfsDir.resolve("var/lib/apt/lists/fixture_Packages")
        index.parentFile?.mkdirs()
        index.writeText("Package: fixture\n")
        val cachedDeb = harness.installer.rootfsDir.resolve("var/cache/apt/archives/fixture.deb")
        cachedDeb.parentFile?.mkdirs()
        cachedDeb.writeText("already downloaded\n")
        val downloadsBeforeRepair = harness.downloads

        // What the real userspace does with that file on disk: every dpkg and apt command refuses in
        // about a second, in a sentence about "another process" that is not true of anything. Scripted
        // so the test fails if even one package step ever meets the lock — the count is asserted below.
        var refusals = 0
        val healthyRespond = harness.spawner.respond
        harness.spawner.respond = { command ->
            if (lock.exists() && (command.contains("dpkg") || command.contains("apt-get"))) {
                refusals++
                100 to "E: Could not get lock $lock. It is held by process 1234 (apt-get)\n"
            } else {
                healthyRespond(command)
            }
        }

        val report = harness.manager.repair()

        assertThat(harness.manager.state.value).isEqualTo(LinuxUserspaceState.Stopped)
        // Gone before anything that would have refused on it. Without the local rung the ladder would
        // have spent all four of its rungs on this one refusal and ended by rebuilding the rootfs.
        assertThat(lock.exists()).isFalse()
        assertThat(refusals).isEqualTo(0)
        assertThat(report.warnings.joinToString("\n")).contains("cleared 1 package-manager lock(s)")
        // Nothing was downloaded and nothing was rebuilt: the failure was a marker on disk, not the
        // base system, and the repair that clears it costs the user no network at all.
        assertThat(userPackage.readText()).isEqualTo("installed by the user\n")
        assertThat(harness.downloads).isEqualTo(downloadsBeforeRepair)
        // Including apt's trees, which this repair never named and therefore never touched. This is
        // the whole of "every press of Repair re-downloads the world": the old rung emptied both of
        // these before the setup run, on every failure there was.
        assertThat(index.readText()).isEqualTo("Package: fixture\n")
        assertThat(cachedDeb.readText()).isEqualTo("already downloaded\n")
    }

    @Test
    fun `scratch directories a gone proot left are cleared by a repair, and it says how many`() = runTest {
        val harness = newHarness()
        harness.manager.install()
        // One directory per launch: what a proot that exits normally left behind before patch 0006,
        // holding the loader link its teardown could not remove. The pid is one nothing here runs,
        // which is the whole of what makes it litter rather than a live session's.
        val gone = TestPids.nothingRuns()
        val leaked = File(harness.runtime.tmpDir, "exec-$gone-bervFx").apply { mkdirs() }
        File(leaked, "bash").writeText("")
        val downloadsBeforeRepair = harness.downloads

        val report = harness.manager.repair()

        assertThat(harness.manager.state.value).isEqualTo(LinuxUserspaceState.Stopped)
        assertThat(leaked.exists()).isFalse()
        assertThat(report.warnings.joinToString("\n"))
            .contains("cleared 1 leftover proot scratch directory")
        assertThat(harness.distribution.diagnostics.export())
            .contains("cleared leftover proot scratch directories")
        // Local, and therefore free: the litter cost the user no network at all.
        assertThat(harness.downloads).isEqualTo(downloadsBeforeRepair)
    }

    @Test
    fun `a setup run that fails over apt's own lists gives them up and runs the pipeline again`() = runTest {
        val harness = newHarness()
        harness.manager.install()
        val rootfs = harness.installer.rootfsDir
        // The two things a repair used to throw away whether or not anything was wrong with them.
        val index = rootfs.resolve("var/lib/apt/lists/fixture_Packages")
        index.parentFile?.mkdirs()
        index.writeText("Package: fixture\n")
        val cachedDeb = rootfs.resolve("var/cache/apt/archives/fixture.deb")
        cachedDeb.parentFile?.mkdirs()
        cachedDeb.writeText("already downloaded\n")

        // apt's own words for its own bookkeeping, and it keeps saying them until the lists it blames
        // are actually gone — so the second setup run is the one that succeeds. Keyed on the file
        // rather than on an attempt count, because a count would make this test pass by outlasting
        // the ladder instead of by the repair working; and the updates that happen *after* the lists
        // went are counted, because those are the pipeline running again, which is the rung's claim.
        var updatesAfterTheListsWent = 0
        val healthyRespond = harness.spawner.respond
        harness.spawner.respond = { command ->
            if (command.startsWith("apt-get update")) {
                if (index.exists()) {
                    100 to "E: Failed to fetch http://m.example/ubuntu/dists/noble/main/binary-amd64/Packages  " +
                        "Hash Sum mismatch\n" +
                        "E: Some index files failed to download. They have been ignored, or old ones used instead.\n"
                } else {
                    updatesAfterTheListsWent++
                    healthyRespond(command)
                }
            } else {
                healthyRespond(command)
            }
        }

        val report = harness.manager.repair()

        assertThat(harness.manager.state.value).isEqualTo(LinuxUserspaceState.Stopped)
        // Both halves, because apt named the bytes themselves: a corrupt `.deb` is the one file apt
        // will never rewrite for itself, and every install of that package fails identically until it
        // is gone. The index would have been re-fetched on the next update either way.
        assertThat(index.exists()).isFalse()
        assertThat(cachedDeb.exists()).isFalse()
        assertThat(report.warnings.joinToString("\n"))
            .contains("apt's package lists and the packages it had already downloaded were given up")
        // And the setup pipeline ran again: the rung that won needed one update, and the unscoped
        // confirmation beside it is the second. A repair that cleared the lists and stopped there
        // would leave the user with a userspace no update has ever completed.
        assertThat(updatesAfterTheListsWent).isEqualTo(2)
    }

    @Test
    fun `a setup run that fails over something else leaves apt's state where it is`() = runTest {
        val harness = newHarness()
        harness.manager.install()
        val rootfs = harness.installer.rootfsDir
        val index = rootfs.resolve("var/lib/apt/lists/fixture_Packages")
        index.parentFile?.mkdirs()
        index.writeText("Package: fixture\n")

        // A failure with nothing to do with apt's bookkeeping: dpkg's own subprocess died while
        // configuring a package. The ladder's answer to that is files put back or the base system
        // rewritten — the rungs below the setup run — and never a download. Emptying the index here
        // would buy the user thirty megabytes of fresh index and fix nothing, which is exactly the
        // trade this rung exists to refuse.
        val healthyRespond = harness.spawner.respond
        harness.spawner.respond = { command ->
            if (command.startsWith("apt-get update")) {
                100 to "dpkg: error processing package fixture (--configure):\n" +
                    " installed fixture package post-installation script subprocess returned error exit status 1\n" +
                    "Errors were encountered while processing:\n fixture\n"
            } else {
                healthyRespond(command)
            }
        }

        runCatching { harness.manager.repair() }

        // The rung ran and declined, in the record rather than in the tree: the deeper rungs rewrite
        // the base system and with it the lists, so what the file looks like at the end says nothing
        // about what this rung decided. The diagnostic is the decision itself.
        assertThat(harness.distribution.diagnostics.export())
            .contains("apt's state left alone: nothing named it")
    }

    @Test
    fun `an unreadable package database is restored from dpkg's own previous copy, not by a rebuild`() = runTest {
        val harness = newHarness()
        harness.manager.install()
        val dpkg = harness.installer.rootfsDir.resolve("var/lib/dpkg")
        val shipped = dpkg.resolve("status").readText()
        // dpkg's own previous generation, and the truncation a killed dpkg leaves in its place. This
        // is the deadlock the ladder could not see: `var/lib/dpkg` is a preserved member, so no rung
        // may write it, and every rung begins with a command that cannot start without reading it.
        dpkg.resolve("status-old").writeText(shipped)
        val truncated = "Package: bash\nStatus: install ok instal"
        dpkg.resolve("status").writeText(truncated)
        val userPackage = harness.installer.rootfsDir.resolve("usr/bin/user-package")
        userPackage.writeText("installed by the user\n")
        val downloadsBeforeRepair = harness.downloads

        // Every package command of the first rung fails the way dpkg really fails on this file, so the
        // ladder has to climb; the probe's `apt-get check` fails with them.
        val healthyRespond = harness.spawner.respond
        harness.spawner.respond = { command ->
            if (dpkg.resolve("status").length() < 512 && (command.contains("dpkg") || command.contains("apt"))) {
                2 to "dpkg: failed to open package info file '${dpkg.resolve("status")}' for reading: " +
                    "No such file or directory\n"
            } else {
                healthyRespond(command)
            }
        }

        val report = harness.manager.repair()

        assertThat(harness.manager.state.value).isEqualTo(LinuxUserspaceState.Stopped)
        assertThat(dpkg.resolve("status").readText()).isEqualTo(shipped)
        // Kept: it is the only remaining evidence of what the user had installed.
        assertThat(dpkg.resolve("status.broken").readText()).isEqualTo(truncated)
        // The package the user installed on top of the base system is still installed — this rung
        // writes one file, where the rebuild it replaces would have untracked every one of them.
        assertThat(userPackage.readText()).isEqualTo("installed by the user\n")
        // And it cost nothing: dpkg's own copy was already on the device.
        assertThat(harness.downloads).isEqualTo(downloadsBeforeRepair)
        assertThat(report.warnings.joinToString("\n")).contains("dpkg's own previous copy")
    }

    @Test
    fun `the ladder refuses to rebuild over what the device or the network owns`() = runTest {
        val harness = newHarness()
        // The gate as the table it is, one row per failure family. Everything on the left is an answer
        // about the phone or about the link: the deepest rung reads the same network, the same disk and
        // the same device, so it would rewrite a working base system on the way to the same failure —
        // and on a metered connection or a nearly-full device, leave the user worse off than the
        // failure did.
        val deviceOrNetwork = listOf(
            UserspaceFailure.Offline(),
            UserspaceFailure.DnsUnresolved(servers = listOf("192.168.1.1")),
            UserspaceFailure.MirrorUnreachable("http://m.example/ubuntu", UserspaceFailure.Kind.Refused),
            UserspaceFailure.RepositoryUnsigned("http://m.example/ubuntu"),
            UserspaceFailure.DiskFull(neededBytes = 900L * 1024 * 1024, freeBytes = 10L * 1024 * 1024),
            UserspaceFailure.StepTimedOut("Installing base packages"),
            // The phone's date, which nothing inside the rootfs can move: apt's index reads as expired
            // because the clock is wrong, and a rebuild reaches that same sentence thirty megabytes later.
            UserspaceFailure.ClockSkew(expired = true),
            UserspaceFailure.KilledBySignal(9, "Killed"),
            // The app's own runtime, not the Ubuntu files: no amount of writing the rootfs supplies it.
            UserspaceFailure.NativeRuntimeMissing("libproot-loader.so", "/data/app/dev.eclipse.ssh/lib/libproot-loader.so"),
            UserspaceFailure.TooManyTerminals(16),
        )
        for (failure in deviceOrNetwork) {
            assertThat(harness.manager.aRebuildCouldFix(failure)).isFalse()
        }

        // And everything whose evidence is inside the rootfs passes: these are what the ladder is for,
        // including the four failures the rungs above exist to repair.
        val rootfsOwned = listOf(
            UserspaceFailure.ProotLaunchFailed(127, ""),
            UserspaceFailure.PackageDbBroken(),
            UserspaceFailure.PackageLocksHeld(holderPid = 4321, locks = listOf("/var/lib/dpkg/lock-frontend")),
            UserspaceFailure.PackageDatabaseUnreadable(),
            UserspaceFailure.IndexHashMismatch("http://m.example/ubuntu"),
            UserspaceFailure.GuestTmpUnwritable("/tmp"),
            UserspaceFailure.DpkgSubprocessFailed(1),
        )
        for (failure in rootfsOwned) {
            assertThat(harness.manager.aRebuildCouldFix(failure)).isTrue()
        }

        // The prefix contracts the runtime layer throws by, read through a wrapper: a failure that has
        // been wrapped is still the failure.
        assertThat(harness.manager.aRebuildCouldFix(IOException("$RUNTIME_STORAGE_PREFIX the tmp probe could not write"))).isFalse()
        assertThat(harness.manager.aRebuildCouldFix(IOException("$DISK_FULL_PREFIX ~900 MB free; 200 MB available"))).isFalse()
        // Anything that is not a typed failure at all passes: untyped prose is what a rung's own
        // failure looks like, and the ladder's answer to that is to keep climbing.
        assertThat(harness.manager.aRebuildCouldFix(IllegalStateException("the health check failed"))).isTrue()
    }

    @Test
    fun `an ending that blames the userspace only moves a userspace the probe agrees is broken`() = runTest {
        val harness = newHarness()
        harness.manager.install()
        harness.manager.start()
        assertThat(harness.manager.state.value).isInstanceOf(LinuxUserspaceState.Running::class.java)

        // `exit 127` typed by hand, or a command that does not exist: the shell ran and exited, which
        // says nothing about the environment. The probe is what tells the two apart, and here it passes.
        harness.manager.noteSessionEnded("session-1", SessionEnd.ShellEnded(status = 127, signal = null))
        assertThat(harness.manager.state.value).isInstanceOf(LinuxUserspaceState.Running::class.java)

        // An ordinary exit and a kill are not the userspace's verdict either, whatever the probe says.
        harness.manager.noteSessionEnded("session-1", SessionEnd.ShellEnded(status = 0, signal = null))
        harness.manager.noteSessionEnded("session-1", SessionEnd.ShellEnded(status = 137, signal = "KILL"))
        assertThat(harness.manager.state.value).isInstanceOf(LinuxUserspaceState.Running::class.java)

        // A sibling terminal still open is proof the environment works, whatever this one exit says.
        val healthyRespond = harness.spawner.respond
        harness.spawner.respond = { command -> if (command == "whoami") 127 to "" else healthyRespond(command) }
        harness.processes.register("session-2", LocalTerminalChannel(FakePtyProcess()))
        harness.manager.noteSessionEnded("session-1", SessionEnd.ShellEnded(status = 127, signal = null))
        assertThat(harness.manager.state.value).isInstanceOf(LinuxUserspaceState.Running::class.java)

        // With the last session gone and the probe failing too, the ending is the userspace's: the card
        // now offers the Repair that fixes a shell proot cannot find, instead of a terminal that cannot
        // open while the host card goes on claiming the userspace is running.
        harness.processes.unregister("session-2")
        harness.manager.noteSessionEnded("session-1", SessionEnd.ShellEnded(status = 127, signal = null))
        val state = harness.manager.state.value
        assertThat(state).isInstanceOf(LinuxUserspaceState.NeedsRepair::class.java)
        // The detail is the probe's own sentence rather than the exit code: what is wrong, not which
        // program the user typed.
        assertThat((state as LinuxUserspaceState.NeedsRepair).detail).contains("root")
    }

    @Test
    fun `start names the Android groups, so an install made before the naming is corrected`() = runTest {
        // An install from a build that had no names to write, over a device whose app is in the
        // four groups the report named. The set is read on every start rather than only at install
        // time, so the terminal is correct the first time it is opened after the update — no
        // reinstall, and no Repair, for a file the user never sees.
        var gids = IntArray(0)
        val harness = newHarness(supplementaryGids = { gids })
        harness.manager.install()
        val group = harness.installer.rootfsDir.resolve("etc/group")
        assertThat(group.readText()).doesNotContain(AndroidGroupNames.PREFIX)

        gids = intArrayOf(3003, 9997, 20504, 50504)
        harness.manager.start()

        assertThat(harness.manager.state.value).isInstanceOf(LinuxUserspaceState.Running::class.java)
        assertThat(group.readLines()).containsAtLeast(
            "android_inet:x:3003:",
            "android_everybody:x:9997:",
            "android_cache_504:x:20504:",
            "android_shared_504:x:50504:",
        )
        // The account line the app writes for itself is still the only one, and still where it was.
        assertThat(group.readLines().count { it.startsWith("ubuntu:") }).isEqualTo(1)
    }

    @Test
    fun `a group list that cannot be read does not stop the userspace starting`() = runTest {
        val harness = newHarness(supplementaryGids = { throw IllegalStateException("no groups for you") })
        harness.manager.install()

        harness.manager.start()

        // Names beside four numbers are not worth a terminal the user cannot open, so this is
        // recorded and not fatal — and recorded, rather than swallowed, because the install log is
        // where someone asking "why does groups print numbers" has to be able to find the answer.
        assertThat(harness.manager.state.value).isInstanceOf(LinuxUserspaceState.Running::class.java)
        assertThat(harness.distribution.diagnostics.export()).contains("supplementary group names")
        assertThat(harness.distribution.diagnostics.export()).contains("no groups for you")
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
            assertThat(spawn.argv.first()).isEqualTo("${fakeNativeLibraryDir()}/libproot.so")
            assertThat(spawn.argv)
                .contains("--rootfs=${harness.rootDir.resolve("rootfs").absolutePath}")
            for (bind in listOf("/dev", "/proc", "/sys")) {
                val index = spawn.argv.indexOf(bind)
                assertThat(spawn.argv[index - 1]).isEqualTo("-b")
            }
            // PROOT_LOADER must point into nativeLibraryDir, or proot extracts its embedded
            // loader into PROOT_TMP_DIR - under filesDir, never executable - and dies with EACCES.
            // PROOT_TMP_DIR names the directory RuntimeStorageManager creates and probes.
            assertThat(spawn.envp).contains("PROOT_LOADER=${fakeNativeLibraryDir()}/libproot-loader.so")
            assertThat(spawn.envp)
                .contains("PROOT_TMP_DIR=${harness.rootDir.resolve("tmp").absolutePath}")
            // Blocked-syscall diagnostics land in a file this app can write (the fork's default
            // points at the fork's own app's cache — see linux/proot-patches/0002).
            assertThat(spawn.envp)
                .contains("PROOT_SIGSYS_LOG=${harness.rootDir.resolve("sigsys-log.txt").absolutePath}")
        }
        // Every proot run carries `-0` — the session's own spawn included, which is the last one
        // here and the one this assertion used to forbid. See ProotRuntimeExecModelTest for why,
        // and for the session argv read back from the spawner directly.
        assertThat(spawns.filterNot { it.argv.contains("-0") }.map { it.argv }).isEmpty()
    }

    @Test
    fun `a command that never finishes is closed at its timeout, not leaked`() = runBlocking {
        val root = Files.createTempDirectory("proot-timeout").toFile().apply { deleteOnExit() }
        val process = BlockingPtyProcess()
        val runtime = ProotRuntime(root, fakeNativeLibraryDir(), spawner = { _, _, _, _, _ -> process })

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
            fakeNativeLibraryDir(),
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
        val runtime = ProotRuntime(root, fakeNativeLibraryDir(), spawner = { _, _, _, _, _ -> process })

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
 * script, and recorded.
 *
 * The recording is the test's window into the exec model: every spawn carries `-0` (proot's fake
 * root — the setup pipeline needs it for dpkg's ownership changes, and a user-facing session needs
 * it because a shell that is not uid 0 cannot run dpkg or `su` at all), and [spawns] is where that
 * is asserted over the argument vectors rather than over a summary of them.
 */
internal class ScriptedPtySpawner : PtySpawner {
    /** Every command that was run, in order; the flags it ran with are in [spawns]. */
    val commands = mutableListOf<String>()

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
            0 to "root\n"
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
        commands += command
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
