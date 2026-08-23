package dev.eclipse.ssh.ui.terminal

import androidx.compose.ui.input.key.Key
import dev.eclipse.ssh.terminal.TerminalKey
import dev.eclipse.ssh.terminal.TerminalModifiers

/**
 * What one key press should do to the remote shell.
 *
 * Three outcomes, and the third is the one that needs a name. A terminal cannot claim every key event
 * it is offered: the invisible IME field behind the grid is how ordinary characters arrive, so a plain
 * `a` has to be *left alone* and allowed to become a text change. Modelling that as a value rather than
 * as a bare `false` is what lets the latch rule below be stated once and tested.
 */
sealed interface TerminalKeyAction {

    /** A key with no character of its own, encoded by `TerminalKeys.encode`. */
    data class Named(val key: TerminalKey, val modifiers: TerminalModifiers) : TerminalKeyAction

    /**
     * A printable character whose modifier changes the byte it sends - Ctrl-C, Alt-B.
     *
     * [modifiers] never carries Shift. By the time a character reaches the app the keyboard has already
     * produced the shifted form, so re-applying Shift would encode `A` as a chord instead of as itself;
     * `TerminalModifiers` documents the same rule from the encoding side.
     */
    data class Chord(val char: Char, val modifiers: TerminalModifiers) : TerminalKeyAction

    /** Not the terminal's key. The event is passed on, and any armed latch stays armed. */
    data object Decline : TerminalKeyAction
}

/**
 * Decides what a key event means, given what is held and what is latched.
 *
 * Pure, and separate from the composable that receives the event, because every interesting rule here
 * is invisible from the outside and each one has a failure mode a user reports as "the keyboard is
 * broken":
 *
 *  * **Only key-downs act.** Android delivers a down and an up for every press. Acting on both sends
 *    each keystroke twice, which on Enter means running every command twice.
 *  * **Held and latched modifiers are the same thing.** A hardware Ctrl and a tapped CTRL cap must
 *    produce the same byte, so the two sets are unioned rather than handled on separate paths.
 *  * **A latch survives a key this function declines.** The caller consumes the latches only when an
 *    action comes back, so arming CTRL and then typing on the software keyboard still yields Ctrl-C:
 *    the `c` arrives later as a text change, and the latch is still there to be applied to it. Reading
 *    the latches here and restoring them on the way out - which is what this replaced - had the same
 *    effect only as long as nobody ever added an early return.
 *  * **A bare printable is declined.** Claiming it would consume the event and stop the field from
 *    reporting the character at all, which is a terminal that silently swallows what is typed.
 */
internal fun mapTerminalKeyEvent(
    keyDown: Boolean,
    key: Key,
    codePoint: Int,
    held: TerminalModifiers,
    armed: TerminalModifiers = TerminalModifiers.NONE,
): TerminalKeyAction {
    if (!keyDown) return TerminalKeyAction.Decline
    val modifiers = held + armed
    val named = TERMINAL_NAMED_KEYS[key]
    if (named != null) return TerminalKeyAction.Named(named, modifiers)
    if (!modifiers.ctrl && !modifiers.alt) return TerminalKeyAction.Decline
    if (codePoint !in PRINTABLE_RANGE) return TerminalKeyAction.Decline
    return TerminalKeyAction.Chord(codePoint.toChar(), TerminalModifiers(ctrl = modifiers.ctrl, alt = modifiers.alt))
}

/** Both sets of modifiers, since a latched Ctrl and a held Ctrl mean the same key. */
private operator fun TerminalModifiers.plus(other: TerminalModifiers): TerminalModifiers =
    if (other.none) this else TerminalModifiers(ctrl || other.ctrl, alt || other.alt, shift || other.shift)

/**
 * The printable ASCII range, which is the only range a chord is formed from.
 *
 * Ctrl and Alt fold a character into a control byte or an ESC prefix, and both operations are defined
 * on ASCII. A Ctrl held with an accented or CJK character has no encoding to give, so the event is
 * declined and the character arrives as text instead of being dropped.
 */
private val PRINTABLE_RANGE = 0x20..0x7E

/**
 * Every platform key that carries no character, and the terminal key it is.
 *
 * `internal` so a test can check it against [TerminalKey] rather than against a second copy of the same
 * list: a key missing from here is not a compile error, it is a key that does nothing.
 */
internal val TERMINAL_NAMED_KEYS: Map<Key, TerminalKey> = mapOf(
    Key.Enter to TerminalKey.ENTER,
    Key.NumPadEnter to TerminalKey.ENTER,
    Key.Backspace to TerminalKey.BACKSPACE,
    Key.Tab to TerminalKey.TAB,
    Key.Escape to TerminalKey.ESCAPE,
    Key.DirectionUp to TerminalKey.ARROW_UP,
    Key.DirectionDown to TerminalKey.ARROW_DOWN,
    Key.DirectionLeft to TerminalKey.ARROW_LEFT,
    Key.DirectionRight to TerminalKey.ARROW_RIGHT,
    Key.MoveHome to TerminalKey.HOME,
    Key.MoveEnd to TerminalKey.END,
    Key.PageUp to TerminalKey.PAGE_UP,
    Key.PageDown to TerminalKey.PAGE_DOWN,
    Key.Insert to TerminalKey.INSERT,
    Key.Delete to TerminalKey.DELETE,
    Key.F1 to TerminalKey.F1,
    Key.F2 to TerminalKey.F2,
    Key.F3 to TerminalKey.F3,
    Key.F4 to TerminalKey.F4,
    Key.F5 to TerminalKey.F5,
    Key.F6 to TerminalKey.F6,
    Key.F7 to TerminalKey.F7,
    Key.F8 to TerminalKey.F8,
    Key.F9 to TerminalKey.F9,
    Key.F10 to TerminalKey.F10,
    Key.F11 to TerminalKey.F11,
    Key.F12 to TerminalKey.F12,
)
