package dev.eclipse.ssh.ui.terminal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.eclipse.ssh.ui.rememberDialogBodyMaxHeight

/**
 * The shortcut bar's editor: which caps exist and show, how the rows are cut, and the presets that
 * offer a starting point.
 *
 * All editing happens on a local copy and one write takes the whole encoded blob - the same
 * whole-blob contract the editor's options sheet and every settings writer in the app follow - so a
 * dismissed editor costs nothing, and the terminal only ever sees a complete configuration.
 * [initial] is the decoded blob straight from settings; a damaged blob has already become the
 * defaults by the time it arrives here.
 *
 * Ordering is buttons, not drag-and-drop, deliberately. The bar lives above a software keyboard on
 * a phone: a drop target there is a thumb-width strip competing with the IME, and every missed drop
 * silently reorders something the user did not touch. Four explicit arrows (left/right in the row,
 * up/down between rows) say exactly what they do and ask for exactly one tap.
 *
 * This editor has two skins over one body. [ShortcutBarDialog] is the modal one, opened over the
 * terminal from the Settings list; `ShortcutBarActivity` in `ui/settings` is the window one, reached
 * from Settings now that the row promotes to a screen. They share [ShortcutBarEditorState],
 * [ShortcutBarEditorBody] and [ShortcutBarEditorDialogs], and they differ in exactly three things:
 * what frames the body, what Save does when it lands, and whether leaving asks. That sharing is the
 * point of the extraction rather than a tidiness: the rules the body carries - where a new cap
 * lands, that a preset replaces a whole arrangement rather than patching one, that a reset is a
 * preset like any other - are not obvious enough to be derived correctly twice, and a screen with its
 * own copy of the sections is precisely the pair that drifts, one of them silently keeping an old
 * rule the other has changed.
 *
 * This class is the state half of that editor: the one copy of the configuration being edited, plus
 * whichever of the editor's own two dialogs is open over it. Held by the skin rather than inside the
 * body, because a skin has questions the body cannot answer for it: the screen's back-out guard reads
 * [isDirty] to know whether leaving would throw work away, and both skins read [hasSubDialog] to decide
 * whether their own frame is on screen at all. The state is created by [rememberShortcutBarEditorState],
 * which is also where a skin states how long the copy should live.
 */
@Stable
class ShortcutBarEditorState(initial: KeyBarPrefs) {

    /**
     * The configuration the editor opened on, normalised the same way the working copy is.
     *
     * Normalised, not the raw [initial]: the working copy below is merged with the standard caps a
     * newer app version knows about, and comparing the edited copy against the *unmerged* original
     * would make a bar that is merely missing a cap read as dirty the instant the editor opened -
     * which is every bar written before the last key was added to the catalog.
     */
    private val openedWith: KeyBarPrefs = initial.withMissingStandardCaps()

    /**
     * The working copy, merged with any standard caps a newer app version knows about so the list
     * below can offer them; they arrive hidden and the user opts in.
     */
    var prefs: KeyBarPrefs by mutableStateOf(openedWith)

    /** The custom cap being added or edited, if any. */
    var editing: KeyBarCap? by mutableStateOf(null)

    /** The preset (or reset) whose destructive apply is awaiting confirmation, if any. */
    var pendingPreset: (() -> KeyBarPrefs)? by mutableStateOf(null)

    /** True when the arrangement on screen is not the one this editor opened on. */
    val isDirty: Boolean get() = prefs != openedWith

    /** True while one of the editor's own dialogs is up. Rendered by [ShortcutBarEditorDialogs]. */
    val hasSubDialog: Boolean get() = editing != null || pendingPreset != null

    /**
     * The blob a save writes: the whole arrangement, encoded the way the codec decodes it.
     *
     * Encoded at the moment of the write rather than kept up to date in a field, so a save cannot
     * write a stale string and there is no second copy of the configuration to fall out of step with
     * this one.
     */
    fun encoded(): String = KeyBarPrefsCodec.encode(prefs)

