package dev.eclipse.ssh.terminal

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * The width table, and the two directions it is read in.
 *
 * This table is the agreement this emulator keeps with `wcwidth` on the far side of the pty, so what is
 * worth pinning is not that a CJK character is wide - that is the easy half - but where the agreement is
 * *not* obvious. Two kinds of case carry that weight:
 *
 *  - **the characters this app meets constantly that must stay one column wide.** Ambiguous is one
 *    column, so every box-drawing glyph `ncurses` draws a dialog with, every block and shade, and the
 *    private-use range a powerline prompt is built from are all one column. A table that widened them
 *    would break every full-screen program and every themed prompt, and would do it silently.
 *  - **every range boundary.** An off-by-one in a table like this is invisible until a dialog is drawn
 *    with a broken border or a column of CJK text walks one cell to the left per line.
 *
 * Every code point asserted here is long established in Unicode, deliberately: [Character.getType] is
 * the *host* JVM's category data in a JVM test and the *device's* on a phone, so a mark Unicode added
 * recently could answer differently in the two places. The one case where that matters in production -
 * a mark with no base under it - is covered in the buffer's own tests.
 */
class TerminalWidthTest {
    private fun assertAll(expected: Int, vararg codePoints: Int) {
        codePoints.forEach { codePoint ->
            assertWithMessage("U+%04X".format(codePoint))
                .that(terminalCharWidth(codePoint))
                .isEqualTo(expected)
        }
    }

    @Test
    fun `a combining mark takes no column of its own`() {
        // The accents, a Cyrillic sign, a Greek mark and the combining half marks: the characters that
        // arrive when text is decomposed rather than precomposed, which is what a filename written on
        // macOS carries and what a paste from many editors produces.
        assertAll(0, 0x0300, 0x0301, 0x0302, 0x0308, 0x0327, 0x0345, 0x0483, 0x05B0, 0x0E31, 0x20D0, 0xFE20)
    }

    @Test
    fun `the invisible characters a shell emits take no column`() {
        // Zero-width space, the two joiners, and the directional marks: the joiner is what every
        // composed emoji is built from, so it arrives from any tool that prints one.
        assertAll(0, 0x200B, 0x200C, 0x200D, 0x200E, 0x200F)
        // The embedding and override controls, and the word joiner - what an editor writes into a file
        // to fix the direction of a run of text.
        assertAll(0, 0x2028, 0x202A, 0x202C, 0x202E, 0x2060, 0x2061, 0x2064)
        // The variation selectors: U+FE0F is in every `⚠️` and `❤️` any tool prints, and the supplement
        // is the astral one - the case the printer has to be handed as a single character.
        assertAll(0, 0xFE00, 0xFE0F, 0xE0100, 0xE0101, 0xE01EF)
    }

    @Test
    fun `a byte order mark takes no column, and neither does a null`() {
        // A BOM is what `cat` of a file written on Windows puts at the head of a line. Counting it as a
        // column shifts the whole line right by one, which is how a shell prompt ends up with its first
        // character gone.
        assertAll(0, 0xFEFF, 0)
    }

    @Test
    fun `the ambiguous characters this app meets stay one column wide`() {
        // Box drawing and blocks: what ncurses draws every dialog, menu and border with. Widening these
        // is the single most destructive thing a width table can do to a terminal.
        assertAll(1, 0x2500, 0x2502, 0x250C, 0x2510, 0x2514, 0x2518, 0x253C, 0x2550, 0x2551, 0x2554)
        assertAll(1, 0x2580, 0x2584, 0x2588, 0x258C, 0x2590, 0x2591, 0x2592, 0x2593, 0x25A0, 0x25B2)
        // The marks a script or a prompt prints: arrows, a tick, circles, bullets, and a star.
        assertAll(1, 0x2190, 0x2191, 0x2192, 0x2193, 0x2713, 0x25CF, 0x25CB, 0x2022, 0x2605, 0x2606)
        // The powerline private-use range, which every themed prompt is drawn from.
        assertAll(1, 0xE0A0, 0xE0B0, 0xE0B1, 0xE0B2, 0xE0B3)
        // Latin-1, Greek and Cyrillic letters and punctuation, including the ones in the existing
        // `plainText matches the straightforward rendering` case: `sandi-üñïçø∂é`.
        assertAll(1, 0x00FC, 0x00F1, 0x00EF, 0x00E7, 0x00F8, 0x00E9, 0x2202, 0x00B0, 0x00D7, 0x00F7)
        assertAll(1, 0x03B1, 0x03C9, 0x0430, 0x044F)
    }

