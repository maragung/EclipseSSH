package dev.eclipse.ssh.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.eclipse.ssh.data.fs.FsEntry
import dev.eclipse.ssh.presentation.files.Crumb
import dev.eclipse.ssh.presentation.files.CrumbOrEllipsis
import dev.eclipse.ssh.presentation.files.ExplorerSession
import dev.eclipse.ssh.presentation.files.ExplorerSort
import dev.eclipse.ssh.presentation.files.ExplorerState
import dev.eclipse.ssh.presentation.files.ExplorerViewMode
import java.text.DateFormat
import java.util.Date

/**
 * The explorer's chrome: one row of session chips (Local always first), the breadcrumb bar, and the
 * toolbar. Everything above the file list, in one place, so the list below it starts at the same
 * place on every device.
 */
@Composable
fun ExplorerTopBar(
    state: ExplorerState,
    crumbs: List<CrumbOrEllipsis>,
    onSession: (String) -> Unit,
    onCrumb: (Crumb) -> Unit,
    onUp: () -> Unit,
    onRefresh: () -> Unit,
    onNewFolder: () -> Unit,
    onNewFile: () -> Unit,
    onSearch: (String) -> Unit,
    onClearSearch: () -> Unit,
    onSort: (ExplorerSort) -> Unit,
    onViewMode: (ExplorerViewMode) -> Unit,
    onPickFolder: () -> Unit,
    /** Uploads files picked on this device into the browsed remote directory; absent on the local session. */
    onUpload: (() -> Unit)? = null,
    /** Opens the folder-sync dialog; absent on the local session, which has no far side to sync with. */
    onSync: (() -> Unit)? = null,
) {
    Column {
        // ---- Sessions: Local first, always, then every saved host ----
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            state.sessions.forEach { session ->
                SessionChip(session, selected = session.id == state.activeSessionId, onClick = { onSession(session.id) })
            }
        }
        Spacer(Modifier.height(10.dp))

        // ---- Path bar: crumbs for a remote path, the trail title and Up for local ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.FolderOpen, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            if (crumbs.isNotEmpty()) {
                Row(
                    Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    crumbs.forEachIndexed { index, crumb ->
                        if (index > 0) {
                            Text("  ›  ", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                        }
                        if (crumb == null) {
                            // The collapsed middle of a long path - see ellipsizeCrumbs. Marked so it
                            // is not read out as one more tappable folder that does nothing.
                            Text("…", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Text(
                                crumb.name,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (index == crumbs.lastIndex) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.combinedClickableCompat { onCrumb(crumb) },
                            )
                        }
                    }
                }
            } else {
                Text(
                    state.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            if (state.canGoUp || state.searchQuery != null) {
                IconButton(onClick = onUp) { Icon(Icons.Default.ArrowUpward, "Go up one folder") }
            }
        }
        Spacer(Modifier.height(10.dp))

        // ---- Search, then the actions that belong to the session ----
        // Held to the spec's phone budget — search, new, upload, sync, refresh, overflow — because
        // a toolbar wider than the screen is what this row used to be: the icon buttons measured
        // first and the weighted count was squeezed to zero width on the remote session's eight
        // actions, which hid "60 item(s)" entirely on a phone. "New" and the kebab carry the rest.
        var searchOpen by remember { mutableStateOf(false) }
        var newMenuOpen by remember { mutableStateOf(false) }
        var moreMenuOpen by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (searchOpen || state.searchQuery != null) {
                OutlinedTextField(
                    value = state.searchQuery ?: "",
                    onValueChange = onSearch,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Search this folder…") },
                    singleLine = true,
                    trailingIcon = {
                        TextButton(onClick = { searchOpen = false; onClearSearch() }) { Text("Close") }
                    },
                )
            } else if (state.isLocal && state.path == null) {
                // Local's front door: nothing can be listed until a folder is granted, and the
                // picker is the user's to open.
                Button(onClick = onPickFolder) { Text("Pick folder") }
            } else {
                Text(
                    "${state.entries.size} item(s)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
            if (!searchOpen && state.searchQuery == null) {
                IconButton(onClick = { searchOpen = true }) { Icon(Icons.Default.Search, "Search") }
            }
            Box {
                IconButton(onClick = { newMenuOpen = true }) { Icon(Icons.Default.Add, "New") }
                DropdownMenu(expanded = newMenuOpen, onDismissRequest = { newMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("New file") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null) },
                        onClick = { newMenuOpen = false; onNewFile() },
                    )
                    DropdownMenuItem(
                        text = { Text("New folder") },
                        leadingIcon = { Icon(Icons.Default.CreateNewFolder, null) },
                        onClick = { newMenuOpen = false; onNewFolder() },
                    )
                }
            }
            if (!state.isLocal) {
                if (onUpload != null) {
                    IconButton(onClick = onUpload) { Icon(Icons.Default.CloudUpload, "Upload files to this folder") }
                }
                if (onSync != null) {
                    IconButton(onClick = onSync) { Icon(Icons.Default.SwapVert, "Sync this folder") }
                }
            }
            IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Refresh") }
            Box {
                IconButton(onClick = { moreMenuOpen = true }) { Icon(Icons.Default.MoreVert, "More actions") }
                DropdownMenu(expanded = moreMenuOpen, onDismissRequest = { moreMenuOpen = false }) {
                    ExplorerSort.entries.forEach { sort ->
                        DropdownMenuItem(
                            text = { Text(sort.label) },
                            trailingIcon = { if (sort == state.sort) { Icon(Icons.Default.CheckCircle, null, Modifier.size(18.dp)) } },
                            onClick = { onSort(sort); moreMenuOpen = false },
                        )
                    }
                    DropdownMenuItem(
                        text = {
                            Text(if (state.viewMode == ExplorerViewMode.LIST) "Switch to grid view" else "Switch to list view")
                        },
                        onClick = {
                            onViewMode(if (state.viewMode == ExplorerViewMode.LIST) ExplorerViewMode.GRID else ExplorerViewMode.LIST)
                            moreMenuOpen = false
                        },
                    )
                    if (state.isLocal && state.path != null) {
                        // SAF offers no navigation above the granted root, so choosing a different
                        // root is the only "up" there is.
                        DropdownMenuItem(
                            text = { Text("Choose a different folder") },
                            leadingIcon = { Icon(Icons.Default.FolderOpen, null) },
                            onClick = { moreMenuOpen = false; onPickFolder() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionChip(session: ExplorerSession, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        leadingIcon = {
            Icon(
                if (session.isLocal) Icons.Default.Computer else Icons.Default.Folder,
                null,
                Modifier.size(16.dp),
            )
        },
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(session.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!session.isLocal) {
                    Spacer(Modifier.width(6.dp))
                    // The same dot the session switcher and the terminal strip use: a server with no
                    // live session can still be browsed as far as its home directory before the
                    // listing fails, so the chip says which ones are connected rather than hiding it.
                    Box(
                        Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(
                            if (session.live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        ),
                    )
                }
            }
        },
    )
}


/**
 * The entries, as a list or a grid.
 *
 * One composable with two bodies rather than two lists, so selection, empty and loading states —
 * the parts that have to agree — are written once. Tap opens; long-press opens the per-entry action
 * sheet, whose Select row starts a selection; once a selection is active, tap selects everywhere,
 * which is the rule a touch file manager is judged by.
 */
@Composable
fun ExplorerList(
    state: ExplorerState,
    onOpen: (FsEntry) -> Unit,
    onToggleSelect: (String) -> Unit,
    onOpenActions: (FsEntry) -> Unit,
) {
    when {
        state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        state.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(state.error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }

        state.entries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                when {
                    state.searchQuery != null -> "Nothing matched “${state.searchQuery}”."
                    // No listing was ever taken: local before its first folder is granted, which is a
                    // missing permission rather than a folder that happens to hold nothing.
                    state.path == null -> "No folder chosen yet — tap “Pick folder” to browse this device."
                    else -> "This folder is empty."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        state.viewMode == ExplorerViewMode.GRID -> LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 96.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(state.entries, key = { it.path }) { entry ->
                ExplorerGridCell(
                    entry = entry,
                    selected = entry.path in state.selection,
                    selecting = state.selection.isNotEmpty(),
                    onOpen = { onOpen(entry) },
                    onToggleSelect = { onToggleSelect(entry.path) },
                    onOpenActions = { onOpenActions(entry) },
                )
            }
        }

        else -> LazyColumn(Modifier.fillMaxSize()) {
            items(state.entries, key = { it.path }) { entry ->
                ExplorerRow(
                    entry = entry,
                    selected = entry.path in state.selection,
                    selecting = state.selection.isNotEmpty(),
                    onOpen = { onOpen(entry) },
                    onToggleSelect = { onToggleSelect(entry.path) },
                    onOpenActions = { onOpenActions(entry) },
                )
            }
        }
    }
}

/** One file as a row: icon, name, and the columns the backend actually reported. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExplorerRow(
    entry: FsEntry,
    selected: Boolean,
    selecting: Boolean,
    onOpen: () -> Unit,
    onToggleSelect: () -> Unit,
    onOpenActions: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            // Long-press opens the action sheet rather than selecting: the sheet's Select row is
            // where a selection starts now, so the gesture that used to start one leads to the same
            // place in one extra tap while gaining every other per-entry action on the way.
            .combinedClickable(onClick = if (selecting) onToggleSelect else onOpen, onLongClick = onOpenActions)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        Icon(
            if (entry.isDirectory) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
            null,
            tint = tint,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            // The metadata line shows only what the provider knew: a local SAF entry has no
            // permissions and a directory has no size, and neither is invented here.
            val pieces = listOfNotNull(
                entry.size?.let { describeSize(it) },
                entry.modifiedEpochMillis?.let { DateFormat.getDateInstance().format(Date(it)) },
                entry.permissions,
            )
            if (pieces.isNotEmpty()) {
                Text(
                    pieces.joinToString("  ·  "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (selected) {
            Icon(Icons.Default.CheckCircle, "Selected", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/** One file as a grid cell: the icon large, the name under it, at most two lines deep. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExplorerGridCell(
    entry: FsEntry,
    selected: Boolean,
    selecting: Boolean,
    onOpen: () -> Unit,
    onToggleSelect: () -> Unit,
    onOpenActions: () -> Unit,
) {
    Column(
        Modifier
            .padding(6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface,
            )
            .combinedClickable(onClick = if (selecting) onToggleSelect else onOpen, onLongClick = onOpenActions)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
            Icon(
                if (entry.isDirectory) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                null,
                Modifier.size(40.dp),
                tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            entry.name,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** The bar of batch actions under a selection, horizontally scrollable so nothing is ever hidden. */
@Composable
fun ExplorerSelectionBar(
    count: Int,
    onClear: () -> Unit,
    onDownload: (() -> Unit)?,
    onUpload: (() -> Unit)?,
    onSchedule: (() -> Unit)?,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$count selected", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(16.dp))
            TextButton(onClick = onClear) { Text("Clear") }
            if (onDownload != null) {
                Button(onClick = onDownload) { Text("Download") }
            }
            if (onUpload != null) {
                Button(onClick = onUpload) { Text("Upload") }
            }
            if (onSchedule != null) {
                TextButton(onClick = onSchedule) { Text("Schedule") }
            }
            TextButton(onClick = onCopy) { Text("Copy") }
            TextButton(onClick = onMove) { Text("Move") }
            TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        }
    }
}

/**
 * The per-entry action sheet: everything one entry can do, offered the same way for local and
 * remote, with the actions the active backend cannot serve simply absent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExplorerFileActionsSheet(
    entry: FsEntry,
    isLocal: Boolean,
    supportsPermissions: Boolean,
    onDismiss: () -> Unit,
    onSelect: () -> Unit,
    onPreview: () -> Unit,
    onEdit: () -> Unit,
    onRename: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onProperties: () -> Unit,
    onChmod: (() -> Unit)?,
    onTransfer: (() -> Unit)?,
    onSendToHost: (() -> Unit)?,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 18.dp)) {
            Text(entry.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            entry.size?.let {
                Text(describeSize(it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            // Selection enters through here now: long-press used to be the gesture, and long-press
            // opens this sheet, so this row is where that gesture lands. First, above Preview,
            // because a started selection is the state the whole explorer reorganizes around.
            ActionRow("Select", onSelect)
            ActionRow("Preview", onPreview)
            if (!entry.isDirectory) {
                ActionRow("Edit", onEdit)
            }
            ActionRow("Rename", onRename)
            ActionRow("Copy to…", onCopy)
            ActionRow("Move to…", onMove)
            if (onTransfer != null) {
                ActionRow(if (isLocal) "Upload to server" else "Download to device", onTransfer)
            }
            if (onSendToHost != null) {
                ActionRow("Send to another server", onSendToHost)
            }
            if (onChmod != null && supportsPermissions) {
                ActionRow("Permissions", onChmod)
            }
            ActionRow("Properties", onProperties)
            ActionRow("Delete", onDelete, destructive = true)
        }
    }
}

@Composable
private fun ActionRow(label: String, onClick: () -> Unit, destructive: Boolean = false) {
    Text(
        label,
        Modifier
            .fillMaxWidth()
            .combinedClickableCompat { onClick() }
            .padding(vertical = 14.dp),
        style = MaterialTheme.typography.bodyLarge,
        color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
}

/** The entry's facts, each row drawn only when the value exists — an invented "0 B" is worse than none. */
@Composable
fun ExplorerPropertiesDialog(entry: FsEntry, isLocal: Boolean, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                entry.mimeType?.let { PropertyRow("Type", it) }
                PropertyRow("Location", if (isLocal) "This device" else "Remote session")
                PropertyRow("Path", entry.path)
                entry.size?.let { PropertyRow("Size", describeSize(it)) }
                entry.modifiedEpochMillis?.let { PropertyRow("Modified", DateFormat.getDateTimeInstance().format(Date(it))) }
                entry.permissions?.let { PropertyRow("Permissions", it) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun PropertyRow(label: String, value: String) {
    Row {
        Text(
            label,
            Modifier.width(96.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

/** `combinedClickable` behind a non-experimental alias so call sites stay readable. */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit): Modifier =
    this.combinedClickable(onClick = onClick)

fun describeSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
    else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
}
