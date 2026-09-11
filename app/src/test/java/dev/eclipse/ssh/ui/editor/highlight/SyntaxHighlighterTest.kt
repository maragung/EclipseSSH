package dev.eclipse.ssh.ui.editor.highlight

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The syntax highlighter, pinned through its spans.
 *
 * The engine's contract is offset-only tokens over one forward pass, and every test here holds
 * it to exactly that: expectations are (kind, text) pairs and exact Token offsets, never colors.
 * The well-formedness checks re-verify ordering, non-overlap, non-zero length and
 * whitespace-only gaps - the structural promises the editor's AnnotatedString span application
 * rests on. The rest of the suite walks each family the engine special-cases (the generic lexer,
 * the key-value languages, XML, Markdown) plus the registry that picks the language, with the
 * behaviors the implementations actually contain pinned as written: unterminated strings stop at
 * the line but raw strings at EOF, comments beat strings and strings beat comment prefixes, and
 * the token count stays proportional to color changes through the accumulator's merging.
 */
class SyntaxHighlighterTest {

    private fun highlight(language: SyntaxLanguage, source: String) =
        SyntaxHighlighter(language).highlight(source)

    private fun spans(source: String, tokens: List<Token>) =
        tokens.map { it.kind to source.substring(it.start, it.end) }

    /**
     * The structural contract every lexer output must satisfy: tokens are ordered,
     * non-overlapping, never zero-length, within bounds, and every character not inside a
     * token is whitespace - so coloring can apply spans without double-painting or dropping
     * characters, and without wondering what the gaps contain.
     */
    private fun assertWellFormed(source: String, tokens: List<Token>) {
        var cursor = 0
        tokens.forEach { token ->
            assertThat(token.end).isGreaterThan(token.start)
            assertThat(token.start).isAtLeast(cursor)
            assertThat(token.end).isAtMost(source.length)
            for (gap in cursor until token.start) {
                assertThat(source[gap].isWhitespace()).isTrue()
            }
            cursor = token.end
        }
        for (rest in cursor until source.length) {
            assertThat(source[rest].isWhitespace()).isTrue()
        }
    }

    @Test
    fun `a Kotlin declaration yields keyword, plain, operator and number spans at exact offsets`() {
        // The editor applies color by offset onto text it already holds, so the offsets - not
        // just the words - are the contract: shift any of these by one and the wrong characters
        // light up. `val x = 1` is the smallest line that exercises four different kinds.
        val source = "val x = 1"
        val tokens = highlight(SyntaxLanguages.KOTLIN, source)
        assertThat(tokens).containsExactly(
            Token(TokenKind.KEYWORD, 0, 3),
            Token(TokenKind.PLAIN, 4, 5),
            Token(TokenKind.OPERATOR, 6, 7),
            Token(TokenKind.NUMBER, 8, 9),
        ).inOrder()
        assertThat(spans(source, tokens)).containsExactly(
            TokenKind.KEYWORD to "val",
            TokenKind.PLAIN to "x",
            TokenKind.OPERATOR to "=",
            TokenKind.NUMBER to "1",
        ).inOrder()
    }

    @Test
    fun `tokens are ordered, non-overlapping and separated only by whitespace`() {
        // The engine's structural promise, checked over a sample that exercises every core
        // branch at once: a keyword, an identifier, operators, a number, both comment kinds,
        // and a string with an escaped quote. If ordering or coverage broke anywhere in it,
        // span application would double-paint or drop characters.
        val source = "val x = 1 // set\nfun main() { /* hi */ }\nval s = \"a\\\"b\""
        assertWellFormed(source, highlight(SyntaxLanguages.KOTLIN, source))
    }

    @Test
    fun `a run of punctuation is one operator span, not one span per character`() {
        // `,,,` and `()()` paint as single colored runs because the lexer takes operator
        // characters greedily and the accumulator merges adjacency: the span count stays
        // proportional to visible color changes, not to keystrokes.
        val commas = highlight(SyntaxLanguages.KOTLIN, ",,,")
        assertThat(spans(",,,", commas)).containsExactly(TokenKind.OPERATOR to ",,,")
        val parens = highlight(SyntaxLanguages.KOTLIN, "()()")
        assertThat(spans("()()", parens)).containsExactly(TokenKind.OPERATOR to "()()")
    }