    @Test
    fun `braille is one column, which is what the spinner alphabet is`() {
        // Wide in no terminal, and it is what a progress spinner prints when it is not using a
        // powerline glyph. Treating the block as wide would put every spinner's frame one cell further
        // right than the one before it.
        assertAll(1, 0x2800, 0x2801, 0x2807, 0x280B, 0x28FF)
    }

    @Test
    fun `the format characters that are not zero width stay one column`() {
        // The category is not the rule: SOFT HYPHEN, the Arabic number signs and the Syriac abbreviation
        // mark are all format characters, and every terminal counts each of them as one column. Taking
        // the whole category as zero-width would pull every Arabic line one cell left per number sign.
        assertAll(1, 0x00AD, 0x0600, 0x0605, 0x06DD, 0x070F, 0x08E2)
    }

    @Test
    fun `wide characters take two columns`() {
        assertAll(2, 0x1100, 0x4E2D, 0x6587, 0x3042, 0x30AB, 0xAC00, 0xD55C)
        assertAll(2, 0xFF01, 0xFF21, 0xFF5E, 0xFFE0, 0xFFE5)
        // Two columns of emoji, which is where the standard itself moved: Unicode 9 widened them, so a
        // program built against an older table will disagree with this. Pinned as the current answer.
        assertAll(2, 0x1F600, 0x1F680, 0x1F9E0, 0x1FA79, 0x20000, 0x2A6D6, 0x30000)
    }

    @Test
    fun `every range boundary is where the table says it is`() {
        // Both endpoints of every range plus the code point on either side, because an off-by-one is the
        // failure a table like this actually has. Adjacent ranges are stated as they are: an endpoint
        // whose neighbour is the start of the next range is still two columns, and the ones below are
        // the boundaries where the answer genuinely changes.
        val boundaries = listOf(
            0x10FF to 1, 0x1100 to 2, 0x115F to 2,
            // Above the initial consonants the conjoining vowels begin, which take no column at all.
            0x1160 to 0, 0x11FF to 0, 0x1200 to 1,
            0x2E7F to 1, 0x2E80 to 2, 0x303E to 2,
            // The ideographic half fill space and the unassigned code point after it are both narrow.
            0x303F to 1, 0x3040 to 1, 0x3041 to 2,
            0x33FF to 2, 0x3400 to 2, 0x4DBF to 2,
            // The Yijing hexagrams sit between two ideograph blocks and are narrow.
            0x4DC0 to 1, 0x4E00 to 2, 0x9FFF to 2,
            // Yi follows the ideographs and is wide too, so the boundary here is not visible from either
            // side. The one after it is: Lisu is narrow.
            0xA000 to 2, 0xA4CF to 2, 0xA4D0 to 1,
            0xA95F to 1, 0xA960 to 2, 0xA97F to 2, 0xA980 to 1,
            0xABFF to 1, 0xAC00 to 2, 0xD7A3 to 2,
            // Hangul Jamo extended-B is narrow, which is what makes the syllable boundary visible.
            0xD7A4 to 1, 0xF8FF to 1, 0xF900 to 2, 0xFAFF to 2, 0xFB00 to 1,
            0xFE0F to 0, 0xFE10 to 2, 0xFE19 to 2,
            // The combining half marks take no column; above them the CJK compatibility forms are wide
            // and the small form variants after those are narrow.
            0xFE1A to 1, 0xFE20 to 0, 0xFE2F to 0, 0xFE30 to 2, 0xFE4F to 2, 0xFE50 to 1,
            0xFEFE to 1, 0xFEFF to 0, 0xFF01 to 2, 0xFF60 to 2,
            // The halfwidth forms run from here to nearly the end of the block, and are narrow.
            0xFF61 to 1, 0xFFDF to 1, 0xFFE0 to 2, 0xFFE6 to 2, 0xFFE7 to 1,
            0x1F2FF to 1, 0x1F300 to 2, 0x1F64F to 2,
            // Ornamental dingbats and the enclosed alphanumerics after the emoticons are narrow.
            0x1F650 to 1, 0x1F8FF to 1, 0x1F900 to 2, 0x1F9FF to 2,
            // Chess symbols are narrow, the extended-A emoji block is wide.
            0x1FA00 to 1, 0x1FA6F to 1, 0x1FA70 to 2, 0x1FAFF to 2, 0x1FB00 to 1,
            0x1FFFF to 1, 0x20000 to 2,
            // The supplementary planes stop at the noncharacters, two code points short of the end.
            0x2FFFD to 2, 0x2FFFE to 1, 0x30000 to 2, 0x3FFFD to 2, 0x3FFFE to 1,
        )

        boundaries.forEach { (codePoint, expected) ->
            assertWithMessage("U+%04X".format(codePoint))
                .that(terminalCharWidth(codePoint))
                .isEqualTo(expected)
        }
    }

