package dev.eclipse.ssh.terminal

/**
 * How many columns a character occupies on a terminal grid: 0, 1 or 2.
 *
 * This exists because a terminal shares a coordinate system with a program it cannot see. The program
 * on the far side of the pty counts columns with its own `wcwidth` when it decides how far to move the
 * cursor, what to erase, and where to write next; this emulator counts in cells. Where the two counts
 * disagree the display is wrong in a way no amount of care in the renderer can repair, because the two
 * sides are no longer talking about the same column - and the disagreement is silent, since a wrong
 * column is a perfectly legal column.
 *
 * [AnsiTerminalBuffer.put] advances one cell per character it is handed. For a character this function
 * calls wide that is one cell too few, and for one it calls zero-width it is one cell too many. Both
 * are drift, and the drift is what leaves characters standing where a program believes it erased them.
 *
 * ## Which table, and why that one
 *
 * The reference is glibc's `wcwidth`, which is what `readline`, `ncurses` and every shell on the other
 * end are actually using. Three consequences, and the first is the one that matters:
 *
 * **Ambiguous is 1, never 2.** The East Asian convention that widens "ambiguous" characters is a
 * different convention from the one the far side uses, and adopting it here would widen every
 * box-drawing glyph `ncurses` draws a dialog with, every block and shade, and the private-use glyphs a
 * powerline prompt is built from. Adopting it would break every full-screen program in exchange for
 * agreeing with software this app never talks to.
 *
 * **Zero-width comes from the character's category, not from a fixed list.** A modern `wcwidth` is
 * generated from Unicode's, so a mark Unicode added last year is zero-width there too, and
 * [Character.getType] is that same answer available on the device. Marks are taken by category because
 * there is no nonspacing mark any terminal counts as one column wide. Format characters are *not*:
 * the category contains U+00AD SOFT HYPHEN and the Arabic number signs, which are one column wide
 * everywhere, so the format characters that are zero-width are named individually below.
 *
 * **Emoji are the one place the standard itself has moved.** Unicode 9 widened them; a program built
 * against an older table will disagree, and this function cannot satisfy both. It follows the current
 * assignment.
 *
 * ## What acts on this
 *
 * [AnsiTerminalBuffer] acts on the `0` arm and **only** on the `0` arm: a zero-width character is
 * attached to the cell before it and does not advance the cursor. The `2` arm is written and tested as
 * data, but no cell is yet marked as the right half of a wide character, so a wide character still
 * occupies one cell here and can still be drawn over its neighbour. Acting on it is a separate change -
 * it needs a continuation cell and every erase, insert and delete to treat the two cells as a unit -
 * and until it lands, do not read this table's `2` arm as a claim that wide characters are right.
 *
 * [terminalCellText] is the same rule read the other way: the buffer uses it to decide what a cell
 * holds, and that function uses it to say which characters in a line of text are not cells of their
 * own.
 */
internal fun terminalCharWidth(codePoint: Int): Int = when {
    // Printable ASCII, which is the overwhelming majority of what a terminal prints and is one column
    // by definition. First because it is the case every character of ordinary output takes.
    codePoint in 0x20..0x7E -> 1
    codePoint == 0 -> 0
    isZeroWidth(codePoint) -> 0
    isWide(codePoint) -> 2
    else -> 1
}

/**
 * The same text with one character per cell, so that a column number indexes it.
 *
 * [AnsiTerminalBuffer.plainText] is the text a person reads: a cell carrying a combining mark
 * contributes its base and the mark together, because that is what makes a copy faithful. It is the
 * wrong string to look a *column* up in, since every character after such a cell then sits one place to
 * the left of the cell it belongs to - and long-press word selection is the one caller that is handed
 * a column and has to find the character under it.
 *
 * Removing the zero-width characters is what restores the alignment, and it is exact rather than
 * approximate: after this change the printer never writes a zero-width character as a cell's own base,
 * so in that text one can only ever appear as part of the cluster of the cell it was attached to. A
 * surrogate pair is left whole, because two characters is what it is and two cells is what the far side
 * counts for the emoji it spells; only a pair that is itself zero-width is removed.
 */
