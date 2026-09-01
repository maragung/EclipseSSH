package dev.eclipse.ssh.presentation

import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.data.model.HostProfile
import org.junit.Test

/**
 * The choice behind the Quick Settings tile, the home-screen widget and the activity's
 * quick-connect: which saved host is "the last one".
 *
 * [mostRecentlyConnectedHost] is a pure function for the same reason the other resolvers in
 * MainViewModel are — the alternative is standing up a database and a view model to observe a
 * `maxByOrNull` — and it has to agree with the tile/widget's own resolver, or the three entry points
 * would dial different hosts. These pin the two properties the feature rides on: the newest
 * connection wins, and a vault with nothing to dial resolves to null so a tap opens the app normally
 * rather than connecting to a host at random.
 */
class LastHostResolutionTest {

    private fun host(name: String, lastConnectedAt: Long?) = HostProfile(
        name = name,
        host = "$name.example.com",
        username = "deploy",
        lastConnectedAt = lastConnectedAt,
    )

    @Test
    fun `the host with the greatest lastConnectedAt is chosen`() {
        val hosts = listOf(
            host("old", 1_000L),
            host("newest", 3_000L),
            host("middle", 2_000L),
        )
        assertThat(mostRecentlyConnectedHost(hosts)?.name).isEqualTo("newest")
    }

    @Test
    fun `an empty vault resolves to null`() {
        assertThat(mostRecentlyConnectedHost(emptyList())).isNull()
    }

    @Test
    fun `a vault whose hosts have never connected resolves to null`() {
        // A fresh install seeds example hosts that have never been dialled; a tile tap must open the
        // app, not silently connect to whichever one happens to sort first.
        val hosts = listOf(host("a", null), host("b", null))
        assertThat(mostRecentlyConnectedHost(hosts)).isNull()
    }

    @Test
    fun `a host that has connected beats hosts that never have`() {
        // A null lastConnectedAt is "never dialled", not "dialled at time zero": it must never win
        // over a host that has actually connected, however small that host's timestamp is.
        val hosts = listOf(
            host("never-a", null),
            host("connected", 42L),
            host("never-b", null),
        )
        assertThat(mostRecentlyConnectedHost(hosts)?.name).isEqualTo("connected")
    }

    @Test
    fun `a single connected host is chosen`() {
        assertThat(mostRecentlyConnectedHost(listOf(host("only", 5L)))?.name).isEqualTo("only")
    }
}
