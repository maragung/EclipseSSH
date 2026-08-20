package dev.eclipse.ssh

import android.app.Application
import android.content.pm.ApplicationInfo
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.eclipse.ssh.background.NotificationChannels
import java.nio.file.Path
import javax.inject.Inject
import org.apache.sshd.common.util.io.PathUtils
import org.apache.sshd.common.util.security.SecurityProviderRegistrar

@HiltAndroidApp
class EclipseApp : Application(), Configuration.Provider {
    @Inject
    lateinit var workerFactory: androidx.hilt.work.HiltWorkerFactory

    override fun onCreate() {
        // Must run before anything else: see configureSshdUserHome.
        configureSshdUserHome()
        scopeSshdSecurityProviders()
        muteSshLoggingInProductionBuilds()
        super.onCreate()
        // Centralised here so a notification can be posted even when the session service never
        // managed to start — posting to a channel that does not exist yet is silently dropped.
        NotificationChannels.ensureCreated(this)
    }

    /**
     * Points Apache MINA SSHD's notion of the user home folder at app-private storage.
     *
     * sshd resolves `~/.ssh` from the `user.home` system property, which is empty on Android, so its
     * `PathUtils.LazyDefaultUserHomeFolderHolder` static initializer threw
     * `IllegalArgumentException: No user home folder available` the first time anything touched
     * `ClientBuilder`. That surfaced as `ExceptionInInitializerError` and killed the process during
     * MainActivity's first composition — the app never drew a frame on a real device. sshd's own
     * error message names the remedy, which is to install a resolver before any of its classes load.
     *
     * Host-JVM tests could not catch this: `user.home` is set there, so the default holder
     * initialises happily and the crash only ever appeared on device. [SshdUserHomeTest] pins the
     * resolver instead of the symptom.
     *
     * Ordering matters. This has to precede `super.onCreate()` (which runs Hilt's member injection)
     * and therefore any construction of `SshConnectionManager`, whose `client` field initializer is
     * what trips the static chain.
     */
    private fun configureSshdUserHome() {
        // filesDir always exists and is private to the app; sshd will resolve `.ssh` beneath it.
        val home: Path = runCatching { filesDir?.toPath() }.getOrNull() ?: return
        runCatching { PathUtils.setUserHomeFolderResolver { home } }
    }

    /**
     * Keeps Apache MINA SSHD's optional JCE providers out of the process-wide provider registry.
     *
     * sshd ships registrars for Bouncy Castle and for net.i2p.crypto's Ed25519, and by default it
     * publishes whichever of them it finds with `Security.addProvider`, under the bare names `BC`
     * and `EdDSA` — then looks them up *by that name* every time it needs a `KeyFactory` to build a
     * private key. Two consequences, neither of them wanted here.
     *
     * The first is not hypothetical on Android: the platform already installs a provider named `BC`
     * (its own repackaged Bouncy Castle), and `Security.addProvider` does nothing when the name is
     * taken. sshd's registration therefore fails silently and every by-name lookup afterwards
     * resolves to the platform's provider rather than the one sshd built — so the implementation
     * that constructs the app's private keys is chosen by whatever happens to hold the name, which
     * on some OEM builds is neither sshd's copy nor the one this app was tested against.
     *
     * The second is the reverse direction: the registration is process-wide, so the app would be
     * publishing an Ed25519 implementation to every other library sharing the VM, which is not its
     * business to do.
     *
     * `useNamed=false` makes sshd hold each provider as an instance and hand it to
     * `KeyFactory.getInstance(algorithm, provider)` directly. Key loading, host key verification and
     * public-key authentication all behave exactly as before — that is what the Ed25519 and ECDSA
     * end-to-end authentication tests cover — with nothing added to `java.security.Security` and
     * no name left for anything else to occupy.
     *
     * The same ordering requirement as [configureSshdUserHome]: sshd reads these once, from
     * `SecurityUtils`' static initializer, and never looks again.
     */
    private fun scopeSshdSecurityProviders() {
        SSHD_PROVIDER_SCOPING.forEach { (property, value) ->
            // A value already present wins, so a developer can put a provider back by name from the
            // command line without editing this.
            if (System.getProperty(property) == null) {
                runCatching { System.setProperty(property, value) }
            }
        }
    }

    /**
     * Silences Apache MINA SSHD's SLF4J output in non-debuggable builds.
     *
     * `slf4j-simple` is on the runtime classpath (sshd requires an SLF4J binding) and writes to
     * `System.err`, which on Android is redirected into logcat. Its default level is INFO, so a
     * release build published a running commentary of the user's SSH activity to the device log:
     * lines such as `WARN ClientSessionImpl - exceptionCaught(ClientSessionImpl[deploy@/10.0.0.5:22])`
     * name the account and the server for every session, successful or not. That is the user's
     * infrastructure inventory — which hosts, as whom, and when — and it ends up in bug reports and
     * in any OEM log collector on the device. The app itself logs nothing and reads none of this;
     * errors reach the user through the UI, so nothing is lost by turning it off.
     *
     * Debuggable builds keep full DEBUG output, which is where that diagnosis actually belongs.
     *
     * Ordering matters as much as it does for [configureSshdUserHome]: `SimpleLoggerConfiguration`
     * snapshots this property the first time any logger is created, so this must precede
     * `super.onCreate()` and the Hilt injection that constructs the SSH stack. A property already
     * set by the host environment wins, so a developer can still override it from the command line.
     */
    private fun muteSshLoggingInProductionBuilds() {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val level = sshLogLevel(debuggable)
        if (System.getProperty(SLF4J_LEVEL_PROPERTY) == null) {
            runCatching { System.setProperty(SLF4J_LEVEL_PROPERTY, level) }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    internal companion object {
        const val SLF4J_LEVEL_PROPERTY = "org.slf4j.simpleLogger.defaultLogLevel"

        /**
         * The level strings `slf4j-simple` understands.
         *
         * Anything outside this set is not an error there — `SimpleLoggerConfiguration.stringToLevel`
         * falls through to INFO — so a typo would reinstate the very logging this is meant to stop,
         * silently and only in release. The set exists so a test can catch that.
         */
        val RECOGNISED_SLF4J_LEVELS = setOf("trace", "debug", "info", "warn", "error", "off")

        /**
         * The sshd providers the app refuses to have registered by name, and the property that says
         * so. Built from sshd's own constants rather than from copies of the strings, so an upstream
         * rename fails the build instead of quietly turning the control off.
         *
         * Only the two optional ones: sshd registers no others, and naming a provider it does not
         * know is harmless but says something untrue about what the app is configuring.
         */
        val SSHD_PROVIDER_SCOPING: Map<String, String> = listOf("BC", "EdDSA").associate { provider ->
            "${SecurityProviderRegistrar.CONFIG_PROP_BASE}.$provider." +
                SecurityProviderRegistrar.NAMED_PROVIDER_PROPERTY to "false"
        }

        /** Off in production, verbose where a developer is watching. */
        fun sshLogLevel(debuggable: Boolean): String = if (debuggable) "debug" else "off"
    }
}
