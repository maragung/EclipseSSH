package dev.eclipse.ssh.ui.archive

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dagger.hilt.android.AndroidEntryPoint
import dev.eclipse.ssh.data.settings.SettingsRepository
import dev.eclipse.ssh.ui.preview.ArchivePreviewRequest
import dev.eclipse.ssh.ui.preview.PreviewRequests
import dev.eclipse.ssh.ui.settings.SettingsDestinationWindow
import javax.inject.Inject

/**
 * One file from inside an archive, in a window of its own.
 *
 * The second of the two previews promoted out of a `ModalBottomSheet`, for the reason the first one
 * was: an entry's text is read, and reading was letterboxed twice over — a body capped at 55% of a
 * sheet that was itself capped at 85% of the display. As a window the entry gets the screen, and the
 * back gesture returns to the browser it was opened from.
 *
 * Everything about *what* is shown is [ArchiveEntryPreviewContent]'s, unchanged: the same name-based
 * kinds, the same two ceilings, and the same single ranged read of one entry's bytes rather than
 * anything around it.
 *
 * The archive is not held open by this window. [ArchivePreviewRequest.readEntry] is a closure over
 * the browser the entry came from, and it is called when this window fetches — so the bytes still
 * come from the open archive's own source, and an archive that has been closed underneath reports
 * itself as a failed read rather than as a wrong file.
 *
 * Same `configChanges` reasoning as the file preview: the request cannot be parcelled, its token is
 * spent the moment this window reads it, and a rotation must therefore be a recomposition rather than
 * a relaunch that closes the window.
 */
@AndroidEntryPoint
class ArchiveEntryPreviewActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository

    private var request by mutableStateOf<ArchivePreviewRequest?>(null)

    /**
     * Makes whatever [token] still holds the entry this window shows, the twin of
     * [dev.eclipse.ssh.ui.preview.FilePreviewActivity.onPreviewToken]: a spent token leaves the entry
     * already on screen alone, and it is `onCreate` that refuses a window with nothing to show.
     */
    internal fun onPreviewToken(token: String?) {
        PreviewRequests.takeArchiveEntry(token)?.let { request = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val arrived = PreviewRequests.takeArchiveEntry(intent?.getStringExtra(EXTRA_REQUEST_TOKEN))
        if (arrived == null) {
            finish()
            return
        }
        request = arrived
        addOnNewIntentListener { incoming -> onPreviewToken(incoming.getStringExtra(EXTRA_REQUEST_TOKEN)) }
        setContent {
            SettingsDestinationWindow(
                settingsRepository = settingsRepository,
                title = "Entry preview",
                onClose = { finish() },
            ) {
                request?.let { current ->
                    ArchiveEntryPreviewContent(entry = current.entry, readEntry = current.readEntry)
                }
            }
        }
    }

    companion object {
        /** The intent extra carrying the [PreviewRequests] token. */
        const val EXTRA_REQUEST_TOKEN = "dev.eclipse.ssh.archive.PREVIEW_TOKEN"

        /**
         * The intent that opens this window on [entry], read through [readEntry].
         *
         * A helper rather than a bare `Intent` at the call site, so the two halves of the handoff —
         * store the request, carry its token — cannot be done in the wrong order or half done. The
         * same shape [dev.eclipse.ssh.ui.preview.FilePreviewActivity] takes, and for the same reason.
         */
        fun intent(
            context: Context,
            entry: ArchiveEntry,
            readEntry: (suspend () -> ByteArray?)?,
        ): Intent = Intent(context, ArchiveEntryPreviewActivity::class.java).putExtra(
            EXTRA_REQUEST_TOKEN,
            PreviewRequests.putArchiveEntry(ArchivePreviewRequest(entry, readEntry)),
        )
    }
}
