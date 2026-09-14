package dev.eclipse.ssh.linux

/**
 * One Linux distribution the userspace can install, pinned to an exact rootfs tarball.
 *
 * The pin is the whole point: the install flow verifies the download against [rootfsSha256] before a
 * single byte of it is extracted, so the rootfs that lands on the device is exactly the rootfs this
 * build was tested with — not whatever cdimage happened to serve that day.
 *
 * Only Ubuntu Base images are offered, because those are the only root filesystems that are (a)
 * officially published by the distribution, (b) small enough for a phone (a 22.04 base is ~30 MB
 * compressed where the desktop image is ~4 GB), and (c) built without an installer, systemd or a
 * kernel expectation — everything a proot environment cannot provide.
 *
 * [release] is the Ubuntu codename (focal, jammy, …) the tarball series tracks. The distribution
 * manager derives every apt suite from it — `$release`, `$release-updates`, `$release-security` —
 * so a catalog entry is all a version needs to be installable: the rootfs pin and the archive
 * name travel together and can never disagree.
 */
data class LinuxDistro(
    /** Stable machine id, used for the installed-state directory name. */
    val id: String,
    /** What the UI shows: "Ubuntu 22.04 LTS". */
    val displayName: String,
    /** The apt codename: jammy for 22.04, noble for 24.04, and so on. */
    val release: String,
    /** The Ubuntu architecture name, as it appears in the tarball file name. */
    val ubuntuArch: String,
    /** Where the verified rootfs tarball comes from. */
    val rootfsTarballUrl: String,
    /** SHA256 of the tarball; a mismatch aborts the install. */
    val rootfsSha256: String,
)

/**
 * The catalogue of installable distributions plus the device-architecture mapping.
 *
 * Every LTS Ubuntu Base publishes — 20.04 through 26.04 — is listed, newest first, so a caller
 * reading [versionsFor] gets "the latest first" without sorting anything itself. Interim releases
 * exist on cdimage too (25.10, 26.10) and are deliberately not offered: a rootfs this app pins and
 * verifies should track the series Ubuntu maintains for five years, not the one that stops
 * receiving updates nine months after it ships.
 *
 * The default ([DEFAULT_DISTRO_ID]) stays 22.04 until the wiring layer grows a version chooser;
 * a user's existing install keeps its installed distro regardless, because the userspace state
 * file records the distro id it was installed from.
 */
object LinuxDistroCatalog {

    /**
     * One LTS series: everything the per-architecture entries share. The per-arch map exists
     * because each architecture's tarball is a separate file with its own hash — the pin is per
     * file, not per release.
     */
    private data class ReleaseSeries(
        val id: String,
        val displayName: String,
        val release: String,
        /** The point release this build was verified against; a bump must re-verify the SHA256s. */
        val pointRelease: String,
        val sha256ByArch: Map<String, String>,
    )

    private const val CDIMAGE_RELEASES_URL = "https://cdimage.ubuntu.com/ubuntu-base/releases"

    /** The architectures Ubuntu Base publishes, in catalogue order within a series. */
    private val ARCHES = listOf("amd64", "arm64", "armhf")

