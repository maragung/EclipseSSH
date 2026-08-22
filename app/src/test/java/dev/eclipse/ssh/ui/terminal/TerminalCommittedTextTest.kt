package dev.eclipse.ssh.ui.terminal

import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.terminal.TerminalKey
import org.junit.Test

/**
 * What the invisible field does with the text a keyboard hands it.
 *
 * The interesting case is a newline. Return usually arrives as a key event and is mapped away from
 * this path entirely, but some keyboards commit it as text, and clipboard suggestions and voice
 * typing put newlines in the middle of a commit. Whether the shell then sees the app's own Return or
 * a bare line feed must not depend on which keyboard is installed, which is what these pin.
 */
class TerminalCommittedTextTest {
    private sealed interface Sent {
        data class Text(val value: String) : Sent
        data class Key(val key: TerminalKey) : Sent
    }

    private val sent = mutableListOf<Sent>()

    private fun send(typed: String): List<Sent> {
        sent.clear()
        sendCommittedText(
            typed,
            onText = { sent += Sent.Text(it) },
            onKey = { key, _, _, _ -> sent += Sent.Key(key) },
        )
        return sent.toList()
    }

    @Test
    fun `ordinary text is passed through in one piece`() {
        // One call, not one per character: the channel writes what it is given, and splitting a
        // multi-byte character across two writes would corrupt it.
        assertThat(send("cd /var/log")).containsExactly(Sent.Text("cd /var/log"))
    }

    @Test
    fun `a committed newline becomes the terminal's own Enter`() {
        assertThat(send("\n")).containsExactly(Sent.Key(TerminalKey.ENTER))
    }

    @Test
    fun `a command committed with its newline runs`() {
        assertThat(send("uptime\n"))
            .containsExactly(Sent.Text("uptime"), Sent.Key(TerminalKey.ENTER))
            .inOrder()
    }

    @Test
    fun `CRLF is one Return, not two`() {
        assertThat(send("uptime\r\n"))
            .containsExactly(Sent.Text("uptime"), Sent.Key(TerminalKey.ENTER))
            .inOrder()
    }

    @Test
    fun `a lone carriage return is also Enter`() {
        assertThat(send("uptime\r"))
            .containsExactly(Sent.Text("uptime"), Sent.Key(TerminalKey.ENTER))
            .inOrder()
    }

    @Test
    fun `every line of a multi-line commit is sent and executed in order`() {
        assertThat(send("cd /tmp\nls -la\npwd"))
            .containsExactly(
                Sent.Text("cd /tmp"),
                Sent.Key(TerminalKey.ENTER),
                Sent.Text("ls -la"),
                Sent.Key(TerminalKey.ENTER),
                Sent.Text("pwd"),
            )
            .inOrder()
    }

    @Test
    fun `consecutive newlines are separate Returns`() {
        // A blank line is a keypress the user made, so it reaches the shell as one: swallowing it
        // would silently drop an Enter on an interactive prompt waiting for a default answer.
        assertThat(send("\n\n")).containsExactly(Sent.Key(TerminalKey.ENTER), Sent.Key(TerminalKey.ENTER))
    }

    @Test
    fun `text around a newline keeps both halves and its spacing`() {
        assertThat(send("  indented\n  again  "))
            .containsExactly(
                Sent.Text("  indented"),
                Sent.Key(TerminalKey.ENTER),
                Sent.Text("  again  "),
            )
            .inOrder()
    }

    @Test
    fun `empty input sends nothing`() {
        assertThat(send("")).isEmpty()
    }
}
