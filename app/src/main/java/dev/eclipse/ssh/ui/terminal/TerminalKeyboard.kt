package dev.eclipse.ssh.ui.terminal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.eclipse.ssh.terminal.TerminalKey

/**
 * The Ctrl / Alt / Shift latches on the on-screen key row.
 *
 * They latch rather than requiring a chord because a touchscreen cannot hold one key while pressing
 * another: tapping CTRL arms it, the next key press consumes it, and [consume] clears all three. Two
 * taps on the same latch turn it off again, so an armed modifier is never a trap. Sticky-until-used
 * is what every terminal keyboard on the platform does, and it is the only scheme that makes Ctrl-C
 * reachable with one thumb.
 */
@Stable
class TerminalLatches(ctrl: Boolean = false, alt: Boolean = false, shift: Boolean = false) {
    var ctrl by mutableStateOf(ctrl)
        private set
    var alt by mutableStateOf(alt)
        private set
    var shift by mutableStateOf(shift)
        private set

    fun toggleCtrl() { ctrl = !ctrl }
    fun toggleAlt() { alt = !alt }
    fun toggleShift() { shift = !shift }

    /** Reads the armed modifiers and disarms them, which is what pressing any other key does. */
    fun consume(): Triple<Boolean, Boolean, Boolean> {
        val armed = Triple(ctrl, alt, shift)
        ctrl = false
        alt = false
        shift = false
        return armed
    }

    companion object {
        /**
         * Survives rotation. An armed Ctrl is invisible state the user is relying on - losing it
         * because the device turned would send a bare `c` to a program they were trying to interrupt.
         */
        val Saver: Saver<TerminalLatches, List<Boolean>> = Saver(
            save = { listOf(it.ctrl, it.alt, it.shift) },
            restore = { TerminalLatches(it[0], it[1], it[2]) },
        )
    }
}

@Composable
fun rememberTerminalLatches(): TerminalLatches =
    rememberSaveable(saver = TerminalLatches.Saver) { TerminalLatches() }

/**
 * The invisible field that owns the IME session, translating everything it produces into terminal
 * input.
 *
 * A terminal has no text field, which is the difficulty: Android's software keyboards will only open
 * for a view holding an `InputConnection`, and they expect that view to contain editable text they
 * can inspect, autocorrect and delete backwards through. A terminal can offer none of that - the line
 * being edited lives in the remote shell's `readline`, and the app never sees it.
 *
 * The resolution is a one-line field, invisible and permanently reset to [SENTINEL], that is watched
 * for *edits* rather than read for content:
 *  - text longer than the sentinel means characters were inserted, and the insertion is sent;
 *  - text shorter means the IME deleted backwards, and one Backspace is sent per character it removed.
 *
 * The sentinel exists for that second case. With a genuinely empty field, Gboard has nothing to
 * delete and reports the Backspace through `deleteSurroundingText` on the input connection instead of
 * as a key event, so the keypress vanished entirely - the most common complaint about every naive
 * Compose terminal. Padding the field with characters the user cannot type gives the IME something to
 * consume, and the *shortfall* is the signal. Zero-width spaces are used because autocorrect and
 * suggestion strips leave them alone, and because filtering them out cannot swallow a real keystroke -
 * an ordinary space would be indistinguishable from one the user pressed.
 *
 * Hardware keys are taken before the field sees them, so an arrow key moves the remote cursor instead
 * of a caret that is not there, and so nothing arrives twice: consuming the event means no text
 * change follows it.
 */
@Composable
fun TerminalInputBridge(
    focusRequester: FocusRequester,
    latches: TerminalLatches,
    onText: (String) -> Unit,
    onKey: (TerminalKey, Boolean, Boolean, Boolean) -> Unit,
    onChar: (Char, Boolean, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var field by remember { mutableStateOf(sentinelValue()) }
    BasicTextField(
        value = field,
        onValueChange = { change ->
            val typed = change.text.filter { it != SENTINEL_CHAR }
            when {
                typed.isNotEmpty() -> {
                    val (ctrl, alt, _) = latches.consume()
                    if (ctrl || alt) {
                        // A latched modifier applies to one character, which is what Ctrl-C is.
                        typed.forEach { char -> onChar(char, ctrl, alt) }
                    } else {
                        onText(typed)
                    }
                }
                change.text.length < SENTINEL.length ->
                    repeat(SENTINEL.length - change.text.length) { onKey(TerminalKey.BACKSPACE, false, false, false) }
            }
            // Always back to the sentinel: the field is a keystroke courier, never a document.
            field = sentinelValue()
        },
        modifier = modifier
            .size(1.dp)
            .focusRequester(focusRequester)
            .semantics { contentDescription = "Terminal input" }
            .onPreviewKeyEvent { event -> handleKeyEvent(event, latches, onKey, onChar) },
        // A terminal is not prose: autocorrect would rewrite commands, capitalisation would rewrite
        // paths, and an IME action would insert a newline the shell did not ask for.
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.None,
        ),
        singleLine = true,
        cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.Transparent),
    )
}

