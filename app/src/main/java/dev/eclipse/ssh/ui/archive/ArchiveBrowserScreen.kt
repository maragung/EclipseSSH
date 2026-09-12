package dev.eclipse.ssh.ui.archive

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.eclipse.ssh.archive.ArchiveEntry
import dev.eclipse.ssh.archive.ArchiveScanProgress
import dev.eclipse.ssh.archive.ArchiveUiState
import dev.eclipse.ssh.ui.files.describeSize

/**
 * The View Archive screen: a file manager over an archive that stays on the server.
 *
 * The screen is deliberately a *renderer*, not a controller: [ArchiveUiState] is the state
 * machine, the [ArchiveActions] callbacks are the commands, and this file owns nothing but
 * view-local state (the current folder, the search box, the selection). That split is what keeps
 * the promise testable - "never download the archive to list it" lives in the state machine's
 * scan, not in anything a composable could get wrong.
 *
 * Virtualization is LazyColumn over the folder slice the tree hands out, never over the whole
 * archive: the 850,000-entry case renders the ~dozen entries of one folder, and the flat entry
 * list never becomes compose nodes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveBrowserScreen(
    archiveName: String,
    state: ArchiveUiState,
    actions: ArchiveActions,
) {
    // View state, saveable so a rotation inside a deep folder keeps the folder.
    var folder by rememberSaveable { mutableStateOf("") }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    // The multi-extract selection, path -> entry. It holds the entries themselves, not just
    // paths, because a selection may span folders and a path alone cannot find its entry again
    // without walking the lazy tree — and it must outlive the folder it was made in, since the
    // destination picker is a whole activity round-trip away. Deliberately not saveable: an
    // [ArchiveEntry] is not a Bundle value, and losing a half-made selection to a rotation is
    // cheaper than serializing one. A reload that invalidates it needs no special case either:
    // the extractor reports a per-entry outcome for entries that no longer exist.
    var selectedEntries by remember { mutableStateOf<Map<String, ArchiveEntry>>(emptyMap()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(archiveName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = actions.onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close archive")
                    }
                },
                // The archive's own facts, offered only once there is a tree to count entries
                // from - a properties sheet that could not fill its own rows would be noise.
                actions = {
                    val ready = state is ArchiveUiState.Ready
                    IconButton(onClick = actions.onShowProperties, enabled = ready) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Archive properties")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                is ArchiveUiState.Loading -> ArchiveLoading(s.progress, actions.onCancelScan)
                is ArchiveUiState.PasswordRequired -> ArchivePasswordSheet(
                    s.message, actions.onUnlock, actions.onClose,
                )
                is ArchiveUiState.Failed -> ArchiveFailed(s.message, actions.onRetry, actions.onClose)
                is ArchiveUiState.Ready -> ArchiveReady(
                    state = s,
                    folder = folder,
                    onFolderChange = { folder = it },
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    selectedEntries = selectedEntries,
                    onToggleSelect = { entry ->
                        selectedEntries = if (entry.path in selectedEntries) {
                            selectedEntries - entry.path
                        } else {
                            selectedEntries + (entry.path to entry)
                        }
                    },
                    onClearSelection = { selectedEntries = emptyMap() },
                    actions = actions,
                )
            }
        }
    }
}

/** The commands the screen can issue; every one has a state-machine meaning, none does I/O here. */
class ArchiveActions(
    val onClose: () -> Unit,
    val onCancelScan: () -> Unit,
    val onRetry: () -> Unit,
    /** Offer the password; the scan restarts with it. Never stored without the user seeing it used. */
    val onUnlock: (password: String) -> Unit,
    val onOpenEntry: (ArchiveEntry) -> Unit,
    val onEntryActions: (ArchiveEntry) -> Unit,
    /**
     * Extract several entries at once. The browser hands the entries over and the destination
     * picker (and the extract behind it) is the caller's plumbing — the same one the per-entry
     * sheet's Extract row uses, so there is exactly one extract path to keep honest.
     */
    val onExtractEntries: (List<ArchiveEntry>) -> Unit,
    /** The reload behind the changed-on-server notification and the refresh control. */
    val onReload: () -> Unit,
    /** Dismiss the changed-on-server notification without reloading ("continue with what's shown"). */
    val onDismissServerChange: () -> Unit,
    /** Shows the archive's own properties - the top bar's info action, when a tree exists. */
    val onShowProperties: () -> Unit,
)

@Composable
private fun ArchiveLoading(progress: ArchiveScanProgress?, onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // The label is the requirement's own rule: reading structure over the wire is not a
        // download, and calling it one would be the one dishonest label this screen refuses.
        Text("Reading archive metadata…", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(20.dp))
        if (progress == null) {
            CircularProgressIndicator()
        } else {
            val fraction = progress.totalBytes
                ?.takeIf { it > 0 }
                ?.let { (progress.bytesScanned.toDouble() / it).coerceIn(0.0, 1.0) }
            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { fraction.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "${progress.entriesScanned} entries scanned",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
private fun ArchivePasswordSheet(message: String, onUnlock: (String) -> Unit, onClose: () -> Unit) {
    var password by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Lock, contentDescription = null)
        Spacer(Modifier.height(12.dp))
        Text(message, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onClose) { Text("Cancel") }
            Button(onClick = { onUnlock(password) }) { Text("Unlock") }
        }
    }
}

