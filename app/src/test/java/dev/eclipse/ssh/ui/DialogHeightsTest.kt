package dev.eclipse.ssh.ui

import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The dialog-body height caps, as fractions of the screen rather than fixed dp.
 *
 * The fixed values these replaced (320-620dp) were sized for one phone. These tests pin the
 * arithmetic that replaced them: on a normal modern phone (891dp, the height the layout Robolectric
 * suites use) every cap genuinely grew, and on a short one (480dp) it shrinks below the old fixed
 * value - which that screen could never have shown anyway, since a 420dp dialog body plus its
 * title and buttons does not fit a 480dp window in the first place.
 */
class DialogHeightsTest {

    @Test
    fun `on a tall phone every dialog cap is higher than the fixed value it replaced`() {
        val screen = 891f
        assertThat(dialogBodyMaxHeight(screen, 0.45f).value).isGreaterThan(320f)
        assertThat(dialogBodyMaxHeight(screen, 0.60f).value).isGreaterThan(420f)
        assertThat(dialogBodyMaxHeight(screen, 0.60f).value).isGreaterThan(380f)
        assertThat(dialogBodyMaxHeight(screen, 0.85f).value).isGreaterThan(620f)
    }

    @Test
    fun `on a short screen a cap shrinks below the fixed value that never fit it anyway`() {
        assertThat(dialogBodyMaxHeight(480f, 0.60f)).isEqualTo(288.dp)
        assertThat(dialogBodyMaxHeight(480f, 0.85f).value).isLessThan(620f)
    }

    @Test
    fun `the monospace preview body fills its window without covering it`() {
        // The preview is a window of its own now, so this fraction is of what the reader is looking
        // at rather than of a sheet, and 0.7 has to carry two claims at once: more than the 0.55 the
        // body was capped at inside a sheet — which is what the promotion bought, a log no longer
        // read through a slot — and less than the window itself, so the title bar above the text and
        // the actions below it stay on screen with the text they belong to.
        val screen = 891f
        assertThat(dialogBodyMaxHeight(screen, 0.7f).value)
            .isGreaterThan(dialogBodyMaxHeight(screen, 0.55f).value)
        assertThat(dialogBodyMaxHeight(screen, 0.7f).value)
            .isLessThan(dialogBodyMaxHeight(screen, 1.0f).value)
    }
}