    @Test
    fun `adjacent same-kind tokens merge, and a whitespace gap is enough to keep them apart`() {
        // `#` is plain in Kotlin (not a comment, not an operator, not an identifier), so `##`
        // exercises the accumulator's merge rule: two one-character PLAIN adds become one
        // span. The space in `# #` breaks the adjacency that rule requires - merging across a
        // gap would paint the whitespace itself. Two string literals that touch (`"a""b"`)
        // merge as well, which is the same rule applied across branch boundaries, not just
        // within one greedy run.
        assertThat(spans("##", highlight(SyntaxLanguages.KOTLIN, "##")))
            .containsExactly(TokenKind.PLAIN to "##")
        assertThat(spans("# #", highlight(SyntaxLanguages.KOTLIN, "# #")))
            .containsExactly(TokenKind.PLAIN to "#", TokenKind.PLAIN to "#")
        val adjacent = "\"a\"\"b\""
        assertThat(spans(adjacent, highlight(SyntaxLanguages.KOTLIN, adjacent)))
            .containsExactly(TokenKind.STRING to adjacent)
    }

    @Test
    fun `an escaped quote does not close the string`() {
        // `"a\"b"` must be one STRING span: the escape consumed the inner quote, and a lexer
        // that closed early would strand `b"` as code - the classic half-typed-string bug.
        val source = "\"a\\\"b\""
        assertThat(spans(source, highlight(SyntaxLanguages.KOTLIN, source)))
            .containsExactly(TokenKind.STRING to source)
    }

    @Test
    fun `an unterminated string ends at the line end and the next line lexes normally`() {
        // While the user is typing a string, everything below the caret must not light up as
        // string-colored - that is the documented reason unterminated strings stop at the
        // newline. The line after is ordinary code, unpainted by the accident above it.
        val source = "\"abc\nnext = 1"
        assertThat(spans(source, highlight(SyntaxLanguages.KOTLIN, source))).containsExactly(
            TokenKind.STRING to "\"abc",
            TokenKind.PLAIN to "next",
            TokenKind.OPERATOR to "=",
            TokenKind.NUMBER to "1",
        ).inOrder()
    }

    @Test
    fun `char literals take the strict one-glyph shape, and anything else falls through`() {
        // `'c'` and the escaped `'\''` are CHAR; a Rust lifetime `'a` is deliberately NOT,
        // because the strict shape - one glyph or one escape, closed on the same line - is
        // what keeps `'a str` from being read as an unterminated char. The quote falls back
        // to operator and the name to an identifier, which is what Rust code needs.
        assertThat(spans("'c'", highlight(SyntaxLanguages.KOTLIN, "'c'")))
            .containsExactly(TokenKind.CHAR to "'c'")
        assertThat(spans("'\\''", highlight(SyntaxLanguages.KOTLIN, "'\\''")))
            .containsExactly(TokenKind.CHAR to "'\\''")
        assertThat(spans("'a", highlight(SyntaxLanguages.RUST, "'a")))
            .containsExactly(TokenKind.OPERATOR to "'", TokenKind.PLAIN to "a")
    }

    @Test
    fun `triple-quoted strings span newlines, and an unterminated one runs to end of file`() {
        // Raw strings are multi-line by definition, so the line-end rule for ordinary strings
        // must not apply: the token runs to the closing triple quote wherever it is - or to
        // EOF when the user has not typed it yet, because there is no line-local fallback.
        val closed = "\"\"\"\nraw\n\"\"\""
        assertThat(spans(closed, highlight(SyntaxLanguages.KOTLIN, closed)))
            .containsExactly(TokenKind.STRING to closed)
        val open = "\"\"\"never closed"
        assertThat(spans(open, highlight(SyntaxLanguages.KOTLIN, open)))
            .containsExactly(TokenKind.STRING to open)
    }

    @Test
    fun `a backtick raw string spans newlines in the languages that configure it`() {
        // JavaScript template literals (and Go raw strings) ride rawStringQuotes, not
        // stringQuotes: no escape processing, and the run crosses newlines to the matching
        // backtick. Languages without the flag would lex a backtick as plain - which is why
        // this test names the language it uses.
        val source = "`a\nb`"
        assertThat(spans(source, highlight(SyntaxLanguages.JAVASCRIPT, source)))
            .containsExactly(TokenKind.STRING to source)
    }

