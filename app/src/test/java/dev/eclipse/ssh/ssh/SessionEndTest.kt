package dev.eclipse.ssh.ssh

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.net.SocketException
import org.apache.sshd.common.SshConstants
import org.apache.sshd.common.SshException
import org.junit.Test

/**
 * What the tab says after a session ends.
 *
 * Worth testing on its own because this text is the only diagnosis most users will ever get. Every
 * ending used to read "Disconnected from the remote host", which meant a bug report could not say
 * which of six quite different things had happened - and the one that mattered, a shell that ended by
 * itself, was the one being reported as a network fault.
 */
class SessionEndTest {

    @Test
    fun `a clean exit reads as an ended session`() {
        assertThat(describeSessionEnd(SessionEnd.ShellEnded(status = 0, signal = null)))
            .isEqualTo("Session ended")
    }

    @Test
    fun `a failing exit keeps its status`() {
        assertThat(describeSessionEnd(SessionEnd.ShellEnded(status = 130, signal = null)))
            .isEqualTo("Session ended (exit 130)")
    }

    @Test
    fun `a killed shell names the signal`() {
        assertThat(describeSessionEnd(SessionEnd.ShellEnded(status = null, signal = "HUP")))
            .isEqualTo("The remote shell was ended by SIGHUP")
        // OpenSSH sends the bare name; some servers prefix it. Neither may produce "SIGSIGTERM".
        assertThat(describeSessionEnd(SessionEnd.ShellEnded(status = null, signal = "SIGTERM")))
            .isEqualTo("The remote shell was ended by SIGTERM")
    }

    @Test
    fun `a shell that reported nothing says so and points at what to do next`() {
        // The user-visible half of the reconnect fix: this is the ending an account with an
        // immediately-exiting login shell produces, and the message has to say the shell went rather
        // than the network, or the user spends the evening on their Wi-Fi.
        val message = describeSessionEnd(SessionEnd.ShellEnded(status = null, signal = null))
        assertThat(message).contains("remote shell")
        assertThat(message).contains("Connect again")
        assertThat(message).doesNotContain("Disconnected from the remote host")
    }

    @Test
    fun `a server that hung up is quoted`() {
        val end = SessionEnd.Disconnected(
            reason = SshConstants.SSH2_DISCONNECT_BY_APPLICATION,
            message = "Timeout, your session not responding.",
            byPeer = true,
        )
        assertThat(describeSessionEnd(end))
            .isEqualTo("The server disconnected: Timeout, your session not responding.")
    }

    @Test
    fun `a disconnect with no message falls back to the reason it carried`() {
        val end = SessionEnd.Disconnected(
            reason = SshConstants.SSH2_DISCONNECT_MAC_ERROR,
            message = "   ",
            byPeer = false,
        )
        val message = describeSessionEnd(end)
        assertThat(message).startsWith("Disconnected: ")
        // Whatever MINA calls the code, the code itself must not be the whole answer.
        assertThat(message).isNotEqualTo("Disconnected: ")
        assertThat(message).doesNotContain("The server disconnected")
    }

    @Test
    fun `a transport failure reports the innermost sentence, which is the one that names the fault`() {
        // MINA wraps: SshException around IOException around SocketException. "Connection reset" is at
        // the bottom and is the only part a user can act on.
        val cause = SshException("Failed (IOException) to execute", IOException("write failed", SocketException("Connection reset")))
        assertThat(describeSessionEnd(SessionEnd.TransportFailed(cause)))
            .isEqualTo("Connection lost: Connection reset")
    }

    @Test
    fun `a transport failure with nothing to say still names itself`() {
        assertThat(describeSessionEnd(SessionEnd.TransportFailed(SocketException())))
            .isEqualTo("Connection lost: SocketException")
    }

    @Test
    fun `a cyclic cause chain terminates`() {
        // Defensive: a self-referencing cause used to be enough to hang a description helper, and this
        // one runs on the path that reports a session dying.
        val outer = IOException("outer")
        val inner = IOException("inner", outer)
        outer.initCause(inner)
        assertThat(describeSessionEnd(SessionEnd.TransportFailed(outer))).contains("Connection lost")
    }

    @Test
    fun `a bare transport close keeps the message it always had`() {
        assertThat(describeSessionEnd(SessionEnd.TransportClosed)).isEqualTo("Disconnected from the remote host")
        assertThat(describeSessionEnd(SessionEnd.Released)).isEqualTo("Disconnected from the remote host")
    }

    @Test
    fun `a network that went away says so, and says the app is waiting for it`() {
        // Distinct wording on purpose. "Disconnected from the remote host" invites the user to look at
        // their server; the truth is that their phone lost its network and the app is already handling it.
        assertThat(describeSessionEnd(SessionEnd.NetworkLost))
            .isEqualTo("The network went away. Reconnecting when it comes back…")
    }
}
