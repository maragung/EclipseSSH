package dev.eclipse.ssh.ui.editor.highlight

/**
 * What a stretch of source text is, for coloring purposes.
 *
 * One COMMENT kind would have been enough if the editor wanted one comment color; the split into
 * block and line comments exists because editors conventionally dim them differently (line
 * comments slightly stronger), and because the lexer produces them from different rules - a line
 * comment cannot be unterminated, a block comment can. Everything else maps one-to-one onto a
 * color the editor picks: keywords and builtins (types, constants, literals) are the language's
 * own vocabulary, TYPE_NAME is the capitalized-identifier heuristic, ANNOTATION covers Java/Kotlin
 * `@` and Python decorators, and PLAIN is the color of unremarkable text.
 */
enum class TokenKind {
    PLAIN,
    KEYWORD,
    BUILTIN,
    STRING,
    CHAR,
    NUMBER,
    COMMENT,
    LINE_COMMENT,
    OPERATOR,
    ANNOTATION,
    TYPE_NAME,
}

/**
 * One colored stretch: [start] inclusive, [end] exclusive, both offsets into the highlighted
 * source. No substring is carried - the editor applies colors to an AnnotatedString by offset, so
 * copying text here would only be a second allocation of something the editor already holds.
 * [end] is always greater than [start]; the lexer never emits zero-length tokens, because a
 * zero-length span is invisible and only ever surprised the code applying it.
 */
data class Token(val kind: TokenKind, val start: Int, val end: Int)

/**
 * One language's lexing rules, as plain data: a class rather than a sealed hierarchy because the
 * 25 shipped languages differ only in the values of these fields, and a language that needs a
 * different *algorithm* (XML, Markdown, and the key-value family) flags it with a mode boolean
 * the highlighter switches on.
 *
 * The word sets are matched exactly when [caseSensitive] (the norm) and case-insensitively when
 * not - SQL is the one shipped language where `SELECT` and `select` must color alike, and doing
 * it in the lookup keeps the lexer branch-free about it.
 */
class SyntaxLanguage(
    val id: String,
    val keywords: Set<String> = emptySet(),
    val builtins: Set<String> = emptySet(),
    val lineCommentPrefixes: List<String> = emptyList(),
    val blockCommentOpen: String? = null,
    val blockCommentClose: String? = null,
    val stringQuotes: Set<Char> = emptySet(),
    /** Quotes whose string runs to the matching quote across newlines and ignores escapes - the JS/Go backtick. */
    val rawStringQuotes: Set<Char> = emptySet(),
    /** Triple-quote openers (Kotlin/Python raw and docstrings); matched before [stringQuotes]. */
    val tripleQuotes: List<String> = emptyList(),
    /** The escape inside strings and chars; null means no escape processing (rare, e.g. YAML). */
    val escapeChar: Char? = '\\',
    val charQuotes: Set<Char> = emptySet(),
    val caseSensitive: Boolean = true,
    /** Extra characters that may *start* an identifier on top of letters and `_`: `$` for Kotlin/Java/JS/PHP. */
    val identifierExtraStart: Set<Char> = emptySet(),
    val operatorChars: Set<Char> = DEFAULT_OPERATORS,
    /** Whether `@word` lexes as an annotation/decorator. */
    val annotations: Boolean = false,
    /** Whether a Capitalized word that is neither keyword nor builtin colors as a type name. */
    val typeNameHeuristic: Boolean = false,
    val xmlMode: Boolean = false,
    val markdownMode: Boolean = false,
    /** Key-value line mode (YAML, TOML, INI, Properties, Makefile): the key before [keySeparators] colors as a keyword. */
    val keyValueMode: Boolean = false,
    val keySeparators: Set<Char> = emptySet(),
) {

    /**
     * Prefix lists sorted longest-first once, so the lexer's "does any prefix match here" probe is
     * a single ordered scan and a match can never be shadowed by its own prefix (`--` vs `--[[`).
     */
    internal val sortedLineComments: List<String> = lineCommentPrefixes.sortedByDescending { it.length }
    internal val sortedTriples: List<String> = tripleQuotes.sortedByDescending { it.length }

    /** Lookup copies for the case-insensitive languages, built once instead of per token. */
    private val keywordLookup: Set<String> =
        if (caseSensitive) keywords else keywords.mapTo(HashSet()) { it.lowercase() }
    private val builtinLookup: Set<String> =
        if (caseSensitive) builtins else builtins.mapTo(HashSet()) { it.lowercase() }

    /** Whether [word] is a keyword of this language, honoring its case sensitivity. */
    fun isKeyword(word: String): Boolean = keywordLookup.contains(if (caseSensitive) word else word.lowercase())

    /** Whether [word] is a builtin type/constant of this language, honoring its case sensitivity. */
    fun isBuiltin(word: String): Boolean = builtinLookup.contains(if (caseSensitive) word else word.lowercase())

    companion object {
        /**
         * The C-family operator/punctuation set, shared by every language that does not override
         * it: operator *characters* rather than multi-char operators, because the lexer greedily
         * takes runs, which paints `>>=` as one token the way editors do.
         */
        val DEFAULT_OPERATORS: Set<Char> =
            "+-*/%=<>!&|^~?:;,.(){}[]".toSet()
    }
}

