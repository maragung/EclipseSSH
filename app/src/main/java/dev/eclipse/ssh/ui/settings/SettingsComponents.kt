package dev.eclipse.ssh.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The three pieces every settings destination is built from.
 *
 * They lived in `MainActivity.kt` as `private` composables, which was right while Settings was the
 * only screen that had them. It is not any more: each Settings row is now a window of its own, and
 * a window that renders one setting should render it exactly the way the list did, or the
 * promotion changes the look of the thing it was supposed to leave alone. They are *moved* here
 * rather than copied - `MainActivity` imports them from this package - so there is one definition
 * and no way for the two to drift.
 */

/**
 * A titled group of settings rows.
 *
 * The header is uppercase with wide letter-spacing: it is a label over a card, not a heading in the
 * content, and the spacing is what keeps it from reading as one more row's title.
 */
@Composable
fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.2.sp, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), content = content)
}

/**
 * One settings line: an icon, a title, a subtitle, and a control on the right.
 *
 * The [trailing] slot is width-capped, and that cap is the fix for a layout that broke on narrow
 * screens. A `Row` measures its unweighted children first and gives the weighted one whatever is
 * left, so a trailing control that wanted more than the row had — the terminal-theme picker used to be
 * a horizontally scrolling strip of one chip per theme, which asks for the width of all of them —
 * consumed nearly the whole line and left the title column a few dozen dp. The title then wrapped one
 * word per line, or clipped, and the taller the theme list grew the worse it got. Capped, the labels
 * always keep the rest of the row, and every control the app actually puts here (a `Switch`, a
 * `TextButton`, a compact dropdown) fits inside the cap on any screen this app supports.
 *
 * The title is one line with an ellipsis for the same reason. The subtitle is allowed to wrap: it is
 * prose describing the setting, not a value, and some of them genuinely need two lines.
 */
@Composable
fun SettingRow(icon: ImageVector, title: String, subtitle: String, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(10.dp))
        Box(Modifier.widthIn(max = SETTING_TRAILING_MAX_WIDTH), contentAlignment = Alignment.CenterEnd) { trailing() }
    }
}

/** How much of a settings row its control may take. The rest belongs to the title and subtitle. */
private val SETTING_TRAILING_MAX_WIDTH = 156.dp

/**
 * A compact single-choice control for a settings row: the current value, a caret, and a menu.
 *
 * Replaces a row of chips, one per option, and it is not only a matter of taste — a chip strip grows
 * with the option list, so adding a theme silently made the Settings screen worse, and a scrolling
 * strip hides the options that do not fit behind a gesture nobody knows is there. A dropdown is a
 * fixed width whatever the list length, shows the current value where a value belongs, and puts every
 * option one tap away with the selected one ticked.
 *
 * The trigger's label is one line with an ellipsis and the button is bounded by
 * [SETTING_TRAILING_MAX_WIDTH] from the row around it, so no option name can push the row out of shape
 * however long it is. Menu items are single-line for the same reason.
 *
 * The content description carries both the setting and its value ("Terminal theme, Amber"), because
 * the visible label alone says only "Amber" — which names the value and not what it sets, and is the
 * one thing a screen reader user cannot recover from the surrounding row.
 */
@Composable
fun <T> SettingDropdown(
    label: String,
    options: List<T>,
    selected: T?,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = selected?.let(optionLabel) ?: ""
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            modifier = Modifier.semantics { contentDescription = "$label, $selectedLabel" },
        ) {
            Text(
                selectedLabel,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(Icons.Default.ArrowDropDown, null, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = { expanded = false; onSelect(option) },
                    trailingIcon = {
                        if (option == selected) Icon(Icons.Default.Check, "Selected", modifier = Modifier.size(18.dp))
                    },
                )
            }
        }
    }
}