@Composable
private fun ArchiveFailed(message: String, onRetry: () -> Unit, onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onClose) { Text("Close") }
            Button(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun ArchiveReady(
    state: ArchiveUiState.Ready,
    folder: String,
    onFolderChange: (String) -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    selectedEntries: Map<String, ArchiveEntry>,
    onToggleSelect: (ArchiveEntry) -> Unit,
    onClearSelection: () -> Unit,
    actions: ArchiveActions,
) {
    Column(Modifier.fillMaxSize()) {
        ArchiveServerChangedBanner(state, actions)
        ArchiveToolbar(searchQuery, onSearchQueryChange)
        ArchiveBreadcrumb(folder, onFolderChange)
        val searching = searchQuery.isNotBlank()
        val entries = if (searching) {
            state.tree.search(searchQuery)
        } else {
            state.tree.children(folder) ?: emptyList()
        }
        // The list is weighted rather than fillMaxSize so the selection bar below it stays on
        // screen — the same arrangement the Files explorer's list and batch bar use.
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when {
                entries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (searching) "Nothing matched \"$searchQuery\"." else "This folder is empty.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(entries, key = { it.path }) { entry ->
                        ArchiveEntryRow(
                            entry = entry,
                            selected = entry.path in selectedEntries,
                            selecting = selectedEntries.isNotEmpty(),
                            onOpen = {
                                if (entry.isDirectory) onFolderChange(entry.path) else actions.onOpenEntry(entry)
                            },
                            onToggleSelect = { onToggleSelect(entry) },
                            onOpenActions = { actions.onEntryActions(entry) },
                        )
                    }
                }
            }
        }
        if (selectedEntries.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            ArchiveSelectionBar(
                count = selectedEntries.size,
                onClear = onClearSelection,
                // Not cleared here: the extract waits on the destination picker, and a
                // cancelled pick must return the user to the selection they made so a retry is
                // one tap, not a re-selection. The bar's Clear is the way out.
                onExtract = { actions.onExtractEntries(selectedEntries.values.toList()) },
            )
        }
    }
}

/**
 * The batch bar under an archive selection: the count, a way out, and the one batch verb an
 * archive has. Extract is all it offers because the archive is read-only where it stands —
 * there is no copy, move, or delete to batch, and offering them would be the pretending this
 * screen refuses everywhere else.
 */
@Composable
private fun ArchiveSelectionBar(count: Int, onClear: () -> Unit, onExtract: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$count selected", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(16.dp))
            TextButton(onClick = onClear) { Text("Clear") }
            Button(onClick = onExtract) { Text("Extract") }
        }
    }
}

@Composable
private fun ArchiveServerChangedBanner(state: ArchiveUiState.Ready, actions: ArchiveActions) {
    // The notification is state on the Ready object (raised by the watcher, cleared by reload);
    // "continue" dismisses it for this session without touching the tree.
    if (!state.serverChanged) return
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(
                "This archive has changed on the server.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Row {
                TextButton(onClick = actions.onDismissServerChange) { Text("Continue") }
                TextButton(onClick = actions.onReload) { Text("Reload") }
            }
        }
    }
}

@Composable
private fun ArchiveToolbar(searchQuery: String, onSearchQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = searchQuery,
        onValueChange = onSearchQueryChange,
        placeholder = { Text("Search files…") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun ArchiveBreadcrumb(folder: String, onFolderChange: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TextButton(onClick = { onFolderChange("") }, contentPadding = PaddingValues(4.dp)) {
            Text("archive root")
        }
        folder.split('/').filter { it.isNotEmpty() }.forEachIndexed { index, segment ->
            Text("/", color = MaterialTheme.colorScheme.onSurfaceVariant)
            val target = folder.split('/').filter { it.isNotEmpty() }.subList(0, index + 1).joinToString("/")
            TextButton(
                onClick = { onFolderChange(target) },
                contentPadding = PaddingValues(4.dp),
            ) {
                Text(segment, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArchiveEntryRow(
    entry: ArchiveEntry,
    selected: Boolean,
    selecting: Boolean,
    onOpen: () -> Unit,
    onToggleSelect: () -> Unit,
    onOpenActions: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            // Long-press selects — the classic batch gesture, and the archive's answer to the
            // Files explorer's selection mode. It used to open this sheet, so the sheet's home
            // is now the row's overflow (where its own KDoc already said it lived); the
            // per-entry verbs are one tap away instead of one long-press away, and batch
            // selection gets the gesture people reach for first.
            .combinedClickable(
                onClick = if (selecting) onToggleSelect else onOpen,
                onLongClick = onToggleSelect,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (entry.isDirectory) Icons.Filled.Folder else Icons.Filled.Description,
            contentDescription = null,
            tint = if (selected || entry.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.path.substringAfterLast('/'),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            if (entry.encrypted) {
                Text(
                    "Encrypted",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        entry.takeIf { !it.isDirectory }?.size?.let {
            Text(
                describeSize(it),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected) {
            Icon(Icons.Filled.CheckCircle, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
        }
        IconButton(onClick = onOpenActions) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Entry actions")
        }
    }
}
