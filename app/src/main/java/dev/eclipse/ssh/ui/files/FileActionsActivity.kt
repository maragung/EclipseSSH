package dev.eclipse.ssh.ui.files

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
import dev.eclipse.ssh.data.fs.FsEntry
import dev.eclipse.ssh.data.settings.SettingsRepository
import dev.eclipse.ssh.ui.actions.ActionAnswer
import dev.eclipse.ssh.ui.actions.ActionRequests
import dev.eclipse.ssh.ui.actions.ActionSubject
import dev.eclipse.ssh.ui.actions.FileActionKind
import dev.eclipse.ssh.ui.settings.SettingsDestinationWindow
import javax.inject.Inject

/**
 * Everything one file or folder can do, in a window of its own.
 *
 * It used to be a `ModalBottomSheet` opened by long-pressing a row in the Files explorer, and the
 * reason it is a window now is the same one every other promoted sheet has: a sheet is a slot at the
 * bottom of the screen, so a menu of twelve rows was drawn over the listing it was acting on - and the
 * listing is exactly what somebody choosing between "Copy to…" and "Move to…" needs to see, because
 * the destination is where the file already is.
 *
 * **What it does not do is act.** Every row answers a [FileActionKind] and closes; the explorer acts.
 * That is not ceremony. Six of these rows open a dialog or a picker the explorer owns - Rename, Copy,
 * Move, Permissions, Properties and Delete all change state in `FilesScreen` and three of them summon
 * an activity result - and the rest are [dev.eclipse.ssh.data.fs.FileSystemProvider] work resolved
 * from the session the explorer is showing. None of that is reachable from a second window, and a
 * second implementation of it would be a second answer to the same question.
 *
 * The entry travels as a token through [ActionRequests], because an [FsEntry] is a row of a listing the
 * explorer's controller holds and nothing an intent can carry. The four facts that are *not* rows -
 * whether this is the local session, whether the backend has permissions, whether View Archive can
 * serve this name, and whether there is another server to send to - are booleans, so they ride the
 * intent as extras. That split is the rule [ActionRequests] states, applied literally: a subject
 * carries exactly what an intent cannot.
 *
 * A spent token closes the window rather than opening it on nothing: the system re-delivers an intent
 * on a rotation, from recents, and after a crash, and the entry may be from a listing that is gone.
 */
@AndroidEntryPoint
class FileActionsActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val subject = ActionRequests.take(intent?.getStringExtra(ActionRequests.EXTRA_SUBJECT_TOKEN))
        val entry = (subject as? ActionSubject.FileActions)?.entry
        if (entry == null) {
            finish()
            return
        }
        // Read once, at launch, and not watched: each is a property of the session that was on screen
        // when the row was long-pressed, and the workspace cannot change underneath a window that is
        // in front of it.
        val isLocal = intent?.getBooleanExtra(EXTRA_IS_LOCAL, false) == true
        val supportsPermissions = intent?.getBooleanExtra(EXTRA_SUPPORTS_PERMISSIONS, false) == true
        val canOpenArchive = intent?.getBooleanExtra(EXTRA_CAN_OPEN_ARCHIVE, false) == true
        setContent {
            SettingsDestinationWindow(
                settingsRepository = settingsRepository,
                title = entry.name,
                onClose = { finish() },
            ) {
                FileActions(
                    entry = entry,
                    isLocal = isLocal,
                    supportsPermissions = supportsPermissions,
                    canOpenArchive = canOpenArchive,
                ) { kind ->
                    ActionRequests.answer(ActionAnswer.FileAction(kind))
                    finish()
                }
            }
        }
    }

    companion object {

        /** Whether the explorer was showing the local session's own storage. */
        const val EXTRA_IS_LOCAL = "dev.eclipse.ssh.files.IS_LOCAL"

        /**
         * Whether the active backend has a permission model at all.
         *
         * A SAF document tree has no mode bits, so both the Permissions row and the action behind it
         * are absent there - the explorer decides this once and the window only draws the row.
         */
        const val EXTRA_SUPPORTS_PERMISSIONS = "dev.eclipse.ssh.files.SUPPORTS_PERMISSIONS"

        /**
         * Whether View Archive can serve *this* name on *this* session.
         *
         * The caller's verdict rather than a fact this window could derive: it is the session's
         * provider and the file's extension together, and both live in the explorer.
         */
        const val EXTRA_CAN_OPEN_ARCHIVE = "dev.eclipse.ssh.files.CAN_OPEN_ARCHIVE"

        /**
         * The intent that opens this window on [entry].
         *
         * The four booleans are not part of the subject because they are not unparcelable - see the
         * class doc. [canOpenArchive] is the only one that is not a property of the session alone, and
         * it is still a boolean the caller already worked out.
         */
        fun intent(
            context: Context,
            entry: FsEntry,
            isLocal: Boolean,
            supportsPermissions: Boolean,
            canOpenArchive: Boolean,
        ): Intent = Intent(context, FileActionsActivity::class.java)
            .putExtra(ActionRequests.EXTRA_SUBJECT_TOKEN, ActionRequests.put(ActionSubject.FileActions(entry)))
            .putExtra(EXTRA_IS_LOCAL, isLocal)
            .putExtra(EXTRA_SUPPORTS_PERMISSIONS, supportsPermissions)
            .putExtra(EXTRA_CAN_OPEN_ARCHIVE, canOpenArchive)
    }
}