/**
 * Maps a file name to the language the editor should highlight it as, or null when nothing is
 * known - the editor falls back to plain text rather than guessing.
 *
 * The ladder: exact well-known names first (case-insensitively - `Makefile`, `README.MD`), then
 * compound prefixes (`Dockerfile.dev`), then extensions. Extension matching takes the *longest*
 * matching suffix, not the text after the last dot: for every dot position from the left, the
 * remainder of the name is tried as an extension, so a hypothetical `foo.tar.gz` rule would beat
 * a `gz` rule, and today's `foo.d.ts` resolves through `d.ts`. Names with no dot, or only
 * unmatched ones, get null - which is why `.tar.gz` stays unhighlighted instead of being read as
 * some extension it is not.
 */
object SyntaxRegistry {

    private val exactNames: Map<String, SyntaxLanguage> = mapOf(
        "dockerfile" to SyntaxLanguages.DOCKERFILE,
        "makefile" to SyntaxLanguages.MAKEFILE,
        "gnumakefile" to SyntaxLanguages.MAKEFILE,
        ".gitignore" to SyntaxLanguages.PROPERTIES,
        ".dockerignore" to SyntaxLanguages.PROPERTIES,
        ".env" to SyntaxLanguages.PROPERTIES,
        ".editorconfig" to SyntaxLanguages.INI,
        ".bashrc" to SyntaxLanguages.SHELL,
        ".bash_profile" to SyntaxLanguages.SHELL,
        ".profile" to SyntaxLanguages.SHELL,
        ".zshrc" to SyntaxLanguages.SHELL,
    )

    private val byExtension: Map<String, SyntaxLanguage> = buildMap {
        for (ext in listOf("kt", "kts")) put(ext, SyntaxLanguages.KOTLIN)
        put("java", SyntaxLanguages.JAVA)
        for (ext in listOf("py", "pyw")) put(ext, SyntaxLanguages.PYTHON)
        for (ext in listOf("js", "mjs", "cjs", "jsx")) put(ext, SyntaxLanguages.JAVASCRIPT)
        for (ext in listOf("ts", "tsx", "d.ts")) put(ext, SyntaxLanguages.TYPESCRIPT)
        put("json", SyntaxLanguages.JSON)
        for (ext in listOf("xml", "html", "htm", "xhtml", "svg", "xsl")) put(ext, SyntaxLanguages.XML)
        for (ext in listOf("yaml", "yml")) put(ext, SyntaxLanguages.YAML)
        put("toml", SyntaxLanguages.TOML)
        for (ext in listOf("sh", "bash", "zsh", "ksh")) put(ext, SyntaxLanguages.SHELL)
        for (ext in listOf("c", "h")) put(ext, SyntaxLanguages.C)
        for (ext in listOf("cpp", "cc", "cxx", "c++", "hpp", "hh", "hxx")) put(ext, SyntaxLanguages.CPP)
        put("go", SyntaxLanguages.GO)
        put("rs", SyntaxLanguages.RUST)
        put("sql", SyntaxLanguages.SQL)
        put("php", SyntaxLanguages.PHP)
        put("rb", SyntaxLanguages.RUBY)
        for (ext in listOf("md", "markdown")) put(ext, SyntaxLanguages.MARKDOWN)
        put("properties", SyntaxLanguages.PROPERTIES)
        for (ext in listOf("ini", "conf", "cfg")) put(ext, SyntaxLanguages.INI)
        put("dockerfile", SyntaxLanguages.DOCKERFILE)
        put("mk", SyntaxLanguages.MAKEFILE)
        put("cs", SyntaxLanguages.CSHARP)
        put("lua", SyntaxLanguages.LUA)
        for (ext in listOf("pl", "pm")) put(ext, SyntaxLanguages.PERL)
    }

