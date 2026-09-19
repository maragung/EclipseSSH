package dev.eclipse.ssh.ui.preview

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
import dev.eclipse.ssh.data.fs.FsEntry
import dev.eclipse.ssh.data.fs.FileSystemProvider
import dev.eclipse.ssh.data.settings.SettingsRepository
import dev.eclipse.ssh.ui.editor.EditorRequest
import dev.eclipse.ssh.ui.editor.EditorRequests
import dev.eclipse.ssh.ui.editor.TextEditorActivity
import dev.eclipse.ssh.ui.settings.SettingsDestinationWindow
import javax.inject.Inject

/**
 * The file preview in a window of its own.
 *
 * It used to be a `ModalBottomSheet` at the root of MainActivity — the same surface the archive
 * browser and the explorer's own sheets use — and the reason it is not one any more is the one that
 * promoted every other read-only panel in this app: a sheet covers the thing it is describing and
 * then hides most of itself. A text preview is *reading*, and reading wants the whole screen: the
 * monospace body was letterboxed into 55% of a sheet that was itself capped at 85% of the display,
 * so a 200-line log was read through a slot. As a window the body gets the screen under the bar, the
 * back gesture returns to where the preview was opened from, and the keyboard — should a future
 * version let this surface edit — resizes a window rather than a panel.
 *
 * Nothing about *what* is shown changed: the same kinds, the same ceilings, the same "no unsafe
 * automatic execution" rule, and the same body composable ([FilePreviewContent]) the sheet drew.
 *
 * The subject travels through [PreviewRequests] rather than the intent, because a
 * [dev.eclipse.ssh.data.fs.FileSystemProvider] is a live object — for a remote file, the one holding
 * the session it is read through. A token that is already spent finishes this window: it means the
 * system re-delivered an intent from before, and a preview of a file the user has closed is not
 * something to reopen on their behalf.
 *
 * The manifest gives this and the archive preview the editor's own `configChanges` list, and it is
 * load-bearing rather than a habit: a rotation that recreated this window would find its token spent
 * and close itself, which is the one failure a preview must not have — a look at a file that vanishes
 * when the phone turns.
 */
@AndroidEntryPoint
class FilePreviewActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository

    /** The file on screen. A property, so a second preview delivered here can replace the first. */
    private var request by mutableStateOf<FilePreviewRequest?>(null)

    /**
     * Makes whatever [token] still holds the file this window shows, the way the editor's
     * `onEditorToken` opens what a token holds there.
     *
     * A spent or unknown token changes nothing rather than closing the window: `onCreate` is where a
     * preview with nothing to show is refused, and once a file is on screen a late re-delivery of an
     * old intent is not a reason to take it away from the reader.
     */
    internal fun onPreviewToken(token: String?) {
        PreviewRequests.takeFile(token)?.let { request = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val arrived = PreviewRequests.takeFile(intent?.getStringExtra(EXTRA_REQUEST_TOKEN))
        if (arrived == null) {
            finish()
            return
        }
        request = arrived
        // launchMode="singleTop", so a preview opened while this one is on top arrives as a new
        // intent rather than as a second window. Replacing the subject is the right answer for a
        // preview - it is a look at a file, not a place - and it keeps the back stack at one entry
        // deep however many files the user walks through. Registered before setContent so no
        // delivery can slip in ahead of it.
        addOnNewIntentListener { incoming -> onPreviewToken(incoming.getStringExtra(EXTRA_REQUEST_TOKEN)) }
        setContent {
            SettingsDestinationWindow(
                settingsRepository = settingsRepository,
                title = "Preview",
                onClose = { finish() },
            ) {
                request?.let { current ->
                    FilePreviewContent(
                        entry = current.entry,
                        provider = current.provider,
                        onEdit = { entry -> openEditor(entry, current) },
                    )
                }
            }
        }
    }

    /**
     * Hands the file to the editor window, which is one token exchange away rather than a callback
     * into a workspace this window does not have.
     *
     * The preview stays open underneath: the editor is a full window too, so closing it lands back on
     * the preview the user was reading, which is where a person who tapped Edit expects to be. No
     * result travels back — this app's windows hand nothing back, and a file the editor saved is read
     * again by whoever lists it, not by the surface that handed it over.
     */
    private fun openEditor(entry: FsEntry, current: FilePreviewRequest) {
        val token = EditorRequests.put(EditorRequest(entry, current.provider))
        startActivity(
            Intent(this, TextEditorActivity::class.java)
                .putExtra(TextEditorActivity.EXTRA_REQUEST_TOKEN, token),
        )
    }

    companion object {
        /** The intent extra carrying the [PreviewRequests] token. */
        const val EXTRA_REQUEST_TOKEN = "dev.eclipse.ssh.preview.FILE_TOKEN"

        /**
         * The intent that opens this window on [entry], read through [provider].
         *
         * A helper rather than a bare `Intent` at the call sites, so the two halves of the handoff —
         * store the request, carry its token — cannot be done in the wrong order or half done. Three
         * places open this window (the explorer, the explorer's own file sheet, and a finished
         * transfer's local file), and one of them getting the order wrong would show the wrong file.
         */
        fun intent(context: Context, entry: FsEntry, provider: FileSystemProvider): Intent =
            Intent(context, FilePreviewActivity::class.java).putExtra(
                EXTRA_REQUEST_TOKEN,
                PreviewRequests.putFile(FilePreviewRequest(entry, provider)),
            )
    }
}
