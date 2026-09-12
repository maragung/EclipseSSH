package dev.eclipse.ssh.ui.archive

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
 * The per-entry action sheet inside the archive: the row's overflow offers everything one
 * entry can do, exactly as the Files sheet does — with the archive's honest vocabulary instead
 * of the explorer's. (The row's long-press selects, so the overflow is the sheet's one home.)
 *
 * The verbs deliberately differ from a normal file manager:
 *  - there is no Rename, Move, or Delete — the archive is read-only where it stands, on the
 *    server, and this sheet never pretends otherwise;
 *  - Download (single entry) and Extract (folder / subtree) are offered, because pulling one
 *    entry's bytes is a range read while pulling a subtree is a scan-and-collect — different
 *    costs the user deserves to see named differently;
 *  - Copy Path copies the path inside the archive, not a server path — it is the archive's own
 *    coordinate system, which is what a person pasting it into a shell alongside the archive
 *    wants to address.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ArchiveEntryActionsSheet(
    entry: ArchiveEntry,
    /** Whether this entry's bytes can be fetched by range - the preview and download paths. */
    canReadEntry: Boolean,
    onDismiss: () -> Unit,
    onPreview: (() -> Unit)?,
    onExtract: () -> Unit,
    onCopyPath: () -> Unit,
    onProperties: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 18.dp)) {
            Text(
                entry.path.substringAfterLast('/'),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            entry.takeIf { !it.isDirectory }?.size?.let {
                Text(
                    describeSize(it),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            if (entry.isDirectory) {
                // A folder's only verb is extract-as-a-tree; opening it is what tapping the row
                // already does, so the sheet does not repeat it.
                ArchiveActionRow("Extract folder", onExtract)
            } else {
                // Preview is offered only where the range read exists: a TAR entry would need the
                // streaming scan, and the honest answer for now is the extract path, not a button
                // that pretends.
                if (onPreview != null && canReadEntry) {
                    ArchiveActionRow("Preview", onPreview)
                }
                if (canReadEntry) {
                    ArchiveActionRow("Download", onExtract)
                } else {
                    ArchiveActionRow("Extract", onExtract)
                }
            }
            ArchiveActionRow("Copy path", onCopyPath)
            ArchiveActionRow("Properties", onProperties)
        }
    }
}

@Composable
private fun ArchiveActionRow(label: String, onClick: () -> Unit) {
    // `combinedClickable` behind a non-experimental alias, the Files sheet's own trick for the
    // same readable call sites.
    Text(
        label,
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick)
            .padding(vertical = 14.dp),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

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
