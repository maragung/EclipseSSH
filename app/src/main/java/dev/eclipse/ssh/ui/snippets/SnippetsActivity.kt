package dev.eclipse.ssh.ui.snippets

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.eclipse.ssh.data.model.Snippet
import dev.eclipse.ssh.data.settings.SettingsRepository
import dev.eclipse.ssh.data.settings.SnippetRepository
import dev.eclipse.ssh.ui.actions.ActionAnswer
import dev.eclipse.ssh.ui.actions.ActionRequests
import dev.eclipse.ssh.ui.actions.SnippetActionKind
import dev.eclipse.ssh.ui.settings.SettingsDestinationWindow
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * The saved commands, in a window of their own.
 *
 * It used to be a `ModalBottomSheet` over the terminal, and the reason it is a window now is the one
 * every other promoted sheet has: a sheet is a slot at the bottom of the screen, so the list of things
 * you might paste *over* the shell you are about to paste them into is drawn by covering that shell.
 * A snippet is only worth offering while the command line it will land on is legible.
 *
 * **It carries no subject, and it is the only window here that does not.** A snippet list is not a row
 * of something the workspace is holding - it is [SnippetRepository], a singleton with a `Flow`, and this
 * window reads it directly. That is the degenerate case of the rule [ActionRequests] states: pass an id
 * when a singleton already holds the subject, and here the singleton holds all of them at once, so an
 * id would be a second way to say what the repository already says. It also buys the thing the sheet
 * could not do: a command saved from the terminal shows up in this list while it is open, because this
 * window is watching the store rather than a copy of it.
 *
 * **What it does not do is type into a shell.** Insert and the save-naming row are answered, not
 * performed - see [SnippetActionKind]. [SnippetActionKind.INSERT] in particular *has* to come back:
 * typing a command into a shell is the terminal's job, and only the terminal knows which session is on
 * screen and how to raise the keyboard over it afterwards. Delete is the exception, and it is the
 * interesting one: the store is already injected here in order to draw the list, so the window makes
 * that write itself and the row disappears as it is tapped.
 */
@AndroidEntryPoint
class SnippetsActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var snippetRepository: SnippetRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val scope = rememberCoroutineScope()
            val snippets by snippetRepository.snippets.collectAsStateWithLifecycle(initialValue = emptyList())
            SettingsDestinationWindow(
                settingsRepository = settingsRepository,
                title = "Snippets",
                onClose = { finish() },
            ) {
                Snippets(
                    snippets = snippets,
                    onSaveCurrent = {
                        ActionRequests.answer(ActionAnswer.SnippetAction(null, SnippetActionKind.SAVE_CURRENT))
                        finish()
                    },
                    onInsert = { snippet ->
                        ActionRequests.answer(ActionAnswer.SnippetAction(snippet.id, SnippetActionKind.INSERT))
                        finish()
                    },
                    // The one row of this window that is not an answer - see [SnippetActionKind]. The
                    // store is what this window is drawing from and what it already injects, so the
                    // delete is a write it can make itself, and making it is what lets the row go
                    // under the finger that removed it. Answered instead, the row would sit there
                    // until the workspace came back to the front, which is not until the window closes.
                    onDelete = { id -> scope.launch { snippetRepository.delete(id) } },
                )
            }
        }
    }

    companion object {
        /**
         * The intent that opens this window.
         *
         * No extras at all: see the class doc for why this is the one window with nothing to carry.
         */
        fun intent(context: Context): Intent = Intent(context, SnippetsActivity::class.java)
    }
}

/**
 * The rows themselves.
 *
 * The list is a plain `Column` and not a `LazyColumn`, deliberately: a snippet is a line the user
 * chose to keep, so the list is short by construction, and a lazy list inside a scrolling window would
 * need the window's scroller turned off - the same trade [SettingsDestinationWindow] documents. The
 * sheet scrolled the same way.
 */
@Composable
private fun Snippets(
    snippets: List<Snippet>,
    onSaveCurrent: () -> Unit,
    onInsert: (Snippet) -> Unit,
    onDelete: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 18.dp)) {
        // The sheet drew a title here as well; the window has one in its own bar, so the body starts
        // at the one thing a title could not say - that the command just typed can become a snippet.
        OutlinedButton(onClick = onSaveCurrent, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Add, null)
            Spacer(Modifier.width(8.dp))
            Text("Save current command")
        }
        Spacer(Modifier.height(12.dp))
        if (snippets.isEmpty()) {
            Text("No snippets yet. Save a command to reuse it later.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            snippets.forEach { snippet ->
                Row(
                    Modifier.fillMaxWidth().clickable { onInsert(snippet) }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(snippet.label, fontWeight = FontWeight.SemiBold)
                        Text(
                            snippet.command,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    IconButton(onClick = { onDelete(snippet.id) }) {
                        Icon(Icons.Default.Close, "Delete snippet", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
