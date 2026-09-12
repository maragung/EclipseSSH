package dev.eclipse.ssh.ui.editor

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the options sheet's pure delay helpers. The Tab-key insertion has its own pin in
 * [TabInsertionTest]; the key interception itself and the sheet's rows are Compose surface that
 * only a device can exercise honestly, and these are the decisions underneath.
 */
class EditorOptionsHelpersTest {

    @Test
    fun `delay stepping walks the offered choices in order`() {
        // 2 s is the default, so one step down and one step up must land on its neighbours.
        assertThat(stepAutoSaveDelay(2_000L, -1)).isEqualTo(1_000L)
        assertThat(stepAutoSaveDelay(2_000L, +1)).isEqualTo(5_000L)
    }

    @Test
    fun `delay stepping clamps at both ends`() {
        assertThat(stepAutoSaveDelay(500L, -1)).isEqualTo(500L)
        assertThat(stepAutoSaveDelay(10_000L, +1)).isEqualTo(10_000L)
    }

    @Test
    fun `a custom delay anchors its steps at the default`() {
        // A hand-edited blob can carry 3 s, which the sheet has never offered; either arrow
        // starting from 2 s (rather than guessing a neighbour) is the pinned behaviour.
        assertThat(stepAutoSaveDelay(3_000L, -1)).isEqualTo(1_000L)
        assertThat(stepAutoSaveDelay(3_000L, +1)).isEqualTo(5_000L)
    }

    @Test
    fun `the delay label names offered values and admits custom ones`() {
        assertThat(autoSaveDelayLabel(2_000L)).isEqualTo("2 s")
        assertThat(autoSaveDelayLabel(500L)).isEqualTo("0.5 s")
        assertThat(autoSaveDelayLabel(3_000L)).isEqualTo("Custom")
    }
}