    /** Queue a preset. It replaces the whole arrangement, so it is applied behind a confirmation. */
    fun requestPreset(preset: () -> KeyBarPrefs) {
        pendingPreset = preset
    }

    /**
     * Restore the shipped defaults, through that same confirmation.
     *
     * Reset is not a special case of its own: `KeyBarPrefs()` is the defaults and the editor has
     * always treated it as one more preset, so it asks the user the same question every other preset
     * asks rather than clearing a hand-built bar on a single tap.
     */
    fun requestReset() {
        requestPreset { KeyBarPrefs() }
    }

    /** Apply the queued replacement, merged with the standard caps the way the opening value was. */
    fun applyPendingPreset() {
        val preset = pendingPreset ?: return
        pendingPreset = null
        prefs = preset().withMissingStandardCaps()
    }

    /** Start a new custom cap: empty text, so the custom-cap dialog opens on an empty field. */
    fun beginAdd() {
        editing = KeyBarCap(prefs.nextCustomId(), KeyBarCapKind.TEXT, text = "")
    }

    /**
     * Store the cap the custom-cap dialog returned: replaced in place if the list already holds that
     * id, otherwise appended at the end of the last row - visible, so what the user just built is what
     * they see when the dialog closes.
     */
    fun saveCap(saved: KeyBarCap) {
        prefs = prefs.copy(caps =
            if (prefs.caps.any { it.id == saved.id }) {
                prefs.caps.map { if (it.id == saved.id) saved else it }
            } else {
                val lastRow = prefs.caps.maxOfOrNull { it.row } ?: 0
                val lastOrder = prefs.caps.filter { it.row == lastRow }.maxOfOrNull { it.order } ?: -1
                prefs.caps + saved.copy(row = lastRow, order = lastOrder + 1)
            })
        editing = null
    }
}

/**
 * The editor's state, held for as long as a skin shows the editor.
 *
 * [sourceKey] is what that state is keyed on, and the default is the dialog's answer: MainActivity
 * decodes the stored blob once, before the dialog opens, and the only writer of that blob is the
 * dialog's own Save, which dismisses it - so the value [initial] came from cannot change underneath
 * the dialog, and keying on it would buy nothing but the risk of resetting the user's edits if it
 * ever did.
 *
 * A skin whose source *can* change has to say so. The settings shell hands its body `AppSettings()`
 * on the first composition, before the store has answered (see `SettingsDestination`), and the stored
 * snapshot on the next - so a screen that simply remembered the first value would open the editor on
 * the defaults and then, on Save, write those defaults over the user's bar. Keying on the blob itself
 * adopts the real value the moment it arrives and never again afterwards: the shell reads the store
 * once, and the screen's own write happens on the way out.
 */
@Composable
fun rememberShortcutBarEditorState(
    initial: KeyBarPrefs,
    sourceKey: Any? = Unit,
): ShortcutBarEditorState = remember(sourceKey) { ShortcutBarEditorState(initial) }

/**
 * The editor's body: the layout chips, every cap's row, and the presets.
 *
 * Frame-free on purpose. The dialog wraps it in an `AlertDialog` and caps its height; the screen
 * wraps it in the shell's `SettingsSection` inside a page that already scrolls. Neither frame belongs
 * in here - a body that carried one would be the letterbox the promotion exists to remove - and
 * neither does the padding a frame supplies, so a skin that wants the app's 16dp gutter puts it on
 * [modifier].
 *
 * [capListScrolls] is the one thing about a frame that reaches in, and it is about scrolling rather
 * than looks. The dialog's body is a fixed-height frame with no scroller of its own, so its cap list
 * carries both the `heightIn` cap and the scroll - it is the only thing in there that can move. The
 * screen's body sits in a page that already scrolls, and a second, capped vertical scroller nested in
 * that one would take a drag that started on the cap list and leave the page unable to reach its own
 * bottom. The rows are the same either way; only their container differs.
 */