    @Test
    fun `line comments run to end of line and block comments across lines`() {
        // A line comment can never be unterminated, a block comment can - the two kinds are
        // colored differently for exactly that reason, so each must come out as its own kind,
        // and the code after the newline must be untouched by the comment above it.
        val source = "val x = 1 // note\nval y = 2"
        assertThat(spans(source, highlight(SyntaxLanguages.KOTLIN, source))).containsExactly(
            TokenKind.KEYWORD to "val",
            TokenKind.PLAIN to "x",
            TokenKind.OPERATOR to "=",
            TokenKind.NUMBER to "1",
            TokenKind.LINE_COMMENT to "// note",
            TokenKind.KEYWORD to "val",
            TokenKind.PLAIN to "y",
            TokenKind.OPERATOR to "=",
            TokenKind.NUMBER to "2",
        ).inOrder()
        val block = "/* a\nb */"
        assertThat(spans(block, highlight(SyntaxLanguages.KOTLIN, block)))
            .containsExactly(TokenKind.COMMENT to block)
    }

    @Test
    fun `a comment prefix inside a string is string, not a comment`() {
        // URLs are where every highlighter is embarrassed: `"http://x"` must stay one STRING.
        // The quote is reached before the comment branch ever sees the `//`, and the string
        // consumes it - that ordering is the whole fix for this class of bug.
        val source = "val url = \"http://x\""
        val tokens = highlight(SyntaxLanguages.KOTLIN, source)
        assertThat(spans(source, tokens)).containsExactly(
            TokenKind.KEYWORD to "val",
            TokenKind.PLAIN to "url",
            TokenKind.OPERATOR to "=",
            TokenKind.STRING to "\"http://x\"",
        ).inOrder()
        assertThat(tokens.none { it.kind == TokenKind.LINE_COMMENT }).isTrue()
    }

    @Test
    fun `a comment swallows quotes and block-comment markers, and only its own line`() {
        // `/*` inside a line comment is text, and a quote inside a block comment is text too:
        // comments are matched before strings, so neither can reopen inside one. And because
        // a line comment stops at its newline, an unterminated-looking `/*` must not swallow
        // the rest of the file - the line after is ordinary code.
        val line = "// /* not opened\nval x = 1"
        assertThat(spans(line, highlight(SyntaxLanguages.KOTLIN, line))).containsExactly(
            TokenKind.LINE_COMMENT to "// /* not opened",
            TokenKind.KEYWORD to "val",
            TokenKind.PLAIN to "x",
            TokenKind.OPERATOR to "=",
            TokenKind.NUMBER to "1",
        ).inOrder()
        val block = "/* \"str\" */"
        assertThat(spans(block, highlight(SyntaxLanguages.KOTLIN, block)))
            .containsExactly(TokenKind.COMMENT to block)
    }

    @Test
    fun `numbers cover hex, binary, fractions, exponents, separators and a leading dot`() {
        // The grammar is scanned by hand, not regexed, to keep the O(n) promise - so the
        // exact forms it accepts are worth pinning: `0x`/`0b` prefixes, `_` digit separators,
        // a fractional part only when a digit follows the dot, an exponent with an optional
        // sign, and `.5`, whose dot is checked before the operator branch can eat it.
        val source = "42 0xFF 0b1010 3.14 2.5e-3 1e10 1_000 .5"
        assertThat(spans(source, highlight(SyntaxLanguages.KOTLIN, source))).containsExactly(
            TokenKind.NUMBER to "42",
            TokenKind.NUMBER to "0xFF",
            TokenKind.NUMBER to "0b1010",
            TokenKind.NUMBER to "3.14",
            TokenKind.NUMBER to "2.5e-3",
            TokenKind.NUMBER to "1e10",
            TokenKind.NUMBER to "1_000",
            TokenKind.NUMBER to ".5",
        ).inOrder()
    }

    @Test
    fun `digits after an identifier are plain, and a minus is an operator, not a sign`() {
        // `abc123` is one plain word: the identifier branch consumed the digits before the
        // number branch could see them, which is the entire number-boundary rule. And the
        // number grammar has no sign - `-5` is an OPERATOR then a NUMBER, two spans, because
        // a greedy sign would misread `a - 5`.
        assertThat(spans("abc123", highlight(SyntaxLanguages.KOTLIN, "abc123")))
            .containsExactly(TokenKind.PLAIN to "abc123")
        assertThat(spans("-5", highlight(SyntaxLanguages.KOTLIN, "-5")))
            .containsExactly(TokenKind.OPERATOR to "-", TokenKind.NUMBER to "5")
    }

