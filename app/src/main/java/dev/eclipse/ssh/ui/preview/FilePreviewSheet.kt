package dev.eclipse.ssh.ui.preview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.eclipse.ssh.data.fs.FsEntry
import dev.eclipse.ssh.data.fs.FileSystemProvider

/**
 * Type-aware preview for one file: image, text, JSON, Markdown, PDF, or an honest
 * "no preview for this type" with the file's information.
 *
 * PLACEHOLDER: this signature is the seam the Files Explorer wires against; the full preview
 * replaces this body. The parameters and placement are the contract — keep them stable.
 */
@Composable
fun FilePreviewSheet(
    entry: FsEntry,
    provider: FileSystemProvider,
    onDismiss: () -> Unit,
    onEdit: (FsEntry) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = "Preview of ${entry.name}")
    }
}
