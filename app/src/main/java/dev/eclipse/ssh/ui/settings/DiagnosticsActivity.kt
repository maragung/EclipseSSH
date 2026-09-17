package dev.eclipse.ssh.ui.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.eclipse.ssh.data.model.AppSettings
import dev.eclipse.ssh.data.settings.SettingsRepository
import dev.eclipse.ssh.feature.vault.VaultUnlockGate
import dev.eclipse.ssh.security.SecureClipboard
import dev.eclipse.ssh.ssh.SessionDiagnostics
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The session trace, in a window of its own.
 *
 * It was an `AlertDialog` over Settings, and it could not stay one for the reason the promotion
 * exists: the ring holds up to five hundred events, a dialog body is capped at a fraction of the
 * screen height, and a long trace was read through a letterbox. The window spends the whole height on
 * it, and the content, the empty state and the wording are the dialog's, unchanged.
 *
 * What the promotion had to settle is the three actions, since they are the one part of this screen
 * that its siblings in this package could not hand it:
 *
 *  - **Copy** goes through the app's audited [SecureClipboard] rather than Compose's
 *    `LocalClipboardManager` (deprecated here). That boundary is what carries the auto-clear deadline,
 *    the sensitive-clip flag and the wipe that survives the process; a trace put on the system
 *    clipboard any other way would sit there indefinitely.
 *  - **Save** opens a Storage Access Framework create-document picker, which is another app's
 *    activity. That is the case [VaultUnlockGate] exists for: while a picker is on screen
 *    `ProcessLifecycleOwner` reports this process as backgrounded, the auto-lock clock starts, and a
 *    user who takes longer over the picker than their configured delay would come back to a lock
 *    screen with their save discarded. The gate is held across the picker and released in the
 *    launcher's callback.
 *  - **Clear** empties the ring in place. The injected singleton is the same ring every connection
 *    records into, so what is cleared here is exactly what an export would have carried.
 *
 * The store behind this screen is a `StateFlow`, so unlike the known-hosts window there is something
 * to collect here: an event recorded while this window is open appears in it as it lands. That is the
 * point of the screen - the drop a user is reading about is often recorded while they are reading.
 */
@AndroidEntryPoint
class DiagnosticsActivity : SettingsDestinationActivity() {

    /**
     * The trace itself, injected rather than reached through the Settings view model.
     *
     * This is the same `@Singleton` the connection path records into, which is the only thing that
     * makes what is on screen believable: elsewhere in the app these entries are written from MINA's
     * I/O threads and from the reconnect ladder, and a copy of the ring held by a view model could
     * show a session that had already ended, or miss the ending.
     */
    @Inject lateinit var diagnostics: SessionDiagnostics

    /** The audited clipboard boundary - the only route this app has to the system clipboard. */
    @Inject lateinit var secureClipboard: SecureClipboard

    /**
     * Held for the length of the Save picker, through no state of this window's own.
     *
     * The flag has to outlive the composition: a picker is another app's activity, and this window can
     * be recreated underneath it, which is why the countdown's other reader lives where the flag does
     * rather than in a `remember`.
     */
    @Inject lateinit var vaultUnlockGate: VaultUnlockGate

    override val screenTitle = "Connection diagnostics"

    @Composable
    override fun Body(settings: AppSettings, repository: SettingsRepository) {
        // Subscribed, not pulled: the ring announces every event, so the count in the header and the
        // rows below it are the app's current state rather than a snapshot taken at open. The window
        // is left open while a session drops, which is precisely when this matters.
        val events by diagnostics.events.collectAsStateWithLifecycle()
        // Newest first. A trace is read to answer "what just happened", and the answer is at the end of
        // a ring that holds up to five hundred entries.
        val ordered = remember(events) { events.asReversed() }
        // Fixed 24-hour with seconds, not the locale's time format: the interval between two events is
        // the whole point of reading this, and half the locales drop seconds entirely.
        val clock = remember {
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
        }
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val report = LocalSettingsReport.current

        /**
         * The text Save is about to write, held until the picker answers.
         *
         * Nullable, and null is the initial value, because the two moments are a document picker
         * apart and the composition in between is not guaranteed to survive. A rotation while the
         * picker is up, or the process being reclaimed behind it, rebuilds this window with the
         * pending export gone - and the callback still arrives, because the result is delivered to
         * whichever instance is registered for it. A non-null default (an empty array, say) would
         * make that case open the user's chosen document for writing and truncate it to nothing: a
         * file where the trace should be, with no failure anywhere to see. Null is what lets the
         * callback tell "there is nothing to write" from "write nothing", and say so.
         */
        var pendingExport by remember { mutableStateOf<ByteArray?>(null) }
        val savePicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
            // Released first and unconditionally, including for a cancelled picker: the callback is
            // the moment the other app's activity is gone, and leaving the flag held would suspend the
            // auto-lock countdown for the rest of the process's life.
            vaultUnlockGate.release()
            val bytes = pendingExport
            pendingExport = null
            when {
                // Cancelled. Nothing was chosen, so there is nothing to report.
                uri == null -> Unit
                // The export did not survive the round trip, which is the case the nullable slot above
                // exists to make visible. Writing here would destroy the document the user just picked,
                // so the loss is reported instead.
                bytes == null -> report("Could not save the diagnostics log")
                else -> scope.launch {
                    // Off the main thread: this is a binder round trip into whichever provider owns the
                    // URI, and it can be a cloud backend that syncs before the stream closes.
                    writeExportDocument(context, uri, bytes)
                        .onFailure { reportWriteFailure(report, "the diagnostics log", it) }
                }
            }
        }

