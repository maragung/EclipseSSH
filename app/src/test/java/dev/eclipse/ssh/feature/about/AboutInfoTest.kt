package dev.eclipse.ssh.feature.about

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AboutInfoTest {

    @Test
    fun `versionLine combines name, versionName and versionCode`() {
        val info = AboutInfo(
            appName = "EclipseSSH",
            versionName = "1.1.5",
            versionCode = 13,
            minSdk = 28,
            targetSdk = 35,
            kotlinVersion = "2.1.20",
            agpVersion = "8.9.1",
            gradleVersion = "8.11.1",
            sshdVersion = "2.14.0",
            composeBom = "2025.04.01",
            hiltVersion = "2.56.1",
            roomVersion = "2.7.1",
        )
        assertThat(info.versionLine).isEqualTo("EclipseSSH 1.1.5 (13)")
    }

    @Test
    fun `every license has a non-empty name, version, license, and url`() {
        // An About screen that omits one of these is a partial copy of
        // the real one. A test that catches the empty case before the
        // UI does.
        for (license in OPEN_SOURCE_LICENSES) {
            assertThat(license.name).isNotEmpty()
            assertThat(license.version).isNotEmpty()
            assertThat(license.license).isNotEmpty()
            assertThat(license.url).isNotEmpty()
            assertThat(license.url).startsWith("https://")
        }
    }

    @Test
    fun `every license is listed once`() {
        val names = OPEN_SOURCE_LICENSES.map { it.name }
        assertThat(names).containsNoDuplicates()
    }

    @Test
    fun `primary licenses are all present`() {
        // The brief calls out a couple of big ones; the list is long
        // enough that "a contributor forgot to add one" is the failure
        // mode.
        val names = OPEN_SOURCE_LICENSES.map { it.name }
        assertThat(names).contains("Apache MINA SSHD")
        assertThat(names).contains("Kotlin / Kotlin Coroutines")
        assertThat(names).contains("AndroidX")
    }
}
