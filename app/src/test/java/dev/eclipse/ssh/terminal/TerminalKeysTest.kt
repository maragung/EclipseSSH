package dev.eclipse.ssh.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The key-to-bytes table.
 *
 * Worth testing byte for byte because every entry fails silently. A wrong arrow-key sequence does not
 * throw, it just makes the arrows do nothing inside `less`; a wrong Backspace echoes `^H` into the
 * command line; a wrong Enter works at a shell prompt and not in `vim`. None of that is visible from
 * the app side, and all of it depends on a single byte.
 *
 * Sequences are written here as [Int] code lists rather than as string escapes, matching the convention
 * in [TerminalKeys] itself: an escape byte in a test file is as invisible as one in production source.
 */
class TerminalKeysTest {

    // region printable characters

    @Test
    fun `a plain character is its own utf8`() {
        assertThat(TerminalKeys.encode('a').codes()).isEqualTo(listOf(0x61))
        assertThat(TerminalKeys.encode('Z').codes()).isEqualTo(listOf(0x5A))
    }

    @Test
    fun `a non ascii character is encoded as utf8 not truncated to a byte`() {
        assertThat(TerminalKeys.encode('é').codes()).isEqualTo(listOf(0xC3, 0xA9))
    }

    @Test
    fun `ctrl folds a letter into the control range regardless of case`() {
        // 'A' is 0x41 and Ctrl-A is 0x01: Ctrl clears bit 6. Lower case must fold the same way,
        // because the keyboard sends 'c' for Ctrl-C and the remote side still has to see 0x03.
        assertThat(TerminalKeys.encode('a', CTRL).codes()).isEqualTo(listOf(0x01))
        assertThat(TerminalKeys.encode('A', CTRL).codes()).isEqualTo(listOf(0x01))
        assertThat(TerminalKeys.encode('c', CTRL).codes()).isEqualTo(listOf(0x03))
        assertThat(TerminalKeys.encode('d', CTRL).codes()).isEqualTo(listOf(0x04))
        assertThat(TerminalKeys.encode('z', CTRL).codes()).isEqualTo(listOf(0x1A))
    }

    @Test
    fun `ctrl with the punctuation that has a control byte`() {
        assertThat(TerminalKeys.encode(' ', CTRL).codes()).isEqualTo(listOf(0x00))
        assertThat(TerminalKeys.encode('@', CTRL).codes()).isEqualTo(listOf(0x00))
        assertThat(TerminalKeys.encode('[', CTRL).codes()).isEqualTo(listOf(0x1B))
        assertThat(TerminalKeys.encode('\\', CTRL).codes()).isEqualTo(listOf(0x1C))
        assertThat(TerminalKeys.encode(']', CTRL).codes()).isEqualTo(listOf(0x1D))
        assertThat(TerminalKeys.encode('^', CTRL).codes()).isEqualTo(listOf(0x1E))
        assertThat(TerminalKeys.encode('_', CTRL).codes()).isEqualTo(listOf(0x1F))
        // Ctrl-? is DEL, which readline binds to backward-delete-char.
        assertThat(TerminalKeys.encode('?', CTRL).codes()).isEqualTo(listOf(0x7F))
    }

    @Test
    fun `ctrl with a character that has no control byte sends the character itself`() {
        // Sending a guessed byte for Ctrl-9 would be worse than sending '9' - the remote program would
        // act on something the user never asked for.
        assertThat(TerminalKeys.encode('9', CTRL).codes()).isEqualTo(listOf(0x39))
        assertThat(TerminalKeys.encode('!', CTRL).codes()).isEqualTo(listOf(0x21))
    }

    @Test
    fun `alt prefixes a character with escape`() {
        assertThat(TerminalKeys.encode('b', ALT).codes()).isEqualTo(listOf(ESC, 0x62))
    }

    @Test
    fun `ctrl and alt together prefix the folded byte`() {
        assertThat(TerminalKeys.encode('c', CTRL_ALT).codes()).isEqualTo(listOf(ESC, 0x03))
    }

    @Test
    fun `shift alone does not alter a printable character`() {
        // The keyboard has already produced the shifted character; applying shift again would send it
        // as a modified key rather than as itself.
        assertThat(TerminalKeys.encode('A', SHIFT).codes()).isEqualTo(listOf(0x41))
    }

    @Test
    fun `a run of typed text is plain utf8`() {
        assertThat(TerminalKeys.encode("ls -la").codes())
            .isEqualTo("ls -la".map { it.code })
        assertThat(TerminalKeys.encode("é").codes()).isEqualTo(listOf(0xC3, 0xA9))
    }

    // endregion

    // region the keys that send a single byte

