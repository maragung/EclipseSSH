package dev.eclipse.ssh.ui.archive

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dagger.hilt.android.AndroidEntryPoint
import dev.eclipse.ssh.archive.ArchiveEntry
import dev.eclipse.ssh.data.settings.SettingsRepository
import dev.eclipse.ssh.ui.actions.ActionAnswer
import dev.eclipse.ssh.ui.actions.ActionRequests
import dev.eclipse.ssh.ui.actions.ActionSubject
import dev.eclipse.ssh.ui.actions.ArchiveEntryActionKind
import dev.eclipse.ssh.ui.files.describeSize
import dev.eclipse.ssh.ui.settings.SettingsDestinationWindow
import javax.inject.Inject

/**
 * Everything one entry inside an archive can do, in a window of its own.
 *
 * It used to be a `ModalBottomSheet` drawn over the archive browser, and the reason it is a window
 * now is the reason every other sheet in this app has become one: the sheet covered the listing the
 * entry was chosen from, so the row the user was acting on was hidden by the menu acting on it.
 *
 * **What it does not do is act.** Every row answers an [ArchiveEntryActionKind] and closes; the
 * archive browser - which owns the tree the entry came from, the destination picker and the
 * properties dialog - does the work. That is not ceremony: Preview opens a second window on the same
 * ranged source, Extract arms a picker that outlives this window, and Copy path writes to the
 * deliberate-clear clipboard. None of those is reachable from here, and a second implementation of
 * any of them would be a second answer to the same question.
 *
 * The entry travels as a token through [ActionRequests] because an [ArchiveEntry] is a row of a tree
 * the browser holds and nothing an intent can carry. Whether its bytes can be read by range travels
 * as a plain boolean extra, because a boolean can - that split is the rule [ActionRequests] states.
 *
 * A spent token closes the window rather than opening it on nothing: the system re-delivers an intent
 * on a rotation, from recents, and after a crash, and the entry may be from a listing that is gone.
 */
@AndroidEntryPoint
class ArchiveEntryActionsActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val subject = ActionRequests.take(intent?.getStringExtra(ActionRequests.EXTRA_SUBJECT_TOKEN))
        val entry = (subject as? ActionSubject.ArchiveEntryActions)?.entry
        if (entry == null) {
            finish()
            return
        }
        // Read once, at launch, and not watched: it is a property of the archive that was open, and
        // the archive cannot change while this window is in front of it.
        val canReadEntry = intent?.getBooleanExtra(EXTRA_CAN_READ_ENTRY, false) == true
        setContent {
            SettingsDestinationWindow(
                settingsRepository = settingsRepository,
                // The entry's own name, without its path inside the archive: the window is opened
                // from a listing where the path is already on screen, and the path is one of the
                // rows below for the case where it needs to be read carefully.
                title = entry.path.substringAfterLast('/'),
                onClose = { finish() },
            ) {
                ArchiveEntryActions(entry, canReadEntry) { kind ->
                    ActionRequests.answer(ActionAnswer.ArchiveEntryAction(kind))
                    finish()
                }
            }
        }
    }

    companion object {

        /** Whether this entry's bytes can be fetched by range - what Preview and Download need. */
        const val EXTRA_CAN_READ_ENTRY = "dev.eclipse.ssh.archive.CAN_READ_ENTRY"

        /**
         * The intent that opens this window on [entry].
         *
         * [canReadEntry] is the caller's verdict rather than a fact this window could derive: it comes
         * from the archive's format (a ranged source) and the entry's kind (a file, not a folder), and
         * both of those live in the browser.
         */
        fun intent(context: Context, entry: ArchiveEntry, canReadEntry: Boolean): Intent =
            Intent(context, ArchiveEntryActionsActivity::class.java)
                .putExtra(ActionRequests.EXTRA_SUBJECT_TOKEN, ActionRequests.put(ActionSubject.ArchiveEntryActions(entry)))
                .putExtra(EXTRA_CAN_READ_ENTRY, canReadEntry)
    }
}

/**
 * The rows themselves, and the archive's own vocabulary.
 *
 * The verbs deliberately differ from a file manager's: there is no Rename, Move or Delete, because
 * the archive is read-only where it stands on the server and this window never pretends otherwise.
 * Extract and Download are one verb under two names - pulling one entry's bytes is a range read while
 * pulling a subtree is a scan-and-collect, different costs the user deserves to see named - and Copy
 * path copies the path *inside* the archive, which is what someone pasting it beside the archive in
 * a shell is addressing.
 */
@Composable
private fun ArchiveEntryActions(
    entry: ArchiveEntry,
    canReadEntry: Boolean,
    onAction: (ArchiveEntryActionKind) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 18.dp)) {
        entry.takeIf { !it.isDirectory }?.size?.let {
            Text(
                describeSize(it),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))
        if (entry.isDirectory) {
            // A folder's only verb is extract-as-a-tree; opening it is what tapping the row already
            // does, so this window does not repeat it.
            ArchiveEntryActionRow("Extract folder") { onAction(ArchiveEntryActionKind.EXTRACT) }
        } else {
            // Preview only where the range read exists: a TAR entry would need the streaming scan,
            // and the honest answer for now is the extract path rather than a row that pretends.
            if (canReadEntry) {
                ArchiveEntryActionRow("Preview") { onAction(ArchiveEntryActionKind.PREVIEW) }
                ArchiveEntryActionRow("Download") { onAction(ArchiveEntryActionKind.EXTRACT) }
            } else {
                ArchiveEntryActionRow("Extract") { onAction(ArchiveEntryActionKind.EXTRACT) }
            }
        }
        ArchiveEntryActionRow("Copy path") { onAction(ArchiveEntryActionKind.COPY_PATH) }
        ArchiveEntryActionRow("Properties") { onAction(ArchiveEntryActionKind.PROPERTIES) }
    }
}

/** One row of the entry window. Not themed as a menu, because the sheet's plain list was right. */
@Composable
private fun ArchiveEntryActionRow(label: String, onClick: () -> Unit) {
    Text(
        label,
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
