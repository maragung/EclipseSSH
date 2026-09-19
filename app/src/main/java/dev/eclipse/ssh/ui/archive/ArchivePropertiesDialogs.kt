package dev.eclipse.ssh.ui.archive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.eclipse.ssh.archive.ArchiveEntry
import dev.eclipse.ssh.archive.ArchiveUiState
import dev.eclipse.ssh.ui.files.describeSize
import java.text.DateFormat
import java.util.Date

/**
 * The archive's own facts: the properties sheet the epic's "archive properties" section names.
 *
 * Every row is drawn only when the value exists — the same rule as the explorer's properties
 * dialog, because an invented "0 B" or a fake 1970 date is worse than an absent row. The entry
 * count comes from the tree, so the sheet is shown from the Ready state only.
 */
@Composable
fun ArchivePropertiesDialog(
    state: ArchiveUiState.Ready,
    remotePath: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Archive properties", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ArchivePropertyRow("Format", state.format.label)
                ArchivePropertyRow("Remote path", remotePath)
                ArchivePropertyRow("Size", describeSize(state.stats.size))
                ArchivePropertyRow("Entries", state.tree.entryCount.toString())
                state.stats.modifiedEpochMillis?.let {
                    ArchivePropertyRow("Modified", DateFormat.getDateTimeInstance().format(Date(it)))
                }
            }
        },
    )
}

/** One entry's own facts, for the per-entry Properties row. */
@Composable
fun ArchiveEntryPropertiesDialog(
    entry: ArchiveEntry,
    formatLabel: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text(entry.path.substringAfterLast('/'), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ArchivePropertyRow("Path in archive", entry.path)
                ArchivePropertyRow("Format", formatLabel)
                entry.size?.let { ArchivePropertyRow("Size", describeSize(it)) }
                entry.compressedSize?.let { ArchivePropertyRow("Compressed size", describeSize(it)) }
                entry.modifiedEpochMillis?.let {
                    ArchivePropertyRow("Modified", DateFormat.getDateTimeInstance().format(Date(it)))
                }
            }
        },
    )
}

@Composable
private fun ArchivePropertyRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            Modifier.padding(end = 12.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
