package dev.eclipse.ssh.ui.transfers

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.eclipse.ssh.data.TransferRepository
import dev.eclipse.ssh.data.model.TransferDirection
import dev.eclipse.ssh.data.model.TransferItem
import dev.eclipse.ssh.data.model.TransferStatus
import dev.eclipse.ssh.data.settings.SettingsRepository
import dev.eclipse.ssh.ui.actions.ActionAnswer
import dev.eclipse.ssh.ui.actions.ActionRequests
import dev.eclipse.ssh.ui.actions.TransferActionKind
import dev.eclipse.ssh.ui.settings.SettingsDestinationWindow
import javax.inject.Inject

/**
 * Everything one transfer can do, in a window of its own.
 *
 * It used to be a `ModalBottomSheet` opened by long-pressing a card in the Transfers list, and the
 * reason it is a window now is the reason the rest of this app's sheets have become windows: a sheet
 * shows a list of rows through a slot at the bottom of the screen, over the very card it is acting
 * on. Here that card is a live progress row, and covering it to offer Pause is exactly backwards.
 *
 * **What it does not do is act.** Every row, including the ones that look self-contained, is an
 * [ActionAnswer] handed back to the workspace — see [TransferActionKind] for why. What that buys is
 * the one thing a second implementation of these ten actions could not: the transfer the user paused
 * is paused by the same code that pauses it everywhere else, with the same host and provider
 * resolution, and there is no second place for that resolution to drift.
 *
 * The subject travels as a plain id in the intent, and this window reads the item itself from
 * [TransferRepository] — a singleton with a `Flow`, so the id is all it takes. That is deliberately
 * the opposite of the token handoff the other windows use, and it is what makes this one better than
 * the sheet was: a download keeps counting up in the header while its actions are on screen.
 *
 * A transfer that is no longer in the repository closes the window. It means something removed it
 * while this was open — "Remove from list" does exactly that — and a menu for a row that is gone has
 * nothing to offer.
 *
 * The manifest gives this the editor's `configChanges` list as well, though unlike the token-carrying
 * windows it does not *depend* on it: the subject is an id in the intent, so a rotation that recreated
 * this window would find the same transfer again. It is there so a rotation is a recomposition and
 * nothing more — the alternative is a visible re-creation of a window whose content never changed.
 */
@AndroidEntryPoint
class TransferActionsActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var transferRepository: TransferRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val transferId = intent?.getStringExtra(EXTRA_TRANSFER_ID)
        if (transferId == null) {
            finish()
            return
        }
        setContent {
            // Null until the repository has answered, which is what tells a first frame apart from
            // an item that has genuinely gone — the two are one frame apart and only one of them
            // should close the window.
            val transfers by transferRepository.transfers.collectAsStateWithLifecycle(initialValue = null)
            val item = transfers?.firstOrNull { it.id == transferId }
            LaunchedEffect(transfers, item) {
                if (transfers != null && item == null) finish()
            }
            SettingsDestinationWindow(
                settingsRepository = settingsRepository,
                title = item?.name ?: "Transfer",
                onClose = { finish() },
            ) {
                item?.let { transfer ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 18.dp)) {
                        TransferActions(transfer) { kind ->
                            ActionRequests.answer(ActionAnswer.TransferAction(transfer.id, kind))
                            finish()
                        }
                    }
                }
            }
        }
    }

    companion object {

        /** The id of the transfer this window offers actions for. */
        const val EXTRA_TRANSFER_ID = "dev.eclipse.ssh.transfers.TRANSFER_ID"

        /**
         * The intent that opens this window on [transfer].
         *
         * An id and not the item: the item is live, and a copy of it taken here would be a snapshot
         * of a download's progress that stops moving.
         */
        fun intent(context: Context, transfer: TransferItem): Intent =
            Intent(context, TransferActionsActivity::class.java).putExtra(EXTRA_TRANSFER_ID, transfer.id)
    }
}

/**
 * The rows themselves: what this transfer's own state can serve, and nothing it cannot.
 *
 * Not themed as a menu — no icons, no dividers — because it is the same list the sheet drew and the
 * sheet was right about the shape. What changed is where it is drawn.
 */
@Composable
private fun TransferActions(item: TransferItem, onAction: (TransferActionKind) -> Unit) {
    // The local file exists in full once a download completes, and from the very start for an
    // upload — it is the source the bytes come from. A download in any other state has only a
    // prefix on disk, and previewing or editing a prefix would show content the user would take
    // for the whole file.
    val hasLocalFile = item.localUri != null &&
        (item.status == TransferStatus.COMPLETE || item.direction == TransferDirection.UPLOAD)
    Text(
        "${item.direction.label} · ${item.hostName} · ${item.status.name.lowercase()}",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(12.dp))
    // The control the status asks for, in the card's own vocabulary: a retry is a resume
    // the user chose to name differently, exactly as the card's icon does.
    when (item.status) {
        TransferStatus.RUNNING -> TransferActionRow("Pause") { onAction(TransferActionKind.PAUSE) }
        TransferStatus.PAUSED -> TransferActionRow("Resume") { onAction(TransferActionKind.RESUME) }
        TransferStatus.FAILED -> TransferActionRow("Retry") { onAction(TransferActionKind.RESUME) }
        TransferStatus.QUEUED -> if (item.scheduledAt != null) {
            TransferActionRow("Run now") { onAction(TransferActionKind.RUN_NOW) }
        } else {
            TransferActionRow("Resume") { onAction(TransferActionKind.RESUME) }
        }
        TransferStatus.COMPLETE -> Unit
    }
    if (item.status != TransferStatus.COMPLETE) {
        TransferActionRow("Cancel transfer", destructive = true) { onAction(TransferActionKind.CANCEL) }
    }
    if (hasLocalFile) {
        TransferActionRow("View file") { onAction(TransferActionKind.VIEW_FILE) }
        // Editing only a finished file: overwriting the source of a running upload, or a
        // half-written download target, races the transfer that is still writing it.
        if (item.status == TransferStatus.COMPLETE) {
            TransferActionRow("Edit") { onAction(TransferActionKind.EDIT_FILE) }
        }
        TransferActionRow("Open") { onAction(TransferActionKind.OPEN_FILE) }
        TransferActionRow("Open with") { onAction(TransferActionKind.OPEN_FILE_WITH) }
    }
    TransferActionRow("Copy details") { onAction(TransferActionKind.COPY_DETAILS) }
    if (item.status == TransferStatus.COMPLETE) {
        // Cancel for a finished transfer stops nothing — it only drops the row, so the
        // window names it for what it does here.
        TransferActionRow("Remove from list", destructive = true) { onAction(TransferActionKind.CANCEL) }
    }
}

/** One row of the transfer actions window, red where the action removes something. */
// The click goes last so every row reads as `TransferActionRow(label) { ... }` — with a trailing
// Boolean the trailing lambda would have nothing to bind to.
@Composable
private fun TransferActionRow(label: String, destructive: Boolean = false, onClick: () -> Unit) {
    Text(
        label,
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        style = MaterialTheme.typography.bodyLarge,
        color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
}