    @Test
    fun `a function signature colors the keyword and merges the empty parameter list`() {
        // `fun main()`: the keyword is the language's own vocabulary, `main` is plain (the
        // word sets hold what editors actually meet, and user names are not among them), and
        // `()` is one OPERATOR span - the greedy operator run again, keeping spans to color
        // changes.
        assertThat(spans("fun main()", highlight(SyntaxLanguages.KOTLIN, "fun main()")))
            .containsExactly(
                TokenKind.KEYWORD to "fun",
                TokenKind.PLAIN to "main",
                TokenKind.OPERATOR to "()",
            ).inOrder()
    }

    @Test
    fun `keyword beats builtin beats the capitalized heuristic`() {
        // Classification is a ladder, and each rung needs a word that would look different on
        // the rung below: `String` is a Kotlin builtin and must not fall through to TYPE_NAME,
        // `Foo` is neither and becomes TYPE_NAME through the capitalized heuristic, and Rust's
        // `println` is a builtin there. Kotlin's own `println` is deliberately absent from its
        // builtin set - the sets are editor-driven, not grammar-complete.
        assertThat(spans("Foo String", highlight(SyntaxLanguages.KOTLIN, "Foo String")))
            .containsExactly(TokenKind.TYPE_NAME to "Foo", TokenKind.BUILTIN to "String")
        assertThat(spans("println", highlight(SyntaxLanguages.RUST, "println")))
            .containsExactly(TokenKind.BUILTIN to "println")
    }

    @Test
    fun `an at-word is an annotation in the languages that switch them on`() {
        // `@Inject` reads as one ANNOTATION span, not an operator glued to an identifier:
        // that is the reason the flag exists, and the editor colors decorators separately
        // from both punctuation and names.
        assertThat(spans("@Inject", highlight(SyntaxLanguages.KOTLIN, "@Inject")))
            .containsExactly(TokenKind.ANNOTATION to "@Inject")
    }

    @Test
    fun `SQL keywords match regardless of case`() {
        // SQL is the one shipped case-insensitive language: `SELECT` and `select` are the
        // same word to a database, so they must color alike - the lookup lowercases both
        // sides rather than making the lexer branch on case.
        assertThat(spans("select", highlight(SyntaxLanguages.SQL, "select")))
            .containsExactly(TokenKind.KEYWORD to "select")
        assertThat(spans("SELECT", highlight(SyntaxLanguages.SQL, "SELECT")))
            .containsExactly(TokenKind.KEYWORD to "SELECT")
        assertThat(spans("Select", highlight(SyntaxLanguages.SQL, "Select")))
            .containsExactly(TokenKind.KEYWORD to "Select")
    }

    @Test
    fun `case-sensitive languages do not match keywords in the wrong case`() {
        // The contrast that makes the SQL test mean something. Kotlin's `VAL` is no keyword -
        // though it does not come out plain either: the capitalized heuristic catches it as a
        // TYPE_NAME, the honest reading of an uppercase word in Kotlin. Dockerfile stores its
        // keywords uppercase on purpose, so a lowercase `from` inside an argument stays plain
        // instead of lighting up.
        assertThat(spans("VAL", highlight(SyntaxLanguages.KOTLIN, "VAL")))
            .containsExactly(TokenKind.TYPE_NAME to "VAL")
        assertThat(spans("from alpine", highlight(SyntaxLanguages.DOCKERFILE, "from alpine")))
            .containsExactly(TokenKind.PLAIN to "from", TokenKind.PLAIN to "alpine")
        assertThat(spans("FROM alpine", highlight(SyntaxLanguages.DOCKERFILE, "FROM alpine")))
            .containsExactly(TokenKind.KEYWORD to "FROM", TokenKind.PLAIN to "alpine")
    }

    @Test
    fun `a YAML key colors as a keyword with its colon as the operator`() {
        // Key-value mode exists so configuration reads structurally: the name left of the
        // separator is the thing you scan for, the value is data. `value` comes out plain
        // because YAML's builtin set holds only the booleans and null-words.
        assertThat(spans("key: value", highlight(SyntaxLanguages.YAML, "key: value")))
            .containsExactly(
                TokenKind.KEYWORD to "key",
                TokenKind.OPERATOR to ":",
                TokenKind.PLAIN to "value",
            ).inOrder()
    }

