package dev.eclipse.ssh.ui.terminal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
 * The shortcut bar's own settings screen: which caps exist and show, how the rows are cut, and the
 * presets that offer a starting point.
 *
 * All editing happens on a local copy and one [onApply] writes the whole encoded blob when the user
 * saves - the same whole-blob contract the editor's options sheet and every settings writer in the
 * app follow - so a dismissed dialog costs nothing, and the terminal only ever sees a complete
 * configuration. [initial] is the decoded blob straight from settings; a damaged blob has already
 * become the defaults by the time it arrives here.
 *
 * Ordering is buttons, not drag-and-drop, deliberately. The bar lives above a software keyboard on
 * a phone: a drop target there is a thumb-width strip competing with the IME, and every missed drop
 * silently reorders something the user did not touch. Four explicit arrows (left/right in the row,
 * up/down between rows) say exactly what they do and ask for exactly one tap.
 */
@Composable
fun ShortcutBarDialog(
    initial: KeyBarPrefs,
    onDismiss: () -> Unit,
    onApply: (String) -> Unit,
) {
    // The working copy, merged with any standard caps a newer app version knows about so the list
    // below can offer them; they arrive hidden and the user opts in.
    var prefs by remember { mutableStateOf(initial.withMissingStandardCaps()) }
    /** The custom cap being added or edited, if any. */
    var editing by remember { mutableStateOf<KeyBarCap?>(null) }
    /** The preset (or reset) whose destructive apply is awaiting confirmation, if any. */
    var pendingPreset by remember { mutableStateOf<(() -> KeyBarPrefs)?>(null) }

    if (editing != null) {
        val capBeingEdited = editing
        CustomCapDialog(
            cap = capBeingEdited,
            onDismiss = { editing = null },
            onSave = { saved ->
                prefs = prefs.copy(caps =
                    if (prefs.caps.any { it.id == saved.id }) {
                        prefs.caps.map { if (it.id == saved.id) saved else it }
                    } else {
                        // A new cap lands at the end of the last row: visible, so what the user
                        // just built is what they see when the dialog closes.
                        val lastRow = prefs.caps.maxOfOrNull { it.row } ?: 0
                        val lastOrder = prefs.caps.filter { it.row == lastRow }.maxOfOrNull { it.order } ?: -1
                        prefs.caps + saved.copy(row = lastRow, order = lastOrder + 1)
                    })
                editing = null
            },
        )
        return
    }

    if (pendingPreset != null) {
        val apply = pendingPreset!!
        AlertDialog(
            onDismissRequest = { pendingPreset = null },
            title = { Text("Replace the current layout?") },
            text = {
                Text("This discards your current caps, rows and order. Custom buttons you added are removed.")
            },
            confirmButton = {
                TextButton(onClick = { prefs = apply().withMissingStandardCaps(); pendingPreset = null })
                { Text("Replace") }
            },
            dismissButton = { TextButton(onClick = { pendingPreset = null }) { Text("Cancel") } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Shortcut bar") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.heightIn(max = rememberDialogBodyMaxHeight(0.75f)),
            ) {
                LayoutSection(prefs = prefs, onChange = { prefs = it })
                CapsSection(
                    prefs = prefs,
                    onChange = { prefs = it },
                    onEdit = { editing = it },
                    onAdd = { editing = KeyBarCap(prefs.nextCustomId(), KeyBarCapKind.TEXT, text = "") },
                    onPreset = { preset -> pendingPreset = preset },
                )
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { pendingPreset = { KeyBarPrefs() } }) { Text("Reset") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
                TextButton(onClick = { onApply(KeyBarPrefsCodec.encode(prefs)) }) { Text("Save") }
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Cap size", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                KeyBarSize.entries.forEach { size ->
                    FilterChip(
                        selected = prefs.size == size,
                        onClick = { onChange(prefs.copy(size = size)) },
                        label = { Text(size.name.lowercase().replaceFirstChar(Char::uppercase)) },
                    )
                }
            }
        }
    }
}

/**
 * Every cap, one row per cap: visibility, an edit affordance for custom caps, and the ordering
 * arrows. In CUSTOM mode the up/down arrows move a cap between rows; in the derived modes they are
 * hidden, because the mode ignores the stored row and the buttons would lie.
 */
@Composable
private fun CapsSection(
    prefs: KeyBarPrefs,
    onChange: (KeyBarPrefs) -> Unit,
    onEdit: (KeyBarCap) -> Unit,
    onAdd: () -> Unit,
    onPreset: (() -> KeyBarPrefs) -> Unit,
) {
    val custom = prefs.mode == KeyBarLayoutMode.CUSTOM
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Buttons", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = null, Modifier.size(16.dp))
                Text("Add")
            }
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.heightIn(max = 320.dp),
        ) {
            items(prefs.caps.sortedWith(compareBy({ it.row }, { it.order })), key = { it.id }) { cap ->
                CapRow(cap, custom, prefs, onChange, onEdit)
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
 * The built-in presets. Each one replaces the whole arrangement, so a pick routes through the same
 * confirmation [ShortcutBarDialog] gives Reset - a preset that overwrote a hand-built bar on a
 * single tap would be the destructive action nobody confirmed.
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
