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
 * The reference is glibc's `wcwidth`, because that is what `readline`, `ncurses` and every shell on the
 * far side are actually using. The tables at the end of this file are that function's own answers,
 * transcribed as a run-length encoding of its output rather than written by hand: every range boundary
 * below is a boundary where the reference's answer changes, so an off-by-one there is a transcription
 * error rather than a judgement call. Four consequences are worth stating.
 *
 * **Ambiguous is 1, never 2.** The East Asian convention that widens "ambiguous" characters is a
 * different convention from the one the far side uses, and adopting it here would widen every
 * box-drawing glyph `ncurses` draws a dialog with, every block and shade, and the private-use glyphs a
 * powerline prompt is built from. Adopting it would break every full-screen program in exchange for
 * agreeing with software this app never talks to.
 *
 * **Zero-width is the reference's own list, with the category rule behind it as a safety net.**
 * [ZERO_WIDTH_MARKS] and [ZERO_WIDTH_RANGES] together name every code point the reference calls
 * zero-width. Nothing in them is inferred, and that is the point: a rule that asks the *platform* what
 * a character is answers differently on different platforms, and this app runs on a phone. The JVM this
 * repository's own tests run on reports 147 code points as unassigned that the reference counts as
 * zero-width - Arabic and Indic vowel signs, mostly - and every one of them would silently spend a cell.
 * Naming them costs a list, and buys the same answer on every device.
 *
 * The category rule is then what is left for the one case a list cannot cover: a mark Unicode adds
 * after the list was generated is still zero-width here, rather than spending a cell until this file is
 * regenerated. It is right in that direction and can be wrong in the other, which is why the only two
 * marks the reference counts as **one** column are named in [NARROW_MARKS] instead of being left to
 * disagree: `U+1734` HANUNOO SIGN PAMUDPOD, and `U+1171E` AHOM CONSONANT SIGN MEDIAL RA.
 *
 * The `FORMAT` category is *not* taken wholesale either, because it also holds U+00AD SOFT HYPHEN and
 * the Arabic number signs, which are one column wide everywhere.
 *
 * **Emoji are the one place the standard itself has moved.** Unicode 9 widened them; a program built
 * against an older table will disagree, and this function cannot satisfy both. It follows the current
 * assignment, which is also the reference's.
 *
 * **Where the reference has no answer, and the one place this departs from it.** glibc returns -1 for a
 * very large set of code points - the C0 and C1 controls, the private use areas, the surrogates, and
 * everything unassigned - meaning "not printable" rather than a width. This function answers 1 for those,
 * because a character that has reached the grid has to go in a cell, and the protocol layer consumes the
 * control characters before any of them can. The one departure is U+2028 and U+2029, the line and
 * paragraph separators, which the reference also answers -1 for and which this counts as taking no
 * column: a program that asks for those and gets -1 does not advance for them either, so zero is the
 * answer that agrees, and one would shift the rest of a line.
 *
 * ## What acts on this
 *
 * [AnsiTerminalBuffer] acts on the `0` arm and **only** on the `0` arm: a zero-width character is
 * attached to the cell before it and does not advance the cursor. The `2` arm is transcribed and tested
 * as data, but no cell is yet marked as the right half of a wide character, so a wide character still
 * occupies one cell here and can still be drawn over its neighbour. Acting on it is a separate change -
 * it needs a continuation cell and every erase, insert and delete to treat the two cells as a unit -
 * and until it lands, do not read [WIDE_RANGES] as a claim that wide characters are right.
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
 * The two lists are the reference's answer stated exactly, and both are consulted before the category
 * rule - which is the whole reason they are lists and not a rule. The category rule is the safety net
 * for a mark newer than they are; [NARROW_MARKS] is what keeps the safety net from catching the two
 * marks that are not zero-width.
 */