    /**
     * The LTS series, newest first — the order [all] and [versionsFor] inherit. SHA256s straight
     * from each release's own `SHA256SUMS`, which is published beside the tarballs. If a hash here
     * ever disagrees with a fresh download, the tarball changed and the pin is stale — the install
     * refuses rather than extracting unknown bytes.
     */
    private val series =
        listOf(
            ReleaseSeries(
                id = "ubuntu-26.04",
                displayName = "Ubuntu 26.04 LTS",
                release = "resolute",
                pointRelease = "26.04.1",
                sha256ByArch =
                    mapOf(
                        "amd64" to "a496a960472ce474a59590b8987d3a1135d3cbef1991f3b1abe8cacfea8bf85a",
                        "arm64" to "5a1906794ced63a71a8119c3f211ef5f0bbe0a243001b4bbd41fdf80c5b219fd",
                        "armhf" to "a40848e9c4ed72c22bdcb6a7bc9832e1ca8a30e5a3e2a0543dc56ed14e2e38b7",
                    ),
            ),
            ReleaseSeries(
                id = "ubuntu-24.04",
                displayName = "Ubuntu 24.04 LTS",
                release = "noble",
                pointRelease = "24.04.5",
                sha256ByArch =
                    mapOf(
                        "amd64" to "e77b6f10c2590cef872b33ee9f635a0e3fd1f57fb074c0e52b5c7f56147a0c86",
                        "arm64" to "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2",
                        "armhf" to "4fcee4d278f1c5232e085a021a85e4c6cef3853557a88d98ff380b5e5d5841bb",
                    ),
            ),
            ReleaseSeries(
                id = "ubuntu-22.04",
                displayName = "Ubuntu 22.04 LTS",
                release = "jammy",
                pointRelease = "22.04.5",
                sha256ByArch =
                    mapOf(
                        "amd64" to "242cd8898b33ea806ef5f13b1076ed7c76f9f989d18384452f7166692438ff1a",
                        "arm64" to "075d4abd2817a5023ab0a82f5cb314c5ec0aa64a9c0b40fd3154ca3bfdae979f",
                        "armhf" to "fd77cb0659326b75c08ce06b6b8649d2e13ef9a704a8e9212fec32cb97d42add",
                    ),
            ),
            ReleaseSeries(
                id = "ubuntu-20.04",
                displayName = "Ubuntu 20.04 LTS",
                release = "focal",
                pointRelease = "20.04.5",
                sha256ByArch =
                    mapOf(
                        "amd64" to "60e216b60947653dc8989be3821380268315b99b2959c29882781541bfe5a426",
                        "arm64" to "f9b999afb4c4b10193087ea8c11be36d688f19e609b05179b571f29357954b52",
                        "armhf" to "6bcbfa7f603d79d368d40e138dad98938907d2fb0d6416521417cf8702c2f5de",
                    ),
            ),
        )

    /** Everything the app can install, newest LTS series first, keyed by nothing — see [byId]. */
    val all: List<LinuxDistro> =
        series.flatMap { s ->
            ARCHES.map { arch ->
                LinuxDistro(
                    id = s.id,
                    displayName = s.displayName,
                    release = s.release,
                    ubuntuArch = arch,
                    rootfsTarballUrl =
                        "$CDIMAGE_RELEASES_URL/${s.id.removePrefix("ubuntu-")}/release/" +
                            "ubuntu-base-${s.pointRelease}-base-$arch.tar.gz",
                    rootfsSha256 = s.sha256ByArch.getValue(arch),
                )
            }
        }

    /**
     * Every LTS this device can run, newest first — the version chooser's list. Empty when the
     * device maps to no Ubuntu architecture, which the UI renders as "unsupported" rather than a
     * chooser with nothing in it.
     *
     * [supportedAbis] is injectable because `Build.SUPPORTED_ABIS` is a device fact a JVM test
     * cannot produce; the first entry is the device's primary ABI, which is the one the proot
     * binaries in `nativeLibraryDir` are built for.
     */
    fun versionsFor(supportedAbis: List<String>): List<LinuxDistro> {
        val ubuntuArch = supportedAbis.firstOrNull()?.toUbuntuArch() ?: return emptyList()
        return all.filter { it.ubuntuArch == ubuntuArch }
    }

    /**
     * The distro the "Local Ubuntu" entry point installs before a version chooser exists. One
     * distribution per device, resolved the same way [versionsFor] resolves the list.
     */
    fun forDevice(supportedAbis: List<String>): LinuxDistro? {
        val primary = supportedAbis.firstOrNull() ?: return null
        val ubuntuArch = primary.toUbuntuArch() ?: return null
        return all.firstOrNull { it.id == DEFAULT_DISTRO_ID && it.ubuntuArch == ubuntuArch }
    }

    /** One exact catalog entry, or null when the id/arch pair names nothing. */
    fun byId(id: String, ubuntuArch: String): LinuxDistro? =
        all.firstOrNull { it.id == id && it.ubuntuArch == ubuntuArch }

    /** The Android ABI name to Ubuntu's, or null where Ubuntu publishes no Base image for it. */
    private fun String.toUbuntuArch(): String? =
        when (this) {
            "arm64-v8a" -> "arm64"
            "armeabi-v7a" -> "armhf"
            "x86_64" -> "amd64"
            // Deliberately unmapped: Ubuntu publishes no i386 Base image, so an x86 device gets no
            // userspace and the UI reports it as unsupported rather than offering an install that
            // cannot finish.
            else -> null
        }

    /** The distro the "Local Ubuntu" entry point installs. One distribution until there are two. */
    const val DEFAULT_DISTRO_ID = "ubuntu-22.04"
}