@Composable
fun ShortcutBarEditorBody(
    state: ShortcutBarEditorState,
    modifier: Modifier = Modifier,
    capListScrolls: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = modifier) {
        LayoutSection(prefs = state.prefs, onChange = { state.prefs = it })
        CapsSection(
            prefs = state.prefs,
            onChange = { state.prefs = it },
            onEdit = { state.editing = it },
            onAdd = { state.beginAdd() },
            onPreset = { state.requestPreset(it) },
            capListScrolls = capListScrolls,
        )
    }
}

/**
 * The editor's own two dialogs: the custom-cap editor, and the confirmation a preset or a reset asks
 * for before it replaces the whole arrangement.
 *
 * Both skins render this, and its wording is the dialog's, unchanged. The confirmation is the one
 * place the editor asks the user to believe something - "this discards your current caps, rows and
 * order" - and a screen that reworded it would be changing that promise in passing.
 *
 * A dialog skin shows this *instead of* its frame, not over it, because two stacked dialogs is a look
 * the old editor never had; a screen shows it over the body, which is what a window's own dialog looks
 * like. The two are mutually exclusive in both skins rather than merely unlikely to overlap: a preset
 * is only ever queued from the body, and the body is not on screen in the dialog skin while the
 * custom-cap dialog is up.
 */
@Composable
fun ShortcutBarEditorDialogs(state: ShortcutBarEditorState) {
    // A local copy of the state, not the delegated property itself: only a val the compiler can see
    // checked here smart-casts to non-null for the dialog below.
    val capBeingEdited = state.editing
    if (capBeingEdited != null) {
        CustomCapDialog(
            cap = capBeingEdited,
            onDismiss = { state.editing = null },
            onSave = { state.saveCap(it) },
        )
        return
    }

    // The confirmation, whose wording is unchanged from when it lived inside the dialog: it is the
    // editor's one destructive act, and the sentence the user is asked to believe about it.
    if (state.pendingPreset != null) {
        AlertDialog(
            onDismissRequest = { state.pendingPreset = null },
            title = { Text("Replace the current layout?") },
            text = {
                Text("This discards your current caps, rows and order. Custom buttons you added are removed.")
            },
            confirmButton = {
                TextButton(onClick = { state.applyPendingPreset() }) { Text("Replace") }
            },
            dismissButton = { TextButton(onClick = { state.pendingPreset = null }) { Text("Cancel") } },
        )
    }
}

/**
 * The modal skin: the editor as a dialog over the terminal, with Reset, Cancel and Save.
 *
 * Its contract is unchanged, and deliberately so - it has one caller, and a user of it should not be
 * able to tell that the body moved. All editing still happens on a local copy, and the single
 * [onApply] receives the whole encoded blob when the user saves, so a dismissed dialog costs nothing
 * and a Cancel is a Cancel.
 *
 * While one of the editor's own dialogs is up this dialog stands down entirely: it is not composed
 * behind the confirmation, exactly as it was before the body was extracted.
 */
