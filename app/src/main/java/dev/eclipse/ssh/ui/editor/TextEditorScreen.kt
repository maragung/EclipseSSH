package dev.eclipse.ssh.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.eclipse.ssh.data.fs.FsEntry
import dev.eclipse.ssh.data.fs.FileSystemProvider

/**
 * A request to open the full-screen text editor on one file, local or remote.
 *
 * [provider] is the filesystem the entry came from — the editor reads and saves through it, which
 * is what makes one editor serve both backends. [isNewFile] marks a file that was just created
 * empty and has never been read from disk.
 */
data class EditorRequest(
    val entry: FsEntry,
    val provider: FileSystemProvider,
    val isNewFile: Boolean = false,
)

/**
 * Full-screen text editor for a local or remote file.
 *
 * PLACEHOLDER: this signature is the seam the Files Explorer wires against; the full editor
 * (line numbers, undo/redo, find & replace, go-to-line, save states, unsaved-changes guard)
 * replaces this body. The parameters and placement are the contract — keep them stable.
 */
@Composable
fun TextEditorScreen(request: EditorRequest, onClose: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = "Editor for ${request.entry.name}")
    }
}
