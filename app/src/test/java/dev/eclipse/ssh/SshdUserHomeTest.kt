package dev.eclipse.ssh

import com.google.common.truth.Truth.assertThat
import org.apache.sshd.client.ClientBuilder
import org.apache.sshd.common.util.io.PathUtils
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Regression cover for the first-launch crash.
 *
 * Apache MINA SSHD resolves `~/.ssh` from the `user.home` system property. Android does not set it,
 * so sshd's `PathUtils.LazyDefaultUserHomeFolderHolder` static initializer threw
 * `IllegalArgumentException: No user home folder available` the first time anything touched
 * `ClientBuilder`, surfacing as `ExceptionInInitializerError` and killing the process during
 * MainActivity's first composition. The app could not open at all on a device.
 *
 * These tests deliberately assert on the *resolver* rather than on the crash. The crash itself is
 * unreproducible here — the host JVM running these tests does set `user.home`, which is exactly why
 * the whole unit and Robolectric suite passed while the installed APK died on launch. What can be
 * pinned is that [EclipseApp] redirects sshd at app-private storage instead of leaving it to guess,
 * so removing that call fails the build rather than shipping.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EclipseApp::class, sdk = [35])
class SshdUserHomeTest {

    @Test
    fun `application startup points sshd at app private storage`() {
        val app = RuntimeEnvironment.getApplication()

        assertThat(PathUtils.getUserHomeFolder()).isEqualTo(app.filesDir.toPath())
    }

    @Test
    fun `sshd home does not fall back to the jvm user home property`() {
        // On a device `user.home` is empty, which is what made the default resolver throw. Pinning
        // the inequality states the intent: the app must never be relying on that property.
        val jvmHome = System.getProperty("user.home")

        assertThat(PathUtils.getUserHomeFolder().toString()).isNotEqualTo(jvmHome)
    }

    @Test
    fun `resolved sshd home is usable as a directory`() {
        // sshd resolves `.ssh` beneath this path and may create it, so it has to be a real writable
        // directory rather than a placeholder.
        val home = PathUtils.getUserHomeFolder()

        assertThat(home).isNotNull()
        assertThat(home.isAbsolute).isTrue()
        assertThat(home.toFile().isDirectory).isTrue()
        assertThat(home.toFile().canWrite()).isTrue()
    }

    @Test
    fun `building an ssh client walks the static chain that used to crash`() {
        // ClientBuilder.<clinit> is what pulled in DefaultConfigFileHostEntryResolver ->
        // PublicKeyEntry.getDefaultKeysFolderPath -> PathUtils.getUserHomeFolder. Walking it
        // explicitly keeps the whole chain covered rather than just the leaf.
        val built = runCatching {
            ClientBuilder.builder().build()
        }

        assertThat(built.exceptionOrNull()).isNull()
        assertThat(built.getOrNull()).isNotNull()
    }
}
