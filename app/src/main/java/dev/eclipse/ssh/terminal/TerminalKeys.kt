package dev.eclipse.ssh.terminal

/**
 * The bytes a key press sends to a remote shell.
 *
 * Every value in this file is built from an integer code point rather than written as a character
 * escape. A raw control byte in source is invisible in a diff, in a review and in most editors, and
 * `TerminalKeys` is almost entirely control bytes - a file where a third of the content cannot be seen
 * is a file where a wrong byte cannot be found. The same convention is used by [AnsiTerminalBuffer] on
 * the receiving side, so the two halves of the protocol read the same way.
 *
 * Pure and stateless on purpose. What a key sends depends only on the key, the modifiers held with it,
 * and the modes the remote side has negotiated - all three are arguments. That keeps the mapping
 * testable without a view, a session or a socket, which matters because it is exactly the kind of
 * table where one transposed byte means a key silently does nothing on one remote program and
 * something wrong on another.
 */

/** ESC, the prefix of nearly everything below. */
private const val ESC = 0x1B

/** NUL, which is what Ctrl-Space and Ctrl-@ send. */
private const val NUL = 0x00

/** HT - the Tab key sends the character, not a sequence. */
private const val HT = 0x09

/**
 * CR, which is what Enter sends.
 *
 * Not LF. A pty is opened with `ICRNL`, so the line discipline turns this into a newline for the
 * program reading it; sending LF directly bypasses that and a few remote programs - notably anything
 * reading in raw mode, which includes most full-screen editors - see a keypress they do not recognise.
 */
private const val CR = 0x0D

private const val LF = 0x0A

/**
 * DEL, which is what Backspace sends.
 *
 * BS (0x08) is the intuitive choice and the wrong one: `stty erase` is DEL on every mainstream Unix,
 * so a terminal that sends BS gets a literal `^H` echoed into the command line instead of deleting a
 * character. BS is what Ctrl-Backspace sends, and only because that is the convention readline expects
 * for "delete previous word".
 */
private const val DEL = 0x7F

/** The non-printable keys a terminal has to encode. */
enum class TerminalKey {
    ENTER,
    BACKSPACE,
    TAB,
    ESCAPE,
    ARROW_UP,
    ARROW_DOWN,
    ARROW_RIGHT,
    ARROW_LEFT,
    HOME,
    END,
    PAGE_UP,
    PAGE_DOWN,
    INSERT,
    DELETE,
    F1,
    F2,
    F3,
    F4,
    F5,
    F6,
    F7,
    F8,
    F9,
    F10,
    F11,
    F12,
}

/**
 * Which of Ctrl, Alt and Shift were held.
 *
 * [shift] is deliberately not applied to printable characters: by the time a character reaches the app
 * the keyboard has already produced the shifted one, and re-applying it would send `A` as `Ctrl-Shift`
 * plus `A` rather than as itself. It only affects keys with no character of their own - Shift-Tab and
 * the shifted arrows and function keys, which are distinct sequences.
 */
data class TerminalModifiers(
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
) {
    val none: Boolean get() = !ctrl && !alt && !shift

    companion object {
        val NONE = TerminalModifiers()
    }
}