private fun isZeroWidth(codePoint: Int): Boolean {
    if (codePoint in NARROW_MARKS) return false
    if (ZERO_WIDTH_MARKS.any { codePoint in it }) return true
    if (ZERO_WIDTH_RANGES.any { codePoint in it }) return true
    val type = Character.getType(codePoint)
    // Each constant is widened because Java declares these categories as `byte`, and `Int == Byte` is
    // not a comparison Kotlin will compile.
    return type == Character.NON_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt()
}

/**
 * Whether the far side counts this character as taking two columns.
 *
 * Every code point the reference answers 2 for, generated the same way the zero-width lists were, and
 * stated exactly rather than at whole-block granularity: the emoji blocks, the BMP symbols and the CJK
 * compatibility forms are all interleaved with narrow characters, so a block-wide range would widen
 * them and pull the rest of a line one cell left.
 */
private fun isWide(codePoint: Int): Boolean = WIDE_RANGES.any { codePoint in it }

/** The only two marks the reference counts as one column rather than zero. */
private val NARROW_MARKS = setOf(
    0x1734,  // HANUNOO SIGN PAMUDPOD, a spacing mark in Unicode 15.1 and a nonspacing one on an older JVM
    0x1171E, // AHOM CONSONANT SIGN MEDIAL RA
)

/**
 * Every combining mark the reference counts as zero-width, transcribed exactly. The category rule in
 * [isZeroWidth] would catch most of these on a current platform; they are here so that the answer does
 * not depend on which platform is asking.
 */