    /** The language for [name], or null when the name carries no evidence. */
    fun forFileName(name: String): SyntaxLanguage? {
        val lower = name.lowercase()
        exactNames[lower]?.let { return it }
        if (lower.startsWith("dockerfile.")) return SyntaxLanguages.DOCKERFILE
        if (lower.startsWith("makefile.")) return SyntaxLanguages.MAKEFILE
        // Earliest dot = longest remaining suffix, so compound rules win over their tails.
        var dot = lower.indexOf('.')
        while (dot != -1) {
            byExtension[lower.substring(dot + 1)]?.let { return it }
            dot = lower.indexOf('.', dot + 1)
        }
        return null
    }
}

/**
 * The syntax highlighting engine: one pass over the source, producing offset-only tokens.
 *
 * Design decisions an editor has to make, made once and documented here:
 *
 * - **Single pass, O(n).** Every branch consumes at least one character and the cursor only moves
 *   forward, so the cost is linear in the file; there is no per-line re-scan and no regex
 *   backtracking (numbers are scanned by hand for exactly that reason).
 * - **Whitespace is not tokenized.** Tokens cover the non-whitespace runs; the gaps between them
 *   are whitespace by construction, which the tests verify. Coloring can therefore apply spans
 *   without worrying about overlapping whitespace runs.
 * - **Adjacent same-kind tokens merge** (a run of plain punctuation becomes one span), which keeps
 *   the token count - and the AnnotatedString span count - proportional to visible color changes.
 * - **Unterminated strings end at the line's end**, not the file's. This is common editor
 *   behavior: while the user is typing a string, everything below the caret should not light up
 *   as string-colored; the moment they close it (or move on), the state is local to the line.
 *   Raw/triple-quoted strings are the exception - they are multi-line by definition, so an
 *   unterminated one runs to end-of-file.
 * - **Comments beat everything** once their prefix matches, which resolves `//` inside a string
 *   (the string consumed it first) and `/* */` inside a `//` comment (the line comment consumed
 *   it) in the directions users expect.
 *
 * The class is stateless per call and depends only on Kotlin's stdlib, so it runs identically in
 * the app and in plain JVM unit tests.
 */
class SyntaxHighlighter(private val language: SyntaxLanguage) {

    /** The colored spans of [source], in order, covering every non-whitespace character. */
    fun highlight(source: String): List<Token> {
        val builder = TokenList()
        when {
            language.xmlMode -> highlightXml(source, builder)
            language.markdownMode -> highlightMarkdown(source, builder)
            language.keyValueMode -> highlightKeyValue(source, builder)
            else -> lexCore(source, 0, builder, stopAtNewline = false)
        }
        return builder.tokens
    }