        SettingsSection(screenTitle) {
            if (events.isEmpty()) {
                Text(
                    "Nothing recorded yet. Connect a host and this becomes a timestamped trace of every connect, disconnect, reconnect and network change.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp).padding(vertical = 14.dp),
                )
            } else {
                Text(
                    "${events.size} event(s) · hosts are labelled s1, s2… and the number after the dot counts that host's connections, so s2.3 is its third. No password, key or host name is recorded, so this is safe to attach to a bug report.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 16.dp).padding(top = 14.dp, bottom = 10.dp),
                )
                // Above the trace rather than under it, where the dialog had them. The dialog's buttons
                // sat below a list the dialog itself scrolled, so they were always one small flick
                // away; here the list is the page, it can be five hundred rows, and a control under it
                // would be reachable only by scrolling past the whole log - the "rows below the fold"
                // problem this promotion exists to remove, rearranged.
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        onClick = {
                            // Read from the ring rather than from the rows on screen: this is what the
                            // app knows right now, including anything recorded between the frame the
                            // user is looking at and this tap.
                            val trace = diagnostics.export()
                            // `SecureClipboard`, never Compose's `LocalClipboardManager`: this is the
                            // app's one clipboard route, and it is what puts the auto-clear deadline on
                            // the copy and marks the clip sensitive on Android 13+.
                            //
                            // `copy` reports nothing back - it swallows a refused write itself, because
                            // a clipboard set from an app without window focus is refused by the
                            // platform on Android 10+ - so this catch is for the throw, not for a
                            // status. The wording is the view model's own sentence for this action.
                            runCatching { secureClipboard.copy(trace, settings.clearClipboardAfterSeconds) }
                                .onFailure { report("Could not copy to the clipboard") }
                        },
                    ) { Text("Copy") }
                    TextButton(
                        onClick = {
                            pendingExport = diagnostics.export().toByteArray(Charsets.UTF_8)
                            // Held before the launch rather than inside the callback, so the countdown
                            // is suspended from the instant the other activity starts.
                            vaultUnlockGate.hold()
                            savePicker.launch("eclipse-diagnostics.log")
                        },
                    ) { Text("Save") }
                    TextButton(
                        onClick = {
                            diagnostics.clear()
                            // The dialog's confirmation, kept even though clearing shows the empty
                            // state below at once. That state is not a reliable report on its own: a
                            // session that is still reconnecting can record its next event within the
                            // second, and the list would be repopulated before the user's eye caught
                            // it. This sentence is what says the clear happened.
                            report("Diagnostics cleared")
                        },
                    ) { Text("Clear", color = MaterialTheme.colorScheme.error) }
                }
                // A plain `forEach` in a `Column`, not the dialog's `LazyColumn`: this body already
                // sits inside `SettingsBody`'s `verticalScroll`, and a lazy vertical scroller nested in
                // it is measured against an infinite maximum height and throws. The lazy list was there
                // to survive the dialog's height cap, and the window is what removed the cap.
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ordered.forEach { entry ->
                        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
                                Text(
                                    clock.format(Instant.ofEpochMilli(entry.atMs)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    // The line() form starts with the epoch millis, which the exported
                                    // text needs and a reader does not; the row shows a clock and drops
                                    // the raw number.
                                    entry.line().substringAfter(' '),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Writes [bytes] to the SAF document at [uri], off the main thread.
 *
 * Activity-result callbacks are delivered on the main thread, and a document write is a binder round
 * trip into whichever provider owns the URI - which may be a cloud backend that syncs over the network
 * before the stream closes. Doing that inline drops frames at best and blocks long enough to ANR at
 * worst, and a revoked grant or a full volume would have thrown straight out of the callback and taken
 * the process down. The [Result] is what lets the callback report the failure through the window's own
 * channel instead.
 *
 * Opened with `"w"`, which is the mode the app's other diagnostics-shaped exports use: the picker has
 * already asked the user about replacing an existing document, so this is the write that answer
 * referred to.
 */
private suspend fun writeExportDocument(context: Context, uri: Uri, bytes: ByteArray): Result<Unit> =
    withContext(Dispatchers.IO) {
        runCatching {
            val stream = context.contentResolver.openOutputStream(uri, "w")
                ?: throw IOException("The selected location could not be opened for writing")
            stream.use { it.write(bytes) }
        }
    }
