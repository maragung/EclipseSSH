package dev.eclipse.ssh.di

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.eclipse.ssh.linux.LinuxDistro
import dev.eclipse.ssh.linux.LinuxDistroCatalog
import dev.eclipse.ssh.linux.LinuxPtySpawner
import dev.eclipse.ssh.linux.LinuxProcessManager
import dev.eclipse.ssh.linux.LinuxUserspaceManager
import dev.eclipse.ssh.linux.LinuxWorkspaceManager
import dev.eclipse.ssh.linux.ProotRuntime
import dev.eclipse.ssh.linux.RootfsInstaller
import dev.eclipse.ssh.linux.UbuntuDistributionManager
import java.io.File
import javax.inject.Singleton

/**
 * The Linux userspace's whole object graph, built in one place.
 *
 * It is one container rather than one @Provides per class for a reason: the graph only exists on a
 * device whose ABI maps to an Ubuntu architecture, and every class in it needs the non-null
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

@Module
@InstallIn(SingletonComponent::class)
object LinuxUserspaceModule {

    /**
     * The userspace graph for this device, or null where the device cannot run one.
     *
     * The root is `filesDir/linux` and the keep-workspace backup deliberately sits outside it
     * (see [LinuxUserspaceManager]); `nativeLibraryDir` is the only directory a targetSdk 29+ app
     * may execve from, which is where the pinned proot binaries ship. The DNS servers are read
     * from the device's active network when it has any — the fallback is public anycast.
     *
     * The graph is a singleton and Dagger builds singletons lazily, so this is constructed at
     * first use, not at process start; the DNS list it captures is the one the first setup or
     * repair writes into the rootfs's resolv.conf.
     */
    @Provides
    @Singleton
    fun provideLinuxUserspaceGraph(@ApplicationContext context: Context): LinuxUserspaceGraph? {
        val distro = LinuxDistroCatalog.forDevice(Build.SUPPORTED_ABIS.toList()) ?: return null

        val rootDir = File(context.filesDir, "linux")
        val runtime = ProotRuntime(
            rootDir = rootDir,
            nativeLibraryDir = context.applicationInfo.nativeLibraryDir,
            spawner = LinuxPtySpawner,
        )
        val installer = RootfsInstaller(rootDir, distro)
        val distribution = UbuntuDistributionManager(
            distro = distro,
            runtime = runtime,
            appUid = Process.myUid(),
            appGid = Process.myGid(),
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
