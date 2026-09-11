package dev.eclipse.ssh.ui.editor.indent

import androidx.compose.ui.text.TextRange
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The indentation engine's contract, which is that it proposes text and never surprises.
 *
 * Every function is a pure map from (text, caret) to (replacement text, caret), so the tests
 * are hand-built strings with the caret walked to the interesting position. What they pin:
 * Enter deepens after an opener and not before its closer, auto-close fires only at word
 * boundaries and never over an existing closer, and a brace-pair Enter lays out three lines
 * with the caret on the middle one. The guard cases matter as much as the firing cases - the
 * failure mode of smart indentation is not "does nothing" but "eats the user's keystroke
 * intent", which is what each null-returning rule here prevents.
 */
class IndentEngineTest {

    private val config = IndentConfig.DEFAULT

    @Test
    fun `a newline after an opening brace deepens one level`() {
        val inserted = IndentEngine.indentAfterNewline(config, "if (x) {", 8)
        assertThat(inserted).isEqualTo("\n    ")
    }

    @Test
    fun `a newline after a closing brace keeps the current level`() {
        val inserted = IndentEngine.indentAfterNewline(config, "}", 1)
        assertThat(inserted).isEqualTo("\n")
    }

    @Test
    fun `a newline typed in front of an existing closing brace keeps the current level`() {
        // The opener says "deepen", the closer sitting right after the caret says "you are
        // closing this block, not opening one". The closer wins.
        val inserted = IndentEngine.indentAfterNewline(config, "if (x) {}", 8)
        assertThat(inserted).isEqualTo("\n")
    }

    @Test
    fun `a newline after a colon deepens one level`() {
        val inserted = IndentEngine.indentAfterNewline(config, "key:", 4)
        assertThat(inserted).isEqualTo("\n    ")
    }

    @Test
    fun `a newline after a quote keeps the current level`() {
        // Strings are not blocks; deepening here would indent the continuation of every
        // multi-line string a user types.
        val inserted = IndentEngine.indentAfterNewline(config, "say \"", 5)
        assertThat(inserted).isEqualTo("\n")
    }

    @Test
    fun `a newline copies the line's own leading whitespace verbatim`() {
        // Recomputing through lineIndentLevel would read this 4-space line as level 2 and
        // re-emit 8 spaces, doubling the indent on every Enter.
        val sameLevel = IndentEngine.indentAfterNewline(config, "    return x", 12)
        assertThat(sameLevel).isEqualTo("\n    ")
        val deepened = IndentEngine.indentAfterNewline(config, "    if (x) {", 12)
        assertThat(deepened).isEqualTo("\n        ")
    }

    @Test
    fun `a newline works on a line other than the first`() {
        val text = "def f():\n    if (x) {"
        val inserted = IndentEngine.indentAfterNewline(config, text, text.length)
        assertThat(inserted).isEqualTo("\n        ")
    }

    @Test
    fun `an opening brace typed into empty text closes itself and parks the caret between`() {
        val result = IndentEngine.autoClose("", 0, '{')
        assertThat(result!!.text).isEqualTo("{}")
        assertThat(result.selection).isEqualTo(TextRange(1))
    }

    @Test
    fun `a quote typed after a space closes, but after a letter it does not`() {
        val closed = IndentEngine.autoClose("say ", 4, '"')
        assertThat(closed!!.text).isEqualTo("say \"\"")
        assertThat(closed.selection).isEqualTo(TextRange(5))
        // "don" + "'" must not become "don''": the apostrophe is inside a word.
        assertThat(IndentEngine.autoClose("don", 3, '\'')).isNull()
    }

    @Test
    fun `an opener typed just before its matching closer is a type over, not a new pair`() {
        assertThat(IndentEngine.autoClose(" ()", 2, '(')).isNull()
    }

