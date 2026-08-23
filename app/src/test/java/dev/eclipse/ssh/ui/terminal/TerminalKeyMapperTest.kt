package dev.eclipse.ssh.ui.terminal

import androidx.compose.ui.input.key.Key
import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.terminal.TerminalKey
import dev.eclipse.ssh.terminal.TerminalKeys
import dev.eclipse.ssh.terminal.TerminalModifiers
import org.junit.Test

/**
 * What a key press means, decided without a keyboard.
 *
 * These rules used to live inside `onPreviewKeyEvent`, where the only way to exercise them was to run a
 * Compose tree and send it synthetic events - so in practice they were never exercised at all, and every
 * one of them has a failure mode a user reports as "the keyboard is broken": a command that runs twice,
 * a latched CTRL that turns into a bare letter, or a character the terminal swallows because it claimed
 * an event it had nothing to do with. Pulling the decision out into a pure function is what makes those
 * three cases assertions rather than field reports.
 */
class TerminalKeyMapperTest {

    @Test
    fun `only a key down acts, so a press is not sent twice`() {
        // Android reports a down and an up for one press. Acting on both would run every command twice.
        assertThat(map(Key.Enter, keyDown = false)).isEqualTo(TerminalKeyAction.Decline)
        assertThat(map(Key.Enter)).isEqualTo(TerminalKeyAction.Named(TerminalKey.ENTER, TerminalModifiers.NONE))
    }

    @Test
    fun `a named key is claimed even with no modifier at all`() {
        // The invisible field could not express any of these: an arrow key would move a caret that does
        // not exist, and Enter would insert a newline into a field nobody reads.
        assertThat(map(Key.DirectionUp)).isEqualTo(TerminalKeyAction.Named(TerminalKey.ARROW_UP, TerminalModifiers.NONE))
        assertThat(map(Key.Backspace)).isEqualTo(TerminalKeyAction.Named(TerminalKey.BACKSPACE, TerminalModifiers.NONE))
        assertThat(map(Key.Tab)).isEqualTo(TerminalKeyAction.Named(TerminalKey.TAB, TerminalModifiers.NONE))
        assertThat(map(Key.Escape)).isEqualTo(TerminalKeyAction.Named(TerminalKey.ESCAPE, TerminalModifiers.NONE))
    }

    @Test
    fun `a latched ctrl and a held ctrl are the same key`() {
        val latched = map(Key.C, codePoint = 'c'.code, armed = TerminalModifiers(ctrl = true))
        val held = map(Key.C, codePoint = 'c'.code, held = TerminalModifiers(ctrl = true))

        assertThat(latched).isEqualTo(TerminalKeyAction.Chord('c', TerminalModifiers(ctrl = true)))
        // The whole point of the union: a thumb tap on the CTRL cap has to reach the shell as the byte a
        // hardware Ctrl would send, because Ctrl-C is how a runaway command is stopped.
        assertThat(latched).isEqualTo(held)
        assertThat(TerminalKeys.encode('c', TerminalModifiers(ctrl = true))).isEqualTo(byteArrayOf(0x03))
    }

    @Test
    fun `a latch also reaches a named key`() {
        val action = map(Key.DirectionLeft, armed = TerminalModifiers(ctrl = true, shift = true))

        // Ctrl-Left is "previous word" in readline and Shift-Left extends a selection in an editor;
        // neither is reachable on a touchscreen except through the latches.
        assertThat(action)
            .isEqualTo(TerminalKeyAction.Named(TerminalKey.ARROW_LEFT, TerminalModifiers(ctrl = true, shift = true)))
    }

    @Test
    fun `shift reaches a named key but is dropped from a chord`() {
        val named = map(Key.Tab, held = TerminalModifiers(shift = true))
        val chord = map(Key.A, codePoint = 'A'.code, held = TerminalModifiers(ctrl = true, shift = true))

        // Shift-Tab is its own sequence, so the modifier has to survive.
        assertThat(named).isEqualTo(TerminalKeyAction.Named(TerminalKey.TAB, TerminalModifiers(shift = true)))
        // A chord is different: the keyboard already produced the capital, and re-applying Shift here
        // would encode `A` as a three-key chord rather than as the character it is.
        assertThat(chord).isEqualTo(TerminalKeyAction.Chord('A', TerminalModifiers(ctrl = true)))
    }

    @Test
    fun `a bare printable is declined so the field can report it`() {
        // Claiming this would consume the event, and the IME text change that carries the character
        // would never arrive - a terminal that silently eats what is typed.
        assertThat(map(Key.A, codePoint = 'a'.code)).isEqualTo(TerminalKeyAction.Decline)
        assertThat(map(Key.Spacebar, codePoint = ' '.code)).isEqualTo(TerminalKeyAction.Decline)
    }

