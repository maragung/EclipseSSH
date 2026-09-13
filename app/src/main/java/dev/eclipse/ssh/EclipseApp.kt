package dev.eclipse.ssh

import android.app.Application
import android.content.pm.ApplicationInfo
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.eclipse.ssh.background.NotificationChannels
import java.nio.file.Path
import java.security.Security
import javax.inject.Inject
import org.apache.sshd.common.util.io.PathUtils
import org.apache.sshd.common.util.security.SecurityProviderRegistrar
import org.bouncycastle.jce.provider.BouncyCastleProvider

@HiltAndroidApp
class EclipseApp : Application(), Configuration.Provider {
    @Inject
    lateinit var workerFactory: androidx.hilt.work.HiltWorkerFactory

    override fun onCreate() {
        // Must run before anything else: see configureSshdUserHome.
        configureSshdUserHome()
        replacePlatformBouncyCastle()
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
     * Replaces the platform's stripped Bouncy Castle with the full one bundled in the APK.
     *
     * Android installs its own repackaged, reduced Bouncy Castle under the provider name `BC`.
     * That name is also how MINA sshd resolves its crypto: `BouncyCastleSecurityProviderRegistrar`
     * holds whatever `Security.getProvider("BC")` answers, and the platform's copy ships without
     * the EC parameter services, so nothing routed through it can service
     * `AlgorithmParameters.getInstance("EC")`.
     *
     * That stopped being a footnote in sshd 2.19.0 (the dependency upgraded in v1.1.12):
     * `ECCurves` no longer hard-codes its curve parameters but resolves them through exactly
     * that call, and its static initializer sorts the curves by key size — so the resolution
     * runs during class initialization, where a failure is an uncatchable
     * `ExceptionInInitializerError`. The first touch of `KeyPairProvider` (BaseBuilder's
     * static chain, walked the moment `SshConnectionManager` is constructed) threw
     * `IllegalArgumentException: No EC params for nistp256` inside the initializer and the
     * process died during MainActivity's first composition. Every release from v1.1.12
     * through v1.1.16 crashed on open, on every device — the app could not draw a frame.
     *
     * Swapping the platform provider for the bundled full one — at the same position, so the
     * process-wide JCE preference order is otherwise unchanged — hands the name to an
     * implementation that has the services. The bundled provider is a superset of the
     * platform's stripped copy: everything that resolved to the old `BC` keeps resolving, and
     * the EC parameters sshd now needs start resolving too.
     *
     * Where no provider answers to the name — a host JVM without one — there is nothing to
     * replace and nothing to fix: sshd's registrar then constructs its own instance of the
     * bundled provider reflectively. The guard makes this a no-op there rather than a change,
     * which is also why the unit suite never saw the crash: it runs on such a JVM, while the
     * device — with the platform collision — died. [SshdBouncyCastleSwapTest] installs a
     * faithful model of the platform's crippled provider and pins the swap against it.
     *
     * The same ordering requirement as [configureSshdUserHome]: sshd caches its registrars
     * and provider instances in static initializers and never looks again, so the swap has to
     * happen before the first of those runs.
     */
    private fun replacePlatformBouncyCastle() {
        runCatching { swapBundledBouncyCastleIntoProviderRegistry() }
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
     * on some OEM builds is neither sshd's copy nor the one this app was tested against. Since
     * [replacePlatformBouncyCastle] runs first, the name is held by the bundled full provider, so
     * both the name and the instance sshd falls back to are the copy this app ships.
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

        /**
         * Puts the bundled full Bouncy Castle into the provider registry under the platform's
         * `BC` name, in the slot the platform's stripped copy occupied. See
         * [replacePlatformBouncyCastle] for why that swap has to happen.
         *
         * `internal` and static so a unit test can drive exactly this code path against a
         * simulated platform provider, rather than a re-implementation that could drift.
         * A no-op when nothing holds the name.
         */
        internal fun swapBundledBouncyCastleIntoProviderRegistry() {
            val name = BouncyCastleProvider.PROVIDER_NAME
            val slot = Security.getProviders().indexOfFirst { it.name.equals(name, ignoreCase = true) }
            if (slot < 0) {
                return
            }
            Security.removeProvider(name)
            // insertProviderAt is 1-based; inserting at slot + 1 lands the replacement exactly
            // where the removed provider sat, leaving the preference order untouched.
            Security.insertProviderAt(BouncyCastleProvider(), slot + 1)
        }

        /** Off in production, verbose where a developer is watching. */
        fun sshLogLevel(debuggable: Boolean): String = if (debuggable) "debug" else "off"
    }
}
