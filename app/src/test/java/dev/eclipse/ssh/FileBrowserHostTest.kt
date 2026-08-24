package dev.eclipse.ssh

import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.data.model.SessionConnectionState
import dev.eclipse.ssh.data.model.SessionTab
import org.junit.Test

/**
 * [fileBrowserHostId]: which session the Files screen points at.
 *
 * The rule exists because the file browser needs an authenticated transport and the host list does not.
 * Selecting a host in Hosts, or having none selected at all on a clean install, used to leave the
 * browser aimed at a server with no session while a working one was open in another tab — with no
 * switcher on the screen, the only way out was to go back and tap a different row.
 *
 * Pure, so every rule here is asserted without a socket, a server or a frame.
 */
class FileBrowserHostTest {

    /** A selected host that has a session of its own keeps the browser. */
    @Test
    fun aSelectedHostWithASessionKeepsTheBrowser() {
        val tabs = listOf(tab("alpha"), tab("beta"))

        assertThat(fileBrowserHostId("beta", tabs)).isEqualTo("beta")
    }

    /**
     * Including a session that is not currently usable.
     *
     * A reconnecting session is one the user chose and is waiting for; moving the browser off it would
     * take the switcher's decision back the moment the network hiccupped.
     */
    @Test
    fun aSelectedSessionKeepsTheBrowserWhileItIsReconnecting() {
        val tabs = listOf(tab("alpha"), tab("beta", SessionConnectionState.RECONNECTING))

        assertThat(fileBrowserHostId("beta", tabs)).isEqualTo("beta")
    }

    /** A selected host with no session hands the browser to a live session, which can actually list. */
    @Test
    fun aSelectedHostWithoutASessionHandsTheBrowserToALiveSession() {
        val tabs = listOf(tab("alpha"))

        assertThat(fileBrowserHostId("saved-but-not-connected", tabs)).isEqualTo("alpha")
    }

    /** The first *live* one: a session still authenticating has nothing more to show than none at all. */
    @Test
    fun aSessionThatIsStillConnectingIsNotWorthSwitchingTo() {
        val tabs = listOf(tab("alpha", SessionConnectionState.CONNECTING), tab("beta"))

        assertThat(fileBrowserHostId(null, tabs)).isEqualTo("beta")
    }

    /**
     * Nothing to move to: null, so the screen keeps whatever it already resolved.
     *
     * Both the clean install and the moment after tapping Connect, when the only session there is has
     * not finished authenticating.
     */
    @Test
    fun withNothingLiveTheBrowserIsLeftWhereItIs() {
        assertThat(fileBrowserHostId("saved-but-not-connected", emptyList())).isNull()
        assertThat(fileBrowserHostId(null, emptyList())).isNull()
        assertThat(
            fileBrowserHostId("saved-but-not-connected", listOf(tab("alpha", SessionConnectionState.CONNECTING))),
        ).isNull()
    }

    private fun tab(hostId: String, state: SessionConnectionState = SessionConnectionState.CONNECTED) =
        SessionTab(hostId = hostId, title = hostId, state = state)
}