    @Test
    fun `enter sends carriage return not line feed`() {
        // LF bypasses the pty's ICRNL translation, and full-screen programs reading in raw mode see a
        // keypress they do not recognise.
        assertThat(TerminalKeys.encode(TerminalKey.ENTER).codes()).isEqualTo(listOf(0x0D))
    }

    @Test
    fun `backspace sends del because that is what stty erase is`() {
        // BS (0x08) is the intuitive choice and echoes a literal ^H into the command line.
        assertThat(TerminalKeys.encode(TerminalKey.BACKSPACE).codes()).isEqualTo(listOf(0x7F))
    }

    @Test
    fun `ctrl backspace sends bs which readline binds to delete previous word`() {
        assertThat(TerminalKeys.encode(TerminalKey.BACKSPACE, CTRL).codes()).isEqualTo(listOf(0x08))
    }

    @Test
    fun `alt backspace is escape then del`() {
        assertThat(TerminalKeys.encode(TerminalKey.BACKSPACE, ALT).codes())
            .isEqualTo(listOf(ESC, 0x7F))
    }

    @Test
    fun `tab sends the tab character and shift tab sends the back tab sequence`() {
        assertThat(TerminalKeys.encode(TerminalKey.TAB).codes()).isEqualTo(listOf(0x09))
        assertThat(TerminalKeys.encode(TerminalKey.TAB, SHIFT).codes())
            .isEqualTo(listOf(ESC, BRACKET, 0x5A))
    }

    @Test
    fun `escape is one byte and alt escape is two`() {
        assertThat(TerminalKeys.encode(TerminalKey.ESCAPE).codes()).isEqualTo(listOf(ESC))
        assertThat(TerminalKeys.encode(TerminalKey.ESCAPE, ALT).codes()).isEqualTo(listOf(ESC, ESC))
    }

    @Test
    fun `alt enter is escape then carriage return`() {
        assertThat(TerminalKeys.encode(TerminalKey.ENTER, ALT).codes()).isEqualTo(listOf(ESC, 0x0D))
    }

    // endregion

    // region cursor keys and DECCKM

    @Test
    fun `unmodified arrows are csi sequences in normal cursor mode`() {
        assertThat(TerminalKeys.encode(TerminalKey.ARROW_UP).text()).isEqualTo("$ESC_TEXT[A")
        assertThat(TerminalKeys.encode(TerminalKey.ARROW_DOWN).text()).isEqualTo("$ESC_TEXT[B")
        assertThat(TerminalKeys.encode(TerminalKey.ARROW_RIGHT).text()).isEqualTo("$ESC_TEXT[C")
        assertThat(TerminalKeys.encode(TerminalKey.ARROW_LEFT).text()).isEqualTo("$ESC_TEXT[D")
    }

    @Test
    fun `application cursor keys switches the arrows to ss3`() {
        // less, and bash with vi bindings, both enable DECCKM. Sending CSI there means the arrow keys
        // do nothing at all while working perfectly at the shell prompt.
        val up = TerminalKeys.encode(TerminalKey.ARROW_UP, applicationCursorKeys = true)
        assertThat(up.codes()).isEqualTo(listOf(ESC, UPPER_O, 0x41))
        assertThat(TerminalKeys.encode(TerminalKey.ARROW_LEFT, applicationCursorKeys = true).text())
            .isEqualTo("${ESC_TEXT}OD")
    }

    @Test
    fun `home and end follow the same mode as the arrows`() {
        assertThat(TerminalKeys.encode(TerminalKey.HOME).text()).isEqualTo("$ESC_TEXT[H")
        assertThat(TerminalKeys.encode(TerminalKey.END).text()).isEqualTo("$ESC_TEXT[F")
        assertThat(TerminalKeys.encode(TerminalKey.HOME, applicationCursorKeys = true).text())
            .isEqualTo("${ESC_TEXT}OH")
        assertThat(TerminalKeys.encode(TerminalKey.END, applicationCursorKeys = true).text())
            .isEqualTo("${ESC_TEXT}OF")
    }

    @Test
    fun `a modified arrow is always csi even in application cursor mode`() {
        // SS3 has nowhere to put a parameter, so a modified cursor key has to use the CSI form
        // whatever mode the remote side asked for.
        assertThat(TerminalKeys.encode(TerminalKey.ARROW_RIGHT, CTRL, applicationCursorKeys = true).text())
            .isEqualTo("$ESC_TEXT[1;5C")
    }