private val ZERO_WIDTH_MARKS = listOf(
    0x00300..0x0036F,
    0x00483..0x00489,
    0x00591..0x005BD,
    0x005BF,
    0x005C1..0x005C2,
    0x005C4..0x005C5,
    0x005C7,
    0x00610..0x0061A,
    0x0064B..0x0065F,
    0x00670,
    0x006D6..0x006DC,
    0x006DF..0x006E4,
    0x006E7..0x006E8,
    0x006EA..0x006ED,
    0x00711,
    0x00730..0x0074A,
    0x007A6..0x007B0,
    0x007EB..0x007F3,
    0x007FD,
    0x00816..0x00819,
    0x0081B..0x00823,
    0x00825..0x00827,
    0x00829..0x0082D,
    0x00859..0x0085B,
    0x00898..0x0089F,
    0x008CA..0x008E1,
    0x008E3..0x00902,
    0x0093A,
    0x0093C,
    0x00941..0x00948,
    0x0094D,
    0x00951..0x00957,
    0x00962..0x00963,
    0x00981,
    0x009BC,
    0x009C1..0x009C4,
    0x009CD,
    0x009E2..0x009E3,
    0x009FE,
    0x00A01..0x00A02,
    0x00A3C,
    0x00A41..0x00A42,
    0x00A47..0x00A48,
    0x00A4B..0x00A4D,
    0x00A51,
    0x00A70..0x00A71,
    0x00A75,
    0x00A81..0x00A82,
    0x00ABC,
    0x00AC1..0x00AC5,
    0x00AC7..0x00AC8,
    0x00ACD,
    0x00AE2..0x00AE3,
    0x00AFA..0x00AFF,
    0x00B01,
    0x00B3C,
    0x00B3F,
    0x00B41..0x00B44,
    0x00B4D,
    0x00B55..0x00B56,
    0x00B62..0x00B63,
    0x00B82,
    0x00BC0,
    0x00BCD,
    0x00C00,
    0x00C04,
    0x00C3C,
    0x00C3E..0x00C40,
    0x00C46..0x00C48,
    0x00C4A..0x00C4D,
    0x00C55..0x00C56,
    0x00C62..0x00C63,
    0x00C81,
    0x00CBC,
    0x00CBF,
    0x00CC6,
    0x00CCC..0x00CCD,
    0x00CE2..0x00CE3,
    0x00D00..0x00D01,
    0x00D3B..0x00D3C,
    0x00D41..0x00D44,
    0x00D4D,
    0x00D62..0x00D63,
    0x00D81,
    0x00DCA,
    0x00DD2..0x00DD4,
    0x00DD6,
    0x00E31,
    0x00E34..0x00E3A,
    0x00E47..0x00E4E,
    0x00EB1,
    0x00EB4..0x00EBC,
    0x00EC8..0x00ECE,
    0x00F18..0x00F19,
    0x00F35,
    0x00F37,
    0x00F39,
    0x00F71..0x00F7E,
    0x00F80..0x00F84,
    0x00F86..0x00F87,
    0x00F8D..0x00F97,
    0x00F99..0x00FBC,
    0x00FC6,
    0x0102D..0x01030,
    0x01032..0x01037,
    0x01039..0x0103A,
    0x0103D..0x0103E,
    0x01058..0x01059,
    0x0105E..0x01060,
    0x01071..0x01074,
    0x01082,
    0x01085..0x01086,
    0x0108D,
    0x0109D,
    0x0135D..0x0135F,
    0x01712..0x01714,
    0x01732..0x01733,
    0x01752..0x01753,
    0x01772..0x01773,
    0x017B4..0x017B5,
    0x017B7..0x017BD,
    0x017C6,
    0x017C9..0x017D3,
    0x017DD,
    0x0180B..0x0180D,
    0x0180F,
    0x01885..0x01886,
    0x018A9,
    0x01920..0x01922,
    0x01927..0x01928,
    0x01932,
    0x01939..0x0193B,
    0x01A17..0x01A18,
    0x01A1B,
    0x01A56,
    0x01A58..0x01A5E,
    0x01A60,
    0x01A62,
    0x01A65..0x01A6C,
    0x01A73..0x01A7C,
    0x01A7F,
    0x01AB0..0x01ACE,
    0x01B00..0x01B03,
    0x01B34,
    0x01B36..0x01B3A,
    0x01B3C,
    0x01B42,
    0x01B6B..0x01B73,
    0x01B80..0x01B81,
    0x01BA2..0x01BA5,
    0x01BA8..0x01BA9,
    0x01BAB..0x01BAD,
    0x01BE6,
    0x01BE8..0x01BE9,
    0x01BED,
    0x01BEF..0x01BF1,
    0x01C2C..0x01C33,
    0x01C36..0x01C37,
    0x01CD0..0x01CD2,
    0x01CD4..0x01CE0,
    0x01CE2..0x01CE8,
    0x01CED,
    0x01CF4,
    0x01CF8..0x01CF9,
    0x01DC0..0x01DFF,
    0x020D0..0x020F0,
    0x02CEF..0x02CF1,
    0x02D7F,
    0x02DE0..0x02DFF,
    0x0302A..0x0302D,
    0x03099..0x0309A,
    0x0A66F..0x0A672,
    0x0A674..0x0A67D,
    0x0A69E..0x0A69F,
    0x0A6F0..0x0A6F1,
    0x0A802,
    0x0A806,
    0x0A80B,
    0x0A825..0x0A826,
    0x0A82C,
    0x0A8C4..0x0A8C5,
    0x0A8E0..0x0A8F1,
    0x0A8FF,
    0x0A926..0x0A92D,
    0x0A947..0x0A951,
    0x0A980..0x0A982,
    0x0A9B3,
    0x0A9B6..0x0A9B9,
    0x0A9BC..0x0A9BD,
    0x0A9E5,
    0x0AA29..0x0AA2E,
    0x0AA31..0x0AA32,
    0x0AA35..0x0AA36,
    0x0AA43,
    0x0AA4C,
    0x0AA7C,
    0x0AAB0,
    0x0AAB2..0x0AAB4,
    0x0AAB7..0x0AAB8,
    0x0AABE..0x0AABF,
    0x0AAC1,
    0x0AAEC..0x0AAED,
    0x0AAF6,
    0x0ABE5,
    0x0ABE8,
    0x0ABED,
    0x0FB1E,
    0x0FE00..0x0FE0F,
    0x0FE20..0x0FE2F,
    0x101FD,
    0x102E0,
    0x10376..0x1037A,
    0x10A01..0x10A03,
    0x10A05..0x10A06,
    0x10A0C..0x10A0F,
    0x10A38..0x10A3A,
    0x10A3F,
    0x10AE5..0x10AE6,
    0x10D24..0x10D27,
    0x10EAB..0x10EAC,
    0x10EFD..0x10EFF,
    0x10F46..0x10F50,
    0x10F82..0x10F85,
    0x11001,
    0x11038..0x11046,
    0x11070,
    0x11073..0x11074,
    0x1107F..0x11081,
    0x110B3..0x110B6,
    0x110B9..0x110BA,
    0x110C2,
    0x11100..0x11102,
    0x11127..0x1112B,
    0x1112D..0x11134,
    0x11173,
    0x11180..0x11181,
    0x111B6..0x111BE,
    0x111C9..0x111CC,
    0x111CF,
    0x1122F..0x11231,
    0x11234,
    0x11236..0x11237,
    0x1123E,
    0x11241,
    0x112DF,
    0x112E3..0x112EA,
    0x11300..0x11301,
    0x1133B..0x1133C,
    0x11340,
    0x11366..0x1136C,
    0x11370..0x11374,
    0x11438..0x1143F,
    0x11442..0x11444,
    0x11446,
    0x1145E,
    0x114B3..0x114B8,
    0x114BA,
    0x114BF..0x114C0,
    0x114C2..0x114C3,
    0x115B2..0x115B5,
    0x115BC..0x115BD,
    0x115BF..0x115C0,
    0x115DC..0x115DD,
    0x11633..0x1163A,
    0x1163D,
    0x1163F..0x11640,
    0x116AB,
    0x116AD,
    0x116B0..0x116B5,
    0x116B7,
    0x1171D,
    0x1171F,
    0x11722..0x11725,
    0x11727..0x1172B,
    0x1182F..0x11837,
    0x11839..0x1183A,
    0x1193B..0x1193C,
    0x1193E,
    0x11943,
    0x119D4..0x119D7,
    0x119DA..0x119DB,
    0x119E0,
    0x11A01..0x11A0A,
    0x11A33..0x11A38,
    0x11A3B..0x11A3E,
    0x11A47,
    0x11A51..0x11A56,
    0x11A59..0x11A5B,
    0x11A8A..0x11A96,
    0x11A98..0x11A99,
    0x11C30..0x11C36,
    0x11C38..0x11C3D,
    0x11C3F,
    0x11C92..0x11CA7,
    0x11CAA..0x11CB0,
    0x11CB2..0x11CB3,
    0x11CB5..0x11CB6,
    0x11D31..0x11D36,
    0x11D3A,
    0x11D3C..0x11D3D,
    0x11D3F..0x11D45,
    0x11D47,
    0x11D90..0x11D91,
    0x11D95,
    0x11D97,
    0x11EF3..0x11EF4,
    0x11F00..0x11F01,
    0x11F36..0x11F3A,
    0x11F40,
    0x11F42,
    0x13440,
    0x13447..0x13455,
    0x16AF0..0x16AF4,
    0x16B30..0x16B36,
    0x16F4F,
    0x16F8F..0x16F92,
    0x16FE4,
    0x1BC9D..0x1BC9E,
    0x1CF00..0x1CF2D,
    0x1CF30..0x1CF46,
    0x1D167..0x1D169,
    0x1D17B..0x1D182,
    0x1D185..0x1D18B,
    0x1D1AA..0x1D1AD,
    0x1D242..0x1D244,
    0x1DA00..0x1DA36,
    0x1DA3B..0x1DA6C,
    0x1DA75,
    0x1DA84,
    0x1DA9B..0x1DA9F,
    0x1DAA1..0x1DAAF,
    0x1E000..0x1E006,
    0x1E008..0x1E018,
    0x1E01B..0x1E021,
    0x1E023..0x1E024,
    0x1E026..0x1E02A,
    0x1E08F,
    0x1E130..0x1E136,
    0x1E2AE,
    0x1E2EC..0x1E2EF,
    0x1E4EC..0x1E4EF,
    0x1E8D0..0x1E8D6,
    0x1E944..0x1E94A,
    0xE0100..0xE01EF,
)