    @Test
    fun `a TOML key with spaces around the equals still colors as a key`() {
        // The just-fixed case: TOML and INI write `name = "tom"`, and the space between key
        // and separator is formatting, not meaning. A word followed by spaces then a
        // separator is a key; the space must not demote `name` to plain and leave the `=` an
        // unexplained operator.
        val source = "name = \"tom\""
        assertThat(spans(source, highlight(SyntaxLanguages.TOML, source))).containsExactly(
            TokenKind.KEYWORD to "name",
            TokenKind.OPERATOR to "=",
            TokenKind.STRING to "\"tom\"",
        ).inOrder()
    }

    @Test
    fun `an INI section header colors its brackets as operators and its name as a type`() {
        // `[core]` is the one key-value line that is not a key at all: the brackets are
        // punctuation and the section name is the closest thing to a type name in the file,
        // which is how editors make sections scannable.
        assertThat(spans("[core]", highlight(SyntaxLanguages.INI, "[core]")))
            .containsExactly(
                TokenKind.OPERATOR to "[",
                TokenKind.TYPE_NAME to "core",
                TokenKind.OPERATOR to "]",
            ).inOrder()
    }

    @Test
    fun `a YAML list marker is an operator, and the key may follow it`() {
        // `- name: x`: the dash is a list marker, not a minus and not plain, and the key rule
        // still applies to what follows it - lists of mappings are half of real YAML.
        assertThat(spans("- name: x", highlight(SyntaxLanguages.YAML, "- name: x")))
            .containsExactly(
                TokenKind.OPERATOR to "-",
                TokenKind.KEYWORD to "name",
                TokenKind.OPERATOR to ":",
                TokenKind.PLAIN to "x",
            ).inOrder()
    }

    @Test
    fun `a word followed by more words is not a key, in YAML lists and Makefile recipes`() {
        // The run-then-more-words case: `- gcc -c` is a command, not `gcc` waiting for a
        // separator, and a tab-indented Makefile recipe is commands too. Nothing on these
        // lines may color as KEYWORD - a false key would repaint every compile line in the
        // file. (A line that carries no key is re-lexed from its start, so the marker's dash
        // is emitted once by the marker branch and again by the generic operator run; what is
        // pinned here is the not-a-key verdict, not the full span list.)
        val yaml = highlight(SyntaxLanguages.YAML, "- gcc -c")
        assertThat(yaml.none { it.kind == TokenKind.KEYWORD }).isTrue()
        assertThat(spans("- gcc -c", yaml)).contains(TokenKind.PLAIN to "gcc")
        val recipe = highlight(SyntaxLanguages.MAKEFILE, "\tgcc -c file.c")
        assertThat(recipe.none { it.kind == TokenKind.KEYWORD }).isTrue()
        assertThat(spans("\tgcc -c file.c", recipe)).contains(TokenKind.PLAIN to "gcc")
    }

    @Test
    fun `properties files take both separators and both comment characters`() {
        // Properties is the one language with two separators (`=` and `:`) and the one with
        // `!` comments; both separators must promote their key, or half of every .properties
        // file on earth would color wrong.
        val source = "port = 8080\n# a comment\ntag: prod"
        assertThat(spans(source, highlight(SyntaxLanguages.PROPERTIES, source))).containsExactly(
            TokenKind.KEYWORD to "port",
            TokenKind.OPERATOR to "=",
            TokenKind.NUMBER to "8080",
            TokenKind.LINE_COMMENT to "# a comment",
            TokenKind.KEYWORD to "tag",
            TokenKind.OPERATOR to ":",
            TokenKind.PLAIN to "prod",
        ).inOrder()
        assertThat(spans("! loud", highlight(SyntaxLanguages.PROPERTIES, "! loud")))
            .containsExactly(TokenKind.LINE_COMMENT to "! loud")
    }

    @Test
    fun `an XML tag colors its name, attributes, values and text distinctly`() {
        // Markup carries four vocabularies in a few characters: the tag name is a TYPE_NAME,
        // an attribute is BUILTIN, its quoted value is STRING, and the text between tags is
        // PLAIN. The closing tag repeats the pattern, and every bracket of the syntax itself
        // is an operator - including the two-character `</`.
        val source = "<tag attr=\"value\">text</tag>"
        assertThat(spans(source, highlight(SyntaxLanguages.XML, source))).containsExactly(
            TokenKind.OPERATOR to "<",
            TokenKind.TYPE_NAME to "tag",
            TokenKind.BUILTIN to "attr",
            TokenKind.OPERATOR to "=",
            TokenKind.STRING to "\"value\"",
            TokenKind.OPERATOR to ">",
            TokenKind.PLAIN to "text",
            TokenKind.OPERATOR to "</",
            TokenKind.TYPE_NAME to "tag",
            TokenKind.OPERATOR to ">",
        ).inOrder()
    }

