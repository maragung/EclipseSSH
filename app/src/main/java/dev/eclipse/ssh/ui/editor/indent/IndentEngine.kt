package dev.eclipse.ssh.ui.editor.indent

import androidx.compose.ui.text.TextRange

/**
 * How the editor indents: the width of one level, and whether levels are built from spaces or
 * tabs. [indentSize] is measured in spaces and only matters when [useSpaces] is true - a tab's
 * width is a property of whoever reads the file, so a tab-indented file records no width at all.
 */
data class IndentConfig(val indentSize: Int, val useSpaces: Boolean) {

    companion object {
        /**
         * Four spaces: the median of what real-world editors ship with (2, 4, 8 are all
         * common; tab-only is not), and what every file already in this repo uses.
         */
        val DEFAULT = IndentConfig(4, true)
    }

    /** The material one indent level is made of: [indentSize] spaces, or a single tab. */
    fun oneIndent(): String =
        if (useSpaces) " ".repeat(indentSize.coerceAtLeast(0)) else "\t"
}

/**
 * The result of an edit the engine proposes: the full replacement text, and where the caret
 * lands in it.
 *
 * [selection] is the Compose [TextRange] rather than a bespoke pair, because the screen splices
 * these results straight into a [androidx.compose.ui.text.input.TextFieldValue] whose selection
 * field is exactly that type - a private range type would only add a conversion at every call
 * site. The class is a pure value holder and drags in no UI machinery, so it stays testable on
 * a bare JVM.
 */
data class AutoCloseResult(val text: String, val selection: TextRange)

/**
 * The editor's smart-indentation rules, as pure functions over strings.
 *
 * Everything here takes the text and a caret offset and returns replacement text plus a new
 * caret - no state, no TextFieldValue. That split is deliberate: the rules are the part worth
 * testing exhaustively, and keeping them free of Compose types (outside the result's
 * [TextRange]) lets the tests run as plain JVM tests.
 */
object IndentEngine {

    /** The opener/closer pairs the engine knows. Brackets pair with a different char; quotes self-pair. */
    private val CLOSERS = mapOf(
        '(' to ')',
        '[' to ']',
        '{' to '}',
        '"' to '"',
        '\'' to '\'',
        '`' to '`',
    )

    /**
     * The bracket openers alone. Where a rule applies to "{}" but not to "''" (pair-newline,
     * brace deepening), this set is what separates them: between a quote pair there is no
     * block to wrap, only text to break.
     */
    private val BRACKET_OPENERS = setOf('(', '[', '{')

    /** The quote openers, which self-close and get the word-boundary guard brackets do not. */
    private val QUOTES = setOf('"', '\'', '`')

    /**
     * Characters that deepen the next line when they are the last thing before the caret.
     * The three brackets are the C-family rule; ':' is the YAML/Python "key:" rule, and it has
     * no closer to suppress it later.
     */
    private val DEEPENERS = setOf('(', '[', '{', ':')

    /** One level of the configured indent material, repeated [level] times. */
    fun indentFor(config: IndentConfig, level: Int): String {
        // A dedent past column zero happens (odd caret moves, undo of an indent) and must mean
        // "no indent", not a negative-repeat crash in the engine.
        val depth = level.coerceAtLeast(0)
        return if (config.useSpaces) {
            " ".repeat(config.indentSize.coerceAtLeast(0) * depth)
        } else {
            "\t".repeat(depth)
        }
    }

    /**
     * Leading-whitespace indent level of a line: each leading tab counts 1, leading spaces
     * count in runs of 2.
     *
     * Why 2, and why not [IndentConfig]'s width: this measure only seeds new-file indentation
     * and the status display, so it has to say the same thing about the same file on every
     * device - tying it to the configured width would make a 4-space file read as level 4 on a
     * 1-space setup and level 1 on a 4-space one. Two is the smallest width anyone really
     * indents by, and the division floors, so a stray single space counts as nothing rather
     * than as a whole level.
     */
    fun lineIndentLevel(line: String): Int {
        var tabs = 0
        var spaces = 0
        for (c in line) {
            when (c) {
                '\t' -> tabs++
                ' ' -> spaces++
                // The first real character ends the leading run; the rest of the line is code.
                else -> return tabs + spaces / 2
            }
        }
        return tabs + spaces / 2
    }