internal fun terminalCellText(text: String): String {
    val out = StringBuilder(text.length)
    var index = 0
    while (index < text.length) {
        val char = text[index]
        if (Character.isHighSurrogate(char) &&
            index + 1 < text.length &&
            Character.isLowSurrogate(text[index + 1])
        ) {
            if (terminalCharWidth(Character.toCodePoint(char, text[index + 1])) != 0) {
                out.append(char).append(text[index + 1])
            }
            index += 2
            continue
        }
        if (terminalCharWidth(char.code) != 0) out.append(char)
        index++
    }
    return out.toString()
}

/**
 * Whether the far side counts this character as taking no column at all.
 *
 * The category tests are the bulk of it. The explicit ranges below are the rest, and they are named
 * individually rather than taken from the `FORMAT` category, because that category also holds U+00AD
 * SOFT HYPHEN and the Arabic number signs, which are one column wide everywhere a terminal runs. Named
 * the other way round, as a guard the ranges then have to satisfy, they would also be wrong: U+2028 and
 * U+2029 are the line and paragraph separators, which the reference counts as taking no column and whose
 * category is not `FORMAT` at all. So the ranges are the authority for the code points they name.
 */
private fun isZeroWidth(codePoint: Int): Boolean {
    // Conjoining Hangul vowels and finals, which compose with the letter before them. Not a mark by
    // category, so the reference table names the range and so does this.
    if (codePoint in 0x1160..0x11FF) return true
    // Combining marks: the accents, vowel signs and diacritics that belong to the character before
    // them. This is the arm a decomposed `e` and `U+0301` arrives through. Taken by category because
    // there is no nonspacing mark any terminal counts as one column wide.
    val type = Character.getType(codePoint)
    // Each constant is widened because Java declares these categories as `byte`, and `Int == Byte` is
    // not a comparison Kotlin will compile.
    if (type == Character.NON_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt()) {
        return true
    }
    // The zero-width space and the directional marks around it, the line and paragraph separators, the
    // directional embeddings and overrides, the word joiner and the invisible operators, and a
    // byte-order mark. The format characters the reference calls zero-width that a program is at all
    // likely to emit; the deprecated U+206A..U+206F block is left out rather than claimed.
    return codePoint in 0x200B..0x200F ||
        codePoint in 0x2028..0x202E ||
        codePoint in 0x2060..0x2064 ||
        codePoint == 0xFEFF
}

/**
 * Whether the far side counts this character as taking two columns.
 *
 * East Asian Wide and Fullwidth only. Stated at whole-block granularity, which is exact for the CJK,
 * kana, Hangul and fullwidth blocks and approximate inside the emoji blocks and the supplementary
 * planes - Unicode's own assignment is not uniform there, and this arm is not acted on yet. The emoji
 * ranges are at their current assignment rather than their pre-Unicode-9 one.
 */
private fun isWide(codePoint: Int): Boolean = WIDE_RANGES.any { codePoint in it }

private val WIDE_RANGES = listOf(
    0x1100..0x115F,     // Hangul Jamo, initial consonants
    0x2E80..0x303E,     // CJK radicals, Kangxi radicals, CJK symbols and punctuation
    0x3041..0x33FF,     // Hiragana, Katakana, Bopomofo, Hangul compatibility, CJK compatibility
    0x3400..0x4DBF,     // CJK unified ideographs extension A
    0x4E00..0x9FFF,     // CJK unified ideographs
    0xA000..0xA4CF,     // Yi syllables and radicals
    0xA960..0xA97F,     // Hangul Jamo extended-A
    0xAC00..0xD7A3,     // Hangul syllables
    0xF900..0xFAFF,     // CJK compatibility ideographs
    0xFE10..0xFE19,     // Vertical forms
    0xFE30..0xFE4F,     // CJK compatibility forms - the small form variants above 0xFE50 are narrow
    0xFF00..0xFF60,     // Fullwidth forms
    0xFFE0..0xFFE6,     // Fullwidth signs
    0x1F300..0x1F64F,   // Emoji: pictographs, emoticons
    0x1F900..0x1F9FF,   // Emoji: supplemental symbols and pictographs
    0x1FA70..0x1FAFF,   // Emoji: symbols and pictographs extended-A
    0x20000..0x2FFFD,   // CJK unified ideographs extension B and beyond, up to the noncharacters
    0x30000..0x3FFFD,   // and its second plane
)
