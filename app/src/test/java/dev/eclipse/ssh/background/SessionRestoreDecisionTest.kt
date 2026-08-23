package dev.eclipse.ssh.background

import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.data.model.HostProfile
import dev.eclipse.ssh.data.model.RECONNECT_BACKOFF_RANGE
import org.junit.Test

/**
 * Which hosts the foreground service is allowed to dial when it restores sessions.
 *
 * This is the decision that used to open a second SSH session on every single Connect tap. The tap
 * starts the service, the service asked the registry which hosts should be up, and the registry -
 * correctly - named the host the app had just authenticated a moment earlier. The service could not
 * see that session because it kept its own map, so it logged in again: two authentications per tap,
 * two heartbeats, two entries against the server's `MaxSessions`, and one of them orphaned with no
 * tab attached to it.
 *
 * The fix was a shared [dev.eclipse.ssh.ssh.SshSessionStore] and this filter. The rule is small
 * enough to state and important enough to pin down, so it is a pure function taking the liveness
 * probe as a parameter.
 */
class SessionRestoreDecisionTest {

    private fun host(id: String) = HostProfile(id = id, name = id, host = "127.0.0.1", username = "u")

    private val hosts = listOf(host("a"), host("b"), host("c"))

    @Test
    fun `a registered host the app already holds is not dialled again`() {
        val pending = hostsNeedingRestore(hosts, activeIds = setOf("a", "b"), isLive = { it == "a" })

        assertThat(pending.map { it.id }).containsExactly("b")
    }

    @Test
    fun `nothing is dialled when every registered session is already live`() {
        val pending = hostsNeedingRestore(hosts, activeIds = setOf("a", "b"), isLive = { true })

        assertThat(pending).isEmpty()
    }

    @Test
    fun `a registered host with no live session is restored`() {
        val pending = hostsNeedingRestore(hosts, activeIds = setOf("a", "c"), isLive = { false })

        assertThat(pending.map { it.id }).containsExactly("a", "c").inOrder()
    }

    @Test
    fun `a host that is not registered as active is never dialled`() {
        // The registry is the record of what the user asked to keep up. A profile that merely exists
        // in the host list must not be connected by a service restart.
        val pending = hostsNeedingRestore(hosts, activeIds = emptySet(), isLive = { false })

        assertThat(pending).isEmpty()
    }

    @Test
    fun `a registered id whose profile was deleted is skipped rather than crashing`() {
        val pending = hostsNeedingRestore(hosts, activeIds = setOf("a", "gone"), isLive = { false })

        assertThat(pending.map { it.id }).containsExactly("a")
    }

    @Test
    fun `a host that switched auto reconnect off is not dialled unattended`() {
        // This pass is the most unattended dialler in the app - it runs from a connectivity callback
        // while the phone is in a pocket - so it is exactly what a host that "must never be dialled
        // unattended" is switching off. Reconnect by hand still works; nothing here does it for them.
        val hosts = listOf(host("a").copy(autoReconnect = false), host("b"))

        val pending = hostsNeedingRestore(hosts, activeIds = setOf("a", "b"), isLive = { false })

        assertThat(pending.map { it.id }).containsExactly("b")
    }

    @Test
    fun `the pass waits the shortest delay any pending host asked for`() {
        // One wait serves every host in the pass, so a host that asked to retry after two seconds must
        // not be held behind another host's minute.
        val hosts = listOf(
            host("slow").copy(reconnectBackoffSeconds = 60),
            host("quick").copy(reconnectBackoffSeconds = 2),
        )

        assertThat(restoreBaseSeconds(hosts, globalSeconds = 30)).isEqualTo(2)
    }

    @Test
    fun `a host that never chose a delay follows Settings`() {
        assertThat(restoreBaseSeconds(listOf(host("a")), globalSeconds = 45)).isEqualTo(45)
        // Including against a host that did choose one, when Settings is the shorter of the two.
        val mixed = listOf(host("a"), host("b").copy(reconnectBackoffSeconds = 60))
        assertThat(restoreBaseSeconds(mixed, globalSeconds = 45)).isEqualTo(45)
    }

    @Test
    fun `a pass with nothing pending still has a delay to report`() {
        // The notification interpolates this number, and the pass can reach the wait with only busy
        // hosts in it.
        assertThat(restoreBaseSeconds(emptyList(), globalSeconds = 45)).isEqualTo(45)
    }

    @Test
    fun `a host carrying a delay no form would have accepted is clamped`() {
        val hosts = listOf(host("a").copy(reconnectBackoffSeconds = 9_999))

        assertThat(restoreBaseSeconds(hosts, globalSeconds = 30))
            .isEqualTo(RECONNECT_BACKOFF_RANGE.last)
    }
}
