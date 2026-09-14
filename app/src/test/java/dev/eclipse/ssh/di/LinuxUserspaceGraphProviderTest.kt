package dev.eclipse.ssh.di

import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.linux.LinuxDistroCatalog
import org.junit.Test

/**
 * The graph's distro resolution, as a pure function of its inputs. The order is not a preference:
 * the manager's own state check compares against the graph's distro, so an installed rootfs must
 * resolve to *its* distro or every process start after an install would report Not Installed —
 * which is exactly the bug this order exists to prevent.
 */
class LinuxUserspaceGraphProviderTest {

    private val versions = LinuxDistroCatalog.versionsFor(listOf("arm64-v8a", "armeabi-v7a"))

    @Test
    fun `the installed distro wins over the pick and the default`() {
        val resolved = LinuxUserspaceGraphProvider.resolveDistro(
            versions = versions,
            installedDistroId = "ubuntu-20.04",
            selectedDistroId = "ubuntu-26.04",
        )

        assertThat(resolved!!.id).isEqualTo("ubuntu-20.04")
    }

    @Test
    fun `the user's pick wins over the default`() {
        val resolved = LinuxUserspaceGraphProvider.resolveDistro(
            versions = versions,
            installedDistroId = null,
            selectedDistroId = "ubuntu-24.04",
        )

        assertThat(resolved!!.id).isEqualTo("ubuntu-24.04")
    }

    @Test
    fun `with nothing recorded the default is resolved`() {
        val resolved = LinuxUserspaceGraphProvider.resolveDistro(versions, null, null)

        assertThat(resolved!!.id).isEqualTo(LinuxDistroCatalog.DEFAULT_DISTRO_ID)
    }

    @Test
    fun `an id this device cannot run falls to the next rung`() {
        // "Installed" on an id the catalogue no longer carries (or never had for this arch) must
        // not fail the graph — the next rung answers, and the manager's state check remains the
        // authority on whether anything is actually installed.
        val staleInstalled = LinuxUserspaceGraphProvider.resolveDistro(
            versions = versions,
            installedDistroId = "ubuntu-18.04",
            selectedDistroId = "ubuntu-26.04",
        )
        assertThat(staleInstalled!!.id).isEqualTo("ubuntu-26.04")

        val stalePick = LinuxUserspaceGraphProvider.resolveDistro(versions, null, "ubuntu-18.04")
        assertThat(stalePick!!.id).isEqualTo(LinuxDistroCatalog.DEFAULT_DISTRO_ID)
    }

    @Test
    fun `a device with no versions resolves nothing`() {
        val resolved = LinuxUserspaceGraphProvider.resolveDistro(emptyList(), "ubuntu-24.04", "ubuntu-24.04")

        assertThat(resolved).isNull()
    }
}
