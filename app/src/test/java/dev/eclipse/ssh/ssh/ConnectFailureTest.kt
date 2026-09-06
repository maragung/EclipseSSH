package dev.eclipse.ssh.ssh

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException
import org.apache.sshd.common.SshConstants
import org.apache.sshd.common.SshException
import org.junit.Test

/**
 * Which connect failures the retry loop is allowed to try again.
 *
 * The distinction is not cosmetic. Retrying used to be unconditional, so a mistyped password went to
 * the server three times — enough to lock an account or earn a `fail2ban` ban for a typo — and the
 * user waited out both backoff delays for a verdict the server had reached immediately. The other
 * direction matters just as much: a phone that lost its network for two seconds must still get its
 * three attempts, so this asserts the transient cases stay retryable rather than only asserting the
 * final ones stop.
 */
class ConnectFailureTest {

    @Test
    fun `a rejected credential is not offered again`() {
        val byCode = SshException(
            SshConstants.SSH2_DISCONNECT_NO_MORE_AUTH_METHODS_AVAILABLE,
            "No more authentication methods available",
        )
        assertThat(connectFailureIsFinal(byCode)).isTrue()
        // The same rejection raised locally, without a disconnect packet to carry the code.
        assertThat(connectFailureIsFinal(SshException("No more authentication methods available"))).isTrue()
        assertThat(connectFailureIsFinal(SshException(SshConstants.SSH2_DISCONNECT_ILLEGAL_USER_NAME, "no such user"))).isTrue()
    }

    /**
     * Which failures mean "the credential was refused" — the set the login-failure dialog answers.
     *
     * The boundary has to hold in both directions. Every refusal marker, wherever it sits in the
     * cause chain, must count: a server that says "Permission denied" over keyboard-interactive and
     * a PAM stack that reports "authentication failed" from inside MINA are both wrong-password
     * moments the user can fix by typing. And every transport failure must stay out, because the
     * dialog for those would ask for a password the network has no use for.
     */
    @Test
    fun `a refused credential is told apart from a transport failure`() {
        val refusals = listOf(
            SshException(SshConstants.SSH2_DISCONNECT_NO_MORE_AUTH_METHODS_AVAILABLE, "No more authentication methods available"),
            SshException("No more authentication methods available"),
            SshException("Permission denied"),
            IOException("wrapped", SshException("Authentication failed")),
            SshException("Too many authentication failures"),
        )
        refusals.forEach { error ->
            assertThat(isCredentialRejection(error)).isTrue()
        }

        val transport = listOf(
            null,
            ConnectException("Connection refused"),
            SocketTimeoutException("connect timed out"),
            UnknownHostException("no.such.host"),
            TimeoutException("timeout"),
            IOException("Connection reset by peer"),
            SshException(SshConstants.SSH2_DISCONNECT_KEY_EXCHANGE_FAILED, "Unable to negotiate"),
            SshException("Server key did not validate"),
        )
        transport.forEach { error ->
            assertThat(isCredentialRejection(error)).isFalse()
        }
    }

    @Test
    fun `a refused login names the credential as what was rejected`() {
        val message = describeConnectFailure(
            SshException(
                SshConstants.SSH2_DISCONNECT_NO_MORE_AUTH_METHODS_AVAILABLE,
                "No more authentication methods available",
            ),
        )
        // The sentence says what to fix rather than quoting the protocol's reason code; the server's
        // own text survives it for anyone diagnosing the account.
        assertThat(message).contains("refused the login")
        assertThat(message).contains("password or key")
        assertThat(message).contains("No more authentication methods available")
    }

    @Test
    fun `an unverified host key is not retried behind the challenge dialog`() {
        assertThat(
            connectFailureIsFinal(
                SshException(SshConstants.SSH2_DISCONNECT_HOST_KEY_NOT_VERIFIABLE, "Server key did not validate"),
            ),
        ).isTrue()
        assertThat(connectFailureIsFinal(SshException("Server key did not validate"))).isTrue()
    }