    @Test
    fun `pressing a modifier key itself is declined, so an armed latch survives it`() {
        // A hardware Ctrl press is an event of its own with no character. Declining leaves the latches
        // to the caller, which only disarms them when an action comes back: arming CTRL and then
        // pressing Shift must not spend the Ctrl.
        assertThat(map(Key.CtrlLeft, codePoint = 0, armed = TerminalModifiers(ctrl = true)))
            .isEqualTo(TerminalKeyAction.Decline)
        assertThat(map(Key.ShiftLeft, codePoint = 0, held = TerminalModifiers(shift = true)))
            .isEqualTo(TerminalKeyAction.Decline)
    }

    @Test
    fun `a character outside ascii is declined rather than folded into a wrong byte`() {
        // Ctrl and Alt are defined on ASCII. There is no control byte for Ctrl-e-acute, so the event is
        // left to the field and the character arrives as text instead of as a mangled one.
        assertThat(map(Key.E, codePoint = 0x00E9, held = TerminalModifiers(ctrl = true)))
            .isEqualTo(TerminalKeyAction.Decline)
    }

    @Test
    fun `ctrl space is a chord, which is the only way to send NUL`() {
        val action = map(Key.Spacebar, codePoint = ' '.code, armed = TerminalModifiers(ctrl = true))

        assertThat(action).isEqualTo(TerminalKeyAction.Chord(' ', TerminalModifiers(ctrl = true)))
        // What it is for: setting the mark in `emacs`, and `set-mark-command` in bash's emacs bindings.
        assertThat(TerminalKeys.encode(' ', TerminalModifiers(ctrl = true))).isEqualTo(byteArrayOf(0x00))
    }

    @Test
    fun `alt prefixes a printable rather than replacing it`() {
        val action = map(Key.B, codePoint = 'b'.code, armed = TerminalModifiers(alt = true))

        assertThat(action).isEqualTo(TerminalKeyAction.Chord('b', TerminalModifiers(alt = true)))
        // Alt-B is "back one word", and it is ESC then the letter - a prefix, not a folded byte.
        assertThat(TerminalKeys.encode('b', TerminalModifiers(alt = true))).isEqualTo(byteArrayOf(0x1B, 'b'.code.toByte()))
    }

    @Test
    fun `every terminal key has a platform key that produces it`() {
        // A key missing from the table is not a compile error - it is a key that quietly does nothing.
        assertThat(TERMINAL_NAMED_KEYS.values.toSet()).containsExactlyElementsIn(TerminalKey.entries)
        // Both Enters, because a hardware numeric keypad sends the other one.
        assertThat(TERMINAL_NAMED_KEYS[Key.NumPadEnter]).isEqualTo(TerminalKey.ENTER)
    }

    @Test
    fun `the shortcut bar carries every key a touch device cannot otherwise reach`() {
        val labels = NAMED_ROW.map { it.first }

        assertThat(labels).containsExactly(
            "ESC", "TAB", "←", "↓", "↑", "→",
            "HOME", "END", "PGUP", "PGDN", "DEL", "BKSP", "ENTER",
        ).inOrder()
    }

    @Test
    fun `every cap on the shortcut bar encodes to bytes for the wire`() {
        // The bar taps straight into the same send path as the keyboard, so a cap is only useful if the
        // key behind it has an encoding. A cap that encodes to nothing is a button that does nothing.
        NAMED_ROW.forEach { (label, key) ->
            assertThat(TerminalKeys.encode(key).isNotEmpty()).isTrue()
            assertThat(label).isNotEmpty()
        }
        val byKey = NAMED_ROW.associate { it.first to it.second }
        // The three the request calls out by name, checked against what a terminal actually expects:
        // Enter is CR because the pty's line discipline turns it into a newline, BKSP is DEL because
        // `stty erase` is DEL on every mainstream Unix, and DEL is the forward-delete sequence.
        assertThat(TerminalKeys.encode(byKey.getValue("ENTER"))).isEqualTo(byteArrayOf(0x0D))
        assertThat(TerminalKeys.encode(byKey.getValue("BKSP"))).isEqualTo(byteArrayOf(0x7F))
        assertThat(TerminalKeys.encode(byKey.getValue("DEL")))
            .isEqualTo(byteArrayOf(0x1B, '['.code.toByte(), '3'.code.toByte(), '~'.code.toByte()))
    }

    @Test
    fun `the arrow caps follow the mode the remote program set`() {
        val left = NAMED_ROW.first { it.first == "←" }.second

        assertThat(TerminalKeys.encode(left, applicationCursorKeys = false))
            .isEqualTo(byteArrayOf(0x1B, '['.code.toByte(), 'D'.code.toByte()))
        // `less` and anything using ncurses set DECCKM, and want SS3. Sending CSI there scrolls nothing.
        assertThat(TerminalKeys.encode(left, applicationCursorKeys = true))
            .isEqualTo(byteArrayOf(0x1B, 'O'.code.toByte(), 'D'.code.toByte()))
    }
}

private fun map(
    key: Key,
    codePoint: Int = 0,
    held: TerminalModifiers = TerminalModifiers.NONE,
    armed: TerminalModifiers = TerminalModifiers.NONE,
    keyDown: Boolean = true,
): TerminalKeyAction = mapTerminalKeyEvent(
    keyDown = keyDown,
    key = key,
    codePoint = codePoint,
    held = held,
    armed = armed,
)
