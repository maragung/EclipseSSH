package dev.eclipse.ssh.feature.mouse

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MouseEncodingTest {

    @Test
    fun `left click at column 5 row 12 is SGR press at 5 12`() {
        val bytes = MouseEncoding.encode(MouseEncoding.Button.LEFT, 5, 12)
        val text = String(bytes, Charsets.UTF_8)
        assertThat(text).isEqualTo("\u001B[<0;5;12M")
    }

    @Test
    fun `release event uses lower-case m terminator`() {
        val bytes = MouseEncoding.encode(MouseEncoding.Button.LEFT, 1, 1, pressed = false)
        val text = String(bytes, Charsets.UTF_8)
        assertThat(text).isEqualTo("\u001B[<0;1;1m")
    }

    @Test
    fun `modifier keys are ORed into the button code`() {
        // Shift = 4, so a left click with shift is 0+4 = 4.
        val bytes = MouseEncoding.encode(
            MouseEncoding.Button.LEFT,
            1,
            1,
            modifiers = MouseEncoding.Modifiers.SHIFT,
        )
        assertThat(String(bytes, Charsets.UTF_8)).isEqualTo("\u001B[<4;1;1M")
    }

    @Test
    fun `scroll up encodes to 64`() {
        val bytes = MouseEncoding.encode(MouseEncoding.Button.SCROLL_UP, 1, 1)
        assertThat(String(bytes, Charsets.UTF_8)).isEqualTo("\u001B[<64;1;1M")
    }

    @Test
    fun `column 0 is clamped to 1`() {
        val bytes = MouseEncoding.encode(MouseEncoding.Button.LEFT, 0, 1)
        assertThat(String(bytes, Charsets.UTF_8)).isEqualTo("\u001B[<0;1;1M")
    }

    @Test
    fun `drag is encoded with the drag button code`() {
        val bytes = MouseEncoding.encode(MouseEncoding.Button.DRAG_LEFT, 1, 1)
        assertThat(String(bytes, Charsets.UTF_8)).isEqualTo("\u001B[<32;1;1M")
    }

    @Test
    fun `parseButton recognises a left click`() {
        assertThat(MouseEncoding.parseButton(0)).isEqualTo(MouseEncoding.Button.LEFT)
    }

    @Test
    fun `parseButton recognises scroll up and down`() {
        assertThat(MouseEncoding.parseButton(64)).isEqualTo(MouseEncoding.Button.SCROLL_UP)
        assertThat(MouseEncoding.parseButton(65)).isEqualTo(MouseEncoding.Button.SCROLL_DOWN)
    }

    @Test
    fun `parseButton recognises drag`() {
        assertThat(MouseEncoding.parseButton(32)).isEqualTo(MouseEncoding.Button.DRAG_LEFT)
        // 34 = 32 + 2 = right-button drag.
        assertThat(MouseEncoding.parseButton(34)).isEqualTo(MouseEncoding.Button.DRAG_RIGHT)
    }
}