    /**
     * The generic single-pass lexer shared by everything that is not XML, Markdown, or key-value.
     * Stops before a newline (leaving it unconsumed) when [stopAtNewline], which is how the
     * key-value driver keeps its line-oriented key rule in charge of line starts.
     */
    private fun lexCore(source: String, from: Int, builder: TokenList, stopAtNewline: Boolean): Int {
        val n = source.length
        var i = from
        while (i < n) {
            val c = source[i]
            if (c == '\n' && stopAtNewline) return i
            if (c.isWhitespace()) {
                i++
                continue
            }
            // Comments before anything else: a string quote inside a comment is text, and the
            // branches below would happily consume comment text as code.
            val linePrefix = matchPrefix(source, i, language.sortedLineComments)
            if (linePrefix != null) {
                var j = i + linePrefix.length
                while (j < n && source[j] != '\n') j++
                builder.add(TokenKind.LINE_COMMENT, i, j)
                i = j
                continue
            }
            val open = language.blockCommentOpen
            if (open != null && source.startsWith(open, i)) {
                val close = language.blockCommentClose!!
                val found = source.indexOf(close, i + open.length)
                val end = if (found == -1) n else found + close.length
                builder.add(TokenKind.COMMENT, i, end)
                i = end
                continue
            }
            val triple = matchPrefix(source, i, language.sortedTriples)
            if (triple != null) {
                val found = source.indexOf(triple, i + triple.length)
                val end = if (found == -1) n else found + triple.length
                builder.add(TokenKind.STRING, i, end)
                i = end
                continue
            }
            if (c in language.rawStringQuotes) {
                val found = source.indexOf(c, i + 1)
                val end = if (found == -1) n else found + 1
                builder.add(TokenKind.STRING, i, end)
                i = end
                continue
            }
            if (c in language.stringQuotes) {
                i = lexString(source, i, c, builder)
                continue
            }
            if (c in language.charQuotes && tryCharLiteral(source, i, c, builder)) {
                continue
            }
            if (language.annotations && c == '@' && i + 1 < n && isIdentifierStart(source[i + 1])) {
                var j = i + 1
                while (j < n && isIdentifierPart(source[j])) j++
                builder.add(TokenKind.ANNOTATION, i, j)
                i = j
                continue
            }
            if (isIdentifierStart(c)) {
                var j = i + 1
                while (j < n && isIdentifierPart(source[j])) j++
                builder.add(classifyWord(source, i, j), i, j)
                i = j
                continue
            }
            // A number may only start a token here, which is the whole boundary rule: any digit
            // preceded by an identifier character was already consumed by that identifier, so
            // `abc123` is one plain word while ` 123` and `(123` are numbers. The leading-dot
            // form (.5) is checked before the operator branch would eat the dot.
            val numberEnd = numberEnd(source, i)
            if (numberEnd > i) {
                builder.add(TokenKind.NUMBER, i, numberEnd)
                i = numberEnd
                continue
            }
            if (c in language.operatorChars) {
                var j = i + 1
                while (j < n && source[j] in language.operatorChars) j++
                builder.add(TokenKind.OPERATOR, i, j)
                i = j
                continue
            }
            // Whatever the language does not name (a stray `@`, a `#` that is not a comment
            // prefix, non-ASCII punctuation) is plain, one character at a time; the builder's
            // merging turns a run of them into a single span.
            builder.add(TokenKind.PLAIN, i, i + 1)
            i++
        }
        return n
    }

    /**
     * A quote-delimited string with [SyntaxLanguage.escapeChar] escapes. The closing quote may be
     * escaped (`"a\"b"`), and an unterminated string stops at the end of the line - the
     * documented editor behavior, chosen so a half-typed string does not color the rest of the
     * file.
     */
    private fun lexString(source: String, start: Int, quote: Char, builder: TokenList): Int {
        val n = source.length
        val escape = language.escapeChar
        var j = start + 1
        while (j < n) {
            val c = source[j]
            if (escape != null && c == escape) {
                j += 2
                continue
            }
            if (c == quote) {
                j++
                break
            }
            if (c == '\n') break
            j++
        }
        val end = j.coerceAtMost(n)
        builder.add(TokenKind.STRING, start, end)
        return end
    }

    /**
     * A character literal: exactly one char, or one escape sequence (`\n`, `A`), between two
     * quotes on one line. Returns false (and consumes nothing) when that shape does not hold, so
     * the caller falls through - a lone `'` then lexes as an operator and what follows as an
     * identifier, which is precisely what a Rust lifetime (`'a`) and an apostrophe in code need.
     * The strict shape is what keeps `'a str` from being read as an unterminated char.
     */
    private fun tryCharLiteral(source: String, start: Int, quote: Char, builder: TokenList): Boolean {
        val lineEnd = source.indexOf('\n', start).let { if (it == -1) source.length else it }
        var j = start + 1
        if (j >= lineEnd) return false
        val escape = language.escapeChar
        if (escape != null && source[j] == escape) {
            j++
            if (j >= lineEnd) return false
            if (source[j] == 'u') {
                j++
                repeat(4) {
                    if (j < lineEnd && (source[j].isDigit() || isHex(source[j]))) j++ else return false
                }
            } else {
                j++
            }
        } else {
            j++
        }
        if (j >= lineEnd || source[j] != quote) return false
        builder.add(TokenKind.CHAR, start, j + 1)
        return true
    }

