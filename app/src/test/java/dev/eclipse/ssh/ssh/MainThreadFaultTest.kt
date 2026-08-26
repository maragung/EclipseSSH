package dev.eclipse.ssh.ssh

import android.os.NetworkOnMainThreadException
import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.presentation.shouldAutoReconnect
import java.io.IOException
import java.net.SocketException
import org.apache.sshd.common.SshException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The one ending that is this app's fault, and what the app is now obliged to do about it.
 *
 * The bug behind this test ran on a real phone: three sessions in a row, each dying 1.6 to 2.0 seconds
 * after its shell opened, each announced as `Connection lost: NetworkOnMainThreadException`, with the
 * ladder climbing to *attempt 3 of 5* and a 29-second wait. Nothing was wrong with the server, the
 * account or the carrier. The app had opened an SFTP client on `Dispatchers.IO` and let `use` close it
 * back on `Dispatchers.Main.immediate`, and Android's `BlockGuard` raises inside MINA's write path - so
 * the transport was broken before any `catch` could see it, and a threading mistake was delivered to the
 * state machine as [SessionEnd.TransportFailed], which is the one ending most worth waiting out.
 *
 * The real fix is that no socket call runs on the main thread: `MainViewModel.transportScope`,
 * `SshConnectionManager.withSftp` and `SshSessionStore.release`, all of which are asserted where they
 * live. This is the net behind it, and it is deliberately not a quiet one. A user cannot fix a bug in
 * this app, so the ladder stops - it could only reproduce the fault - and the tab says whose fault it is
 * rather than blaming a link that never failed.
 *
 * Robolectric, because the subject is a real `android.os.NetworkOnMainThreadException` instance rather
 * than a name: the predicate is a type check, and a type check deserves the type.
 */
@RunWith(RobolectricTestRunner::class)
class MainThreadFaultTest {

    @Test
    fun `a transport failure caused on the main thread is the app's fault`() {
        assertThat(SessionEnd.TransportFailed(NetworkOnMainThreadException()).isAppFault).isTrue()
    }

    @Test
    fun `the cause is found through MINA's wrapping`() {
        // How it actually arrives. MINA does not hand over the throwable that was raised; it hands over
        // its own exception with that one somewhere underneath, which is why the recorded detail said
        // only "NetworkOnMainThreadException" - the innermost message, and it has none.
        val wrapped = SshException("Connection lost", IOException("write failed", NetworkOnMainThreadException()))
        assertThat(SessionEnd.TransportFailed(wrapped).isAppFault).isTrue()
    }

    @Test
    fun `a cause chain that points at itself is answered rather than followed`() {
        // The bound exists for this: a chain walked without a self-reference guard would spin here
        // instead of classifying, and it would do it while a session was being torn down.
        val looping = IOException("write failed")
        looping.initCause(SshException("Connection lost", looping))
        assertThat(SessionEnd.TransportFailed(looping).isAppFault).isFalse()
    }

    @Test
    fun `an ordinary transport failure is not the app's fault`() {
        // The over-reach guard. A predicate that answered true for anything that ended a session would
        // disable auto-reconnect altogether, which is the feature this whole area exists to provide.
        assertThat(SessionEnd.TransportFailed(SocketException("Connection reset")).isAppFault).isFalse()
        assertThat(SessionEnd.TransportFailed(IOException("Broken pipe")).isAppFault).isFalse()
        assertThat(SessionEnd.NetworkLost.isAppFault).isFalse()
        assertThat(SessionEnd.TransportClosed.isAppFault).isFalse()
        assertThat(SessionEnd.Released.isAppFault).isFalse()
        assertThat(SessionEnd.ShellEnded(status = 0, signal = null).isAppFault).isFalse()
        assertThat(SessionEnd.Disconnected(reason = 11, message = "bye", byPeer = true).isAppFault).isFalse()
    }

    @Test
    fun `the ladder does not answer a bug in this app`() {
        val end = SessionEnd.TransportFailed(NetworkOnMainThreadException())
        assertThat(shouldAutoReconnect(end, tabIsOpen = true, endedDeliberately = false)).isFalse()
        // Not even with the host's own switch on, which is what the reported device had: five rungs and
        // a 29-second wait, all of them spent re-running the same defect.
        assertThat(
            shouldAutoReconnect(end, tabIsOpen = true, endedDeliberately = false, autoReconnectEnabled = true),
        ).isFalse()
    }

    @Test
    fun `a genuine drop is still reconnect-worthy`() {
        // The regression that would matter more than the bug. Auto-reconnect on a flaky link is the
        // reason this app has a ladder at all.
        val reset = SessionEnd.TransportFailed(SocketException("Connection reset"))
        assertThat(shouldAutoReconnect(reset, tabIsOpen = true, endedDeliberately = false)).isTrue()
        assertThat(shouldAutoReconnect(SessionEnd.NetworkLost, tabIsOpen = true, endedDeliberately = false)).isTrue()
    }

    @Test
    fun `the tab says whose fault it is`() {
        val said = describeSessionEnd(SessionEnd.TransportFailed(NetworkOnMainThreadException()))
        // Names the app, and does not blame either of the two things a user would otherwise go and
        // check. "Connection lost" was worse than useless here: it sent people to their sshd_config.
        assertThat(said.lowercase()).contains("app bug")
        // By the name on the launcher, not the package name or the log tag.
        assertThat(said).contains("Eclipse SSH")
        assertThat(said).doesNotContain("Connection lost")
        // No class name and no stack trace: this string is a line on a session tab.
        assertThat(said).doesNotContain("Exception")
        assertThat(said).doesNotContain("\n")
    }

    @Test
    fun `an ordinary drop still says what happened`() {
        // The other half of the wording change: the specific transport message is the whole diagnostic
        // for a real fault, and it must survive having a special case added beside it.
        val said = describeSessionEnd(SessionEnd.TransportFailed(SocketException("Connection reset")))
        assertThat(said).isEqualTo("Connection lost: Connection reset")
    }

    @Test
    fun `it is still a fault, so the tab shows an error rather than looking connected`() {
        // Suppressing the ladder is not suppressing the problem. The session really did die and the user
        // really is without a shell, so this has to reach ERROR - a quieter classification would be the
        // workaround this change exists to avoid.
        assertThat(SessionEnd.TransportFailed(NetworkOnMainThreadException()).isFault).isTrue()
    }
}