    @Test
    fun `the modifier parameter is the xterm bit field offset by one`() {
        // 1 = none, +1 shift, +2 alt, +4 ctrl. Offset because 0 is read as "parameter omitted".
        assertThat(TerminalKeys.encode(TerminalKey.ARROW_UP, SHIFT).text()).isEqualTo("$ESC_TEXT[1;2A")
        assertThat(TerminalKeys.encode(TerminalKey.ARROW_UP, CTRL).text()).isEqualTo("$ESC_TEXT[1;5A")
        assertThat(TerminalKeys.encode(TerminalKey.ARROW_UP, CTRL_SHIFT).text())
            .isEqualTo("$ESC_TEXT[1;6A")
    }

    @Test
    fun `alt on a cursor key prefixes the unmodified sequence`() {
        // Alt-arrow is expressed as ESC followed by the arrow, not as a parameter, which is how a
        // terminal has always expressed a Meta key it does not physically have.
        assertThat(TerminalKeys.encode(TerminalKey.ARROW_LEFT, ALT).text())
            .isEqualTo("$ESC_TEXT$ESC_TEXT[D")
        assertThat(TerminalKeys.encode(TerminalKey.ARROW_LEFT, ALT, applicationCursorKeys = true).text())
            .isEqualTo("$ESC_TEXT${ESC_TEXT}OD")
    }

    // endregion

    // region tilde keys and function keys

    @Test
    fun `the tilde keys carry their vt220 numbers`() {
        assertThat(TerminalKeys.encode(TerminalKey.INSERT).text()).isEqualTo("$ESC_TEXT[2~")
        assertThat(TerminalKeys.encode(TerminalKey.DELETE).text()).isEqualTo("$ESC_TEXT[3~")
        assertThat(TerminalKeys.encode(TerminalKey.PAGE_UP).text()).isEqualTo("$ESC_TEXT[5~")
        assertThat(TerminalKeys.encode(TerminalKey.PAGE_DOWN).text()).isEqualTo("$ESC_TEXT[6~")
    }

    @Test
    fun `a modified tilde key inserts the parameter after its number`() {
        assertThat(TerminalKeys.encode(TerminalKey.DELETE, CTRL).text()).isEqualTo("$ESC_TEXT[3;5~")
        assertThat(TerminalKeys.encode(TerminalKey.PAGE_UP, SHIFT).text()).isEqualTo("$ESC_TEXT[5;2~")
    }

    @Test
    fun `f1 to f4 are ss3 keys and f5 upwards are numbered`() {
        // Not principled - it is what the VT220 did and what every terminal has copied since.
        assertThat(TerminalKeys.encode(TerminalKey.F1).text()).isEqualTo("${ESC_TEXT}OP")
        assertThat(TerminalKeys.encode(TerminalKey.F4).text()).isEqualTo("${ESC_TEXT}OS")
        assertThat(TerminalKeys.encode(TerminalKey.F5).text()).isEqualTo("$ESC_TEXT[15~")
        assertThat(TerminalKeys.encode(TerminalKey.F12).text()).isEqualTo("$ESC_TEXT[24~")
    }

    @Test
    fun `the numbers that vt220 skipped stay skipped`() {
        // 16 and 22 have no key. Renumbering them to be tidy would break every remote program.
        assertThat(TerminalKeys.encode(TerminalKey.F6).text()).isEqualTo("$ESC_TEXT[17~")
        assertThat(TerminalKeys.encode(TerminalKey.F11).text()).isEqualTo("$ESC_TEXT[23~")
    }

    @Test
    fun `every key encodes to something and nothing encodes to an empty array`() {
        // A key that silently sends nothing is the failure this whole table exists to avoid.
        TerminalKey.entries.forEach { key ->
            for (application in listOf(false, true)) {
                MODIFIER_SETS.forEach { modifiers ->
                    val bytes = TerminalKeys.encode(key, modifiers, application)
                    assertThat(bytes.isNotEmpty()).isTrue()
                }
            }
        }
    }

    // endregion

    // region newline normalisation and paste

    @Test
    fun `line feeds and crlf pairs both become one carriage return`() {
        assertThat(TerminalKeys.normalizeNewlines("a" + LF_TEXT + "b").codesOf())
            .isEqualTo(listOf(0x61, 0x0D, 0x62))
        assertThat(TerminalKeys.normalizeNewlines("a" + CR_TEXT + LF_TEXT + "b").codesOf())
            .isEqualTo(listOf(0x61, 0x0D, 0x62))
        assertThat(TerminalKeys.normalizeNewlines("a" + CR_TEXT + "b").codesOf())
            .isEqualTo(listOf(0x61, 0x0D, 0x62))
    }

    @Test
    fun `a trailing crlf does not become two line breaks`() {
        assertThat(TerminalKeys.normalizeNewlines("x" + CR_TEXT + LF_TEXT).codesOf())
            .isEqualTo(listOf(0x78, 0x0D))
    }

