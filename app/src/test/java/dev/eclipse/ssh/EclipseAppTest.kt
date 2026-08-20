package dev.eclipse.ssh

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The SSH logging posture of a release build.
 *
 * `slf4j-simple` is on the runtime classpath because Apache MINA SSHD needs an SLF4J binding, and it
 * writes to `System.err` — which Android redirects into logcat — at INFO by default. That published
 * the user's SSH activity to the device log, `ClientSessionImpl[deploy@/10.0.0.5:22]` and all:
 * which servers, as which account, and when. [EclipseApp] pins the level instead, and these are the
 * assertions that keep it pinned.
 */
class EclipseAppTest {

    @Test
    fun `a production build logs nothing from the ssh stack`() {
        assertThat(EclipseApp.sshLogLevel(debuggable = false)).isEqualTo("off")
    }

    @Test
    fun `a debuggable build keeps its diagnostics`() {
        // Turning the leak off must not cost a developer the session trace they debug with.
        assertThat(EclipseApp.sshLogLevel(debuggable = true)).isEqualTo("debug")
    }

    @Test
    fun `both levels are strings slf4j-simple actually understands`() {
        // The trap this closes: SimpleLoggerConfiguration.stringToLevel does not reject an unknown
        // level, it falls through to INFO. A typo would therefore restore full logging, silently,
        // and only in the release build nobody watches the log of.
        assertThat(EclipseApp.RECOGNISED_SLF4J_LEVELS).contains(EclipseApp.sshLogLevel(false))
        assertThat(EclipseApp.RECOGNISED_SLF4J_LEVELS).contains(EclipseApp.sshLogLevel(true))
    }

    @Test
    fun `the property name is the one slf4j-simple reads`() {
        // Held against the binding's own constant rather than a copy of the string, so an upstream
        // rename fails the build instead of quietly disabling the control.
        assertThat(EclipseApp.SLF4J_LEVEL_PROPERTY)
            .isEqualTo(org.slf4j.simple.SimpleLogger.DEFAULT_LOG_LEVEL_KEY)
    }

    // --- JCE provider scoping ---

    @Test
    fun `sshd is told to keep its optional providers unnamed`() {
        // Both of sshd's optional registrars, and the value that stops each one calling
        // Security.addProvider. Asserted as whole property names so a change to either half of the
        // key — sshd's prefix constant or the provider name — is visible here.
        assertThat(EclipseApp.SSHD_PROVIDER_SCOPING).containsExactly(
            "org.apache.sshd.security.provider.BC.useNamed", "false",
            "org.apache.sshd.security.provider.EdDSA.useNamed", "false",
        )
    }

    @Test
    fun `the test jvm runs with the same scoping the app installs`() {
        // The unit-test JVM sets these from build.gradle.kts, because sshd reads them in a static
        // initializer and no Application runs first here. This is what keeps that copy honest: if
        // the build script and EclipseApp ever name different properties, one of these is unset and
        // Ed25519 key parsing goes back to depending on which test ran first.
        EclipseApp.SSHD_PROVIDER_SCOPING.forEach { (property, value) ->
            assertThat(System.getProperty(property)).isEqualTo(value)
        }
    }

    /**
     * Initialising sshd adds nothing to the process-wide provider registry.
     *
     * The before/after comparison is the general statement, and `EdDSA` is the specific one: that
     * name belongs to net.i2p.crypto and nothing else on this classpath claims it, so its absence
     * is direct evidence that sshd's registrar ran without publishing.
     *
     * `BC` is deliberately *not* asserted absent. Android installs a provider under that name and
     * Robolectric reproduces it, so a `BC` in the list says nothing about sshd — it is the very
     * collision the scoping exists to sidestep. Asserting it were null would be asserting something
     * false about the platform the app actually runs on.
     */
    @Test
    fun `initialising sshd publishes no provider under a shared name`() {
        val before = java.security.Security.getProviders().map { it.name }

        // Forces the optional registrars to run, if nothing in this JVM has needed them yet, and
        // doubles as the check that scoping a provider does not disable it: sshd still has to be
        // able to build Ed25519 keys, it just uses the instance it created rather than one it looked
        // up by name.
        assertThat(org.apache.sshd.common.util.security.SecurityUtils.isEDDSACurveSupported()).isTrue()

        assertThat(java.security.Security.getProviders().map { it.name }).isEqualTo(before)
        assertThat(java.security.Security.getProvider("EdDSA")).isNull()
    }
}
