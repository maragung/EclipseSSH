package dev.eclipse.ssh.linux

import java.io.File

/**
 * The names a userspace gives the Android group IDs its own process is in.
 *
 * A proot session really is in the groups the app process is in. `-0` fakes the identity a program
 * *asks for* — `getuid`, `geteuid`, `getgid`, `getegid` — and does not touch `getgroups`, which the
 * kernel answers from the process itself. So the shell inside the rootfs is in `AID_INET` (3003),
 * in `AID_EVERYBODY` (9997), and in the per-app cache and shared groups the platform gives every
 * app process (`AID_CACHE_GID_START` 20000 and `AID_SHARED_GID_START` 50000, each plus the app id).
 * None of them has a line in the rootfs's `/etc/group` — a stock Ubuntu Base group file plus the
 * single `ubuntu:` line [UbuntuDistributionManager.registerUbuntuUser] writes — so `groups`, `id`
 * and `ls -l` report each one by number and print
 * `groups: cannot find name for group ID 3003` on stderr, once per unnamed ID.
 *
 * That is a naming gap, not a permissions one: the session had those groups all along, and what
 * complains is reading `/etc/group` — the database this object writes. Which is why naming them
 * there is the fix, rather than hiding them from the session instead. Hiding is possible (the
 * pinned proot fork carries a `getgroups`/`setgroups` handler written for this exact complaint, in
 * `extension/fake_id0/fake_id0.c`, under the comment "On Android, the system is returning gids that
 * our rootfs knows nothing about which is generating errors"), but it sits behind an `#ifdef
 * USERLAND` that no build file in the fork defines, and enabling that flag wholesale would also
 * compile out the `chown` emulation dpkg needs to unpack anything at all.
 *
 * Which IDs those are is [selfGroups]'s question and how to name them is [groupFile]'s; both are
 * pure functions of text — a `/proc` status body, a group file — so every rule either follows is
 * pinned by a JVM test rather than by a device.
 *
 * The label is [PREFIX] plus the platform's own name for the ID wherever Android has one (the
 * numbers and the names come from `libcutils/include/private/android_filesystem_config.h`), the
 * derived name of the range for the two IDs the platform computes from the app id, and
 * `android_gid_<n>` for anything else — a group this file does not know is written as the number it
 * is, never guessed at. An ID the rootfs already names is left alone, and every line carrying
 * [PREFIX] is dropped before the current set is written, so a group the app no longer has does not
 * stay named in the rootfs forever.
 */
internal object AndroidGroupNames {

    /** What every line this object writes starts with: the mark that says which lines are ours. */
    const val PREFIX = "android_"

    /** The label of the `/proc/<pid>/status` line that carries the supplementary group IDs. */
    private const val GROUPS = "Groups:"

    /**
     * The whole of `/etc/group`, given what it says today and the groups the app process is in.
     *
     * Pure and total: [existing] is the file's current lines, [gids] whatever [groupsIn] read off
     * the process, and the result is what the file should say. A caller writes it when the result
     * differs from [existing], and does nothing when it does not.
     */
    fun groupFile(existing: List<String>, gids: IntArray): List<String> {
        // Ours from a previous run first, so a device that lost a permission loses its line too,
        // and so a group ID renamed by a newer version of this object is not named twice.
        val kept = existing.filterNot { it.startsWith(PREFIX) }
        val named = kept.mapNotNullTo(mutableSetOf()) { gidOf(it) }
        val appended = gids.asSequence()
            // GID 0 is root, which the rootfs's own group file already names; and a supplementary
            // list never carries the primary gid, so nothing here is the `ubuntu` account itself.
            .filter { it > 0 && it !in named }
            .distinct()
            .sorted()
            .map { "${label(it)}:x:$it:" }
            .toList()
        return kept + appended
    }

    /** The group ID a `/etc/group` line names — its third colon-separated field — or null. */
    private fun gidOf(line: String): Int? = line.split(':').getOrNull(2)?.trim()?.toIntOrNull()