object TerminalKeys {
    /**
     * The bytes for [key] held with [modifiers].
     *
     * [applicationCursorKeys] is DECCKM, read from [TerminalFrame.applicationCursorKeys]. When a
     * program turns it on it is asking for the arrows and Home/End to arrive as `SS3` sequences
     * instead of `CSI` ones, and it matters more than its obscurity suggests: `bash` with `vi` bindings,
     * `less`, and most curses programs enable it, and sending the wrong form means the arrow keys do
     * nothing at all in those programs while working perfectly at the shell prompt. Being unable to
     * scroll inside `less` is the single most visible symptom of getting this wrong.
     */
    fun encode(
        key: TerminalKey,
        modifiers: TerminalModifiers = TerminalModifiers.NONE,
        applicationCursorKeys: Boolean = false,
    ): ByteArray = when (key) {
        // The three keys that send a plain byte. Alt still prefixes them - Alt-Enter and
        // Alt-Backspace ("delete previous word") are both bindings people rely on.
        TerminalKey.ENTER -> withAlt(modifiers, bytesOf(CR))
        TerminalKey.TAB -> if (modifiers.shift) csi("Z") else withAlt(modifiers, bytesOf(HT))
        TerminalKey.BACKSPACE ->
            withAlt(modifiers, bytesOf(if (modifiers.ctrl) 0x08 else DEL))

        // Esc is its own byte, and Alt-Esc is genuinely two of them.
        TerminalKey.ESCAPE -> withAlt(modifiers, bytesOf(ESC))

        TerminalKey.ARROW_UP -> cursorKey("A", modifiers, applicationCursorKeys)
        TerminalKey.ARROW_DOWN -> cursorKey("B", modifiers, applicationCursorKeys)
        TerminalKey.ARROW_RIGHT -> cursorKey("C", modifiers, applicationCursorKeys)
        TerminalKey.ARROW_LEFT -> cursorKey("D", modifiers, applicationCursorKeys)
        TerminalKey.HOME -> cursorKey("H", modifiers, applicationCursorKeys)
        TerminalKey.END -> cursorKey("F", modifiers, applicationCursorKeys)

        TerminalKey.INSERT -> tildeKey(2, modifiers)
        TerminalKey.DELETE -> tildeKey(3, modifiers)
        TerminalKey.PAGE_UP -> tildeKey(5, modifiers)
        TerminalKey.PAGE_DOWN -> tildeKey(6, modifiers)

        // F1-F4 are SS3 keys like the arrows; F5 upwards are numbered. Nothing about that is
        // principled - it is what the VT220 did and what every terminal has copied since.
        TerminalKey.F1 -> functionKey("P", modifiers)
        TerminalKey.F2 -> functionKey("Q", modifiers)
        TerminalKey.F3 -> functionKey("R", modifiers)
        TerminalKey.F4 -> functionKey("S", modifiers)
        TerminalKey.F5 -> tildeKey(15, modifiers)
        TerminalKey.F6 -> tildeKey(17, modifiers)
        TerminalKey.F7 -> tildeKey(18, modifiers)
        TerminalKey.F8 -> tildeKey(19, modifiers)
        TerminalKey.F9 -> tildeKey(20, modifiers)
        TerminalKey.F10 -> tildeKey(21, modifiers)
        TerminalKey.F11 -> tildeKey(23, modifiers)
        TerminalKey.F12 -> tildeKey(24, modifiers)
    }

    /**
     * The bytes for a printable [char] held with [modifiers].
     *
     * Ctrl folds the character into the C0 range, which is the whole reason a terminal can send
     * Ctrl-C at all: there is no "Ctrl" byte, only the 32 characters that Ctrl produces. Alt prefixes
     * with ESC, which is how a terminal has always expressed a Meta key it does not have.
     */
    fun encode(char: Char, modifiers: TerminalModifiers = TerminalModifiers.NONE): ByteArray {
        val base = if (modifiers.ctrl) controlByte(char) ?: utf8(char) else utf8(char)
        return withAlt(modifiers, base)
    }

    /** The bytes for a run of typed text, with no modifiers applied. */
    fun encode(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    /**
     * The bytes for pasting [text], wrapped for bracketed paste when the remote side asked for it.
     *
     * Two things happen here that are not obvious.
     *
     * Newlines become CR. A paste carrying LF would reach the shell as a line that has already been
     * terminated in a way `ICRNL` does not touch, and `readline` responds to that inconsistently -
     * usually by running some lines and leaving others on the input line. CR is what the Enter key
     * sends, so a pasted multi-line command behaves exactly like typing it.
     *
     * The end marker is removed from the payload. `ESC [ 201 ~` inside a paste would close the bracket
     * early, and everything after it would arrive as ordinary typing - which the shell then executes
     * the moment it sees a newline. That is the exact attack bracketed paste exists to prevent, so
     * copying text off a web page that contains the marker must not be able to run the remainder. The
     * marker is dropped rather than escaped because there is no escape for it, and a paste that
     * silently loses seven bytes is a far smaller surprise than one that silently runs a command. The
     * start marker goes too: a nested one is not dangerous, only confusing to the remote side.
     */
    fun paste(text: String, bracketed: Boolean): ByteArray {
        val normalized = normalizeNewlines(text)
        if (!bracketed) return normalized.toByteArray(Charsets.UTF_8)
        val guarded = normalized.replace(PASTE_END_MARKER, "").replace(PASTE_START_MARKER, "")
        return PASTE_START_MARKER.toByteArray(Charsets.UTF_8) +
            guarded.toByteArray(Charsets.UTF_8) +
            PASTE_END_MARKER.toByteArray(Charsets.UTF_8)
    }

    /** CRLF and LF both become CR - see [paste]. */
    fun normalizeNewlines(text: String): String {
        if (text.indexOf(Char(LF)) < 0 && text.indexOf(Char(CR)) < 0) return text
        val out = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when (char.code) {
                CR -> {
                    out.append(Char(CR))
                    // Swallow the LF of a CRLF pair so one line break does not become two.
                    if (index + 1 < text.length && text[index + 1].code == LF) index++
                }
                LF -> out.append(Char(CR))
                else -> out.append(char)
            }
            index++
        }
        return out.toString()
    }

