package dev.eclipse.ssh.di

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import androidx.annotation.VisibleForTesting
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.eclipse.ssh.linux.LinuxDistro
import dev.eclipse.ssh.linux.LinuxDistroCatalog
import dev.eclipse.ssh.linux.LinuxPtySpawner
import dev.eclipse.ssh.linux.LinuxProcessManager
import dev.eclipse.ssh.linux.LinuxUserspaceManager
import dev.eclipse.ssh.linux.LinuxUserspaceState
import dev.eclipse.ssh.linux.LinuxWorkspaceManager
import dev.eclipse.ssh.linux.ProotRuntime
import dev.eclipse.ssh.linux.RootfsInstaller
import dev.eclipse.ssh.linux.UbuntuDistributionManager
import java.io.File
import java.util.Properties
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The Linux userspace's whole object graph, built in one place.
 *
 * It is one class rather than one @Provides per member for a reason: the graph only exists on a
 * device whose ABI maps to an Ubuntu architecture, and every member needs the non-null
 * [LinuxDistro] that mapping produces. A device that maps to none (an x86 emulator, an ABI the
 * catalog has no rootfs for) gets `null` for the whole graph, and the UI reads that as "this
 * device is not supported" — one nullable seam instead of five providers each with their own
 * unsupported story to tell.
 */
class LinuxUserspaceGraph(
    val distro: LinuxDistro,
    val runtime: ProotRuntime,
    val installer: RootfsInstaller,
    val distribution: UbuntuDistributionManager,
    val processes: LinuxProcessManager,
    val workspace: LinuxWorkspaceManager,
    val manager: LinuxUserspaceManager,
)

/**
 * Builds, resolves and — only while nothing is installed — swaps the userspace graph.
 *
 * The distro a graph is built around used to be a constant ([LinuxDistroCatalog.forDevice]'s
 * default), which was safe exactly as long as there was one version. It no longer is: the install
 * screen offers every LTS, so the graph must be built around the distro that is *relevant right
 * now*, and that is a resolution, not a constant —
 *
 * 1. the distro already installed (the userspace state file under the root records it), because
 *    an installed rootfs is only ever itself, and the manager's own state check compares against
 *    the graph's distro;
 * 2. else the version the user last picked on the install screen, which survives an uninstall
 *    because it lives outside the root directory the uninstall deletes;
 * 3. else the catalog default.
 *
 * Resolution happens at build time, and builds happen at process start and when the user picks a
 * version while nothing is installed — the one state in which a swap is legal. Once anything is
 * on disk (Installed, NeedsRepair, mid-install), [selectDistro] refuses: an install that failed
 * halfway is still *some* distro's files, and offering a second version beside half of a first
 * one is how two root filesystems get interleaved into one broken directory.
 *
 * The swap is a new graph value on a [StateFlow], not a mutation of the old graph: subscribers
 * (the controller's UI state, the host-list card) re-derive from the new graph, and one-shot
 * readers (the connect path, the session registry) read the flow's current value, so no consumer
 * can hold the previous process-lifetime's graph forever.
 */
