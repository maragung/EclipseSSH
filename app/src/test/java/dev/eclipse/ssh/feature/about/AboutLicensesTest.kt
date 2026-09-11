package dev.eclipse.ssh.feature.about

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The About dialog's licence list, kept honest. The list is the single source the dialog
 * renders, so these tests are what stands between a hand edit and a half-accurate About
 * screen.
 */
class AboutLicensesTest {

    @Test
    fun `every license has a non-empty name, version, license, and url`() {
        // An About screen that omits one of these is a partial copy of the real one. A test
        // that catches the empty case before the UI does.
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
    fun `a purpose, where one is given, is a sentence not a stub`() {
        // The purpose line is what a reader of the About screen is actually after; an empty
        // one would render as a dangling "License ·" with nothing after the dot.
        for (license in OPEN_SOURCE_LICENSES) {
            license.purpose?.let { purpose ->
                assertThat(purpose).isNotEmpty()
            }
        }
    }

    @Test
    fun `the engines the app could not exist without are all present`() {
        // The list is long enough that "a contributor forgot to add one" is the failure mode:
        // the SSH stack, both remote-desktop clients, and the language everything is written
        // in are the four that must never go missing.
        val names = OPEN_SOURCE_LICENSES.map { it.name }
        assertThat(names).contains("Apache MINA SSHD")
        assertThat(names).contains("FreeRDP")
        assertThat(names).contains("vernacular-vnc")
        assertThat(names).contains("Kotlin / Kotlin Coroutines")
    }

    @Test
    fun `the ed25519 library is attributed as public domain, not MIT`() {
        // Corrected against upstream's own licence file in September 2026 and pinned here so
        // it cannot drift back: ed25519-java is a CC0/public-domain release, and its home is
        // the str4d repository, not the i2p tree the coordinates suggest.
        val eddsa = OPEN_SOURCE_LICENSES.single { it.name == "ed25519-java" }
        assertThat(eddsa.license).isEqualTo("CC0 1.0 (public domain)")
        assertThat(eddsa.url).isEqualTo("https://github.com/str4d/ed25519-java")
    }
}
