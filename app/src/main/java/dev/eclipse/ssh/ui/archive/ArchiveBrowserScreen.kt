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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(archiveName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = actions.onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close archive")
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
    /** The reload behind the changed-on-server notification and the refresh control. */
    val onReload: () -> Unit,
    /** Dismiss the changed-on-server notification without reloading ("continue with what's shown"). */
    val onDismissServerChange: () -> Unit,
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
                        onOpen = {
                            if (entry.isDirectory) onFolderChange(entry.path) else actions.onOpenEntry(entry)
                        },
                        onOpenActions = { actions.onEntryActions(entry) },
                    )
                }
            }
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
    onOpen: () -> Unit,
    onOpenActions: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onOpenActions)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (entry.isDirectory) Icons.Filled.Folder else Icons.Filled.Description,
            contentDescription = null,
            tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.path.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    }
}