@Singleton
class LinuxUserspaceGraphProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * Where the user's version choice lives. Deliberately outside the userspace root: the root is
     * what an uninstall deletes, and the choice is a preference about the *next* install, not a
     * fact about the current one.
     */
    private val selectionFile: File = File(context.filesDir, "linux-distro-selection.properties")

    /** Every LTS this device can run, newest first — the install screen's version list. */
    val versions: List<LinuxDistro> = LinuxDistroCatalog.versionsFor(Build.SUPPORTED_ABIS.toList())

    // Both of the declarations above have to precede this one. Property initializers run in
    // declaration order, and buildGraph() reads selectionFile and versions; declared below it,
    // each is still the JVM default — null — while the graph builds, and process start died with
    // an NPE inside readDistroId before a frame could be drawn. The crash is device-identical;
    // Robolectric is merely what saw it first, because it builds the real Hilt graph.
    private val _graph = MutableStateFlow(buildGraph())

    /** The graph to act on right now; null on a device that cannot run a userspace. */
    val graph: LinuxUserspaceGraph? get() = _graph.value

    /** The graph as a flow, for subscribers that must re-derive when a version choice swaps it. */
    val graphFlow: StateFlow<LinuxUserspaceGraph?> = _graph

    /**
     * Chooses the version the Install button downloads. Refused (false) on an unsupported device,
     * for an id this device cannot run, or while anything is installed or mid-install — see the
     * class doc for why a swap is legal only from a clean Not Installed.
     */
    fun selectDistro(distroId: String): Boolean {
        val current = _graph.value ?: return false
        if (current.manager.state.value !is LinuxUserspaceState.NotInstalled) return false
        val distro = versions.firstOrNull { it.id == distroId } ?: return false
        writeSelection(distro.id)
        // A re-selection of the running default still records the choice (it wins over the
        // default on the next process start); only a genuinely different distro needs a rebuild.
        if (distro != current.distro) {
            _graph.value = buildGraph()
        }
        return true
    }

    /**
     * Puts a prebuilt graph in place of the resolved one. The real build needs
     * `nativeLibraryDir`, which Robolectric does not provide (a graph under test is always
     * hand-built around the scripted proot), so the controller's tests inject their graph here
     * instead of going through a build that can only produce null on the JVM.
     */
    @VisibleForTesting
    internal fun setGraphForTest(graph: LinuxUserspaceGraph?) {
        _graph.value = graph
    }

    /**
     * The graph for the distro resolution says is relevant now, or null where the device cannot
     * run one.
     *
     * The root is `filesDir/linux` and the keep-workspace backup deliberately sits outside it
     * (see [LinuxUserspaceManager]); `nativeLibraryDir` is the only directory a targetSdk 29+ app
     * may execve from, which is where the pinned proot binaries ship. The DNS servers are read
     * from the device's active network when it has any — the fallback is public anycast.
     */
    private fun buildGraph(): LinuxUserspaceGraph? {
        val distro = resolveDistro() ?: return null

        // A context with no native library directory (Robolectric, or an install whose native
        // code was never extracted) has nowhere the pinned proot could be execve'd from, which is
        // the same story as an unmapped ABI: this device cannot run a userspace.
        val nativeLibraryDir = context.applicationInfo.nativeLibraryDir ?: return null

        val rootDir = File(context.filesDir, "linux")
        val runtime = ProotRuntime(
            rootDir = rootDir,
            nativeLibraryDir = nativeLibraryDir,
            spawner = LinuxPtySpawner,
        )
        val installer = RootfsInstaller(rootDir, distro)
        val distribution = UbuntuDistributionManager(
            distro = distro,
            runtime = runtime,
            appUid = Process.myUid(),
            // android.os.Process has no myGid() in the modern SDK, so the gid comes from the
            // syscall wrapper; on Android an app's primary gid is its own uid, which is the
            // value the runCatching fallback would land on anyway.
            appGid = runCatching { android.system.Os.getgid() }.getOrDefault(Process.myUid()),
            dnsServers = liveDnsServers(context),
        )
        val processes = LinuxProcessManager()
        val workspace = LinuxWorkspaceManager(runtime)
        val manager = LinuxUserspaceManager(
            rootDir = rootDir,
            distro = distro,
            runtime = runtime,
            installer = installer,
            distribution = distribution,
            processes = processes,
            workspace = workspace,
        )
        return LinuxUserspaceGraph(distro, runtime, installer, distribution, processes, workspace, manager)
    }

    /** Installed distro, else the user's pick, else the default — as the class doc resolves it. */
    private fun resolveDistro(): LinuxDistro? {
        // An id that names no entry this device can run (a catalog change, a moved install) falls
        // through to the next rung rather than failing the whole graph: the manager's own state
        // check is the authority on whether anything is actually installed.
        val installed = readDistroId(File(context.filesDir, "linux/state.properties"))
        val selected = readDistroId(selectionFile)
        return resolveDistro(versions, installed, selected)
    }

    internal companion object {
        /**
         * The resolution as a pure function of its inputs, because the order is the rule the
         * whole graph hangs on: an installed distro is what the manager's state check compares
         * against, so it wins unconditionally; the user's pick beats the default; the default is
         * the last resort, never an override. Unknown ids — a catalog change, a selection file
         * edited by hand — fall to the next rung rather than failing the graph.
         */
        fun resolveDistro(
            versions: List<LinuxDistro>,
            installedDistroId: String?,
            selectedDistroId: String?,
        ): LinuxDistro? =
            versions.firstOrNull { it.id == installedDistroId }
                ?: versions.firstOrNull { it.id == selectedDistroId }
                ?: versions.firstOrNull { it.id == LinuxDistroCatalog.DEFAULT_DISTRO_ID }
                ?: versions.firstOrNull()
    }

    private fun readDistroId(file: File): String? {
        if (!file.isFile) return null
        return runCatching {
            file.inputStream().use { input -> Properties().apply { load(input) }.getProperty("distroId") }
        }.getOrNull()
    }

    private fun writeSelection(distroId: String) {
        val props = Properties()
        props.setProperty("distroId", distroId)
        selectionFile.outputStream().use { output -> props.store(output, "Eclipse SSH Linux distro selection") }
    }

    /**
     * The device's current resolvers, read from the active network's link properties — the same
     * servers the OS itself uses, which is what a rootfs cut off from Android's resolver needs.
     * Falls back to the distribution manager's public anycast defaults when there is no network
     * or the platform reports none.
     */
    private fun liveDnsServers(context: Context): List<String> {
        val managers = runCatching {
            context.getSystemService(ConnectivityManager::class.java)
        }.getOrNull() ?: return UbuntuDistributionManager.DEFAULT_DNS_SERVERS
        val linkProperties = managers.activeNetwork?.let { managers.getLinkProperties(it) }
        val servers = linkProperties?.dnsServers?.mapNotNull { it.hostAddress }.orEmpty()
        return servers.ifEmpty { UbuntuDistributionManager.DEFAULT_DNS_SERVERS }
    }
}