    /**
     * The end (exclusive) of a number starting at [i], or [i] when there is no number there.
     * Scanned by hand rather than with a regex: the grammar is fixed and tiny, and a hand scan
     * cannot backtrack - the engine's O(n) promise would otherwise rest on the regex engine's
     * good behavior. The grammar: `0x` hex / `0b` binary with digits and `_` separators, or
     * decimal digits with `_`, optional fractional part, optional exponent with sign; a leading
     * `.` starts a number only when a digit follows.
     */
    private fun numberEnd(source: String, i: Int): Int {
        val n = source.length
        val c = source[i]
        if (c == '0' && i + 1 < n && (source[i + 1] == 'x' || source[i + 1] == 'X')) {
            var j = i + 2
            while (j < n && (isHex(source[j]) || source[j] == '_')) j++
            return j
        }
        if (c == '0' && i + 1 < n && (source[i + 1] == 'b' || source[i + 1] == 'B')) {
            var j = i + 2
            while (j < n && (source[j] == '0' || source[j] == '1' || source[j] == '_')) j++
            return j
        }
        if (c == '.' && !(i + 1 < n && source[i + 1].isDigit())) return i
        var j = i
        if (c == '.') j++
        while (j < n && (source[j].isDigit() || source[j] == '_')) j++
        if (j < n && source[j] == '.' && j + 1 < n && source[j + 1].isDigit()) {
            j++
            while (j < n && (source[j].isDigit() || source[j] == '_')) j++
        }
        if (j < n && (source[j] == 'e' || source[j] == 'E')) {
            var k = j + 1
            if (k < n && (source[k] == '+' || source[k] == '-')) k++
            if (k < n && source[k].isDigit()) {
                j = k
                while (j < n && source[j].isDigit()) j++
            }
        }
        return j
    }

    /** Keyword, then builtin, then the capitalized heuristic, then plain - in that precedence. */
    private fun classifyWord(source: String, start: Int, end: Int): TokenKind {
        val word = source.substring(start, end)
        if (language.isKeyword(word)) return TokenKind.KEYWORD
        if (language.isBuiltin(word)) return TokenKind.BUILTIN
        if (language.typeNameHeuristic && word[0].isUpperCase()) return TokenKind.TYPE_NAME
        return TokenKind.PLAIN
    }

    private fun isIdentifierStart(c: Char): Boolean =
        c.isLetter() || c == '_' || c in language.identifierExtraStart

    private fun isIdentifierPart(c: Char): Boolean =
        c.isLetterOrDigit() || c == '_' || c in language.identifierExtraStart

    /** The longest entry of [prefixes] that [source] has at [i], or null. */
    private fun matchPrefix(source: String, i: Int, prefixes: List<String>): String? {
        var best: String? = null
        for (prefix in prefixes) {
            if (prefix.length > (best?.length ?: 0) && source.startsWith(prefix, i)) best = prefix
        }
        return best
    }

    // ---------------------------------------------------------------------------------------
    // Key-value mode (YAML, TOML, INI, Properties, Makefile)
    // ---------------------------------------------------------------------------------------

    /**
     * Line-oriented driver for the key-value family: at each line start it decides whether the
     * line carries a key (highlighted KEYWORD, with the separator as an operator) and hands the
     * remainder to the generic lexer. A line that carries no key - a YAML list item, a Makefile
     * recipe, a bare scalar - is lexed generically from its start, so `- foo` still gets its
     * dash and strings colored.
     */
    private fun highlightKeyValue(source: String, builder: TokenList) {
        var i = 0
        while (i < source.length) {
            i = keyValueLine(source, i, builder)
        }
    }