@Composable
fun ShortcutBarDialog(
    initial: KeyBarPrefs,
    onDismiss: () -> Unit,
    onApply: (String) -> Unit,
) {
    val editor = rememberShortcutBarEditorState(initial)

    if (editor.hasSubDialog) {
        ShortcutBarEditorDialogs(editor)
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Shortcut bar") },
        text = {
            ShortcutBarEditorBody(
                state = editor,
                modifier = Modifier.heightIn(max = rememberDialogBodyMaxHeight(0.75f)),
                capListScrolls = true,
            )
        },
        confirmButton = {
            Row {
                TextButton(onClick = { editor.requestReset() }) { Text("Reset") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
                TextButton(onClick = { onApply(editor.encoded()) }) { Text("Save") }
            }
        },
    )
}

/** Layout mode, custom row count, and cap size. */
@Composable
private fun LayoutSection(prefs: KeyBarPrefs, onChange: (KeyBarPrefs) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Layout", style = MaterialTheme.typography.titleSmall)
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(
                selected = prefs.mode == KeyBarLayoutMode.SINGLE,
                onClick = { onChange(prefs.copy(mode = KeyBarLayoutMode.SINGLE)) },
                label = { Text("1 row") },
            )
            FilterChip(
                selected = prefs.mode == KeyBarLayoutMode.DOUBLE,
                onClick = { onChange(prefs.copy(mode = KeyBarLayoutMode.DOUBLE)) },
                label = { Text("2 rows") },
            )
            FilterChip(
                selected = prefs.mode == KeyBarLayoutMode.CUSTOM,
                onClick = { onChange(prefs.copy(mode = KeyBarLayoutMode.CUSTOM)) },
                label = { Text("Custom") },
            )
        }
        if (prefs.mode == KeyBarLayoutMode.CUSTOM) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IconButton(
                    onClick = { onChange(prefs.copy(rows = (prefs.rows - 1).coerceIn(1, KeyBarPrefsCodec.MAX_ROWS))) },
                    modifier = Modifier.semantics { contentDescription = "Fewer rows" },
                ) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = null) }
                Text(
                    "${prefs.rows} row(s)",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                IconButton(
                    onClick = { onChange(prefs.copy(rows = (prefs.rows + 1).coerceIn(1, KeyBarPrefsCodec.MAX_ROWS))) },
                    modifier = Modifier.semantics { contentDescription = "More rows" },
                ) { Icon(Icons.Default.KeyboardArrowUp, contentDescription = null) }
            }
        }
        // A dropdown, not three chips: the dialog's column is narrow, and three
        // side-by-side chips squeezed this label until it wrapped into a stacked mess and
        // pushed the row wider than the layout chips above it. One control, every size,
        // one tap.
        var sizeMenuOpen by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Cap size",
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                // One line, always - a label stacking under width pressure is exactly the
                // layout bug this dropdown replaces.
                maxLines = 1,
            )
            Box {
                TextButton(
                    onClick = { sizeMenuOpen = true },
                    modifier = Modifier.semantics { contentDescription = "Cap size" },
                ) {
                    Text(prefs.size.name.lowercase().replaceFirstChar(Char::uppercase))
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
                }
                DropdownMenu(expanded = sizeMenuOpen, onDismissRequest = { sizeMenuOpen = false }) {
                    KeyBarSize.entries.forEach { size ->
                        DropdownMenuItem(
                            text = { Text(size.name.lowercase().replaceFirstChar(Char::uppercase)) },
                            onClick = { onChange(prefs.copy(size = size)); sizeMenuOpen = false },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Every cap, one row per cap: visibility, an edit affordance for custom caps, and the ordering
 * arrows. In CUSTOM mode the up/down arrows move a cap between rows; in the derived modes they are
 * hidden, because the mode ignores the stored row and the buttons would lie.
 *
 * [capListScrolls] picks how those rows are held, and it is the one thing the surrounding frame gets
 * to decide (see [ShortcutBarEditorBody]): lazily, inside the dialog's fixed-height frame, where this
 * list is the only thing in there that can move; or plainly, in the window, where the page around it
 * already scrolls and a nested scroller would take the drag. What is drawn is the same either way -
 * and both paths are bounded, because a decoded blob carries at most [KeyBarPrefsCodec.MAX_CAPS] caps,
 * so the plain column is finite work rather than a list drawn eagerly.
 */
@Composable
private fun CapsSection(
    prefs: KeyBarPrefs,
    onChange: (KeyBarPrefs) -> Unit,
    onEdit: (KeyBarCap) -> Unit,
    onAdd: () -> Unit,
    onPreset: (() -> KeyBarPrefs) -> Unit,
    capListScrolls: Boolean,
) {
    val custom = prefs.mode == KeyBarLayoutMode.CUSTOM
    val ordered = prefs.caps.sortedWith(compareBy({ it.row }, { it.order }))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Buttons", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = null, Modifier.size(16.dp))
                Text("Add")
            }
        }
        if (capListScrolls) {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.heightIn(max = 320.dp),
            ) {
                items(ordered, key = { it.id }) { cap ->
                    CapRow(cap, custom, prefs, onChange, onEdit)
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ordered.forEach { cap -> CapRow(cap, custom, prefs, onChange, onEdit) }
            }
        }
        PresetSection(onPick = onPreset)
    }
}
@Composable
private fun CapRow(
    cap: KeyBarCap,
    customMode: Boolean,
    prefs: KeyBarPrefs,
    onChange: (KeyBarPrefs) -> Unit,
    onEdit: (KeyBarCap) -> Unit,
) {
    val label = cap.label ?: KeyBarCatalog.defaultLabel(cap)
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Switch(
                checked = cap.visible,
                onCheckedChange = { visible ->
                    onChange(prefs.copy(caps = prefs.caps.map { if (it.id == cap.id) it.copy(visible = visible) else it }))
                },
                modifier = Modifier.semantics { contentDescription = "Show $label" },
            )
            Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                if (cap.kind == KeyBarCapKind.TEXT) {
                    Text(
                        "Sends ${cap.text.orEmpty()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (customMode) {
                    Text(
                        "Row ${cap.row + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (cap.kind == KeyBarCapKind.TEXT) {
                IconButton(onClick = { onEdit(cap) }, modifier = Modifier.semantics { contentDescription = "Edit $label" }) {
                    Icon(Icons.Default.KeyboardArrowLeft, contentDescription = null, Modifier.size(18.dp))
                }
                IconButton(
                    onClick = { onChange(prefs.copy(caps = prefs.caps.filterNot { it.id == cap.id })) },
                    modifier = Modifier.semantics { contentDescription = "Delete $label" },
                ) { Icon(Icons.Default.Delete, contentDescription = null, Modifier.size(18.dp)) }
            }
            OrderArrows(cap, customMode, prefs, onChange)
        }
    }
}

/**
 * The four order arrows for one cap. Left/right swap with the cap's neighbours in the same row;
 * up/down (CUSTOM mode only) move it to the end of the previous or next row, clamped.
 */
@Composable
private fun OrderArrows(
    cap: KeyBarCap,
    customMode: Boolean,
    prefs: KeyBarPrefs,
    onChange: (KeyBarPrefs) -> Unit,
) {
    val label = cap.label ?: KeyBarCatalog.defaultLabel(cap)
    Column {
        Row {
            IconButton(
                onClick = { onChange(move(prefs, cap, -1)) },
                modifier = Modifier.size(28.dp).semantics { contentDescription = "Move $label left" },
            ) { Icon(Icons.Default.KeyboardArrowLeft, contentDescription = null, Modifier.size(16.dp)) }
            IconButton(
                onClick = { onChange(move(prefs, cap, +1)) },
                modifier = Modifier.size(28.dp).semantics { contentDescription = "Move $label right" },
            ) { Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, Modifier.size(16.dp)) }
        }
        if (customMode) {
            Row {
                IconButton(
                    onClick = { onChange(moveRow(prefs, cap, -1)) },
                    modifier = Modifier.size(28.dp).semantics { contentDescription = "Move $label up a row" },
                ) { Icon(Icons.Default.ArrowUpward, contentDescription = null, Modifier.size(16.dp)) }
                IconButton(
                    onClick = { onChange(moveRow(prefs, cap, +1)) },
                    modifier = Modifier.size(28.dp).semantics { contentDescription = "Move $label down a row" },
                ) { Icon(Icons.Default.ArrowDownward, contentDescription = null, Modifier.size(16.dp)) }
            }
        }
    }
}