    @Test
    fun `an unmapped astral character is one column`() {
        // A private-use character from a supplementary plane: nothing says it is wide, so it is one.
        assertAll(1, 0xF0000, 0x100000, 0x10FFFD)
    }

    // --- terminalCellText: the same table, read the other way ---

    @Test
    fun `terminalCellText drops the marks so a column indexes it`() {
        // The case the function exists for: `e` and its accent are one cell in two code points, and the
        // string a column indexes has to have one character for it. Written with escapes throughout,
        // because a zero-width character in a source file is one no reader can see and check.
        assertThat(terminalCellText("cafe\u0301")).isEqualTo("cafe")
        assertThat(terminalCellText("a\u200Db")).isEqualTo("ab")
        assertThat(terminalCellText("\uFEFFhello")).isEqualTo("hello")
        assertThat(terminalCellText("e\u0301\u0302\u0308x")).isEqualTo("ex")
    }

    @Test
    fun `terminalCellText leaves a surrogate pair whole`() {
        // A pair is two characters and two cells, which is what the far side counts for the emoji it
        // spells, so removing half of it would be worse than leaving it: the text would no longer be
        // the text.
        val rocket = String(Character.toChars(0x1F680))
        assertThat(terminalCellText("a${rocket}b")).isEqualTo("a${rocket}b")
        // And an unpaired surrogate is its own character, one cell as far as this can tell.
        assertThat(terminalCellText("a\uD83Db")).isEqualTo("a\uD83Db")
    }

    @Test
    fun `terminalCellText drops a zero-width character that is astral`() {
        // U+E0100 is a variation selector from the supplement block: two `Char`s, no column. Removing it
        // needs the pair to be read as one character, which is the whole reason the walk is by code point.
        val selector = String(Character.toChars(0xE0100))
        assertThat(terminalCellText("a$selector" + "b")).isEqualTo("ab")
    }

    @Test
    fun `terminalCellText leaves ordinary text alone`() {
        assertThat(terminalCellText("")).isEmpty()
        assertThat(terminalCellText("plain ascii 123")).isEqualTo("plain ascii 123")
        // Wide characters are one cell each in this buffer, so they stay one character each here.
        assertThat(terminalCellText("中文 x")).isEqualTo("中文 x")
        assertThat(terminalCellText("sandi-üñïçø∂é")).isEqualTo("sandi-üñïçø∂é")
    }
}