    private fun keyValueLine(source: String, start: Int, builder: TokenList): Int {
        val n = source.length
        var i = start
        while (i < n && (source[i] == ' ' || source[i] == '\t')) i++
        if (i >= n) return n
        if (source[i] == '\n') return i + 1
        if (source[i] == '\r') {
            return if (i + 1 < n) i + 2 else n
        }
        val lineEnd = source.indexOf('\n', i).let { if (it == -1) n else it }

        matchPrefix(source, i, language.sortedLineComments)?.let {
            builder.add(TokenKind.LINE_COMMENT, i, lineEnd)
            return consumeNewline(source, lineEnd)
        }
        // A YAML list marker is an operator, and the key may follow it (`- name: x`).
        if (source[i] == '-' && i + 1 < lineEnd && source[i + 1] == ' ') {
            builder.add(TokenKind.OPERATOR, i, i + 1)
            i++
            while (i < lineEnd && source[i] == ' ') i++
            if (i >= lineEnd || source[i] == '\r') return consumeNewline(source, lineEnd)
        }
        // A section header ([core] in INI/TOML): brackets as operators, the name as a type.
        if (source[i] == '[') {
            builder.add(TokenKind.OPERATOR, i, i + 1)
            var j = i + 1
            while (j < lineEnd && source[j] != ']') j++
            builder.add(TokenKind.TYPE_NAME, i + 1, j)
            if (j < lineEnd) {
                builder.add(TokenKind.OPERATOR, j, j + 1)
                j++
            }
            return coreToLineEnd(source, j, lineEnd, builder)
        }
        // A quoted key stays a string; only the separator behind it confirms it was a key.
        if (source[i] == '"' || source[i] == '\'') {
            val quote = source[i]
            var j = i + 1
            while (j < lineEnd && source[j] != quote) j++
            val strEnd = if (j < lineEnd) j + 1 else lineEnd
            builder.add(TokenKind.STRING, i, strEnd)
            var k = strEnd
            while (k < lineEnd && source[k] == ' ') k++
            if (k < lineEnd && source[k] in language.keySeparators) {
                builder.add(TokenKind.OPERATOR, k, k + 1)
                k++
            }
            return coreToLineEnd(source, k, lineEnd, builder)
        }
        // A plain key: a run of non-space, non-separator, non-quote characters that is followed by
        // a separator - with spaces allowed between the two, because TOML and INI write
        // `name = "tom"` and the space is formatting, not meaning. The run itself still stops at
        // the first separator or space, which is what keeps YAML's `- gcc -c` and Makefile recipes
        // from being mistaken for keys: a word followed by more words is not a key, and a word
        // followed only by spaces then a separator is.
        var j = i
        while (j < lineEnd && source[j] != ' ' && source[j] != '\t' && source[j] != '\r' &&
            source[j] !in language.keySeparators && source[j] != '"' && source[j] != '\''
        ) {
            j++
        }
        var k = j
        while (k < lineEnd && (source[k] == ' ' || source[k] == '\t')) k++
        if (j > i && k < lineEnd && source[k] in language.keySeparators) {
            builder.add(TokenKind.KEYWORD, i, j)
            builder.add(TokenKind.OPERATOR, k, k + 1)
            return coreToLineEnd(source, k + 1, lineEnd, builder)
        }
        return coreToLineEnd(source, start, lineEnd, builder)
    }

    /** Runs the generic lexer to [lineEnd] and steps past the newline that ended the line. */
    private fun coreToLineEnd(source: String, from: Int, lineEnd: Int, builder: TokenList): Int {
        lexCore(source, from, builder, stopAtNewline = true)
        return consumeNewline(source, lineEnd)
    }

    private fun consumeNewline(source: String, at: Int): Int =
        if (at >= source.length) at else at + 1

    // ---------------------------------------------------------------------------------------
    // XML/HTML mode
    // ---------------------------------------------------------------------------------------

    /**
     * Markup lexing: tags and their names (TYPE_NAME), attribute names (BUILTIN), quoted
     * attribute values (STRING), comments and CDATA, entities in text, and PLAIN text between
     * the tags. An unterminated construct runs to end-of-file rather than the line - markup has
     * no line-oriented grammar to fall back on, and a tag left open mid-edit is still a tag.
     */
    private fun highlightXml(source: String, builder: TokenList) {
        val n = source.length
        var i = 0
        while (i < n) {
            when {
                source.startsWith("<!--", i) -> {
                    val found = source.indexOf("-->", i + 4)
                    val end = if (found == -1) n else found + 3
                    builder.add(TokenKind.COMMENT, i, end)
                    i = end
                }
                source.startsWith("<![CDATA[", i) -> {
                    val found = source.indexOf("]]>", i + 9)
                    val end = if (found == -1) n else found + 3
                    builder.add(TokenKind.STRING, i, end)
                    i = end
                }
                source[i] == '<' -> i = lexXmlTag(source, i, builder)
                else -> i = lexXmlText(source, i, builder)
            }
        }
    }

