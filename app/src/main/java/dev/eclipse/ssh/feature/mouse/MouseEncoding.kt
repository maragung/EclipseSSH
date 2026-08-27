package dev.eclipse.ssh.feature.mouse

/**
 * SGR mouse encoding for the terminal.
 *
 * SGR mouse mode (`CSI ? 1006 h`) is what `htop --mouse`, `vim`'s
 * mouse-aware mode, `tmux`, `less`, and the like use. The wire format
 * is a CSI sequence with parameters:
 *
 *   `ESC [ < Cb ; < Cx ; < Cy M`
 *
 * where `Cb` is the button code, `Cx`/`Cy` are 1-based cell
 * coordinates, and the trailing letter is `M` for press / `m` for
 * release (SGR mode uses lower-case `m` for release, where the older
 * `? 1000` mode used a non-printable character).
 *
 * Button codes:
 *  - 0 = left button
 *  - 1 = middle button
 *  - 2 = right button
 *  - 32 = left + motion (drag)
 *  - 33 = middle + motion
 *  - 34 = right + motion
 *  - 64 = scroll up
 *  - 65 = scroll down
 *  - 128-255 = button + 64 + modifier keys (shift, alt, ctrl)
 *
 * The terminal emulator sends these when the user clicks/drags/scrolls
 * in the grid; the remote program's mouse-aware code reads them and
 * acts. Bitvise's terminal, `tmux`, and `htop --mouse` all accept this
 * format; older xterm mouse mode (`? 1000` with non-SGR encoding) is
 * what MINA's terminal emulators default to and what the app is not
 * currently using.
 */
object MouseEncoding {

    /** Mouse button codes for SGR mode. */
    enum class Button(val code: Int) {
        LEFT(0),
        MIDDLE(1),
        RIGHT(2),
        RELEASE(0),  // SGR uses lower-case 'm' for release
        DRAG_LEFT(32),
        DRAG_MIDDLE(33),
        DRAG_RIGHT(34),
        SCROLL_UP(64),
        SCROLL_DOWN(65),
    }

    /** Modifier keys, OR-ed into the button code. */
    object Modifiers {
        const val SHIFT = 4
        const val ALT = 8
        const val CTRL = 16
    }

    /**
     * Encodes a mouse event for SGR mode.
     *
     * @param button the button that moved
     * @param column 1-based cell column (clamped to 1..223, the SGR
     *   range; values > 223 wrap per xterm convention)
     * @param row 1-based cell row
     * @param modifiers bitwise OR of [Modifiers] values
     * @param pressed true for press, false for release
     * @return the byte sequence to send to the remote pty
     */
    fun encode(
        button: Button,
        column: Int,
        row: Int,
        modifiers: Int = 0,
        pressed: Boolean = true,
    ): ByteArray {
        val buttonCode = (button.code + modifiers).coerceAtLeast(0)
        val cx = column.coerceAtLeast(1)
        val cy = row.coerceAtLeast(1)
        val terminator = if (pressed) 'M' else 'm'
        val sequence = "\u001B[<${buttonCode};${cx};${cy}${terminator}"
        return sequence.toByteArray(Charsets.UTF_8)
    }

    /**
     * Parses the prefix of an SGR mouse response to determine the
     * button. The terminal does not currently emit mouse events on
     * receive; this helper is for the future "select with a remote
     * mouse" path.
     *
     * The SGR encoding packs the kind into the top two bits of the
     * button code: `0b00` for press, `0b01` for scroll, `0b10` for
     * drag. The low two bits are the button index (0 = left, 1 =
     * middle, 2 = right). Modifiers (shift/alt/ctrl) are OR-ed into
     * the high bits and must be masked out before the kind is read.
     */
    fun parseButton(encoded: Int): Button {
        val kind = encoded and 0x60  // top two meaningful bits, mask modifier bits
        val index = encoded and 0x03
        return when (kind) {
            0x00 -> when (index) {
                0 -> Button.LEFT
                1 -> Button.MIDDLE
                2 -> Button.RIGHT
                else -> Button.RELEASE
            }
            0x40 -> if (index == 0) Button.SCROLL_UP else Button.SCROLL_DOWN
            0x20 -> when (index) {
                0 -> Button.DRAG_LEFT
                1 -> Button.DRAG_MIDDLE
                2 -> Button.DRAG_RIGHT
                else -> Button.RELEASE
            }
            else -> Button.RELEASE
        }
    }
}
