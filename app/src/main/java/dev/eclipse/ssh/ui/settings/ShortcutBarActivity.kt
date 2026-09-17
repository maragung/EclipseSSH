package dev.eclipse.ssh.ui.settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dagger.hilt.android.AndroidEntryPoint
import dev.eclipse.ssh.data.model.AppSettings
import dev.eclipse.ssh.data.settings.SettingsRepository
import dev.eclipse.ssh.ui.terminal.KeyBarPrefsCodec
import dev.eclipse.ssh.ui.terminal.ShortcutBarEditorBody
import dev.eclipse.ssh.ui.terminal.ShortcutBarEditorDialogs
import dev.eclipse.ssh.ui.terminal.rememberShortcutBarEditorState
import kotlinx.coroutines.launch

/**
 * How this setting is named when its write fails: the view model's own string, lifted rather than
 * reinvented.
 *
 * `MainViewModel.setTerminalKeyBarJson` was `writeSetting("the shortcut bar layout")`, so this is
 * word for word what the user was already told when the dialog's Save could not reach the store. It
 * is not the `what` the [ChoiceDestinationActivity] base would derive from the title ("the shortcut
 * bar"), because the base's default exists for the destinations whose view-model string happened to
 * match their title, and a failure that has moved out of the view model should not also change how it
 * reads.
 */
private const val KEY_BAR_WRITE = "the shortcut bar layout"

/**
 * The shortcut bar, in its own window: the Settings row's dialog promoted to a screen.
 *
 * The promotion follows the same rule as every other destination in this batch. The dialog's body was
 * capped at a fraction of the screen height and the cap list inside it carried a second cap of its
 * own, so a bar with more caps than fit was edited through a window inside a window. What the
 * promotion must *not* do is rewrite the editor: the body is not reimplemented here.
 * `ShortcutBarEditorBody` in `ui/terminal` draws it, and the dialog and this screen are two skins over
 * that one body - the only things this file decides are the frame, what a saved write does, and what
 * leaving asks. A screen with its own copy of the sections is exactly the pair that drifts, and the
 * rules in there (where a new cap lands, that a preset is a whole arrangement, that a reset is
 * confirmed like any other replacement) are not obvious enough to be got right twice.
 *
 * Like every destination, this screen writes through the repository the shell injects rather than
 * through the Settings view model: one call, [SettingsRepository.setTerminalKeyBarJson], with the
 * whole encoded blob - the same whole-blob contract the editor has always had. `MainViewModel`
 * wrapped that call in `writeSetting(KEY_BAR_WRITE)`, which reported a failed DataStore write to the
 * user; a destination that calls the repository itself owns that report, which is why the save below
 * routes a failure through [LocalSettingsReport] and [reportWriteFailure] instead of letting it
 * escape a coroutine and take the process down.
 */
@AndroidEntryPoint
class ShortcutBarActivity : SettingsDestinationActivity() {

    override val screenTitle = "Shortcut bar"