/** Swaps [cap] with its neighbour in reading order, or no-op at the edge. */
internal fun move(prefs: KeyBarPrefs, cap: KeyBarCap, delta: Int): KeyBarPrefs {
    val sorted = prefs.caps.sortedWith(compareBy({ it.row }, { it.order }))
    val index = sorted.indexOfFirst { it.id == cap.id }
    if (index < 0) return prefs
    val target = index + delta
    if (target < 0 || target >= sorted.size) return prefs
    if (sorted[target].row != sorted[index].row) return prefs
    val swapped = sorted.toMutableList()
    val held = swapped[index]
    swapped[index] = swapped[target]
    swapped[target] = held
    return prefs.copy(caps = renumber(swapped))
}

/**
 * Moves [cap] to the end of the adjacent row (CUSTOM mode's cross-row arrow). Moving up from row 0
 * or down from the last row is a no-op, matching what the buttons can visibly do.
 */
internal fun moveRow(prefs: KeyBarPrefs, cap: KeyBarCap, delta: Int): KeyBarPrefs {
    val target = cap.row + delta
    if (target < 0 || target >= prefs.rows.coerceIn(1, KeyBarPrefsCodec.MAX_ROWS)) return prefs
    val without = prefs.caps.filterNot { it.id == cap.id }
    val lastOrder = without.filter { it.row == target }.maxOfOrNull { it.order } ?: -1
    return prefs.copy(caps = without + cap.copy(row = target, order = lastOrder + 1))
}