    @Test
    fun `an opener typed in front of a word does not wrap the word`() {
        assertThat(IndentEngine.autoClose("word", 0, '"')).isNull()
        assertThat(IndentEngine.autoClose("word", 0, '(')).isNull()
    }

    @Test
    fun `a doubled opener does not close`() {
        // "{{" is typed on purpose when someone wants a run of brackets; pairing each one
        // would double the closers.
        assertThat(IndentEngine.autoClose("{", 1, '{')).isNull()
    }

    @Test
    fun `an escaped opener does not close`() {
        assertThat(IndentEngine.autoClose("a\\", 2, '"')).isNull()
    }

    @Test
    fun `an unmatched character returns no auto close result`() {
        assertThat(IndentEngine.autoClose("x", 1, 'x')).isNull()
    }

    @Test
    fun `enter inside a brace pair lays out three lines with the caret on the middle one`() {
        val result = IndentEngine.pairNewline(config, "if (x) {}", 8)
        assertThat(result!!.text).isEqualTo("if (x) {\n    \n}")
        assertThat(result.selection).isEqualTo(TextRange(13))
    }

    @Test
    fun `enter inside a square bracket pair lays out the same three lines`() {
        val result = IndentEngine.pairNewline(config, "list[]", 5)
        assertThat(result!!.text).isEqualTo("list[\n    \n]")
        assertThat(result.selection).isEqualTo(TextRange(5 + 1 + 4))
    }

    @Test
    fun `enter inside a quote pair is just a newline`() {
        // A quote pair is not a block; there is nothing to lay out inside it.
        assertThat(IndentEngine.pairNewline(config, "\"\"", 1)).isNull()
    }

    @Test
    fun `enter between characters that are not a pair does nothing`() {
        assertThat(IndentEngine.pairNewline(config, "{)", 1)).isNull()
        assertThat(IndentEngine.pairNewline(config, "{}", 0)).isNull()
        assertThat(IndentEngine.pairNewline(config, "{}", 2)).isNull()
    }

    @Test
    fun `enter inside an already indented pair deepens by one level from the pair's own indent`() {
        val result = IndentEngine.pairNewline(config, "    {}", 5)
        assertThat(result!!.text).isEqualTo("    {\n        \n    }")
        assertThat(result.selection).isEqualTo(TextRange(5 + 1 + 8))
    }

    @Test
    fun `tab insert produces the configured material`() {
        assertThat(IndentEngine.tabInsert(IndentConfig(4, true))).isEqualTo("    ")
        assertThat(IndentEngine.tabInsert(IndentConfig(2, true))).isEqualTo("  ")
        assertThat(IndentEngine.tabInsert(IndentConfig(4, false))).isEqualTo("\t")
    }

    @Test
    fun `indent for builds the configured material per level`() {
        assertThat(IndentEngine.indentFor(IndentConfig(2, true), 3)).isEqualTo("      ")
        assertThat(IndentEngine.indentFor(IndentConfig(4, false), 2)).isEqualTo("\t\t")
        // A dedent past column zero means "no indent", not a crash.
        assertThat(IndentEngine.indentFor(IndentConfig(4, true), -1)).isEmpty()
    }

    @Test
    fun `line indent level counts each tab as one and spaces in runs of two`() {
        assertThat(IndentEngine.lineIndentLevel("\t\t")).isEqualTo(2)
        assertThat(IndentEngine.lineIndentLevel("    ")).isEqualTo(2)
        assertThat(IndentEngine.lineIndentLevel("  ")).isEqualTo(1)
        assertThat(IndentEngine.lineIndentLevel("\t  ")).isEqualTo(2)
    }

    @Test
    fun `line indent level stops at the first character and ignores the rest of the line`() {
        assertThat(IndentEngine.lineIndentLevel("    x  y")).isEqualTo(2)
        assertThat(IndentEngine.lineIndentLevel("x\t\t\t")).isEqualTo(0)
        assertThat(IndentEngine.lineIndentLevel("")).isEqualTo(0)
    }
}