    private fun cursorKey(
        final: String,
        modifiers: TerminalModifiers,
        applicationCursorKeys: Boolean,
    ): ByteArray = when {
        // A modified cursor key is always a CSI sequence: SS3 has nowhere to put a parameter.
        !modifiers.none && !modifiers.alt -> csi("1;${modifierParameter(modifiers)}$final")
        modifiers.alt -> withAlt(modifiers, cursorKey(final, modifiers.copy(alt = false), applicationCursorKeys))
        applicationCursorKeys -> ss3(final)
        else -> csi(final)
    }

    private fun functionKey(final: String, modifiers: TerminalModifiers): ByteArray = when {
        !modifiers.none && !modifiers.alt -> csi("1;${modifierParameter(modifiers)}$final")
        modifiers.alt -> withAlt(modifiers, functionKey(final, modifiers.copy(alt = false)))
        else -> ss3(final)
    }

    private fun tildeKey(number: Int, modifiers: TerminalModifiers): ByteArray = when {
        !modifiers.none && !modifiers.alt -> csi("$number;${modifierParameter(modifiers)}~")
        modifiers.alt -> withAlt(modifiers, tildeKey(number, modifiers.copy(alt = false)))
        else -> csi("$number~")
    }

    /**
     * The xterm modifier parameter: a bit field offset by one, because 0 is not a legal CSI parameter
     * and would be read as "omitted".
     */
    private fun modifierParameter(modifiers: TerminalModifiers): Int =
        1 + (if (modifiers.shift) 1 else 0) + (if (modifiers.alt) 2 else 0) + (if (modifiers.ctrl) 4 else 0)

    /**
     * [char] folded into the C0 control range, or null if Ctrl means nothing with it.
     *
     * Returning null rather than a guess is what lets [encode] fall back to the plain character:
     * Ctrl-9 has no encoding, and sending a mangled byte for it would be worse than sending `9`.
     */
    private fun controlByte(char: Char): ByteArray? {
        val upper = char.uppercaseChar().code
        return when {
            // Ctrl-A..Ctrl-Z clear bit 6, which is the whole trick: 'A' is 0x41 and Ctrl-A is 0x01.
            upper in 0x41..0x5A -> bytesOf(upper and 0x1F)
            char.code == 0x20 || char == '@' -> bytesOf(NUL)
            char == '[' -> bytesOf(ESC)
            char == '\\' -> bytesOf(0x1C)
            char == ']' -> bytesOf(0x1D)
            char == '^' -> bytesOf(0x1E)
            char == '_' || char == '-' -> bytesOf(0x1F)
            // Ctrl-? is DEL, which readline binds to backward-delete-char.
            char == '?' -> bytesOf(DEL)
            char == '/' -> bytesOf(0x1F)
            else -> null
        }
    }

    private fun withAlt(modifiers: TerminalModifiers, bytes: ByteArray): ByteArray =
        if (modifiers.alt) bytesOf(ESC) + bytes else bytes

    private fun utf8(char: Char): ByteArray = char.toString().toByteArray(Charsets.UTF_8)

    private fun bytesOf(vararg codes: Int): ByteArray =
        ByteArray(codes.size) { codes[it].toByte() }

    /** `ESC [` followed by [tail], which is always plain ASCII. */
    private fun csi(tail: String): ByteArray =
        bytesOf(ESC, LEFT_BRACKET) + tail.toByteArray(Charsets.US_ASCII)

    /** `ESC O` followed by [final] - the "single shift 3" form the application keypad uses. */
    private fun ss3(final: String): ByteArray =
        bytesOf(ESC, UPPER_O) + final.toByteArray(Charsets.US_ASCII)

    private const val LEFT_BRACKET = 0x5B
    private const val UPPER_O = 0x4F

    private val PASTE_START_MARKER: String = Char(ESC) + "[200~"
    private val PASTE_END_MARKER: String = Char(ESC) + "[201~"
}
