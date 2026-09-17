package dev.eclipse.ssh.presentation.linux

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.background.LinuxUserspaceService
import dev.eclipse.ssh.di.LinuxUserspaceGraph
import dev.eclipse.ssh.di.LinuxUserspaceGraphProvider
import dev.eclipse.ssh.linux.LinuxDistro
import dev.eclipse.ssh.linux.LinuxInstallStep
import dev.eclipse.ssh.linux.LinuxProcessManager
import dev.eclipse.ssh.linux.LinuxUserspaceManager
import dev.eclipse.ssh.linux.LinuxUserspaceState
import dev.eclipse.ssh.linux.LinuxWorkspaceManager
import dev.eclipse.ssh.linux.ProotRuntime
import dev.eclipse.ssh.linux.RootfsInstaller
import dev.eclipse.ssh.linux.ScriptedPtySpawner
import dev.eclipse.ssh.linux.TestTarballs
import dev.eclipse.ssh.linux.UbuntuDistributionManager
import dev.eclipse.ssh.linux.UserspaceDiagnostics
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The controller's contract with the two screens that read it: every lifecycle verb surfaces in
 * one state object, a failure becomes a sentence rather than a crash, and an unsupported device
 * reports itself instead of exposing half a feature.
 *
 * The graph under test is real end to end down to the fork seam — a real rootfs fixture extracted
 * by the real installer, setup and probes answered by the scripted proot — so these tests pin the
 * same pipeline the manager's own tests do, but through the action surface the UI calls.
 *
 * Robolectric, not the bare JVM, because the controller owns the foreground-service binding and
 * needs a real [Context] to build intents with — the binding test reads what it started and
 * stopped out of Robolectric's shadow application.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LinuxUserspaceControllerTest {

    private val appContext: Context get() = ApplicationProvider.getApplicationContext()

    private class Harness(distro: LinuxDistro) {
        val rootDir: File = Files.createTempDirectory("linux-controller").toFile().apply { deleteOnExit() }
        val spawner = ScriptedPtySpawner()
        val runtime = ProotRuntime(rootDir, "/fake/native/lib", spawner)
        // One ring for both halves of the install, exactly as the DI graph wires it: a log that
        // held only the apt phase would answer half the questions a failed install raises.
        private val diagnostics = UserspaceDiagnostics()
        val installer =
            RootfsInstaller(
                rootDir,
                distro,
                downloader = { _, target, onChunk ->
                    TestTarballs.serving(FIXTURE).download("https://fixtures.invalid/rootfs.tar.gz", target, onChunk)
                },
                diagnostics = diagnostics,
            )
        val distribution =
            UbuntuDistributionManager(
                distro,
                runtime,
                appUid = 10150,
                appGid = 10150,
                diagnostics = diagnostics,
            )
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
        val graph =
            LinuxUserspaceGraph(distro, runtime, installer, distribution, processes, workspace, manager)
    }

    companion object {
        private val FIXTURE: File by lazy {
            TestTarballs.writeRootfsFixture(
                Files.createTempDirectory("linux-controller-fixture").toFile().resolve("rootfs.tar.gz"),
            )
        }

        private fun newHarness(): Harness =
            Harness(TestTarballs.fixtureDistro("https://fixtures.invalid/rootfs.tar.gz", TestTarballs.sha256(FIXTURE)))

        /**
         * The controller under a hand-built graph. The provider's real build needs
         * `nativeLibraryDir`, which Robolectric does not provide (it can only produce null), so
         * the graph under test is injected through the provider's test seam — the same seam exists
         * precisely because this suite builds its graph around the scripted proot instead.
         */
        private fun newController(graph: LinuxUserspaceGraph?): LinuxUserspaceController {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val provider = LinuxUserspaceGraphProvider(context).apply { setGraphForTest(graph) }
            return LinuxUserspaceController(context, provider)
        }
    }

    /**
     * The controller's scope is Dispatchers.Main.immediate, and Robolectric's main looper is
     * paused - nothing queued on it ever runs, so every act() would hang its first{} wait.
     * Point Main at the test's own scheduler so the controller runs on the virtual time the
     * assertions advance, and restore the real main looper afterwards for the rest of the suite.
     */
    @After
    fun restoreMainDispatcher() {
        Dispatchers.resetMain()
    }

    private fun TestScope.mainOnTheTestScheduler() {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    }

    @Test
    fun `an unsupported device reports itself with no state to render`() = runTest {
        val controller = newController(null)

        val ui = controller.uiState.value
        assertThat(ui.supported).isFalse()
        // Null, not defaulted: a screen cannot accidentally render a fake status line for a device
        // that has no userspace to describe.
        assertThat(ui.distro).isNull()
        assertThat(ui.state).isNull()

        // And the actions are no-ops rather than crashes — the section is hidden, but a stray call
        // (a stale recomposition, a test) must not take the app down.
        controller.install()
        controller.start()
        controller.uninstall(keepWorkspace = false)
    }

    @Test
    fun `install surfaces in the UI state and refreshes the storage numbers`() = runTest {
        mainOnTheTestScheduler()
        val harness = newHarness()
        val controller = newController(harness.graph)

        controller.install()
        // Wait for the settled state *and* the storage numbers that follow it: the state turns
        // Stopped before the background recompute lands, and a settings screen that showed "0 B
        // used" beside a fresh install would be wrong about both.
        val ui = controller.uiState.first { it.state is LinuxUserspaceState.Stopped && it.storageUsedBytes > 0 }

        assertThat(ui.supported).isTrue()
        assertThat(ui.distro).isNotNull()
        assertThat(ui.distro!!.displayName).isEqualTo("Ubuntu 22.04 LTS")
        assertThat(ui.health).isNotNull()
        assertThat(ui.health!!.healthy).isTrue()
        assertThat(ui.error).isNull()
        assertThat(ui.installWarnings).isEmpty()
        assertThat(ui.hasPendingWorkspaceBackup).isFalse()
        assertThat(ui.sessionCount).isEqualTo(0)
        // The fixture rootfs is on disk, so storage counts something; its workspace is empty.
        assertThat(ui.storageUsedBytes).isGreaterThan(0L)
        assertThat(ui.workspaceFileCount).isEqualTo(0)
    }

    @Test
    fun `an impossible action becomes an error sentence, not a crash`() = runTest {
        mainOnTheTestScheduler()
        val harness = newHarness()
        val controller = newController(harness.graph)

        // Start on a not-installed userspace: the manager refuses, the controller relays the
        // refusal as the sentence the settings screen shows under the section.
        controller.start()
        val ui = controller.uiState.first { it.error != null }

        assertThat(ui.error).contains("Start failed")
        // The state machine was untouched by the refusal - nothing half-started.
        assertThat(ui.state).isEqualTo(LinuxUserspaceState.NotInstalled)

        // And the sentence clears when the user has read it. The check subscribes rather than
        // reading .value: the sharing is WhileSubscribed, so a bare read after this test's first
        // collector left can observe the sharing already stopped — a screen is always subscribed,
        // and the honest assertion is what a subscribed screen sees.
        controller.clearError()
        val cleared = controller.uiState.first { it.error == null }
        assertThat(cleared.error).isNull()
    }

    @Test
    fun `a running userspace promotes the foreground hold service, and stopping demotes it`() = runTest {
        mainOnTheTestScheduler()
        val harness = newHarness()
        val controller = newController(harness.graph)
        val app = appContext as android.app.Application
        // The binding's first act is a demotion: the collector's initial read of NotInstalled
        // stops a service that was never started. The flush runs the collector's start (it may
        // be queued rather than inline); consumed here so the asserts below see only the
        // transitions they mean.
        testScheduler.advanceUntilIdle()
        assertThat(shadowOf(app).nextStoppedService).isNotNull()

        controller.install()
        controller.uiState.first { it.state is LinuxUserspaceState.Stopped && it.storageUsedBytes > 0 }
        controller.start()
        controller.uiState.first { it.state is LinuxUserspaceState.Running }
        // The binding collector resumes on the same state emission the first{} above saw; give
        // the scheduler a flush so the promotion has provably happened before the assert.
        testScheduler.advanceUntilIdle()

        // Promotion is the state machine's consequence, not a UI action: no screen is involved,
        // and the intent names the service because nothing else may start it.
        assertThat(shadowOf(app).nextStartedService?.component?.className)
            .isEqualTo(LinuxUserspaceService::class.java.name)

        controller.stop()
        controller.uiState.first { it.state is LinuxUserspaceState.Stopped }
        testScheduler.advanceUntilIdle()
        assertThat(shadowOf(app).nextStoppedService?.component?.className)
            .isEqualTo(LinuxUserspaceService::class.java.name)
    }

    @Test
    fun `the holding predicate covers every state that runs proot children`() {
        // The truth table the binding rule and the service's self-stop rule share. The left
        // column is every state the machine has: the four that fork or hold proot children hold
        // the process, and the two settled ones do not.
        assertThat(LinuxUserspaceState.Running(sinceMs = 0).holdsProcess()).isTrue()
        assertThat(LinuxUserspaceState.Installing(LinuxInstallStep.Verifying).holdsProcess()).isTrue()
        assertThat(LinuxUserspaceState.Starting.holdsProcess()).isTrue()
        assertThat(LinuxUserspaceState.Stopping.holdsProcess()).isTrue()
        assertThat(LinuxUserspaceState.NotInstalled.holdsProcess()).isFalse()
        assertThat(LinuxUserspaceState.NeedsRepair("broken").holdsProcess()).isFalse()
    }

    @Test
    fun `an install promotes the foreground hold while it runs`() = runTest {
        mainOnTheTestScheduler()
        val harness = newHarness()
        val controller = newController(harness.graph)
        val app = appContext as android.app.Application

        // Consume the binding's first act — the demotion of the initial NotInstalled state — so
        // the starts below are only the ones this test means.
        testScheduler.advanceUntilIdle()
        assertThat(shadowOf(app).nextStoppedService).isNotNull()

        controller.install()
        controller.uiState.first { it.state is LinuxUserspaceState.Stopped && it.storageUsedBytes > 0 }
        testScheduler.advanceUntilIdle()

        // An install ends at Stopped and never enters Running, so the only service start this
        // whole sequence can have produced is Installing's own promotion — the coverage that
        // makes a screen-off during the minutes-long apt phase unable to kill the process.
        assertThat(shadowOf(app).nextStartedService?.component?.className)
            .isEqualTo(LinuxUserspaceService::class.java.name)
        // And the settled install demotes it again.
        assertThat(shadowOf(app).nextStoppedService?.component?.className)
            .isEqualTo(LinuxUserspaceService::class.java.name)
    }

    @Test
    fun `a lost foreground hold is asked for again`() = runTest {
        mainOnTheTestScheduler()
        val harness = newHarness()
        val controller = newController(harness.graph)
        val app = appContext as android.app.Application

        controller.install()
        controller.uiState.first { it.state is LinuxUserspaceState.Stopped && it.storageUsedBytes > 0 }
        controller.start()
        controller.uiState.first { it.state is LinuxUserspaceState.Running }
        testScheduler.advanceUntilIdle()

        // Drain the two promotions the sequence above produced — the install's and Running's —
        // so what follows can only be the binding asking again.
        assertThat(shadowOf(app).nextStartedService?.component?.className)
            .isEqualTo(LinuxUserspaceService::class.java.name)
        assertThat(shadowOf(app).nextStartedService?.component?.className)
            .isEqualTo(LinuxUserspaceService::class.java.name)
        // The hold stands: nothing re-asks while it does.
        assertThat(shadowOf(app).peekNextStartedService()).isNull()

        // The six-hour dataSync cap took the hold away while the userspace stays Running —
        // without the lost signal, the edge-triggered rule would never promote again and the
        // user would be degraded until the next Stop/Start.
        controller.onForegroundHoldLost()
        testScheduler.advanceUntilIdle()

        assertThat(shadowOf(app).nextStartedService?.component?.className)
            .isEqualTo(LinuxUserspaceService::class.java.name)
    }

    @Test
    fun `a keep-workspace uninstall parks the backup the next install restores`() = runTest {
        mainOnTheTestScheduler()
        val harness = newHarness()
        val controller = newController(harness.graph)

        controller.install()
        controller.uiState.first { it.state is LinuxUserspaceState.Stopped && it.storageUsedBytes > 0 }
        // One project in the workspace, so keep-vs-delete is a real choice with real stakes.
        val project = File(harness.workspace.workspaceDir, "projects")
        project.mkdirs()
        File(project, "site.txt").writeText("kept across the uninstall\n")

        controller.uninstall(keepWorkspace = true)
        val uninstalled = controller.uiState.first {
            it.state is LinuxUserspaceState.NotInstalled && it.hasPendingWorkspaceBackup
        }
        // The workspace's files went with the rootfs; what remains is the parked snapshot.
        assertThat(uninstalled.workspaceFileCount).isEqualTo(0)
        assertThat(uninstalled.storageUsedBytes).isEqualTo(0)

        controller.install()
        // The whole storage-facts value arrives in one emission: the recompute used to be three
        // separate StateFlow writes, and an intermediate emission could pair a restored workspace
        // with a backup still claimed as parked - the exact lie the assert below pins out.
        val reinstalled = controller.uiState.first {
            it.state is LinuxUserspaceState.Stopped &&
                it.storageUsedBytes > 0 &&
                it.workspaceFileCount == 1L
        }
        assertThat(reinstalled.workspaceFileCount).isEqualTo(1)
        // The snapshot is consumed by the restore, not left to shadow the live workspace.
        assertThat(reinstalled.hasPendingWorkspaceBackup).isFalse()
    }

    @Test
    fun `the install log carries the install's own events out of the app`() = runTest {
        mainOnTheTestScheduler()
        val harness = newHarness()
        val controller = newController(harness.graph)

        // Nothing has happened yet, so the ring is empty and the settings row says so rather than
        // showing a count of zero events it would have to invent.
        assertThat(controller.installLog.first()).isEmpty()
        assertThat(controller.exportInstallLog()).isEmpty()

        controller.install()
        controller.uiState.first { it.state is LinuxUserspaceState.Stopped && it.storageUsedBytes > 0 }

        // The ring the UI reads is the one the install wrote into. Before this surface existed the
        // only reader was logcat, which is the one channel a user without `adb` does not have — so
        // a failed install's evidence was unreachable by the person who hit it.
        val events = controller.installLog.first { it.isNotEmpty() }
        val categories = events.map { it.category.tag }
        // Both halves of the install are in the one log: the rootfs download and extraction run
        // before the distribution manager exists, so a ring owned by the manager alone would have
        // been blank for exactly the failures that happen first (a truncated download, a checksum
        // mismatch, an extraction that skipped hard links).
        assertThat(categories).contains("download")
        assertThat(categories).contains("rootfs")
        assertThat(categories).contains("apt")

        // The exported text is the ring's own `line()` form, which is what makes a pasted report
        // readable: one event per line, with the subsystem tag the diagnostics were filed under.
        val export = controller.exportInstallLog()
        assertThat(export.lines()).hasSize(events.size)
        assertThat(export).contains("[download]")

        // Clear is half the point of a ring this size: clear, reproduce, export only the failure.
        controller.clearInstallLog()
        assertThat(controller.installLog.first()).isEmpty()
        assertThat(controller.exportInstallLog()).isEmpty()
    }

    @Test
    fun `an unsupported device has an empty install log rather than an error`() = runTest {
        // There is no graph, so there is no ring. Every reader must answer "nothing recorded"
        // instead of throwing, because the settings section renders this row on devices whose
        // feature is absent — a null dereference here would take the screen down.
        val controller = newController(null)

        assertThat(controller.installLog.first()).isEmpty()
        assertThat(controller.exportInstallLog()).isEmpty()
        controller.clearInstallLog()
    }
}
