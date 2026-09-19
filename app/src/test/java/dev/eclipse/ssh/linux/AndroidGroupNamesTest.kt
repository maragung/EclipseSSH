package dev.eclipse.ssh.linux

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The `/etc/group` a userspace should carry, given the groups its process is really in.
 *
 * This is the whole of the fix for `groups: cannot find name for group ID 3003` — the file is the
 * database `groups`, `id` and `ls -l` read, so naming the IDs there is what makes the message go
 * away — and it is a pure function of the file's current lines and `Os.getgroups()`, so every rule
 * it follows is pinned here rather than on a device: which IDs get a name, what the names are,
 * which lines are left alone, and which of its own lines it takes back.
 */
class AndroidGroupNamesTest {

    /** What the rootfs ships before anything in this app touches it. */
    private val stock = listOf(
        "root:x:0:",
        "daemon:x:1:",
        "nogroup:x:65534:",
    )

    @Test
    fun `the ids the session is really in are named, in order`() {
        val named = AndroidGroupNames.groupFile(stock, intArrayOf(9997, 3003, 50504, 20504))

        // Sorted by ID, derived last, so the file reads the same however the kernel ordered them.
        assertThat(named.drop(stock.size)).containsExactly(
            "android_inet:x:3003:",
            "android_everybody:x:9997:",
            "android_cache_504:x:20504:",
            "android_shared_504:x:50504:",
        ).inOrder()
        // And nothing above them moved: the account lines stay where the rootfs put them.
        assertThat(named.take(stock.size)).containsExactlyElementsIn(stock).inOrder()
    }

    @Test
    fun `an id the table does not know is still named`() {
        val named = AndroidGroupNames.groupFile(stock, intArrayOf(4242, 20505, 50505, 10151))

        // A name this object does not know is written as the number it is, never guessed at —
        // but it is written, because an unnamed ID is one more line of `cannot find name`.
        assertThat(named.drop(stock.size)).containsExactly(
            "android_gid_4242:x:4242:",
            "android_app_151:x:10151:",
            "android_cache_505:x:20505:",
            "android_shared_505:x:50505:",
        ).inOrder()
    }

    @Test
    fun `an id the rootfs already names is left alone`() {
        val named = AndroidGroupNames.groupFile(stock, intArrayOf(65534, 1))

        // 65534 is nogroup and 1 is daemon: the file already answers for both, and a second line
        // with the same ID but a different name is a file that contradicts itself.
        assertThat(named).containsExactlyElementsIn(stock).inOrder()
    }

    @Test
    fun `root is never named by us`() {
        val named = AndroidGroupNames.groupFile(stock, intArrayOf(0))

        assertThat(named).containsExactlyElementsIn(stock).inOrder()
    }

    @Test
    fun `a group the app no longer has loses its name`() {
        val withOldSet = stock + listOf("android_inet:x:3003:", "android_everybody:x:9997:")

        val named = AndroidGroupNames.groupFile(withOldSet, intArrayOf(3004))

        // The lines this object wrote before are removed before the current set is written, so a
        // permission the app lost does not leave a name behind for a group nothing is in — and a
        // group that got renamed by a newer version of the table is not named twice.
        assertThat(named).containsExactlyElementsIn(stock + "android_net_raw:x:3004:").inOrder()
    }

    @Test
    fun `naming what is already named changes nothing`() {
        val once = AndroidGroupNames.groupFile(stock, intArrayOf(3003, 9997))

        // The property the start path leans on: the second call — and every call after it — is
        // one read of a small file, and nothing is rewritten.
        assertThat(AndroidGroupNames.groupFile(once, intArrayOf(3003, 9997))).isEqualTo(once)
    }

    @Test
    fun `a repeated id is named once`() {
        val named = AndroidGroupNames.groupFile(stock, intArrayOf(3003, 3003, 3003))

        assertThat(named).containsExactlyElementsIn(stock + "android_inet:x:3003:").inOrder()
    }
}