    /** One tag from `<` to its `>`, `/>`, or `?>` - or to end-of-file when never closed. */
    private fun lexXmlTag(source: String, start: Int, builder: TokenList): Int {
        val n = source.length
        var i = start
        when {
            source.startsWith("<?", i) -> {
                builder.add(TokenKind.OPERATOR, i, i + 2)
                i += 2
            }
            source.startsWith("</", i) -> {
                builder.add(TokenKind.OPERATOR, i, i + 2)
                i += 2
            }
            else -> {
                builder.add(TokenKind.OPERATOR, i, i + 1)
                i++
            }
        }
        var j = i
        while (j < n && isXmlNameChar(source[j])) j++
        if (j > i) {
            builder.add(TokenKind.TYPE_NAME, i, j)
            i = j
        }
        while (i < n) {
            val c = source[i]
            when {
                c == '>' -> {
                    builder.add(TokenKind.OPERATOR, i, i + 1)
                    return i + 1
                }
                c == '/' && i + 1 < n && source[i + 1] == '>' -> {
                    builder.add(TokenKind.OPERATOR, i, i + 2)
                    return i + 2
                }
                c == '?' && i + 1 < n && source[i + 1] == '>' -> {
                    builder.add(TokenKind.OPERATOR, i, i + 2)
                    return i + 2
                }
                c == '=' -> {
                    builder.add(TokenKind.OPERATOR, i, i + 1)
                    i++
                }
                c == '"' || c == '\'' -> {
                    var k = i + 1
                    while (k < n && source[k] != c && source[k] != '\n') k++
                    val end = if (k < n && source[k] == c) k + 1 else k
                    builder.add(TokenKind.STRING, i, end)
                    i = end
                }
                c.isWhitespace() -> i++
                else -> {
                    var k = i
                    while (k < n && isXmlNameChar(source[k])) k++
                    if (k > i) {
                        builder.add(TokenKind.BUILTIN, i, k)
                        i = k
                    } else {
                        i++
                    }
                }
            }
        }
        return i
    }

    /**
     * Text between tags: PLAIN runs with entities (`&amp;`) lifted out as BUILTIN, because an
     * entity is the one thing in element text that is not just prose.
     */
    private fun lexXmlText(source: String, start: Int, builder: TokenList): Int {
        val n = source.length
        var i = start
        var runStart = start
        while (i < n && source[i] != '<') {
            if (source[i] == '&') {
                var k = i + 1
                while (k < n && k <= i + 10 && source[k] != ';' && source[k] != '<' &&
                    (source[k].isLetterOrDigit() || source[k] == '#')
                ) {
                    k++
                }
                if (k < n && source[k] == ';') {
                    builder.add(TokenKind.PLAIN, runStart, i)
                    builder.add(TokenKind.BUILTIN, i, k + 1)
                    i = k + 1
                    runStart = i
                    continue
                }
            }
            i++
        }
        builder.add(TokenKind.PLAIN, runStart, i)
        return i
    }

    private fun isXmlNameChar(c: Char): Boolean =
        c.isLetterOrDigit() || c == '_' || c == '-' || c == ':' || c == '.'

    // ---------------------------------------------------------------------------------------
    // Markdown mode
    // ---------------------------------------------------------------------------------------

    /**
     * Markdown, special-cased line by line: a fenced code block paints its fence lines as
     * LINE_COMMENT and its body as STRING (the editor's cheapest readable rendering of "this is
     * verbatim"); a heading line is KEYWORD; setext underlines and inline emphasis markers are
     * OPERATOR; inline code spans are STRING. The line orientation mirrors how Markdown itself
     * is defined - almost every construct is anchored to a line start.
     */
    private fun highlightMarkdown(source: String, builder: TokenList) {
        val n = source.length
        var i = 0
        while (i < n) {
            val lineEnd = source.indexOf('\n', i).let { if (it == -1) n else it }
            val contentStart = leadingSpaces(source, i, lineEnd, max = 3)
            val fence = fenceMarkerAt(source, contentStart, lineEnd)
            if (fence != null) {
                builder.add(TokenKind.LINE_COMMENT, contentStart, lineEnd)
                i = consumeNewline(source, lineEnd)
                // Body lines are STRING until a matching fence line (or end-of-file).
                while (i < n) {
                    val bodyEnd = source.indexOf('\n', i).let { if (it == -1) n else it }
                    val bodyStart = leadingSpaces(source, i, bodyEnd, max = 3)
                    if (fenceMarkerAt(source, bodyStart, bodyEnd) != null) {
                        builder.add(TokenKind.LINE_COMMENT, bodyStart, bodyEnd)
                        i = consumeNewline(source, bodyEnd)
                        break
                    }
                    builder.add(TokenKind.STRING, i, bodyEnd)
                    i = consumeNewline(source, bodyEnd)
                }
                continue
            }
            if (isHeadingAt(source, contentStart, lineEnd)) {
                builder.add(TokenKind.KEYWORD, contentStart, lineEnd)
                i = consumeNewline(source, lineEnd)
                continue
            }
            if (isUnderlineAt(source, contentStart, lineEnd)) {
                builder.add(TokenKind.OPERATOR, contentStart, lineEnd)
                i = consumeNewline(source, lineEnd)
                continue
            }
            // Line-anchored markers: blockquote and list bullets.
            if (contentStart < lineEnd && source[contentStart] == '>') {
                builder.add(TokenKind.OPERATOR, contentStart, contentStart + 1)
                i = contentStart + 1
            } else if (isListMarkerAt(source, contentStart, lineEnd)) {
                val markerEnd = contentStart + listMarkerLength(source, contentStart, lineEnd)
                builder.add(TokenKind.OPERATOR, contentStart, markerEnd)
                i = markerEnd
            } else {
                i = contentStart
            }
            inlineMarkdown(source, i, lineEnd, builder)
            i = consumeNewline(source, lineEnd)
        }
    }

