package dev.eclipse.ssh.linux

/**
 * One Linux distribution the userspace can install, pinned to an exact rootfs tarball.
 *
 * The pin is the whole point: the install flow verifies the download against [rootfsSha256] before a
 * single byte of it is extracted, so the rootfs that lands on the device is exactly the rootfs this
 * build was tested with — not whatever cdimage happened to serve that day.
 *
 * Only Ubuntu Base images are offered, because those are the only root filesystems that are (a)
 * officially published by the distribution, (b) small enough for a phone (the 22.04 base is ~30 MB
 * compressed where the desktop image is ~4 GB), and (c) built without an installer, systemd or a
 * kernel expectation — everything a proot environment cannot provide.
 */
data class LinuxDistro(
    /** Stable machine id, used for the installed-state directory name. */
    val id: String,
    /** What the UI shows: "Ubuntu 22.04 LTS". */
    val displayName: String,
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
 * One entry today — Ubuntu 22.04 LTS, the distribution the feature spec names — with the shape of a
 * list so adding 24.04 later is an entry, not a redesign.
 */
object LinuxDistroCatalog {

    /** The base URL every 22.04.x point release publishes under. */
    private const val UBUNTU_22_04_RELEASE_URL =
        "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release"

    /** The point release this build was verified against; a bump must re-verify the SHA256s. */
    private const val UBUNTU_22_04_POINT_RELEASE = "22.04.5"

    /**
     * SHA256s straight from the release's own `SHA256SUMS`, which is published beside the tarballs.
     * If a hash here ever disagrees with a fresh download, the tarball changed and the pin is stale —
     * the install refuses rather than extracting unknown bytes.
     */
    private val UBUNTU_22_04_SHA256 =
        mapOf(
            "amd64" to "242cd8898b33ea806ef5f13b1076ed7c76f9f989d18384452f7166692438ff1a",
            "arm64" to "075d4abd2817a5023ab0a82f5cb314c5ec0aa64a9c0b40fd3154ca3bfdae979f",
            "armhf" to "fd77cb0659326b75c08ce06b6b8649d2e13ef9a704a8e9212fec32cb97d42add",
        )

    /** Everything the app can install, keyed by [LinuxDistro.id]. */
    val all: List<LinuxDistro> =
        listOf(
            LinuxDistro(
                id = "ubuntu-22.04",
                displayName = "Ubuntu 22.04 LTS",
                ubuntuArch = "amd64",
                rootfsTarballUrl =
                    "$UBUNTU_22_04_RELEASE_URL/ubuntu-base-$UBUNTU_22_04_POINT_RELEASE-base-amd64.tar.gz",
                rootfsSha256 = UBUNTU_22_04_SHA256.getValue("amd64"),
            ),
            LinuxDistro(
                id = "ubuntu-22.04",
                displayName = "Ubuntu 22.04 LTS",
                ubuntuArch = "arm64",
                rootfsTarballUrl =
                    "$UBUNTU_22_04_RELEASE_URL/ubuntu-base-$UBUNTU_22_04_POINT_RELEASE-base-arm64.tar.gz",
                rootfsSha256 = UBUNTU_22_04_SHA256.getValue("arm64"),
            ),
            LinuxDistro(
                id = "ubuntu-22.04",
                displayName = "Ubuntu 22.04 LTS",
                ubuntuArch = "armhf",
                rootfsTarballUrl =
                    "$UBUNTU_22_04_RELEASE_URL/ubuntu-base-$UBUNTU_22_04_POINT_RELEASE-base-armhf.tar.gz",
                rootfsSha256 = UBUNTU_22_04_SHA256.getValue("armhf"),
            ),
        )

    /**
     * The distro this device can run, or `null` when it cannot run one at all.
     *
     * Maps the Android ABI to Ubuntu's architecture names: `arm64-v8a` → `arm64`, `armeabi-v7a` →
     * `armhf`, `x86_64` → `amd64`. `x86` is deliberately unmapped — Ubuntu publishes no i386 Base
     * image, so an x86 device gets no userspace and the UI reports it as unsupported rather than
     * offering an install that cannot finish.
     *
     * [supportedAbis] is injectable because `Build.SUPPORTED_ABIS` is a device fact a JVM test
     * cannot produce; the first entry is the device's primary ABI, which is the one the proot
     * binaries in `nativeLibraryDir` are built for.
     */
    fun forDevice(supportedAbis: List<String>): LinuxDistro? {
        val primary = supportedAbis.firstOrNull() ?: return null
        val ubuntuArch =
            when (primary) {
                "arm64-v8a" -> "arm64"
                "armeabi-v7a" -> "armhf"
                "x86_64" -> "amd64"
                else -> return null
            }
        return all.firstOrNull { it.id == DEFAULT_DISTRO_ID && it.ubuntuArch == ubuntuArch }
    }

    /** The distro the "Local Ubuntu" entry point installs. One distribution until there are two. */
    const val DEFAULT_DISTRO_ID = "ubuntu-22.04"
}