    @Test
    fun `a failed algorithm negotiation is not retried`() {
        val negotiation = SshException(
            SshConstants.SSH2_DISCONNECT_KEY_EXCHANGE_FAILED,
            "Unable to negotiate key exchange for encryption client to server (client: aes128-ctr / server: aes256-cbc)",
        )
        assertThat(connectFailureIsFinal(negotiation)).isTrue()
    }

    @Test
    fun `a wrapped final failure is still recognised`() {
        val wrapped = IOException("connect failed", SshException("Server key did not validate"))
        assertThat(connectFailureIsFinal(wrapped)).isTrue()
    }

    @Test
    fun `a cause chain that points at itself does not hang the classifier`() {
        // Not hypothetical enough to ignore: some libraries initialise a cause to the exception
        // itself. Walking the chain unbounded would spin here instead of returning a verdict.
        val looping = object : IOException("stalled") {
            override val cause: Throwable get() = this
        }
        assertThat(connectFailureIsFinal(looping)).isFalse()
    }

    @Test
    fun `transient failures keep all three attempts`() {
        val transient = listOf(
            SocketTimeoutException("connect timed out"),
            ConnectException("Connection refused"),
            UnknownHostException("no.such.host"),
            TimeoutException("timeout"),
            IOException("Connection reset by peer"),
            SshException(SshConstants.SSH2_DISCONNECT_CONNECTION_LOST, "connection lost"),
            SshException(SshConstants.SSH2_DISCONNECT_MAC_ERROR, "corrupt packet"),
        )
        transient.forEach { error ->
            assertThat(connectFailureIsFinal(error)).isFalse()
        }
    }

    @Test
    fun `a negotiation failure points the user at the setting that fixes it`() {
        val message = describeConnectFailure(
            SshException(
                SshConstants.SSH2_DISCONNECT_KEY_EXCHANGE_FAILED,
                "Unable to negotiate key exchange for encryption client to server",
            ),
        )
        assertThat(message).contains("Legacy algorithms")
        // The hint comes first, but SSHD's own text has to survive: it is the only place the missing
        // algorithm is actually named.
        assertThat(message).contains("Unable to negotiate key exchange for encryption client to server")
        assertThat(message.indexOf("Legacy algorithms")).isLessThan(message.indexOf("Unable to negotiate"))
    }

    /**
     * The SFTP failures a user is most likely to hit, turned into sentences that say what still works.
     *
     * Separate from [describeConnectFailure] because the two mean opposite things. A connect failure is
     * the session; an SFTP failure is one channel on a session that is up, so every sentence here has
     * to leave the user knowing the terminal is fine — otherwise a locked-down account looks like a
     * broken app. The marker strings are what Apache MINA and OpenSSH actually put in the message when
     * a subsystem is not offered.
     */
    @Test
    fun `an unavailable sftp subsystem is explained without blaming the session`() {
        val refusals = listOf(
            SshException("Failed to open subsystem sftp"),
            SshException("SSH_OPEN_UNKNOWN_CHANNEL_TYPE"),
            SshException("open failed: SSH_OPEN_ADMINISTRATIVELY_PROHIBITED"),
            IllegalStateException("unknown channel type"),
        )
        refusals.forEach { error ->
            val message = describeSftpFailure(error)
            assertThat(message).contains("does not offer SFTP")
            assertThat(message).contains("terminal still works")
        }
    }

    @Test
    fun `an account refused sftp is told so, and told the shell is unaffected`() {
        val message = describeSftpFailure(IOException("Permission denied"))

        assertThat(message).contains("not allowed to use SFTP")
        assertThat(message).contains("terminal still works")
    }

    @Test
    fun `a session that dropped before sftp started says that rather than blaming the server`() {
        listOf(
            SshException(SshConstants.SSH2_DISCONNECT_CONNECTION_LOST, "connection lost"),
            IOException("Session is closed"),
        ).forEach { error ->
            assertThat(describeSftpFailure(error)).isEqualTo("The connection closed before SFTP could start.")
        }
    }