/**
 * The rows themselves, in the order the sheet drew them.
 *
 * Two of them are conditional and both stay conditional: a backend with no permission model gets no
 * Permissions row, and a name View Archive cannot read gets no View Archive row - hiding a verb beats
 * offering it and failing, which is the same rule the sheet followed.
 *
 * "Select" is first and is not a file operation at all: long-press used to be the explorer's selection
 * gesture and long-press is what opens this window, so this row is where that gesture lands now, and a
 * started selection is the state the whole explorer reorganizes around.
 */
@Composable
private fun FileActions(
    entry: FsEntry,
    isLocal: Boolean,
    supportsPermissions: Boolean,
    canOpenArchive: Boolean,
    onAction: (FileActionKind) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 18.dp)) {
        entry.size?.let {
            Text(
                describeSize(it),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))
        FileActionRow("Select") { onAction(FileActionKind.SELECT) }
        FileActionRow("Preview") { onAction(FileActionKind.PREVIEW) }
        if (canOpenArchive) {
            // Above Edit: for a 50 GB archive this is the only row that can answer without moving
            // the archive, and it is the reason the row exists.
            FileActionRow("View Archive") { onAction(FileActionKind.OPEN_ARCHIVE) }
        }
        if (!entry.isDirectory) {
            // "Edit as text", the preview window's own label, so both doors into the editor promise
            // the same thing in the same words.
            FileActionRow("Edit as text") { onAction(FileActionKind.EDIT) }
        }
        FileActionRow("Rename") { onAction(FileActionKind.RENAME) }
        FileActionRow("Copy to…") { onAction(FileActionKind.COPY) }
        FileActionRow("Move to…") { onAction(FileActionKind.MOVE) }
        // One verb under two names, exactly as the sheet had it: the direction is the session's, not
        // the file's, and naming it "Transfer" would make the user work out which way it goes.
        FileActionRow(if (isLocal) "Upload to server" else "Download to device") { onAction(FileActionKind.TRANSFER) }
        if (!isLocal) {
            // Sending is remote-to-remote only. From the local session there is no source path a
            // second server could be told to fetch.
            FileActionRow("Send to another server") { onAction(FileActionKind.SEND_TO_HOST) }
        }
        if (supportsPermissions) {
            FileActionRow("Permissions") { onAction(FileActionKind.CHMOD) }
        }
        FileActionRow("Properties") { onAction(FileActionKind.PROPERTIES) }
        FileActionRow("Delete", destructive = true) { onAction(FileActionKind.DELETE) }
    }
}

/** One row, red where the action removes something. The sheet's own list, in the window's frame. */
@Composable
private fun FileActionRow(label: String, destructive: Boolean = false, onClick: () -> Unit) {
    Text(
        label,
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        style = MaterialTheme.typography.bodyLarge,
        color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
