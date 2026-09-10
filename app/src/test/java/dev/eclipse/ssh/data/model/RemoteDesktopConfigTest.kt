package dev.eclipse.ssh.data.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The one-line-per-protocol codec behind [HostProfile.remoteDesktop].
 *
 * Like [ForwardRulesTest] beside it, this column is structured text read in places that cannot
 * see each other - the per-host config form, the viewer that opens from the menu, and the vault
 * importer - so the rules are stated once, here. Decoding has to be **total** for the same
 * reason it is there: a hand-edited or truncated backup must not cost the host its load, and a
 * line that names nothing the engine could dial is dropped, never thrown.
 *
 * One rule here has no counterpart in the forward codec: an unknown *kind* is skipped rather
 * than fatal, because the `R:` line RDP will speak one day is a line today's decoder must be
 * able to read past without a migration.
 */
class RemoteDesktopConfigTest {

    @Test
    fun `a VNC target round trips through its line`() {
        val target = RemoteDesktopTarget(port = 5900)

        val text = encodeRemoteDesktop(RemoteDesktopConfig(vnc = target))
        assertThat(text).isEqualTo("V:5900")

        val decoded = decodeRemoteDesktop(text)
        assertThat(decoded.vnc).isEqualTo(target)
    }

    @Test
    fun `every optional field is written only when it is not the default`() {
        // The same backward-compat guarantee the forward codec makes: a target that says only what
        // the first release said is written exactly as the first release wrote it.
        assertThat(encodeRemoteDesktop(RemoteDesktopConfig(vnc = RemoteDesktopTarget(port = 5900))))
            .isEqualTo("V:5900")
        assertThat(encodeRemoteDesktop(RemoteDesktopConfig(vnc = RemoteDesktopTarget(host = "10.0.1.5", port = 5901))))
            .isEqualTo("V:10.0.1.5:5901")
        assertThat(encodeRemoteDesktop(RemoteDesktopConfig(vnc = RemoteDesktopTarget(port = 5900, viewOnly = true))))
            .isEqualTo("V:5900 view-only")
        assertThat(encodeRemoteDesktop(RemoteDesktopConfig(vnc = RemoteDesktopTarget(port = 5900, enabled = false))))
            .isEqualTo("#V:5900")
        // And a host with no endpoint writes nothing at all, which is what every existing row holds.
        assertThat(encodeRemoteDesktop(RemoteDesktopConfig())).isEmpty()
    }

    @Test
    fun `the round trip returns exactly the fields the line carried`() {
        val decoded = decodeRemoteDesktop("#V:10.0.1.5:5901 view-only")
        assertThat(decoded.vnc).isEqualTo(
            RemoteDesktopTarget(host = "10.0.1.5", port = 5901, enabled = false, viewOnly = true),
        )
    }

    @Test
    fun `a line the engine cannot act on is dropped, not fatal`() {
        // The shapes a hand-edited file can hold. A port that is no port, a kind this codec does
        // not speak, an empty line, a flag that is not the one flag there is: every one of them
        // falls out, and the host keeps whatever valid line it also had.
        val text = listOf(
            "V:not-a-port",
            "V:",
            "X:5900",
            "V:5900 unexpected-flag",
            "",
            "V:5900",
        ).joinToString("\n")

        val decoded = decodeRemoteDesktop(text)
        assertThat(decoded.vnc).isEqualTo(RemoteDesktopTarget(port = 5900))
    }

    @Test
    fun `the first VNC line wins when a hand edit leaves two`() {
        // A column with two targets was hand-edited; asking which one the user meant is a question
        // no screen in this app can ask, so the first one wins and the second is dropped. And the
        // text that comes back out is normalised: the duplicate does not survive the round trip.
        val decoded = decodeRemoteDesktop("V:5901\nV:5902")
        assertThat(decoded.vnc).isEqualTo(RemoteDesktopTarget(port = 5901))
        assertThat(encodeRemoteDesktop(decoded)).isEqualTo("V:5901")
    }

    @Test
    fun `an RDP line is skipped today so RDP can arrive without a migration`() {
        // The forward-decoder's "unknown line falls out" rule, one protocol early: today's reader
        // must not choke on tomorrow's line shape, and tomorrow's reader must be able to read past
        // it in an old backup.
        val decoded = decodeRemoteDesktop("R:3389\nV:5900")
        assertThat(decoded.vnc).isEqualTo(RemoteDesktopTarget(port = 5900))
        assertThat(encodeRemoteDesktop(decoded)).isEqualTo("V:5900")
    }
}
