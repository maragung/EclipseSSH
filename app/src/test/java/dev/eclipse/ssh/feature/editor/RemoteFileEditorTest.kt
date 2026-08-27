package dev.eclipse.ssh.feature.editor

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RemoteFileEditorTest {

    @Test
    fun `canEdit accepts files under the cap`() {
        val editor = RemoteFileEditor()
        assertThat(editor.canEdit(0L)).isTrue()
        assertThat(editor.canEdit(1024L)).isTrue()
        assertThat(editor.canEdit(editor.maxEditableBytes - 1)).isTrue()
    }

    @Test
    fun `canEdit refuses files over the cap`() {
        val editor = RemoteFileEditor()
        assertThat(editor.canEdit(editor.maxEditableBytes + 1)).isFalse()
        assertThat(editor.canEdit(50L * 1024L * 1024L)).isFalse()
    }

    @Test
    fun `canEdit accepts files with unknown size`() {
        // A `null` size is what the file browser sees for a remote
        // path the SFTP server has not statted yet. The editor opens
        // it and refuses on first read if the cap is exceeded.
        val editor = RemoteFileEditor()
        assertThat(editor.canEdit(null)).isTrue()
    }
}
