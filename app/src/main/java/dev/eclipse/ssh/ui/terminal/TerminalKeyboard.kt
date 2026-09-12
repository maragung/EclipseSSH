package dev.eclipse.ssh.ui.terminal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
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
import dev.eclipse.ssh.terminal.TerminalModifiers

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
 * The resolution is a one-line field, invisible, holding a [SENTINEL] the user cannot type and every
 * keystroke the IME has committed since the last reset, that is watched for *edits* rather than read
 * for content: an incoming edit is diffed against what the platform held, the insertion is sent, and
 * the IME's own text is adopted as the new value - so the app never performs a programmatic edit on
 * the field the IME is composing into, which is the bug this used to have (see
 * [TerminalInputBridge.resetPendingFrom] and [nextFieldValue]).
 *
 * The sentinel exists for Backspace. With a genuinely empty field, Gboard has nothing to delete and
 * reports the Backspace through `deleteSurroundingText` on the input connection instead of as a key
 * event, so the keypress vanished entirely - the most common complaint about every naive Compose
 * terminal. Padding the field with characters the user cannot type gives the IME something to consume,
 * and the deletion is read out of the diff. Zero-width spaces are used because autocorrect and
 * suggestion strips leave them alone, and because filtering them out cannot swallow a real keystroke -
 * an ordinary space would be indistinguishable from one the user pressed.
 *
 * Hardware keys are taken before the field sees them, so an arrow key moves the remote cursor instead
 * of a caret that is not there, and so nothing arrives twice: consuming the event means no text
 * change follows it.
 *
 * Focus is the whole thing. Nothing here can receive a keystroke - not the IME's text, not a hardware
 * key through [onPreviewKeyEvent] - unless this field holds focus, which is why [onFocusChanged] is
 * reported outwards: the screen has to be able to tell whether the keyboard is connected to the shell
 * and ask again if it is not. Guessing "focus was requested, so it must be focused" is not the same
 * fact, and the difference is a terminal that silently swallows everything the user types.
 */
