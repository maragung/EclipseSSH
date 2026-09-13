package dev.eclipse.ssh.presentation.linux

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.background.LinuxUserspaceService
import dev.eclipse.ssh.di.LinuxUserspaceGraph
import dev.eclipse.ssh.linux.LinuxDistro
import dev.eclipse.ssh.linux.LinuxProcessManager
import dev.eclipse.ssh.linux.LinuxUserspaceManager
import dev.eclipse.ssh.linux.LinuxUserspaceState
import dev.eclipse.ssh.linux.LinuxWorkspaceManager
import dev.eclipse.ssh.linux.ProotRuntime
import dev.eclipse.ssh.linux.RootfsInstaller
import dev.eclipse.ssh.linux.ScriptedPtySpawner
import dev.eclipse.ssh.linux.TestTarballs
import dev.eclipse.ssh.linux.UbuntuDistributionManager
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
        val installer =
            RootfsInstaller(rootDir, distro) { _, target, onChunk ->
                TestTarballs.serving(FIXTURE).download("https://fixtures.invalid/rootfs.tar.gz", target, onChunk)
            }
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
        val controller = LinuxUserspaceController(appContext, graph = null)

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
        val controller = LinuxUserspaceController(appContext, harness.graph)

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
        val controller = LinuxUserspaceController(appContext, harness.graph)

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
        val controller = LinuxUserspaceController(appContext, harness.graph)
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
    fun `a keep-workspace uninstall parks the backup the next install restores`() = runTest {
        mainOnTheTestScheduler()
        val harness = newHarness()
        val controller = LinuxUserspaceController(appContext, harness.graph)

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
        // Both numbers in the predicate, because the recompute sets them as three separate
        // MutableStateFlow writes and an intermediate emission can carry one without the other.
        val reinstalled = controller.uiState.first {
            it.state is LinuxUserspaceState.Stopped &&
                it.storageUsedBytes > 0 &&
                it.workspaceFileCount == 1L
        }
        assertThat(reinstalled.workspaceFileCount).isEqualTo(1)
        // The snapshot is consumed by the restore, not left to shadow the live workspace.
        assertThat(reinstalled.hasPendingWorkspaceBackup).isFalse()
    }
}