    private fun inlineMarkdown(source: String, from: Int, to: Int, builder: TokenList) {
        var i = from
        while (i < to) {
            val c = source[i]
            when {
                c == '`' -> {
                    var j = i + 1
                    while (j < to && source[j] != '`') j++
                    val end = if (j < to) j + 1 else to
                    builder.add(TokenKind.STRING, i, end)
                    i = end
                }
                (c == '*' || c == '_') && i + 1 < to && source[i + 1] == c -> {
                    builder.add(TokenKind.OPERATOR, i, i + 2)
                    i += 2
                }
                c == '*' || c == '_' || c == '#' || c == '[' || c == ']' ||
                    c == '(' || c == ')' || c == '!' -> {
                    builder.add(TokenKind.OPERATOR, i, i + 1)
                    i++
                }
                else -> {
                    var j = i
                    while (j < to && !isMarkdownSpecial(source[j])) j++
                    builder.add(TokenKind.PLAIN, i, j)
                    i = j
                }
            }
        }
    }

    private fun isMarkdownSpecial(c: Char): Boolean =
        c == '*' || c == '_' || c == '`' || c == '#' || c == '[' || c == ']' ||
            c == '(' || c == ')' || c == '!'

    private fun fenceMarkerAt(source: String, at: Int, lineEnd: Int): String? {
        if (at + 3 > lineEnd) return null
        val three = source.substring(at, at + 3)
        return if (three == "```" || three == "~~~") three else null
    }

    private fun isHeadingAt(source: String, at: Int, lineEnd: Int): Boolean {
        var j = at
        while (j < lineEnd && source[j] == '#') j++
        val level = j - at
        return level in 1..6 && (j == lineEnd || source[j] == ' ')
    }

    private fun isUnderlineAt(source: String, at: Int, lineEnd: Int): Boolean {
        if (at >= lineEnd || lineEnd - at < 2) return false
        val c = source[at]
        if (c != '=' && c != '-') return false
        for (j in at until lineEnd) {
            if (source[j] != c && source[j] != '\r') return false
        }
        return true
    }

    private fun isListMarkerAt(source: String, at: Int, lineEnd: Int): Boolean =
        listMarkerLength(source, at, lineEnd) > 0

    private fun listMarkerLength(source: String, at: Int, lineEnd: Int): Int {
        if (at >= lineEnd) return 0
        val c = source[at]
        if ((c == '-' || c == '+' || c == '*') && at + 1 < lineEnd && source[at + 1] == ' ') return 2
        var j = at
        while (j < lineEnd && source[j].isDigit()) j++
        return if (j > at && j < lineEnd && source[j] == '.' && j + 1 < lineEnd && source[j + 1] == ' ') {
            j + 2 - at
        } else {
            0
        }
    }

    private fun leadingSpaces(source: String, from: Int, to: Int, max: Int): Int {
        var i = from
        while (i < to && i - from < max && (source[i] == ' ' || source[i] == '\t')) i++
        return i
    }

    private fun isHex(c: Char): Boolean =
        c in 'a'..'f' || c in 'A'..'F'
}

/**
 * The token accumulator: appends, drops zero-length spans, and merges a span into the previous
 * one when it continues it in both offset and kind. Merging here - rather than in a post-pass -
 * keeps the working list proportional to color changes from the first token on.
 */
private class TokenList {

    val tokens = ArrayList<Token>(64)

    fun add(kind: TokenKind, start: Int, end: Int) {
        if (end <= start) return
        val last = tokens.lastOrNull()
        if (last != null && last.kind == kind && last.end == start) {
            tokens[tokens.size - 1] = Token(kind, last.start, end)
            return
        }
        tokens.add(Token(kind, start, end))
    }
}