    @Test
    fun `a lone lf after a crlf pair is still a break of its own`() {
        assertThat(TerminalKeys.normalizeNewlines("a" + CR_TEXT + LF_TEXT + LF_TEXT + "b").codesOf())
            .isEqualTo(listOf(0x61, 0x0D, 0x0D, 0x62))
    }

    @Test
    fun `text with no line breaks is returned unchanged and uncopied`() {
        val text = "no breaks here"
        assertThat(TerminalKeys.normalizeNewlines(text)).isSameInstanceAs(text)
    }

    @Test
    fun `an unbracketed paste is just the normalised text`() {
        assertThat(TerminalKeys.paste("ls" + LF_TEXT, bracketed = false).codes())
            .isEqualTo(listOf(0x6C, 0x73, 0x0D))
    }

    @Test
    fun `a bracketed paste is wrapped in the start and end markers`() {
        val bytes = TerminalKeys.paste("ls", bracketed = true)
        assertThat(bytes.text()).isEqualTo("$ESC_TEXT[200~ls$ESC_TEXT[201~")
    }

    @Test
    fun `an end marker inside a bracketed paste is removed rather than closing it early`() {
        // This is the attack bracketed paste exists to prevent: text copied off a web page that
        // contains the end marker would otherwise have its remainder arrive as ordinary typing, and
        // the shell runs that the moment it sees a newline.
        val hostile = "echo safe" + ESC_TEXT + "[201~" + LF_TEXT + "rm -rf /"
        val bytes = TerminalKeys.paste(hostile, bracketed = true)
        val text = bytes.text()
        assertThat(text).startsWith("$ESC_TEXT[200~")
        assertThat(text).endsWith("$ESC_TEXT[201~")
        // Exactly one of each marker: the payload's own copy is gone.
        assertThat(text.windowed(6).count { it == "$ESC_TEXT[201~" }).isEqualTo(1)
        assertThat(text.windowed(6).count { it == "$ESC_TEXT[200~" }).isEqualTo(1)
        // The dangerous command survives as text, which is the point - it is pasted, not run.
        assertThat(text).contains("rm -rf /")
    }

    @Test
    fun `a nested start marker inside a bracketed paste is removed too`() {
        val bytes = TerminalKeys.paste("a" + ESC_TEXT + "[200~b", bracketed = true)
        assertThat(bytes.text()).isEqualTo("$ESC_TEXT[200~ab$ESC_TEXT[201~")
    }

    @Test
    fun `a marker in an unbracketed paste is left alone because there is no bracket to close`() {
        val payload = "a" + ESC_TEXT + "[201~b"
        assertThat(TerminalKeys.paste(payload, bracketed = false).text()).isEqualTo(payload)
    }

    @Test
    fun `an empty bracketed paste still sends both markers`() {
        // The remote side is waiting for the close once it has seen the open.
        assertThat(TerminalKeys.paste("", bracketed = true).text())
            .isEqualTo("$ESC_TEXT[200~$ESC_TEXT[201~")
    }

    @Test
    fun `a multi line bracketed paste keeps every line`() {
        val bytes = TerminalKeys.paste("one" + LF_TEXT + "two" + LF_TEXT + "three", bracketed = true)
        assertThat(bytes.text()).isEqualTo("$ESC_TEXT[200~one${CR_TEXT}two${CR_TEXT}three$ESC_TEXT[201~")
    }

    // endregion

    private fun ByteArray.codes(): List<Int> = map { it.toInt() and 0xFF }

    private fun ByteArray.text(): String = String(this, Charsets.UTF_8)

    private fun String.codesOf(): List<Int> = map { it.code }

    private companion object {
        const val ESC = 0x1B
        const val BRACKET = 0x5B
        const val UPPER_O = 0x4F

        val ESC_TEXT: String = Char(0x1B).toString()
        val CR_TEXT: String = Char(0x0D).toString()
        val LF_TEXT: String = Char(0x0A).toString()

        val CTRL = TerminalModifiers(ctrl = true)
        val ALT = TerminalModifiers(alt = true)
        val SHIFT = TerminalModifiers(shift = true)
        val CTRL_ALT = TerminalModifiers(ctrl = true, alt = true)
        val CTRL_SHIFT = TerminalModifiers(ctrl = true, shift = true)

        val MODIFIER_SETS = listOf(
            TerminalModifiers.NONE,
            CTRL,
            ALT,
            SHIFT,
            CTRL_ALT,
            CTRL_SHIFT,
            TerminalModifiers(ctrl = true, alt = true, shift = true),
        )
    }
}
