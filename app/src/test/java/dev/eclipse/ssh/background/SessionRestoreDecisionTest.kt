package dev.eclipse.ssh.background

import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.data.model.HostProfile
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
}