    /**
     * The auto-indent inserted after a newline: the current line's indent, plus one level when
     * the last non-whitespace char before the caret is an opener ({ [ ( or ':') and the text
     * after the caret does not begin with the matching closer; same level otherwise (the
     * "newline before }" case). Returns the text to insert after the caret: "\n<indent>".
     *
     * The current line's indent is copied verbatim rather than recomputed through
     * [lineIndentLevel] and [indentFor]: a 4-space line under the default config would measure
     * as level 2 (spaces count in runs of two) and re-emit as 8 spaces, doubling the indent on
     * every Enter. Copying the line's own leading whitespace preserves whatever convention the
     * file already uses - tabs stay tabs, odd widths stay put - and only the deepening step
     * consults the config.
     *
     * Quotes never affect the level: a string's opening quote does not open a block, and
     * treating it as one would indent the continuation lines of every multi-line string.
     */
    fun indentAfterNewline(config: IndentConfig, text: String, caret: Int): String {
        // A stale selection can trail a programmatic edit out of range; clamping keeps the
        // engine total instead of throwing from inside a keypress handler.
        val at = caret.coerceIn(0, text.length)
        val lineStart = if (at == 0) 0 else text.lastIndexOf('\n', at - 1) + 1
        val beforeCaret = text.substring(lineStart, at)
        var indent = leadingWhitespace(beforeCaret)
        val deepener = lastNonWhitespace(beforeCaret)?.takeIf { it in DEEPENERS }
        if (deepener != null) {
            // The closer sitting right after the caret means the user pressed Enter in front of
            // it; deepening would strand the closer one level below its opener. ':' has no
            // closer, so a "key:" line always deepens.
            val closer = CLOSERS[deepener]
            val blockedByCloser = closer != null && at < text.length && text[at] == closer
            if (!blockedByCloser) {
                indent += config.oneIndent()
            }
        }
        return "\n" + indent
    }

    /**
     * The auto-close pair for a typed opener, with the caret left between the two characters;
     * null when no rule fires and the screen should insert the typed character alone.
     *
     * The guards, each with its reason:
     * - a backslash before the caret means the opener is escaped, and a closer would land
     *   outside the escape's reach;
     * - the same opener immediately before means a run like "{{" is being typed on purpose, and
     *   pairing every one of them doubles the closers;
     * - for quotes, an alphanumeric or opener character immediately before means the quote is
     *   being typed into a word or after an opening bracket ("don't" must not become "don''"),
     *   while brackets get no such guard - "f(" is the normal way to open a call;
     * - an alphanumeric character after the caret means the opener is being typed in front of a
     *   word, and wrapping that word in closers it never asked for;
     * - the matching closer already sitting after the caret is a type-over: the user is closing
     *   an existing pair, and the screen just moves over it.
     */
    fun autoClose(text: String, caret: Int, opener: Char): AutoCloseResult? {
        val closer = CLOSERS[opener] ?: return null
        val at = caret.coerceIn(0, text.length)
        val before = if (at > 0) text[at - 1] else null
        val after = if (at < text.length) text[at] else null
        if (before == '\\') return null
        if (before == opener) return null
        if (opener in QUOTES && before != null && (before.isLetterOrDigit() || before in CLOSERS.keys)) {
            return null
        }
        if (after != null && after.isLetterOrDigit()) return null
        if (after == closer) return null
        val newText = text.substring(0, at) + opener.toString() + closer.toString() + text.substring(at)
        return AutoCloseResult(newText, TextRange(at + 1))
    }

    /**
     * Enter pressed between a matching brace pair: "{|}" becomes three lines with the caret on
     * the middle one, at the pair's own indent plus one level. Brackets only - for a quote pair
     * there is no block to lay out, so Enter should simply break the line. Null when the
     * characters around the caret are not a pair.
     */
    fun pairNewline(config: IndentConfig, text: String, caret: Int): AutoCloseResult? {
        val at = caret.coerceIn(0, text.length)
        if (at <= 0 || at >= text.length) return null
        val opener = text[at - 1]
        if (opener !in BRACKET_OPENERS) return null
        if (CLOSERS.getValue(opener) != text[at]) return null
        // Same verbatim-copy reasoning as indentAfterNewline: the pair's own leading
        // whitespace, plus one configured level inside it.
        val lineStart = text.lastIndexOf('\n', at - 1) + 1
        val ownIndent = leadingWhitespace(text.substring(lineStart, at))
        val innerIndent = ownIndent + config.oneIndent()
        val inserted = "\n$innerIndent\n$ownIndent"
        val newText = text.substring(0, at) + inserted + text.substring(at)
        // End of the middle line: past the newline and the inner indent, so the next thing
        // typed lands between the pair.
        return AutoCloseResult(newText, TextRange(at + 1 + innerIndent.length))
    }

    /**
     * A Tab keypress's insertion: exactly one level of the configured material. What a Tab
     * does to a selection (replace it, or indent every line it spans) is a selection question
     * and stays in the screen, which owns the selection.
     */
    fun tabInsert(config: IndentConfig): String = config.oneIndent()

    /** The leading spaces and tabs of [line], verbatim. */
    private fun leadingWhitespace(line: String): String {
        val end = line.indexOfFirst { it != ' ' && it != '\t' }
        return if (end == -1) line else line.substring(0, end)
    }

    /** The last non-whitespace character of [text], or null when it is all whitespace. */
    private fun lastNonWhitespace(text: String): Char? {
        for (i in text.indices.reversed()) {
            if (!text[i].isWhitespace()) return text[i]
        }
        return null
    }
}