    @Test
    fun `XML comments and CDATA are single spans`() {
        // Both are regions the parser skips entirely, so the highlighter must too - a `--`
        // inside a comment or a `<` inside CDATA must not open anything. They land in
        // different kinds because CDATA is character data, closer kin to a string than to a
        // comment.
        val comment = "<!-- not -- markup -->"
        assertThat(spans(comment, highlight(SyntaxLanguages.XML, comment)))
            .containsExactly(TokenKind.COMMENT to comment)
        val cdata = "<![CDATA[a < b]]>"
        assertThat(spans(cdata, highlight(SyntaxLanguages.XML, cdata)))
            .containsExactly(TokenKind.STRING to cdata)
    }

    @Test
    fun `an entity in XML text is lifted out of the plain run as a builtin`() {
        // `&amp;` is the one thing in element text that is not prose; the plain run breaks
        // around it so entities color like the markup vocabulary they are.
        val source = "<p>a &amp; b</p>"
        assertThat(spans(source, highlight(SyntaxLanguages.XML, source))).containsExactly(
            TokenKind.OPERATOR to "<",
            TokenKind.TYPE_NAME to "p",
            TokenKind.OPERATOR to ">",
            TokenKind.PLAIN to "a ",
            TokenKind.BUILTIN to "&amp;",
            TokenKind.PLAIN to " b",
            TokenKind.OPERATOR to "</",
            TokenKind.TYPE_NAME to "p",
            TokenKind.OPERATOR to ">",
        ).inOrder()
    }

    @Test
    fun `an unterminated XML tag runs to end of file, not end of line`() {
        // Markup has no line-oriented grammar to fall back on: a tag left open mid-edit is
        // still a tag, so the attribute name and the half-typed value keep their colors to
        // EOF - the value string included, unterminated quote and all.
        val source = "<tag attr=\"val"
        assertThat(spans(source, highlight(SyntaxLanguages.XML, source))).containsExactly(
            TokenKind.OPERATOR to "<",
            TokenKind.TYPE_NAME to "tag",
            TokenKind.BUILTIN to "attr",
            TokenKind.OPERATOR to "=",
            TokenKind.STRING to "\"val",
        ).inOrder()
    }

    @Test
    fun `a Markdown heading colors the whole line as a keyword`() {
        // The heading is the line: `# Title` is one KEYWORD span from the marker through the
        // text, because the line-anchored grammar has no separate marker concept here - the
        // editor dims the entire heading as structure. The space after `#` is required by the
        // grammar check; `#Title` would not be a heading at all.
        assertThat(spans("# Title", highlight(SyntaxLanguages.MARKDOWN, "# Title")))
            .containsExactly(TokenKind.KEYWORD to "# Title")
    }

    @Test
    fun `Markdown list markers and bold delimiters are operators`() {
        // The marker `- ` (dash and its space, as the grammar requires) is one OPERATOR span,
        // and `**bold**` puts an operator on each side of the emphasized text - emphasis is
        // structure, the word between is content. Note the marker span swallows its own
        // trailing space, so the plain run after it starts with the space before `item`.
        assertThat(spans("- item", highlight(SyntaxLanguages.MARKDOWN, "- item")))
            .containsExactly(TokenKind.OPERATOR to "- ", TokenKind.PLAIN to " item")
        assertThat(spans("**bold**", highlight(SyntaxLanguages.MARKDOWN, "**bold**")))
            .containsExactly(
                TokenKind.OPERATOR to "**",
                TokenKind.PLAIN to "bold",
                TokenKind.OPERATOR to "**",
            ).inOrder()
    }

    @Test
    fun `a fenced code block paints its fences as comments and its body as a string`() {
        // The cheapest readable rendering of "this is verbatim": fence lines are
        // LINE_COMMENT and the body STRING, so a code block reads as a unit without the
        // editor needing a language for the fenced content.
        val source = "```\ncode\n```"
        assertThat(spans(source, highlight(SyntaxLanguages.MARKDOWN, source))).containsExactly(
            TokenKind.LINE_COMMENT to "```",
            TokenKind.STRING to "code",
            TokenKind.LINE_COMMENT to "```",
        ).inOrder()
    }