/** Rewrites (row, order) as a dense sequence after any reorder. */
private fun renumber(caps: List<KeyBarCap>): List<KeyBarCap> {
    var row = -1
    var order = -1
    return caps.map { cap ->
        if (cap.row != row) { row = cap.row; order = 0 } else order++
        cap.copy(row = row, order = order)
    }
}

/**
 * The built-in presets. Each one replaces the whole arrangement, so a pick is queued behind the
 * confirmation [ShortcutBarEditorDialogs] shows - the same one Reset takes, in both skins - rather
 * than applied on the tap that chose it: a preset that overwrote a hand-built bar on a single tap
 * would be the destructive action nobody confirmed.
 */
@Composable
private fun PresetSection(onPick: (() -> KeyBarPrefs) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Presets", style = MaterialTheme.typography.titleSmall)
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PresetChip("Basic") { onPick(KeyBarPresets::basic) }
            PresetChip("Terminal") { onPick(KeyBarPresets::terminal) }
            PresetChip("Developer") { onPick(KeyBarPresets::developer) }
            PresetChip("Function keys") { onPick(KeyBarPresets::functionKeys) }
            PresetChip("Full") { onPick(KeyBarPresets::full) }
        }
    }
}

@Composable
private fun PresetChip(label: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.clickable(onClickLabel = "Apply the $label preset", onClick = onClick),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/**
 * Adds or edits one custom text cap: label (optional, defaults to the text) and the text it sends.
 * The text is capped at [KeyBarPrefsCodec.MAX_TEXT_LENGTH] and the label at
 * [KeyBarPrefsCodec.MAX_LABEL_LENGTH], the same limits the codec will enforce on save - refusing
 * here is what keeps "saved" and "decoded" the same cap.
 */
@Composable
private fun CustomCapDialog(
    cap: KeyBarCap,
    onDismiss: () -> Unit,
    onSave: (KeyBarCap) -> Unit,
) {
    var text by remember { mutableStateOf(cap.text.orEmpty()) }
    var label by remember { mutableStateOf(cap.label.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom button") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(KeyBarPrefsCodec.MAX_TEXT_LENGTH) },
                    label = { Text("Sends (max ${KeyBarPrefsCodec.MAX_TEXT_LENGTH} characters)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it.take(KeyBarPrefsCodec.MAX_LABEL_LENGTH) },
                    label = { Text("Label (optional)") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(cap.copy(text = text.trim(), label = label.trim().ifEmpty { null })) },
                enabled = text.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