    @Composable
    override fun Body(settings: AppSettings, repository: SettingsRepository) {
        // Decoded from the snapshot the shell hands over, and keyed on the blob it came from: the
        // first composition of any destination runs against `AppSettings()`'s defaults, before the
        // store has answered, so both this decode and the editor's copy below have to adopt the real
        // value when it arrives a frame later. See [rememberShortcutBarEditorState] for why the blob
        // is the right key rather than the decoded prefs.
        val initial = remember(settings.terminalKeyBarJson) {
            KeyBarPrefsCodec.decode(settings.terminalKeyBarJson)
        }
        val editor = rememberShortcutBarEditorState(initial, sourceKey = settings.terminalKeyBarJson)
        val scope = rememberCoroutineScope()
        val report = LocalSettingsReport.current
        val activity = LocalActivity.current
        // True while the back-out confirmation is up.
        var confirmingDiscard by remember { mutableStateOf(false) }

        // Unchanged edits are not work, so the guard is down when there is nothing to lose: back then
        // falls through to the activity and leaves, exactly as back leaves every other destination.
        // It is also down while one of the editor's own dialogs is up - that dialog's own back belongs
        // to it (it dismisses), and a guard firing behind it would be answering a question nobody
        // asked.
        BackHandler(enabled = editor.isDirty && !editor.hasSubDialog) { confirmingDiscard = true }

        // Writes the whole blob, and leaves the window only if the write landed.
        //
        // A local function, like the known-hosts screen's own mutations, so that everything it needs
        // is visible where it is called: the blob is encoded at the moment of the tap rather than
        // held, and the report and the close come from this composition.
        //
        // The order matters and is the reason this is not one expression. The window closing is what
        // says the write landed - there is no row below to re-read and no value on screen to watch
        // change - so a save that *failed* must not close: the message would go to a window on its way
        // out, and the user would be left believing a bar they never saved. So the failure is reported
        // first and `finish()` is only reached when nothing was thrown, which also means the edits are
        // still on screen to retry with.
        //
        // `activity` is nullable because [LocalActivity] is, and a window whose host could not be
        // resolved still saves - it simply stays put rather than pretending the save failed.
        fun save() {
            val json = editor.encoded()
            scope.launch {
                try {
                    repository.setTerminalKeyBarJson(json)
                } catch (failure: Throwable) {
                    // A failed DataStore write throws rather than returning a status; see
                    // [reportWriteFailure], which also re-throws cancellation instead of reporting the
                    // window going away as a save that failed.
                    reportWriteFailure(report, KEY_BAR_WRITE, failure)
                    return@launch
                }
                activity?.finish()
            }
        }

        SettingsSection(screenTitle) {
            // The Settings row this came from, in the shape every promoted destination keeps it: the
            // same icon, the same title and the same subtitle, so the screen reads as the row that
            // opened it. Its control is a count of what the bar currently shows - the one summary a
            // configuration has - and it tracks the working copy rather than the store, so a tap on a
            // switch below is answered on the line above it instead of one round-trip later.
            SettingRow(Icons.Default.Keyboard, screenTitle, "Choose the keys on the bar, add custom buttons, set the rows") {
                val buttons = editor.prefs.caps.count { it.visible }
                Text(
                    if (buttons == 1) "1 button" else "$buttons buttons",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            // The dialog's own frame supplied this padding, and its height cap; the cap is gone with
            // the dialog (the page scrolls instead) and the gutter is the app's usual 16dp, which is
            // what the sections in here are drawn against on either skin.
            ShortcutBarEditorBody(
                state = editor,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 14.dp),
                // The page around this body scrolls, so the cap list must not: a second vertical
                // scroller nested in this one would take the drag and leave the page unable to reach
                // its own bottom. See [ShortcutBarEditorBody].
                capListScrolls = false,
            )
        }

        // The dialog's Reset and Save, kept as buttons under the card rather than in the bar: the
        // shell's bar has no actions slot, and these are not a setting row - they are what this screen
        // does with the whole arrangement. Cancel is gone on purpose, because leaving *is* Cancel
        // here.
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Queue the reset, which is a preset like any other: the confirmation it takes is the one
            // the dialog's Reset has always taken, and it is shared, not reworded here.
            TextButton(onClick = { editor.requestReset() }) { Text("Reset") }
            // No in-flight flag on this button: the write is the same blob if it is reached twice and
            // `finish()` is idempotent, so a double tap costs a redundant write and nothing else.
            Button(onClick = { save() }) { Text("Save") }
        }

        // The editor's own dialogs - the custom-cap editor, and the confirmation a preset or a reset
        // takes - drawn over this screen, which is what a window's own dialog looks like. In the
        // dialog skin the same call is made *instead of* the frame; here the frame is the page, and it
        // has no business disappearing because a preset is being confirmed.
        ShortcutBarEditorDialogs(editor)

        // The screen's back-out guard, and the judgement it encodes.
        //
        // The dialog's Cancel discarded the working copy without asking, and its own back did the
        // same; this screen deliberately does not copy that. A dialog's Cancel is a button the user
        // reached for and read, inside a frame whose whole message is "you are in a mode, and here are
        // your two ways out" - losing the edits was the price of a decision. A window has no such
        // frame: the system back gesture is a swipe from the edge that a user makes for a dozen
        // reasons, and a screen that dropped the arrangement on it would be dropping work nobody chose
        // to abandon. So back asks *when there is something to lose*, and only then.
        //
        // This is the one place the two skins are not symmetric, and the asymmetry is held to the
        // smallest thing that makes the screen honest. The protection the dialog did have - the
        // confirmation before a preset or a reset discards a hand-built bar - is not weakened or
        // reworded: it is the shared dialog above, firing in both skins.
        //
        // The shell's back arrow calls `finish()` directly from outside this body, so this guard
        // covers the system back and not the arrow. That is a real gap and it is not hidden here: a
        // screen that wants the arrow to ask as well needs the shell to hand its close action to the
        // content, which is a change to `SettingsDestination`, not to this file.
        if (confirmingDiscard) {
            AlertDialog(
                onDismissRequest = { confirmingDiscard = false },
                title = { Text("Discard your changes?") },
                text = {
                    Text(
                        "The caps, rows and order you have changed here have not been saved. Leaving " +
                            "now keeps the shortcut bar as it was.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = { confirmingDiscard = false; activity?.finish() }) {
                        Text("Discard", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = { TextButton(onClick = { confirmingDiscard = false }) { Text("Keep editing") } },
            )
        }
    }
}