/**
 * Turns a hardware or IME key event into terminal input, or declines it.
 *
 * Returning true consumes the event, which is what stops the invisible field from also processing it
 * and reporting the same keystroke a second time as a text change.
 */
private fun handleKeyEvent(
    event: KeyEvent,
    latches: TerminalLatches,
    onKey: (TerminalKey, Boolean, Boolean, Boolean) -> Unit,
    onChar: (Char, Boolean, Boolean) -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val armed = latches.consume()
    val ctrl = event.isCtrlPressed || armed.first
    val alt = event.isAltPressed || armed.second
    val shift = event.isShiftPressed || armed.third
    val named = NAMED_KEYS[event.key]
    if (named != null) {
        onKey(named, ctrl, alt, shift)
        return true
    }
    // Only intercept a printable key when a modifier makes it something the field could not express.
    if (ctrl || alt) {
        val code = event.utf16CodePoint
        if (code in PRINTABLE_RANGE) {
            onChar(code.toChar(), ctrl, alt)
            return true
        }
    }
    // Nothing matched, so re-arm what was read: the latch is meant for the next key, not this one.
    if (armed.first) latches.toggleCtrl()
    if (armed.second) latches.toggleAlt()
    if (armed.third) latches.toggleShift()
    return false
}

private val PRINTABLE_RANGE = 0x20..0x7E

private val NAMED_KEYS: Map<Key, TerminalKey> = mapOf(
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

/** U+200B, written by code point because an invisible character in source is a trap. */
private val SENTINEL_CHAR: Char = Char(0x200B)
private val SENTINEL = SENTINEL_CHAR.toString().repeat(SENTINEL_LENGTH)

/** Long enough that a fast repeated Backspace cannot empty the field between resets. */
private const val SENTINEL_LENGTH = 8

private fun sentinelValue() = TextFieldValue(SENTINEL, TextRange(SENTINEL.length))

/**
 * The row of keys a phone keyboard does not have.
 *
 * Everything here is reachable no other way on a touch device - there is no Ctrl on Gboard, no Esc,
 * and no Page Up - so this row is not a convenience but the difference between the terminal being
 * usable and being a text box. It scrolls horizontally rather than wrapping, so the leftmost keys
 * stay where the thumb expects them at every screen width.
 */
@Composable
fun TerminalKeyRow(
    latches: TerminalLatches,
    onKey: (TerminalKey, Boolean, Boolean, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LatchKey("CTRL", latches.ctrl, latches::toggleCtrl)
        LatchKey("ALT", latches.alt, latches::toggleAlt)
        LatchKey("SHIFT", latches.shift, latches::toggleShift)
        NAMED_ROW.forEach { (label, key) ->
            TerminalKeyCap(label) {
                val (ctrl, alt, shift) = latches.consume()
                onKey(key, ctrl, alt, shift)
            }
        }
    }
}

private val NAMED_ROW: List<Pair<String, TerminalKey>> = listOf(
    "ESC" to TerminalKey.ESCAPE,
    "TAB" to TerminalKey.TAB,
    "←" to TerminalKey.ARROW_LEFT,
    "↓" to TerminalKey.ARROW_DOWN,
    "↑" to TerminalKey.ARROW_UP,
    "→" to TerminalKey.ARROW_RIGHT,
    "HOME" to TerminalKey.HOME,
    "END" to TerminalKey.END,
    "PGUP" to TerminalKey.PAGE_UP,
    "PGDN" to TerminalKey.PAGE_DOWN,
    "DEL" to TerminalKey.DELETE,
    "BKSP" to TerminalKey.BACKSPACE,
    "ENTER" to TerminalKey.ENTER,
)

@Composable
private fun LatchKey(label: String, armed: Boolean, onToggle: () -> Unit) {
    TerminalKeyCap(
        label = label,
        armed = armed,
        // Announced rather than only coloured: an armed latch changes what the next key does, which a
        // screen reader user has no other way to discover.
        description = if (armed) "$label on" else "$label off",
        onClick = onToggle,
    )
}

@Composable
private fun TerminalKeyCap(
    label: String,
    armed: Boolean = false,
    description: String? = null,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .clickable(onClick = onClick)
            .semantics { contentDescription = description ?: label },
        shape = RoundedCornerShape(8.dp),
        color = if (armed) MaterialTheme.colorScheme.primary else KEY_BACKGROUND,
    ) {
        Text(
            label,
            color = if (armed) MaterialTheme.colorScheme.onPrimary else KEY_FOREGROUND,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

private val KEY_BACKGROUND = Color(0xFF1D2638)
private val KEY_FOREGROUND = Color(0xFFB3C1D9)
