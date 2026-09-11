package dev.eclipse.ssh.ui.editor

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The editor tab's state machine, without a filesystem.
 *
 * Every rule here is one the requirements state in prose — never overwrite a server change
 * silently, never lose the user's text to a failed upload, read-only refuses rather than errors
 * late — and prose requirements are exactly what a pure state machine is for: each one becomes
 * an assertion instead of a hope.
 */
class EditorTabModelTest {

    private fun tab(
        text: String = "original",
        modified: Long? = 1_000L,
        size: Long? = 8L,
        readOnly: Boolean = false,
    ) = EditorTabModel(
        name = "server.js",
        path = "/srv/server.js",
        providerId = "sftp",
        connectionName = "Production edge",
        initialText = text,
        initialServerModified = modified,
        initialServerSize = size,
        initialReadOnly = readOnly,
    )

    @Test
    fun `a fresh tab is unmodified and agrees with the server`() {
        val tab = tab()
        assertThat(tab.modified).isFalse()
        assertThat(tab.changedOnServer).isFalse()
        assertThat(tab.saving).isFalse()
    }

    @Test
    fun `typing marks the tab modified, and only the working text moves`() {
        val tab = tab()
        tab.setText("original + edit")
        assertThat(tab.modified).isTrue()
        assertThat(tab.savedText).isEqualTo("original")
    }

    @Test
    fun `a successful save re-establishes the baseline and clears server-change`() {
        val tab = tab()
        tab.setText("edited")
        tab.serverChanged()
        tab.saveStarted()
        assertThat(tab.saving).isTrue()
        tab.saveSucceeded(newModified = 2_000L, newSize = 7L)

        assertThat(tab.modified).isFalse()
        assertThat(tab.saving).isFalse()
        assertThat(tab.changedOnServer).isFalse()
        assertThat(tab.serverStatsDiffer(modified = 2_000L, size = 7L)).isFalse()
    }

    @Test
    fun `a failed save keeps the working text and the modified state`() {
        // The requirement: a failed upload must not lose the user's changes. "Losing" includes
        // the subtle kind, where the editor decides the save happened because the request did.
        val tab = tab()
        tab.setText("edited")
        tab.saveStarted()
        tab.saveFailed()

        assertThat(tab.text).isEqualTo("edited")
        assertThat(tab.modified).isTrue()
        assertThat(tab.saving).isFalse()
    }

    @Test
    fun `the conflict guard exposes the mtime the save must compare against`() {
        val tab = tab(modified = 1_234L)
        assertThat(tab.conflictGuard()).isEqualTo(1_234L)
    }

    @Test
    fun `a server change is a flag, never a text change`() {
        // The watcher's verdict must not touch the text: the user's edits survive until they
        // answer the notification, whichever way they answer.
        val tab = tab()
        tab.setText("local edits")
        tab.serverChanged()

        assertThat(tab.changedOnServer).isTrue()
        assertThat(tab.text).isEqualTo("local edits")
        assertThat(tab.modified).isTrue()
    }

    @Test
    fun `a watcher tick while a save is in flight does not raise the conflict flag`() {
        // The upload itself moves the mtime; a stat racing it would look like a server change.
        val tab = tab()
        tab.setText("edited")
        tab.saveStarted()
        tab.serverChanged()

        assertThat(tab.changedOnServer).isFalse()
    }

    @Test
    fun `an explicit reload adopts the server text and clears both flags`() {
        val tab = tab()
        tab.setText("thrown away")
        tab.serverChanged()
        tab.reloadSucceeded("fresh from server", newModified = 3_000L, newSize = 17L)

        assertThat(tab.text).isEqualTo("fresh from server")
        assertThat(tab.modified).isFalse()
        assertThat(tab.changedOnServer).isFalse()
    }

    @Test
    fun `server stats differ on mtime, on size, and on the file vanishing`() {
        // Size alone counts because some servers report coarse timestamps: a same-mtime write
        // with a different size is still a different file.
        val tab = tab(modified = 1_000L, size = 100L)
        assertThat(tab.serverStatsDiffer(modified = 1_001L, size = 100L)).isTrue()
        assertThat(tab.serverStatsDiffer(modified = 1_000L, size = 101L)).isTrue()
        assertThat(tab.serverStatsDiffer(modified = null, size = null)).isTrue()
        assertThat(tab.serverStatsDiffer(modified = 1_000L, size = 100L)).isFalse()
    }

    @Test
    fun `stats the server cannot report are not a change`() {
        // Unknown is not different: a backend with no stat must not put every tab into conflict.
        val tab = tab(modified = 1_000L, size = 100L)
        assertThat(tab.serverStatsDiffer(modified = null, size = 100L)).isFalse()
        assertThat(tab.serverStatsDiffer(modified = 1_000L, size = null)).isFalse()
    }

    @Test
    fun `auto-save fires only on idle past the debounce, with changes, when safe`() = runTest {
        val tab = tab()
        val saves = mutableListOf<Unit>()
        val ticks = flowOf(
            AutoSaveTick.Idle(AUTO_SAVE_DEBOUNCE_MS - 1),
            AutoSaveTick.Idle(AUTO_SAVE_DEBOUNCE_MS),
            AutoSaveTick.Idle(AUTO_SAVE_DEBOUNCE_MS + 5_000),
        )
        tab.setText("edited")
        autoSaveTicks(ticks, { tab }, { true }).collect { saves += it }
        // Twice, not three times: the first tick is inside the debounce window, so typing had
        // not stopped yet and no upload happens. This is the requirement that auto-save must
        // not upload on every character, held by the smallest case that shows it.
        assertThat(saves).hasSize(2)
    }

    @Test
    fun `auto-save never fires for an unmodified, saving, conflicted, read-only or disabled tab`() = runTest {
        val cases = listOf(
            tab(),                                                    // unmodified
            tab().apply { setText("e"); saveStarted() },               // upload in flight
            tab().apply { setText("e"); serverChanged() },             // server moved: conflict, not save
            tab(readOnly = true).apply { setText("e") },               // cannot write
        )
        cases.forEachIndexed { index, case ->
            val saves = mutableListOf<Unit>()
            autoSaveTicks(flowOf(AutoSaveTick.Idle(60_000)), { case }, { true }).collect { saves += it }
            assertThat(saves).isEmpty()
        }
        // And the switch itself:
        val on = tab().apply { setText("e") }
        val savesOn = mutableListOf<Unit>()
        autoSaveTicks(flowOf(AutoSaveTick.Idle(60_000)), { on }, { false }).collect { savesOn += it }
        assertThat(savesOn).isEmpty()
    }
}