    /**
     * A cause deeper than the message is still read. MINA wraps the channel failure in an
     * `SshException` whose own text names nothing, so matching only the top-level message would send
     * the raw wrapper to the screen.
     */
    @Test
    fun `a wrapped subsystem refusal is still recognised through its cause`() {
        val wrapped = IOException("SFTP channel failed", SshException("Failed to open subsystem sftp"))

        assertThat(describeSftpFailure(wrapped)).contains("does not offer SFTP")
    }

    @Test
    fun `an sftp failure with nothing to say still produces a line`() {
        // Never an empty status message, and never a bare "null": both go on screen verbatim.
        assertThat(describeSftpFailure(null)).isEqualTo("SFTP is unavailable")
        assertThat(describeSftpFailure(IOException())).isEqualTo("IOException")
        assertThat(describeSftpFailure(IOException("   "))).isEqualTo("IOException")
    }

    @Test
    fun `other failures are reported as they came`() {
        assertThat(describeConnectFailure(ConnectException("Connection refused"))).isEqualTo("Connection refused")
        run {
            // The one message that is replaced rather than passed through. MINA's text names an internal
            // exception class and prints both socket addresses - the second of which is the device's own
            // address on the local network - and says nothing a user can act on.
            val raw = "DefaultConnectFuture[me@/10.1.2.3:22]: Failed (MissingAttachedSessionException) " +
                "to execute: No session attached to Nio2Session[local=/192.168.1.24:41234, remote=/10.1.2.3:22]"
            val described = describeConnectFailure(SshException(raw))
            assertThat(described).contains("closed it during the SSH handshake")
            assertThat(described).doesNotContain("192.168.1.24")
            assertThat(described).doesNotContain("MissingAttachedSessionException")
        }
    }

    /**
     * Which failures happened *at* the handshake, as against on the way to it.
     *
     * The interoperability tests assert with this that a server configured to share no algorithm actually
     * refused, so it has to hold the line in both directions: everything that means "reached the server
     * and was turned away" is true, and everything that means "never got that far" is false. A refused
     * port or an unstarted server passing here would let those tests go green while proving nothing.
     */
    @Test
    fun `a refusal at the handshake is told apart from never reaching one`() {
        assertThat(failedDuringSshHandshake(SshException("Unable to negotiate key exchange"))).isTrue()
        assertThat(
            failedDuringSshHandshake(
                SshException(SshConstants.SSH2_DISCONNECT_KEY_EXCHANGE_FAILED, "no common cipher"),
            ),
        ).isTrue()
        // The same refusal when the socket dies before the disconnect packet lands - the case that made
        // a string match on "negotiate" flaky on a loaded machine.
        assertThat(failedDuringSshHandshake(SshException("Failed (MissingAttachedSessionException) to execute"))).isTrue()
        assertThat(failedDuringSshHandshake(IOException("wrapped", SshException("No session attached to Nio2Session")))).isTrue()

        assertThat(failedDuringSshHandshake(ConnectException("Connection refused"))).isFalse()
        assertThat(failedDuringSshHandshake(UnknownHostException("no.such.host"))).isFalse()
        assertThat(failedDuringSshHandshake(SocketTimeoutException("connect timed out"))).isFalse()
        assertThat(failedDuringSshHandshake(TimeoutException("timeout"))).isFalse()
        // An authentication failure is past the handshake, not at it: the transport was built.
        assertThat(failedDuringSshHandshake(SshException("No more authentication methods available"))).isFalse()
        assertThat(failedDuringSshHandshake(null)).isFalse()
        assertThat(describeConnectFailure(null)).isEqualTo("Connection failed")
        // A message-less exception would otherwise put an empty status line where the error goes.
        assertThat(describeConnectFailure(IOException())).isEqualTo("Connection failed")
        assertThat(describeConnectFailure(IOException("   "))).isEqualTo("Connection failed")
    }
}