/**
 * Every zero-width code point that is not a combining mark: the format characters, the joiners and the
 * directional marks, the Hangul fillers and the tags. Exhaustive by construction, the same way
 * [ZERO_WIDTH_MARKS] is.
 */
private val ZERO_WIDTH_RANGES = listOf(
    0x00000,  // NUL, which the width function answers before it ever reaches a table
    0x0061C,  // ARABIC LETTER MARK, the Arabic counterpart of the directional marks below
    0x00897,  // ARABIC PEPET, zero-width to the reference and unassigned in Unicode 15.1
    0x01160..0x011FF,  // the conjoining Hangul vowels and finals of the old Jamo block
    0x0180E,  // MONGOLIAN VOWEL SEPARATOR
    0x0200B..0x0200F,  // zero-width space, the two joiners, and the two directional marks
    0x02028..0x02029,  // the line and paragraph separators: the one deliberate departure, see above
    0x0202A..0x0202E,  // the directional embeddings and overrides
    0x02060..0x02064,  // word joiner and the invisible operators
    0x02066..0x0206F,  // the directional isolates, and the deprecated block that follows them
    0x03164,  // HANGUL FILLER, a choseong filler that takes no column
    0x0D7B0..0x0D7C6,  // Hangul Jamo Extended-B: the same conjoining letters as above, second block
    0x0D7CB..0x0D7FB,  // and its trailing consonants
    0x0FEFF,  // a byte-order mark
    0x0FFA0,  // HALFWIDTH HANGUL FILLER
    0x10D69..0x10D6D,  // Garay and Arabic vowel signs the reference zero-widths in their own right,
    0x10EFC,  // and this Arabic mark with them
    0x113BB..0x113C0,  // the Grantha and Tulu-Tigalari signs, and the rest of the supplementary marks:
    0x113CE,  // each of these is zero-width to the reference while Unicode 15.1 still calls it
    0x113D0,  // unassigned, which is exactly the discrepancy that makes this list explicit
    0x113D2,  // rather than leaving the answer to the platform's own tables
    0x113E1..0x113E2,
    0x11F5A,  // Kawi
    0x1611E..0x16129,  // Gurung Khema, whose vowel signs and digits the reference zero-widths
    0x1612D..0x1612F,  // and its final consonants
    0x1BCA0..0x1BCA3,  // the shorthand format controls
    0x1D173..0x1D17A,  // the musical symbol begin and end beam, tie, slur and phrase marks
    0x1E5EE..0x1E5EF,  // Ol Onal
    0xE0001,  // a language tag, and the tag characters that carry it
    0xE0020..0xE007F,  // which is the whole of what a tag is made of
)