    @Test
    fun `a Markdown link colors its delimiters, and adjacent brackets merge`() {
        // `[text](url)`: brackets and parens are OPERATOR, the label and target PLAIN. The
        // `](` pair lands as ONE operator span - same kind, touching offsets - which is the
        // accumulator's merge rule showing up inside the Markdown lexer.
        assertThat(spans("[text](url)", highlight(SyntaxLanguages.MARKDOWN, "[text](url)")))
            .containsExactly(
                TokenKind.OPERATOR to "[",
                TokenKind.PLAIN to "text",
                TokenKind.OPERATOR to "](",
                TokenKind.PLAIN to "url",
                TokenKind.OPERATOR to ")",
            ).inOrder()
    }

    @Test
    fun `the registry maps well-known names case-insensitively and by extension`() {
        // `Main.kt` is extension matching; `README.MD` proves the name is lowercased before
        // any lookup; `Dockerfile`, `Makefile` and `.gitignore` are the exact-name ladder
        // (dotfiles included); and `notes` - no dot, no known name - is null, because the
        // editor falls back to plain text rather than guessing.
        assertThat(SyntaxRegistry.forFileName("Main.kt")).isSameInstanceAs(SyntaxLanguages.KOTLIN)
        assertThat(SyntaxRegistry.forFileName("README.MD")).isSameInstanceAs(SyntaxLanguages.MARKDOWN)
        assertThat(SyntaxRegistry.forFileName("Dockerfile")).isSameInstanceAs(SyntaxLanguages.DOCKERFILE)
        assertThat(SyntaxRegistry.forFileName("Makefile")).isSameInstanceAs(SyntaxLanguages.MAKEFILE)
        assertThat(SyntaxRegistry.forFileName(".gitignore")).isSameInstanceAs(SyntaxLanguages.PROPERTIES)
        assertThat(SyntaxRegistry.forFileName("notes")).isNull()
    }

    @Test
    fun `extension matching takes the longest suffix, and unknown suffixes get nothing`() {
        // `build.gradle.kts` resolves through `kts` to Kotlin only after `gradle.kts` is
        // found not to be a rule; `foo.d.ts` resolves through the compound `d.ts` rule to
        // TypeScript, which is the case the longest-suffix walk exists for. `archive.tar.gz`
        // matches no rule at either dot - `gz` is not a language - and a Dockerfile variant
        // resolves through its prefix before any extension is consulted.
        assertThat(SyntaxRegistry.forFileName("build.gradle.kts")).isSameInstanceAs(SyntaxLanguages.KOTLIN)
        assertThat(SyntaxRegistry.forFileName("foo.d.ts")).isSameInstanceAs(SyntaxLanguages.TYPESCRIPT)
        assertThat(SyntaxRegistry.forFileName("archive.tar.gz")).isNull()
        assertThat(SyntaxRegistry.forFileName("Dockerfile.dev")).isSameInstanceAs(SyntaxLanguages.DOCKERFILE)
    }

    @Test
    fun `one hundred kilobytes of Kotlin highlights with tokens proportional to lines`() {
        // The O(n) promise, held by the cheapest possible check: a 100 KB file of repetitive
        // declarations must not blow up, and its token count must stay linear in the line
        // count - five spans per line here (keyword, name, equals, number, comment), asserted
        // exact, with the well-formedness check run over the whole output so that size cannot
        // hide an overlap.
        val lines = 5_000
        val source = "val item = 1 // done\n".repeat(lines)
        val tokens = highlight(SyntaxLanguages.KOTLIN, source)
        assertThat(tokens.size).isEqualTo(5 * lines)
        assertThat(tokens.size).isAtMost(10 * lines)
        assertWellFormed(source, tokens)
    }

    @Test
    fun `empty and whitespace-only sources produce no tokens`() {
        // No span is better than a zero-length span: the lexer drops them by construction,
        // and an empty file must not hand the editor a token it would try to apply. The
        // key-value driver gets the same check because it owns its own line loop.
        assertThat(highlight(SyntaxLanguages.KOTLIN, "")).isEmpty()
        assertThat(highlight(SyntaxLanguages.KOTLIN, "  \n\t\n")).isEmpty()
        assertThat(highlight(SyntaxLanguages.YAML, " \n")).isEmpty()
    }
}
