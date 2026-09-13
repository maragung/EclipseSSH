package dev.eclipse.ssh.linux

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The device-architecture mapping, which is the gate on the whole feature: a device that maps to
 * no distro must be told "unsupported", not offered an install that downloads a tarball nothing on
 * the phone can execute.
 *
 * The three mappings and the one deliberate refusal are each asserted directly, because each is a
 * promise to a different device: an arm64 phone (every modern one), an armv7 phone (the ones
 * Ubuntu calls armhf), and an x86_64 emulator or tablet. The refusal is `x86`: Ubuntu publishes no
 * i386 Base image, so mapping it would be a lie with a download progress bar.
 */
class LinuxDistroCatalogTest {

    @Test
    fun `arm64 devices get the arm64 rootfs`() {
        val distro = LinuxDistroCatalog.forDevice(listOf("arm64-v8a", "armeabi-v7a", "x86_64"))
        assertThat(distro).isNotNull()
        assertThat(distro!!.ubuntuArch).isEqualTo("arm64")
        assertThat(distro.id).isEqualTo(LinuxDistroCatalog.DEFAULT_DISTRO_ID)
    }

    @Test
    fun `armv7 devices get the armhf rootfs`() {
        val distro = LinuxDistroCatalog.forDevice(listOf("armeabi-v7a", "x86"))
        assertThat(distro).isNotNull()
        assertThat(distro!!.ubuntuArch).isEqualTo("armhf")
    }

    @Test
    fun `x86_64 devices get the amd64 rootfs`() {
        val distro = LinuxDistroCatalog.forDevice(listOf("x86_64", "x86"))
        assertThat(distro).isNotNull()
        assertThat(distro!!.ubuntuArch).isEqualTo("amd64")
    }

    @Test
    fun `x86-only devices are unsupported because no i386 base image exists`() {
        // The refusal, not a crash and not a fallback: `null` is what the wiring layer turns into
        // "this device cannot run the Linux userspace".
        assertThat(LinuxDistroCatalog.forDevice(listOf("x86"))).isNull()
        assertThat(LinuxDistroCatalog.forDevice(emptyList())).isNull()
        assertThat(LinuxDistroCatalog.forDevice(listOf("mips", "riscv64"))).isNull()
    }

    @Test
    fun `every catalog entry is pinned to a sha256 and an https url`() {
        // The pin is the feature's supply-chain boundary: an entry without a hash would extract
        // whatever cdimage served that day, and a non-https URL would let a network attacker
        // substitute the tarball mid-download.
        LinuxDistroCatalog.all.forEach { distro ->
            assertThat(distro.rootfsSha256).matches("[0-9a-f]{64}")
            assertThat(distro.rootfsTarballUrl).startsWith("https://")
            assertThat(distro.rootfsTarballUrl).contains(distro.ubuntuArch)
        }
    }

    @Test
    fun `the default distro id exists in the catalogue`() {
        // forDevice filters by DEFAULT_DISTRO_ID; an id nothing answers to would make every
        // device unsupported, silently.
        assertThat(LinuxDistroCatalog.all.map { it.id }).contains(LinuxDistroCatalog.DEFAULT_DISTRO_ID)
    }
}