@Composable
fun TerminalInputBridge(
    focusRequester: FocusRequester,
    latches: TerminalLatches,
    onText: (String) -> Unit,
    onKey: (TerminalKey, Boolean, Boolean, Boolean) -> Unit,
    onChar: (Char, Boolean, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onFocusChanged: (Boolean) -> Unit = {},
) {
    var field by remember { mutableStateOf(sentinelValue()) }
    /**
     * The field content a reset has not reached yet. Setting [field] back to the sentinel is a
     * programmatic edit, and Compose pushes those to the platform on the next recomposition - so
     * until that frame lands, an edit can still arrive that was computed against the *old* content.
     * This holds that old content, and [pendingResetBase] decides which of the two an incoming
     * change was computed against. Null in the common case, where nothing is in flight and the
     * adopted value is exactly what the platform holds.
     */
    var resetPendingFrom by remember { mutableStateOf<String?>(null) }
    BasicTextField(
        value = field,
        onValueChange = { change ->
            // The diff base is what the platform held when the IME computed this edit: normally the
            // value the field already carries (the last adopted edit, which is exactly what the
            // platform holds - adoption pushes nothing), but while a reset is in flight the
            // platform may still hold the pre-reset content for a frame, and diffing against the
            // wrong one of the two is how a keystroke gets sent twice or not at all.
            val base = pendingResetBase(resetPendingFrom, field.text, change.text)
            val edit = diffTerminalEdit(base, change.text)
            // Deletions first: an edit that both replaces and inserts emulates the replacement as
            // Backspaces followed by the new text, which is the order a shell would need to see.
            repeat(edit.deleted) { onKey(TerminalKey.BACKSPACE, false, false, false) }
            val typed = edit.inserted.filter { it != SENTINEL_CHAR }
            if (typed.isNotEmpty()) {
                val (ctrl, alt, _) = latches.consume()
                if (ctrl || alt) {
                    // A latched modifier applies to one character, which is what Ctrl-C is.
                    typed.forEach { char -> onChar(char, ctrl, alt) }
                } else {
                    sendCommittedText(typed, onText, onKey)
                }
            }
            // Adopt what the IME produced rather than clearing it: `change` *is* the platform
            // field's content, so adopting it keeps the two in lockstep with no programmatic edit
            // for the IME to trip over. Clearing back to the sentinel here instead - the previous
            // design - tore the IME's state twice over: a commit that arrived before the reset was
            // pushed was diffed against stale content and re-sent the previous keystroke with the
            // new one (symbols doubled), and tearing a pending composition made some keyboards
            // drop its last character entirely, so Enter ran "hel" for "help". See
            // [nextFieldValue] for when a reset does happen, and the sentinel's own docs for why
            // the field is never left empty.
            val next = nextFieldValue(change)
            field = next
            // If that was a reset, the platform still holds what `change` says until the next frame.
            resetPendingFrom = if (next.text != change.text) change.text else null
        },
        modifier = modifier
            .size(1.dp)
            .focusRequester(focusRequester)
            // Before the requester in the chain would report the focus of whatever is above it; after
            // it, this observes the field itself.
            .onFocusChanged { state -> onFocusChanged(state.isFocused) }
            .semantics { contentDescription = "Terminal input" }
            .onPreviewKeyEvent { event ->
                val handled = handleKeyEvent(event, latches, onKey, onChar)
                // Enter that arrived as a key event is the moment the line is finished and the
                // buffer's tail becomes history, which is the safe point to clear it: the IME has
                // finished any composition before sending the key, and the next keystroke is a new
                // line rather than the same frame. KeyDown only - the up event of the same press
                // must not clear twice.
                if (handled && event.type == KeyEventType.KeyDown &&
                    TERMINAL_NAMED_KEYS[event.key] == TerminalKey.ENTER
                ) {
                    if (resetPendingFrom == null && field.text.length > SENTINEL.length) {
                        resetPendingFrom = field.text
                        field = sentinelValue()
                    }
                }
                handled
            },
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
 * Sends text the IME committed, turning any newline inside it into a real Enter.
 *
 * Most keyboards report Return as a key event, which [handleKeyEvent] maps to [TerminalKey.ENTER];
 * some commit it as text instead, and a multi-line clipboard suggestion or a voice-typed "new line"
 * arrives the same way. Passing those through verbatim would put a bare LF on the wire where every
 * other route in the app sends CR - `TerminalKeys.normalizeNewlines` already holds that line for a
 * paste - so the same Return would encode differently depending on which keyboard the user installed,
 * and a shell reading a raw pty would see the wrong end-of-line. CRLF is one Return, not two.
 *
 * This is not the paste path: a paste goes through `MainViewModel.pasteIntoTerminal`, which brackets
 * it when the remote asked for bracketed paste.
 */
internal fun sendCommittedText(
    typed: String,
    onText: (String) -> Unit,
    onKey: (TerminalKey, Boolean, Boolean, Boolean) -> Unit,
) {
    // Nothing to send is not a write of nothing: the call site already guards this, and a helper that
    // cannot be trusted on its own is the kind that grows a second caller without one.
    if (typed.isEmpty()) return
    if (typed.none { it == LINE_FEED || it == CARRIAGE_RETURN }) {
        onText(typed)
        return
    }
    val run = StringBuilder(typed.length)
    var index = 0
    while (index < typed.length) {
        val char = typed[index]
        if (char != LINE_FEED && char != CARRIAGE_RETURN) {
            run.append(char)
            index++
            continue
        }
        if (run.isNotEmpty()) {
            onText(run.toString())
            run.setLength(0)
        }
        onKey(TerminalKey.ENTER, false, false, false)
        index += if (char == CARRIAGE_RETURN && typed.getOrNull(index + 1) == LINE_FEED) 2 else 1
    }
    if (run.isNotEmpty()) onText(run.toString())
}

private const val LINE_FEED = '\n'
private const val CARRIAGE_RETURN = '\r'

/**
 * Hands a hardware or IME key event to [mapTerminalKeyEvent] and carries out what comes back.
 *
 * Returning true consumes the event, which is what stops the invisible field from also processing it
 * and reporting the same keystroke a second time as a text change. Nothing is decided here: the rules
 * live in the pure mapper, and this reads the event and disarms the latches, which is all a composable
 * is in a position to do.
 */
private fun handleKeyEvent(
    event: KeyEvent,
    latches: TerminalLatches,
    onKey: (TerminalKey, Boolean, Boolean, Boolean) -> Unit,
    onChar: (Char, Boolean, Boolean) -> Unit,
): Boolean {
    val action = mapTerminalKeyEvent(
        keyDown = event.type == KeyEventType.KeyDown,
        key = event.key,
        codePoint = event.utf16CodePoint,
        held = TerminalModifiers(
            ctrl = event.isCtrlPressed,
            alt = event.isAltPressed,
            shift = event.isShiftPressed,
        ),
        armed = TerminalModifiers(ctrl = latches.ctrl, alt = latches.alt, shift = latches.shift),
    )
    return when (action) {
        // Declined, so the latches are left alone: they are armed for the key the user is about to
        // type, and this was not it.
        TerminalKeyAction.Decline -> false
        is TerminalKeyAction.Named -> {
            latches.consume()
            onKey(action.key, action.modifiers.ctrl, action.modifiers.alt, action.modifiers.shift)
            true
        }
        is TerminalKeyAction.Chord -> {
            latches.consume()
            onChar(action.char, action.modifiers.ctrl, action.modifiers.alt)
            true
        }
    }
}

/** U+200B, written by code point because an invisible character in source is a trap. */
private val SENTINEL_CHAR: Char = Char(0x200B)
private val SENTINEL = SENTINEL_CHAR.toString().repeat(SENTINEL_LENGTH)

/** Long enough that a fast repeated Backspace cannot empty the field between resets. */
private const val SENTINEL_LENGTH = 8

/**
 * How much committed text the courier keeps before it resets to the sentinel. Generous on purpose:
 * a reset is the only programmatic edit this field ever makes, so each one is a moment the IME can
 * trip over, and the fewer of them there are the better. It only has to be short enough that a
 * fumbled reset cannot lose a whole command line.
 */
private const val FIELD_HARD_CAP = 64

private fun sentinelValue() = TextFieldValue(SENTINEL, TextRange(SENTINEL.length))

/**
 * One incoming edit, expressed as what the shell needs to be told: how many characters were removed
 * from the end, and what was inserted after that removal.
 *
 * Deletions in the middle cannot happen here - the caret sits at the end, the field is one line, and
 * the IME has no selection UI over an invisible field - so the diff is a suffix-count, not a general
 * tree diff. A replace-em-all edit (autocorrect rewriting the tail, or the IME delivering a batched
 * composition as one change) arrives as deleted > 0 *and* inserted text, and the deletions are sent
 * first so the shell applies them in the order a user's hands would.
 *
 * Deletions that reach into the sentinel count like any other: a held Backspace keeps sending them,
 * which is the deliberate choice - the alternative, clamping at the padding, is a terminal that
 * swallows the last few presses of a long hold instead of passing them on.
 */
internal data class TerminalEdit(val deleted: Int, val inserted: String)

/**
 * What changed between the text the platform held and the text it now reports.
 *
 * Pure, so the exact worst cases - a commit racing the previous one, a composition landing after a
 * reset, autocorrect rewriting a tail - are assertions in `TerminalCourierFieldTest` rather than
 * field reports of doubled symbols and eaten characters.
 */
internal fun diffTerminalEdit(base: String, incoming: String): TerminalEdit {
    // The shared prefix: everything up to where the two texts stop agreeing.
    var common = 0
    while (common < base.length && common < incoming.length &&
        base[common] == incoming[common] && common < SENTINEL_LENGTH + FIELD_HARD_CAP
    ) common++
    val deleted = base.length - common
    val inserted = incoming.substring(common)
    return TerminalEdit(deleted, inserted)
}

/**
 * The content an incoming edit was diffed against.
 *
 * Compose pushes a programmatic value change to the platform field at the next recomposition, so
 * between `field = sentinelValue()` and that frame landing there are two candidates for what the
 * platform held when the IME computed its edit: the pre-reset text the platform may still hold, or
 * the reset value the recomposition is installing ([adopted], which is what the field carries).
 * The discriminator is structural: an edit computed against the pre-reset content worked from its
 * keystroke tail, so one of the two tails extends the other - an insertion appends past it, a
 * deletion truncates it - while an edit computed after the reset landed starts a tail of its own
 * that merely happens to overlap. Tail relation means the old base; anything else means the reset
 * landed and [adopted] is the truth.
 *
 * Both tails must be non-empty for the relation to mean anything: an empty pre-reset tail carries
 * no evidence (the reset was from a bare sentinel), and an empty incoming tail is the
 * reset-landed-then-backspace-into-the-padding case, where guessing "raced" would send a handful
 * of spurious Backspaces for what was one press.
 */
internal fun pendingResetBase(resetPendingFrom: String?, adopted: String, incoming: String): String {
    if (resetPendingFrom == null) return adopted
    val preResetTail = resetPendingFrom.drop(SENTINEL_LENGTH)
    val incomingTail = incoming.drop(SENTINEL_LENGTH)
    val raced = preResetTail.isNotEmpty() && incomingTail.isNotEmpty() &&
        (incomingTail.startsWith(preResetTail) || preResetTail.startsWith(incomingTail))
    return if (raced) resetPendingFrom else adopted
}

/**
 * The next value for the courier field: adopt the IME's edit, unless it is time to reset.
 *
 * Adoption is the rule and the reset is the exception, each for its own reason:
 *  - **Adopt.** The value `onValueChange` reports *is* the platform field's content, so echoing it
 *    back as state keeps the two in lockstep with no programmatic edit at all. The previous design
 *    reset on every keystroke, which meant tearing the field's text twice per character: a commit
 *    that arrived before the reset's recomposition was diffed against stale content and re-sent the
 *    previous symbol along with the new one (the doubled `/`), and a keyboard mid-composition could
 *    lose its pending character to the tear, so Enter ran `hel` for `help`.
 *  - **Reset, but only when the shell cannot lose anything.** The field cannot grow forever, and it
 *    must never run empty of sentinel. Both are handled here, and the two moments are chosen for
 *    safety rather than frequency: right after a newline in the committed text (the line is
 *    finished), or when the hard cap is reached. At those moments the IME is between words, which
 *    is when a reset is least able to eat a character.
 */
internal fun nextFieldValue(change: TextFieldValue): TextFieldValue {
    val resetsOnNewline = change.text.any { it == LINE_FEED || it == CARRIAGE_RETURN }
    if (!resetsOnNewline && change.text.startsWith(SENTINEL) &&
        change.text.length < SENTINEL_LENGTH + FIELD_HARD_CAP
    ) {
        // Plain adoption: what the IME wrote is what the field now holds.
        return change
    }
    // A reset, at one of the two safe moments (a finished line, the hard cap) or because the
    // sentinel was somehow consumed. The new tail is the keystrokes that survive the reset - none,
    // when the line is finished - so the shell's next Backspace still has something to eat.
    val tail = if (resetsOnNewline) {
        ""
    } else {
        change.text.filter { it != SENTINEL_CHAR }.takeLast(FIELD_HARD_CAP - SENTINEL_LENGTH)
    }
    return TextFieldValue(SENTINEL + tail, TextRange(SENTINEL.length + tail.length))
}

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

/**
 * The caps, in thumb order.
 *
 * `internal` so a test can hold the bar to the keys a terminal is unusable without: on a touch device
 * there is no other way to reach Esc, Tab, the arrows or Page Up, so a cap quietly dropped from this
 * list is a key that no longer exists on the device.
 */
internal val NAMED_ROW: List<Pair<String, TerminalKey>> = listOf(
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
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 10.dp)
                // A thumb misses; 44dp is the smallest cap that stays easy to hit, and every cap the
                // same height is what makes the row read as one firm, even strip.
                .heightIn(min = 24.dp),
        )
    }
}

private val KEY_BACKGROUND = Color(0xFF1D2638)
private val KEY_FOREGROUND = Color(0xFFB3C1D9)
