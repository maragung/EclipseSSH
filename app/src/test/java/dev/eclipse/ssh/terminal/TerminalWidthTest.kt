package dev.eclipse.ssh.terminal

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * The width table, and the two directions it is read in.
 *
 * This table is the agreement this emulator keeps with `wcwidth` on the far side of the pty, so what is
 * worth pinning is not that a CJK character is wide - that is the easy half - but where the agreement is
 * *not* obvious. Three kinds of case carry that weight:
 *
 *  - **the characters this app meets constantly that must stay one column wide.** Ambiguous is one
 *    column, so every box-drawing glyph `ncurses` draws a dialog with, every block and shade, and the
 *    private-use range a powerline prompt is built from are all one column. A table that widened them
 *    would break every full-screen program and every themed prompt, and would do it silently.
 *  - **every range boundary, from both sides.** An off-by-one in a table like this is invisible until a
 *    dialog is drawn with a broken border or a column of CJK text walks one cell further left on every
 *    line. `the shape of the table at its boundaries` walks them for a reader; `every run ends where the
 *    reference ends it` walks all of them, mechanically, because 131 runs of emoji and CJK interleaved
 *    with narrow characters is more than a list a person should be asked to check by eye.
 *  - **the answers that do not come from the platform.** The table is explicit precisely so that a
 *    device whose Unicode is years older than the server's answers the same as one whose Unicode is
 *    current. `a mark the platform alone would miss` is the case that pins it: on the JVM this suite
 *    runs on, those code points are *unassigned*, so only the explicit list can answer zero for them.
 *
 * Every number below is glibc's `wcwidth` under `C.UTF-8`, read off it rather than reasoned about -
 * including the two places this table deliberately does not follow it, which say so where they appear.
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
        // The directional embeddings and overrides, and the word joiner: what an editor writes into a
        // file to fix the direction of a run of text. U+2028 and U+2029 are in here too, and they are the
        // one place the table departs from the reference on purpose - it answers -1 for them, meaning
        // "not printable", and the departure is argued in the file's own documentation.
        assertAll(0, 0x2028, 0x2029, 0x202A, 0x202C, 0x202E, 0x2060, 0x2061, 0x2064)
        // The variation selectors: U+FE0F is in every `⚠️` and `❤️` any tool prints, and the
        // supplement is the astral one - the case the printer has to be handed as a single character.
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
        // U+1F680 is the one that matters for a prompt - a rocket in a status line.
        assertAll(2, 0x1F600, 0x1F680, 0x1F9E0, 0x1FA79, 0x20000, 0x2A6D6, 0x30000)
    }

    @Test
    fun `the two marks the reference counts as one column are the two exceptions`() {
        // Both are combining marks by category, and the reference counts each of them as a column, so the
        // category rule would give the wrong answer on any platform. U+1734 is the plainer case of the
        // two: a mark between two zero-width marks, and it is the one that is one column wide.
        assertAll(0, 0x1732, 0x1733)
        assertAll(1, 0x1734)
        assertAll(1, 0x1735)
        // U+1171E is a nonspacing mark in every Unicode version there has been, and still one column.
        assertAll(0, 0x1171D, 0x1171F)
        assertAll(1, 0x1171E)
    }

    @Test
    fun `a mark the platform alone would miss is still zero width`() {
        // These are all nonspacing marks in current Unicode whose `Character.getType` on the JVM this
        // suite runs on is *unassigned* - the JVM's tables are older than the reference's. Each one is a
        // real character a device would meet: an Arabic vowel sign, a Telugu nukta, a Lao sign, a
        // Mongolian free variation selector. Only the explicit list can answer zero for them, which is
        // why it is a list and not a rule, and why this test runs at all.
        assertAll(0, 0x0898, 0x08CA, 0x0C3C, 0x0ECE, 0x180F, 0x1AC1, 0x1AC5)
        // And the same case one plane up, where the mark is not assigned on *any* platform yet: the
        // reference zero-widths it and Unicode 15.1 still calls it unassigned.
        assertAll(0, 0x10D69, 0x10EFC, 0x113BB, 0x11F5A, 0x1611E, 0x1E5EE)
    }

    @Test
    fun `where the reference has no answer the table puts one column`() {
        // A very large set of code points is not printable at all and the reference answers -1 for them:
        // the C0 and C1 controls, the private use areas, the surrogates, the unassigned code points. A
        // terminal that has been handed one still has to put something in a cell, and one column is what
        // this table puts there. The control characters never reach a cell in practice - the protocol
        // layer consumes them first - but a lone surrogate or a private-use glyph can, and counting
        // either as zero is what would shift the rest of a line.
        assertAll(1, 0x0001, 0x0007, 0x001B, 0x007F, 0x0085, 0x009F)
        assertAll(1, 0xE000, 0xF8FF, 0xD800, 0xDFFF, 0x0378, 0x2FFFD)
        // The two exceptions, and the only ones: the line and paragraph separators, argued in the file.
        assertAll(0, 0x2028, 0x2029)
    }

    @Test
    fun `an unmapped astral character is one column`() {
        // A private-use character from a supplementary plane: nothing says it is wide, so it is one.
        assertAll(1, 0xF0000, 0x100000, 0x10FFFD)
    }

    @Test
    fun `the shape of the table at its boundaries`() {
        // Both sides of every boundary that a reader would doubt, in the order the code space runs. Where
        // the reference has no width for a code point at all it answers -1 and the entry says
        // `unassigned`: those are the ones `where the reference has no answer` above states the rule for.
        val boundaries = listOf(
            0x010FF to 1,  // narrow, and the Hangul initial consonants above it are not
            0x01100 to 2,
            0x0115F to 2,
            0x01160 to 0,  // the conjoining vowels and finals begin: these take no column, unlike the initials above
            0x011FF to 0,
            0x01200 to 1,
            0x02E7F to 1,  // unassigned
            0x02E80 to 2,  // the CJK radicals start here and run on to the ideographs
            0x0303E to 2,
            0x0303F to 1,  // the ideographic half fill space is one column, which is not what its name suggests
            0x03041 to 2,  // hiragana begins
            0x03163 to 2,  // a Hangul letter, two columns, and the filler right after it is not:
            0x03164 to 0,  // HANGUL FILLER, a zero-width code point inside a run of wide letters
            0x03165 to 2,  // and the letters resume as wide
            0x0321E to 2,  // the parenthesised ideographs stop one code point before the circled ones
            0x03220 to 2,
            0x04DBF to 2,  // the Yijing hexagrams are inside the wide run, not an exception to it
            0x04DC0 to 2,
            0x04E00 to 2,
            0x0A48C to 2,
            0x0A48D to 1,  // unassigned
            0x0A4C6 to 2,  // the Yi radicals are wide, and Lisu after them is not
            0x0A4D0 to 1,
            0x0A97C to 2,  // Hangul Jamo Extended-A ends
            0x0A97D to 1,  // unassigned
            0x0A980 to 0,  // JAVANESE SIGN PANYANGGA, a mark the reference counts as taking no column - the same case
            0x0A981 to 0,  // as the Hangul filler above: zero-width inside a wide run
            0x0ABFF to 1,  // unassigned
            0x0AC00 to 2,  // the precomposed syllables begin
            0x0D7A3 to 2,
            0x0D7A4 to 1,  // narrow, then Hangul Jamo Extended-B, which is conjoining and takes no column
            0x0D7B0 to 0,
            0x0D7FB to 0,
            0x0D7FC to 1,  // unassigned
            0x0F8FF to 1,  // the private use area is one column, so the compatibility ideographs after it stand out
            0x0F900 to 2,
            0x0FA6D to 2,
            0x0FA6E to 1,  // the two gaps inside them are narrow, and so are the ones after
            0x0FA70 to 2,
            0x0FE19 to 2,  // the vertical forms end
            0x0FE1A to 1,  // unassigned
            0x0FE20 to 0,  // the combining half marks take no column at all, between two wide ranges
            0x0FE2F to 0,
            0x0FE30 to 2,
            0x0FE52 to 2,  // the small form variants are wide
            0x0FE53 to 1,  // unassigned
            0x0FE54 to 2,
            0x0FE66 to 2,
            0x0FE67 to 1,  // one more gap, then the small equals sign
            0x0FE68 to 2,
            0x0FE6B to 2,
            0x0FEFF to 0,  // a byte order mark, one code point short of the fullwidth forms
            0x0FF00 to 1,  // unassigned
            0x0FF01 to 2,  // the fullwidth forms begin - every one of them two columns
            0x0FF60 to 2,
            0x0FF61 to 1,  // the halfwidth forms are narrow: all of them but the Hangul filler at U+FFA0
            0x0FFA0 to 0,
            0x0FFDF to 1,  // unassigned
            0x0FFE0 to 2,  // the fullwidth signs, with the halfwidth won sign after them narrow again
            0x0FFE6 to 2,
            0x0FFE7 to 1,  // unassigned
            0x1F2FF to 1,  // unassigned
            0x1F300 to 2,  // the emoticons begin, and the runs inside them have gaps:
            0x1F320 to 2,
            0x1F321 to 1,  // which is what a gap in an emoji block looks like
            0x1F64F to 2,
            0x1F650 to 1,  // the ornamental dingbats are narrow, and so is the whole symbols block after them
            0x1F8FF to 1,  // unassigned
            0x1F900 to 1,  // one column: the wide run inside the supplemental symbols starts nine code points later
            0x1F90C to 2,
            0x1F93A to 2,
            0x1F93B to 1,  // a single narrow code point splits the run
            0x1F93C to 2,
            0x1F9FF to 2,
            0x1FA00 to 1,  // chess symbols are narrow: a dingbat block, not an emoji one
            0x1FA6F to 1,  // unassigned
            0x1FA70 to 2,  // then the symbols and pictographs extended-A
            0x1FA7C to 2,
            0x1FA7D to 1,  // unassigned
            0x2A6DF to 2,  // the first ideographic plane, and the unassigned run after it inside the same plane
            0x2A6E0 to 1,  // unassigned
            0x3134A to 2,  // the last assigned ideograph of the third plane
            0x3134B to 1,  // unassigned
            0x31350 to 2,
            0x323AF to 2,
            0x323B0 to 1,  // unassigned
            0xE0001 to 0,  // a language tag: zero-width, and astral, which is the case the printer must be handed whole
            0xE0002 to 1,  // unassigned
            0xE0020 to 0,  // the tag characters take no column either, and the last of them is the end of the table
            0xE007F to 0,
            0xE0080 to 1,  // unassigned
        )

        boundaries.forEach { (codePoint, expected) ->
            assertWithMessage("U+%04X".format(codePoint))
                .that(terminalCharWidth(codePoint))
                .isEqualTo(expected)
        }
    }

    @Test
    fun `every run ends where the reference ends it`() {
        // The same walk, but over every run of both tables and from both sides - the code point before a
        // run, the two endpoints, and the one after - so that a run which is one code point too long or
        // too short cannot pass. That is the failure a generated table has, and there are 161 runs of
        // zero-width and two-column code points here because the reference's own runs stop and start
        // wherever the assigned characters do. Written out rather than derived, because a test that read
        // the table it is testing would agree with it for free.
        val probes = listOf(
            0x00000 to 0, 0x0061B to 1, 0x0061C to 0, 0x0061D to 1, 0x00897 to 0, 0x00898 to 0,
            0x010FF to 1, 0x01100 to 2, 0x0115F to 2, 0x01160 to 0, 0x011FF to 0, 0x01200 to 1,
            0x0180D to 0, 0x0180E to 0, 0x0180F to 0, 0x0200A to 1, 0x0200B to 0, 0x0200F to 0,
            0x02010 to 1, 0x02027 to 1, 0x0202A to 0, 0x0202E to 0, 0x0202F to 1, 0x0205F to 1,
            0x02060 to 0, 0x02064 to 0, 0x02066 to 0, 0x0206F to 0, 0x02070 to 1, 0x02319 to 1,
            0x0231A to 2, 0x0231B to 2, 0x0231C to 1, 0x02328 to 1, 0x02329 to 2, 0x0232A to 2,
            0x0232B to 1, 0x023E8 to 1, 0x023E9 to 2, 0x023EC to 2, 0x023ED to 1, 0x023EF to 1,
            0x023F0 to 2, 0x023F1 to 1, 0x023F2 to 1, 0x023F3 to 2, 0x023F4 to 1, 0x025FC to 1,
            0x025FD to 2, 0x025FE to 2, 0x025FF to 1, 0x02613 to 1, 0x02614 to 2, 0x02615 to 2,
            0x02616 to 1, 0x0262F to 1, 0x02630 to 2, 0x02637 to 2, 0x02638 to 1, 0x02647 to 1,
            0x02648 to 2, 0x02653 to 2, 0x02654 to 1, 0x0267E to 1, 0x0267F to 2, 0x02680 to 1,
            0x02689 to 1, 0x0268A to 2, 0x0268F to 2, 0x02690 to 1, 0x02692 to 1, 0x02693 to 2,
            0x02694 to 1, 0x026A0 to 1, 0x026A1 to 2, 0x026A2 to 1, 0x026A9 to 1, 0x026AA to 2,
            0x026AB to 2, 0x026AC to 1, 0x026BC to 1, 0x026BD to 2, 0x026BE to 2, 0x026BF to 1,
            0x026C3 to 1, 0x026C4 to 2, 0x026C5 to 2, 0x026C6 to 1, 0x026CD to 1, 0x026CE to 2,
            0x026CF to 1, 0x026D3 to 1, 0x026D4 to 2, 0x026D5 to 1, 0x026E9 to 1, 0x026EA to 2,
            0x026EB to 1, 0x026F1 to 1, 0x026F2 to 2, 0x026F3 to 2, 0x026F4 to 1, 0x026F5 to 2,
            0x026F6 to 1, 0x026F9 to 1, 0x026FA to 2, 0x026FB to 1, 0x026FC to 1, 0x026FD to 2,
            0x026FE to 1, 0x02704 to 1, 0x02705 to 2, 0x02706 to 1, 0x02709 to 1, 0x0270A to 2,
            0x0270B to 2, 0x0270C to 1, 0x02727 to 1, 0x02728 to 2, 0x02729 to 1, 0x0274B to 1,
            0x0274C to 2, 0x0274D to 1, 0x0274E to 2, 0x0274F to 1, 0x02752 to 1, 0x02753 to 2,
            0x02755 to 2, 0x02756 to 1, 0x02757 to 2, 0x02758 to 1, 0x02794 to 1, 0x02795 to 2,
            0x02797 to 2, 0x02798 to 1, 0x027AF to 1, 0x027B0 to 2, 0x027B1 to 1, 0x027BE to 1,
            0x027BF to 2, 0x027C0 to 1, 0x02B1A to 1, 0x02B1B to 2, 0x02B1C to 2, 0x02B1D to 1,
            0x02B4F to 1, 0x02B50 to 2, 0x02B51 to 1, 0x02B54 to 1, 0x02B55 to 2, 0x02B56 to 1,
            0x02E80 to 2, 0x02E99 to 2, 0x02E9B to 2, 0x02EF3 to 2, 0x02F00 to 2, 0x02FD5 to 2,
            0x02FF0 to 2, 0x03029 to 2, 0x0302A to 0, 0x0302D to 0, 0x0302E to 2, 0x0303E to 2,
            0x0303F to 1, 0x03041 to 2, 0x03096 to 2, 0x0309A to 0, 0x0309B to 2, 0x030FF to 2,
            0x03105 to 2, 0x0312F to 2, 0x03131 to 2, 0x03163 to 2, 0x03164 to 0, 0x03165 to 2,
            0x0318E to 2, 0x03190 to 2, 0x031E5 to 2, 0x031EF to 2, 0x0321E to 2, 0x03220 to 2,
            0x0A48C to 2, 0x0A490 to 2, 0x0A4C6 to 2, 0x0A95F to 1, 0x0A960 to 2, 0x0A97C to 2,
            0x0AC00 to 2, 0x0D7A3 to 2, 0x0D7B0 to 0, 0x0D7C6 to 0, 0x0D7CB to 0, 0x0D7FB to 0,
            0x0F8FF to 1, 0x0F900 to 2, 0x0FA6D to 2, 0x0FA70 to 2, 0x0FAD9 to 2, 0x0FE0F to 0,
            0x0FE10 to 2, 0x0FE19 to 2, 0x0FE2F to 0, 0x0FE30 to 2, 0x0FE52 to 2, 0x0FE54 to 2,
            0x0FE66 to 2, 0x0FE68 to 2, 0x0FE6B to 2, 0x0FEFF to 0, 0x0FF01 to 2, 0x0FF60 to 2,
            0x0FF61 to 1, 0x0FF9F to 1, 0x0FFA0 to 0, 0x0FFA1 to 1, 0x0FFE0 to 2, 0x0FFE6 to 2,
            0x10D69 to 0, 0x10D6D to 0, 0x10D6E to 1, 0x10EFC to 0, 0x10EFD to 0, 0x113BA to 1,
            0x113BB to 0, 0x113C0 to 0, 0x113CD to 1, 0x113CE to 0, 0x113CF to 1, 0x113D0 to 0,
            0x113D1 to 1, 0x113D2 to 0, 0x113D3 to 1, 0x113E1 to 0, 0x113E2 to 0, 0x11F59 to 1,
            0x11F5A to 0, 0x1611D to 1, 0x1611E to 0, 0x16129 to 0, 0x1612A to 1, 0x1612C to 1,
            0x1612D to 0, 0x1612F to 0, 0x16130 to 1, 0x16FE0 to 2, 0x16FE3 to 2, 0x16FE4 to 0,
            0x16FF0 to 2, 0x16FF1 to 2, 0x17000 to 2, 0x187F7 to 2, 0x18800 to 2, 0x18CD5 to 2,
            0x18CFF to 2, 0x18D08 to 2, 0x1AFF0 to 2, 0x1AFF3 to 2, 0x1AFF5 to 2, 0x1AFFB to 2,
            0x1AFFD to 2, 0x1AFFE to 2, 0x1B000 to 2, 0x1B122 to 2, 0x1B132 to 2, 0x1B150 to 2,
            0x1B152 to 2, 0x1B155 to 2, 0x1B164 to 2, 0x1B167 to 2, 0x1B170 to 2, 0x1B2FB to 2,
            0x1BC9F to 1, 0x1BCA0 to 0, 0x1BCA3 to 0, 0x1D172 to 1, 0x1D173 to 0, 0x1D17A to 0,
            0x1D17B to 0, 0x1D300 to 2, 0x1D356 to 2, 0x1D360 to 2, 0x1D376 to 2, 0x1D377 to 1,
            0x1E5ED to 1, 0x1E5EE to 0, 0x1E5EF to 0, 0x1E5F0 to 1, 0x1F003 to 1, 0x1F004 to 2,
            0x1F005 to 1, 0x1F0CE to 1, 0x1F0CF to 2, 0x1F18D to 1, 0x1F18E to 2, 0x1F18F to 1,
            0x1F190 to 1, 0x1F191 to 2, 0x1F19A to 2, 0x1F19B to 1, 0x1F1FF to 1, 0x1F200 to 2,
            0x1F202 to 2, 0x1F210 to 2, 0x1F23B to 2, 0x1F240 to 2, 0x1F248 to 2, 0x1F250 to 2,
            0x1F251 to 2, 0x1F260 to 2, 0x1F265 to 2, 0x1F300 to 2, 0x1F320 to 2, 0x1F321 to 1,
            0x1F32C to 1, 0x1F32D to 2, 0x1F335 to 2, 0x1F336 to 1, 0x1F337 to 2, 0x1F37C to 2,
            0x1F37D to 1, 0x1F37E to 2, 0x1F393 to 2, 0x1F394 to 1, 0x1F39F to 1, 0x1F3A0 to 2,
            0x1F3CA to 2, 0x1F3CB to 1, 0x1F3CE to 1, 0x1F3CF to 2, 0x1F3D3 to 2, 0x1F3D4 to 1,
            0x1F3DF to 1, 0x1F3E0 to 2, 0x1F3F0 to 2, 0x1F3F1 to 1, 0x1F3F3 to 1, 0x1F3F4 to 2,
            0x1F3F5 to 1, 0x1F3F7 to 1, 0x1F3F8 to 2, 0x1F43E to 2, 0x1F43F to 1, 0x1F440 to 2,
            0x1F441 to 1, 0x1F442 to 2, 0x1F4FC to 2, 0x1F4FD to 1, 0x1F4FE to 1, 0x1F4FF to 2,
            0x1F53D to 2, 0x1F53E to 1, 0x1F54A to 1, 0x1F54B to 2, 0x1F54E to 2, 0x1F54F to 1,
            0x1F550 to 2, 0x1F567 to 2, 0x1F568 to 1, 0x1F579 to 1, 0x1F57A to 2, 0x1F57B to 1,
            0x1F594 to 1, 0x1F595 to 2, 0x1F596 to 2, 0x1F597 to 1, 0x1F5A3 to 1, 0x1F5A4 to 2,
            0x1F5A5 to 1, 0x1F5FA to 1, 0x1F5FB to 2, 0x1F64F to 2, 0x1F650 to 1, 0x1F67F to 1,
            0x1F680 to 2, 0x1F6C5 to 2, 0x1F6C6 to 1, 0x1F6CB to 1, 0x1F6CC to 2, 0x1F6CD to 1,
            0x1F6CF to 1, 0x1F6D0 to 2, 0x1F6D2 to 2, 0x1F6D3 to 1, 0x1F6D4 to 1, 0x1F6D5 to 2,
            0x1F6D7 to 2, 0x1F6DC to 2, 0x1F6DF to 2, 0x1F6E0 to 1, 0x1F6EA to 1, 0x1F6EB to 2,
            0x1F6EC to 2, 0x1F6F3 to 1, 0x1F6F4 to 2, 0x1F6FC to 2, 0x1F7E0 to 2, 0x1F7EB to 2,
            0x1F7F0 to 2, 0x1F90B to 1, 0x1F90C to 2, 0x1F93A to 2, 0x1F93B to 1, 0x1F93C to 2,
            0x1F945 to 2, 0x1F946 to 1, 0x1F947 to 2, 0x1F9FF to 2, 0x1FA00 to 1, 0x1FA70 to 2,
            0x1FA7C to 2, 0x1FA80 to 2, 0x1FA89 to 2, 0x1FA8F to 2, 0x1FAC6 to 2, 0x1FACE to 2,
            0x1FADC to 2, 0x1FADF to 2, 0x1FAE9 to 2, 0x1FAF0 to 2, 0x1FAF8 to 2, 0x20000 to 2,
            0x2A6DF to 2, 0x2A700 to 2, 0x2B739 to 2, 0x2B740 to 2, 0x2B81D to 2, 0x2B820 to 2,
            0x2CEA1 to 2, 0x2CEB0 to 2, 0x2EBE0 to 2, 0x2EBF0 to 2, 0x2EE5D to 2, 0x2F800 to 2,
            0x2FA1D to 2, 0x30000 to 2, 0x3134A to 2, 0x31350 to 2, 0x323AF to 2, 0xE0001 to 0,
            0xE0020 to 0, 0xE007F to 0,
        )

        probes.forEach { (codePoint, expected) ->
            assertWithMessage("U+%04X".format(codePoint))
                .that(terminalCharWidth(codePoint))
                .isEqualTo(expected)
        }
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