/**
 * Every code point the reference counts as two columns, transcribed the same way. Nothing acts on this
 * yet - see the note on the `2` arm at the top of this file.
 */
private val WIDE_RANGES = listOf(
    0x01100..0x0115F,  // Hangul Jamo initial consonants
    0x0231A..0x0231B,  // the BMP symbols Unicode 9 widened, which a shell prints constantly
    0x02329..0x0232A,  // the BMP symbols Unicode 9 widened, which a shell prints constantly
    0x023E9..0x023EC,  // the BMP symbols Unicode 9 widened, which a shell prints constantly
    0x023F0..0x023F0,  // the BMP symbols Unicode 9 widened, which a shell prints constantly
    0x023F3..0x023F3,  // the BMP symbols Unicode 9 widened, which a shell prints constantly
    0x025FD..0x025FE,  // the BMP symbols Unicode 9 widened, which a shell prints constantly
    0x02614..0x02615,  // the BMP symbols Unicode 9 widened, which a shell prints constantly
    0x02630..0x02637,  // the BMP symbols Unicode 9 widened, which a shell prints constantly
    0x02648..0x02653,  // the BMP symbols Unicode 9 widened, which a shell prints constantly
    0x0267F..0x0267F,  // the BMP symbols Unicode 9 widened, which a shell prints constantly
    0x0268A..0x0268F,  // the BMP symbols Unicode 9 widened, which a shell prints constantly
    0x02693..0x02693,  // the BMP symbols Unicode 9 widened, which a shell prints constantly
    0x026A1..0x026A1,  // the BMP symbols Unicode 9 widened, which a shell prints constantly
    0x026AA..0x026AB,  // the BMP symbols Unicode 9 widened, which a shell prints constantly
    0x026BD..0x026BE,  // the BMP symbols Unicode 9 widened, which a shell prints constantly
    0x026C4..0x026C5,  // the BMP symbols Unicode 9 widened, which a shell prints constantly
    0x026CE..0x026CE,  // the BMP symbols Unicode 9 widened, which a shell prints constantly
    0x026D4..0x026D4,  // the BMP symbols Unicode 9 widened, which a shell prints constantly
    0x026EA..0x026EA,  // the BMP symbols Unicode 9 widened, which a shell prints constantly
    0x026F2..0x026F3,  // the BMP symbols Unicode 9 widened, which a shell prints constantly
    0x026F5..0x026F5,  // the BMP symbols Unicode 9 widened, which a shell prints constantly
    0x026FA..0x026FA,  // the BMP symbols Unicode 9 widened, which a shell prints constantly
    0x026FD..0x026FD,  // the BMP symbols Unicode 9 widened, which a shell prints constantly
    0x02705..0x02705,  // the BMP symbols Unicode 9 widened, which a shell prints constantly
    0x0270A..0x0270B,  // the BMP symbols Unicode 9 widened, which a shell prints constantly
    0x02728..0x02728,  // the BMP symbols Unicode 9 widened, which a shell prints constantly
    0x0274C..0x0274C,  // the BMP symbols Unicode 9 widened, which a shell prints constantly
    0x0274E..0x0274E,  // the BMP symbols Unicode 9 widened, which a shell prints constantly
    0x02753..0x02755,  // the BMP symbols Unicode 9 widened, which a shell prints constantly
    0x02757..0x02757,  // the BMP symbols Unicode 9 widened, which a shell prints constantly
    0x02795..0x02797,  // the BMP symbols Unicode 9 widened, which a shell prints constantly
    0x027B0..0x027B0,  // the BMP symbols Unicode 9 widened, which a shell prints constantly
    0x027BF..0x027BF,  // the BMP symbols Unicode 9 widened, which a shell prints constantly
    0x02B1B..0x02B1C,  // the BMP symbols Unicode 9 widened, which a shell prints constantly
    0x02B50..0x02B50,  // the BMP symbols Unicode 9 widened, which a shell prints constantly
    0x02B55..0x02B55,  // the BMP symbols Unicode 9 widened, which a shell prints constantly
    0x02E80..0x02E99,  // CJK radicals, Kangxi radicals, ideographic description
    0x02E9B..0x02EF3,  // CJK radicals, Kangxi radicals, ideographic description
    0x02F00..0x02FD5,  // CJK radicals, Kangxi radicals, ideographic description
    0x02FF0..0x03029,  // CJK radicals, Kangxi radicals, ideographic description
    0x0302E..0x0303E,  // Hangul compatibility Jamo, CJK symbols, kana, Bopomofo, CJK compatibility
    0x03041..0x03096,  // Hangul compatibility Jamo, CJK symbols, kana, Bopomofo, CJK compatibility
    0x0309B..0x030FF,  // Hangul compatibility Jamo, CJK symbols, kana, Bopomofo, CJK compatibility
    0x03105..0x0312F,  // Hangul compatibility Jamo, CJK symbols, kana, Bopomofo, CJK compatibility
    0x03131..0x03163,  // Hangul compatibility Jamo, CJK symbols, kana, Bopomofo, CJK compatibility
    0x03165..0x0318E,  // Hangul compatibility Jamo, CJK symbols, kana, Bopomofo, CJK compatibility
    0x03190..0x031E5,  // Hangul compatibility Jamo, CJK symbols, kana, Bopomofo, CJK compatibility
    0x031EF..0x0321E,  // Hangul compatibility Jamo, CJK symbols, kana, Bopomofo, CJK compatibility
    0x03220..0x0A48C,
    0x0A490..0x0A4C6,  // Yi radicals
    0x0A960..0x0A97C,  // Hangul Jamo Extended-A
    0x0AC00..0x0D7A3,  // Hangul syllables
    0x0F900..0x0FA6D,  // CJK compatibility ideographs
    0x0FA70..0x0FAD9,  // CJK compatibility ideographs
    0x0FE10..0x0FE19,  // vertical forms
    0x0FE30..0x0FE52,  // CJK compatibility forms, which the small form variants belong to
    0x0FE54..0x0FE66,  // CJK compatibility forms, which the small form variants belong to
    0x0FE68..0x0FE6B,  // CJK compatibility forms, which the small form variants belong to
    0x0FF01..0x0FF60,  // fullwidth forms
    0x0FFE0..0x0FFE6,  // fullwidth signs
    0x16FE0..0x16FE3,  // Tangut, Khitan and Nushu
    0x16FF0..0x16FF1,  // Tangut, Khitan and Nushu
    0x17000..0x187F7,  // Tangut, Khitan and Nushu
    0x18800..0x18CD5,  // Tangut, Khitan and Nushu
    0x18CFF..0x18D08,  // Tangut, Khitan and Nushu
    0x1AFF0..0x1AFF3,  // Kana extended and Kana supplement
    0x1AFF5..0x1AFFB,  // Kana extended and Kana supplement
    0x1AFFD..0x1AFFE,  // Kana extended and Kana supplement
    0x1B000..0x1B122,  // Kana extended and Kana supplement
    0x1B132..0x1B132,  // Kana extended and Kana supplement
    0x1B150..0x1B152,  // Kana extended and Kana supplement
    0x1B155..0x1B155,  // Kana extended and Kana supplement
    0x1B164..0x1B167,  // Kana extended and Kana supplement
    0x1B170..0x1B2FB,  // Kana extended and Kana supplement
    0x1D300..0x1D356,
    0x1D360..0x1D376,
    0x1F004..0x1F004,
    0x1F0CF..0x1F0CF,
    0x1F18E..0x1F18E,
    0x1F191..0x1F19A,
    0x1F200..0x1F202,
    0x1F210..0x1F23B,
    0x1F240..0x1F248,
    0x1F250..0x1F251,
    0x1F260..0x1F265,
    0x1F300..0x1F320,  // emoji: the pictographs, transport symbols, emoticons and their supplements
    0x1F32D..0x1F335,  // emoji: the pictographs, transport symbols, emoticons and their supplements
    0x1F337..0x1F37C,  // emoji: the pictographs, transport symbols, emoticons and their supplements
    0x1F37E..0x1F393,  // emoji: the pictographs, transport symbols, emoticons and their supplements
    0x1F3A0..0x1F3CA,  // emoji: the pictographs, transport symbols, emoticons and their supplements
    0x1F3CF..0x1F3D3,  // emoji: the pictographs, transport symbols, emoticons and their supplements
    0x1F3E0..0x1F3F0,  // emoji: the pictographs, transport symbols, emoticons and their supplements
    0x1F3F4..0x1F3F4,  // emoji: the pictographs, transport symbols, emoticons and their supplements
    0x1F3F8..0x1F43E,  // emoji: the pictographs, transport symbols, emoticons and their supplements
    0x1F440..0x1F440,  // emoji: the pictographs, transport symbols, emoticons and their supplements
    0x1F442..0x1F4FC,  // emoji: the pictographs, transport symbols, emoticons and their supplements
    0x1F4FF..0x1F53D,  // emoji: the pictographs, transport symbols, emoticons and their supplements
    0x1F54B..0x1F54E,  // emoji: the pictographs, transport symbols, emoticons and their supplements
    0x1F550..0x1F567,  // emoji: the pictographs, transport symbols, emoticons and their supplements
    0x1F57A..0x1F57A,  // emoji: the pictographs, transport symbols, emoticons and their supplements
    0x1F595..0x1F596,  // emoji: the pictographs, transport symbols, emoticons and their supplements
    0x1F5A4..0x1F5A4,  // emoji: the pictographs, transport symbols, emoticons and their supplements
    0x1F5FB..0x1F64F,  // emoji: the pictographs, transport symbols, emoticons and their supplements
    0x1F680..0x1F6C5,  // emoji: the pictographs, transport symbols, emoticons and their supplements
    0x1F6CC..0x1F6CC,  // emoji: the pictographs, transport symbols, emoticons and their supplements
    0x1F6D0..0x1F6D2,  // emoji: the pictographs, transport symbols, emoticons and their supplements
    0x1F6D5..0x1F6D7,  // emoji: the pictographs, transport symbols, emoticons and their supplements
    0x1F6DC..0x1F6DF,  // emoji: the pictographs, transport symbols, emoticons and their supplements
    0x1F6EB..0x1F6EC,  // emoji: the pictographs, transport symbols, emoticons and their supplements
    0x1F6F4..0x1F6FC,  // emoji: the pictographs, transport symbols, emoticons and their supplements
    0x1F7E0..0x1F7EB,  // emoji: the pictographs, transport symbols, emoticons and their supplements
    0x1F7F0..0x1F7F0,  // emoji: the pictographs, transport symbols, emoticons and their supplements
    0x1F90C..0x1F93A,  // emoji: the pictographs, transport symbols, emoticons and their supplements
    0x1F93C..0x1F945,  // emoji: the pictographs, transport symbols, emoticons and their supplements
    0x1F947..0x1F9FF,  // emoji: the pictographs, transport symbols, emoticons and their supplements
    0x1FA70..0x1FA7C,  // emoji: the pictographs, transport symbols, emoticons and their supplements
    0x1FA80..0x1FA89,  // emoji: the pictographs, transport symbols, emoticons and their supplements
    0x1FA8F..0x1FAC6,  // emoji: the pictographs, transport symbols, emoticons and their supplements
    0x1FACE..0x1FADC,  // emoji: the pictographs, transport symbols, emoticons and their supplements
    0x1FADF..0x1FAE9,  // emoji: the pictographs, transport symbols, emoticons and their supplements
    0x1FAF0..0x1FAF8,  // emoji: the pictographs, transport symbols, emoticons and their supplements
    0x20000..0x2A6DF,  // CJK unified ideographs extension B and beyond, up to the noncharacters
    0x2A700..0x2B739,  // CJK unified ideographs extension B and beyond, up to the noncharacters
    0x2B740..0x2B81D,  // CJK unified ideographs extension B and beyond, up to the noncharacters
    0x2B820..0x2CEA1,  // CJK unified ideographs extension B and beyond, up to the noncharacters
    0x2CEB0..0x2EBE0,  // CJK unified ideographs extension B and beyond, up to the noncharacters
    0x2EBF0..0x2EE5D,  // CJK unified ideographs extension B and beyond, up to the noncharacters
    0x2F800..0x2FA1D,  // CJK unified ideographs extension B and beyond, up to the noncharacters
    0x30000..0x3134A,  // CJK unified ideographs extension B and beyond, up to the noncharacters
    0x31350..0x323AF,  // CJK unified ideographs extension B and beyond, up to the noncharacters
)
