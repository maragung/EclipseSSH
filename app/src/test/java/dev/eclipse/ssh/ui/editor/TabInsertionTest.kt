package dev.eclipse.ssh.ui.editor

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * What the Tab key inserts, as a pure function of the prefs.
 *
 * The interception itself — [androidx.compose.ui.input.key.onPreviewKeyEvent] on the field, the
 * insertion routed through the history-recording change callback — is Compose machinery only a
 * device can exercise honestly, so the testable core is the one decision in it: one tab character
 * or [EditorPrefs.tabSize] spaces. Pinned here because it is the whole difference between the two
 * preferences; everything downstream (cursor math, undo) treats the result as an opaque string.
 */
class TabInsertionTest {

    @Test
    fun `the default inserts one tab character`() {
        // Real tabs by default, matching what the file will contain if the user never opens the
        // sheet — the editor must not reformat anyone's indentation unprompted.
        assertThat(tabInsertion(EditorPrefs())).isEqualTo("\t")
    }

    @Test
    fun `spaces when asked for, exactly as many as the tab size`() {
        assertThat(tabInsertion(EditorPrefs(spacesInsteadOfTabs = true, tabSize = 2)))
            .isEqualTo("  ")
        assertThat(tabInsertion(EditorPrefs(spacesInsteadOfTabs = true, tabSize = 8)))
            .isEqualTo("        ")
    }

    @Test
    fun `tab size one still inserts a space, not a tab`() {
        // The degenerate width is a legal choice from the sheet's steppers; it must not fall back
        // to the tab character, which would quietly re-enable the other preference.
        assertThat(tabInsertion(EditorPrefs(spacesInsteadOfTabs = true, tabSize = 1)))
            .isEqualTo(" ")
    }
}