    /**
     * The supplementary group IDs in a `/proc/<pid>/status` body: the `Groups:` line, which is the
     * same answer `getgroups` reads.
     *
     * From the file rather than from `android.system.Os.getgroups()`, because that method is not in
     * the public SDK — android-37.0's `android.jar` carries `getuid`, `geteuid`, `getgid` and
     * `getegid` and no `getgroups` at all, so calling it does not compile. `/proc/self/status` is
     * the kernel's own account of the process, needs no permission and no hidden API, and an app
     * reading it about itself is the plainest form of the question. The line is tab-separated from
     * its label and space-separated between IDs, so the label is dropped and each token trimmed
     * before it is read as a number.
     *
     * Total: no `Groups:` line, or a token that is not a number, answers with the IDs that could be
     * read — an empty array when that is none of them.
     */
    fun groupsIn(procStatus: String): IntArray {
        val line = procStatus.lineSequence().firstOrNull { it.startsWith(GROUPS) } ?: return IntArray(0)
        return line.removePrefix(GROUPS).split(' ').mapNotNull { it.trim().toIntOrNull() }.toIntArray()
    }

    /**
     * The supplementary groups of the calling process, as `/proc/self/status` reports them.
     *
     * The empty array on any failure — an unreadable `/proc`, a status this platform words
     * differently — because nothing here is fatal: naming no groups is exactly what the userspace
     * did before this object existed, and a session whose `/etc/group` is a little short is a
     * cosmetic difference, not a broken install.
     */
    fun selfGroups(status: File = File("/proc/self/status")): IntArray =
        runCatching { groupsIn(status.readText()) }.getOrDefault(IntArray(0))

    /**
     * What one Android group ID is called inside the rootfs. The table below is a courtesy rather
     * than the contract: an ID it does not know is named too, as `android_gid_<n>`, because every
     * unnamed ID is one more line of `cannot find name` for the user to read.
     */
    private fun label(gid: Int): String = PREFIX + when {
        gid in AID_NAMES -> AID_NAMES.getValue(gid)
        gid in CACHE_GIDS -> "cache_${gid - CACHE_GIDS.first}"
        gid in SHARED_GIDS -> "shared_${gid - SHARED_GIDS.first}"
        gid in APP_UIDS -> "app_${gid - APP_UIDS.first}"
        else -> "gid_$gid"
    }

    /**
     * `AID_CACHE_GID_START`…`AID_CACHE_GID_END` — the cache group of every app on the device, its
     * app id added to the start — and `AID_SHARED_GID_START`…`AID_SHARED_GID_END`, which is the
     * group apps in one profile share. Both are derived here rather than listed because the app id
     * is a property of the device's install, not a constant.
     */
    private val CACHE_GIDS = 20_000..29_999
    private val SHARED_GIDS = 50_000..59_999

    /**
     * `AID_APP_START`…`AID_APP_END`: the uids, and primary gids, of the apps themselves. An app is
     * never in *its own* supplementary groups, so this range is for a session that is in another
     * app's group — a shared uid, a bound directory whose owner is another app — and is here
     * mostly so that such an ID lands on a name that says what it is.
     */
    private val APP_UIDS = 10_000..19_999

    /**
     * The Android group IDs an app process can be in, under the platform's own names. A group gets
     * here because a permission assigns it — `inet` for `INTERNET`, the Bluetooth pair for
     * `BLUETOOTH` and `BLUETOOTH_ADMIN`, the storage ones for `WRITE_EXTERNAL_STORAGE` on the
     * releases before scoped storage — or because it is in every app process on the device, as
     * `everybody` is.
     */
    private val AID_NAMES = mapOf(
        1015 to "sdcard_rw",
        1023 to "media_rw",
        1028 to "sdcard_r",
        1078 to "ext_data_rw",
        1079 to "ext_obb_rw",
        3001 to "net_bt_admin",
        3002 to "net_bt",
        3003 to "inet",
        3004 to "net_raw",
        3005 to "net_admin",
        3006 to "net_bw_stats",
        3007 to "net_bw_acct",
        9997 to "everybody",
    )
}
