package dev.eclipse.ssh.ui.editor.highlight

/**
 * The 25 shipped languages, as pure data. Each is a [SyntaxLanguage] declaration, not a class:
 * two languages differ only in their word lists and quote rules, so a declaration is the whole
 * of a language. The sets are deliberately the words an editor actually meets, not the complete
 * grammar of each standard - highlighting `internal` matters, highlighting every reserved word
 * that has been reserved and never used does not.
 *
 * Where a language gets special lexing rather than special data, the matching flag is the only
 * thing that differs: XML/HTML ([SyntaxLanguage.xmlMode]), Markdown
 * ([SyntaxLanguage.markdownMode]), and the key-value family - YAML, TOML, INI/Conf, Properties,
 * Makefile - ([SyntaxLanguage.keyValueMode]).
 */
object SyntaxLanguages {

    /** Splits a spaced word list into the set the lexer matches against. */
    private fun words(text: String): Set<String> = text.trim().split(Regex("\\s+")).toSet()

    /** Kotlin: raw strings and `$` identifiers are the two data-level quirks. */
    val KOTLIN = SyntaxLanguage(
        id = "kotlin",
        keywords = words(
            "as as? break by catch class companion continue crossinline data do dynamic else " +
                "enum expect external false final finally for fun get if import in infix " +
                "init inline inner interface internal is lateinit noinline null object open " +
                "operator out override package private protected public reified return sealed " +
                "set super suspend tailrec this throw true try typealias val var vararg when " +
                "where while",
        ),
        builtins = words(
            "Any Array Boolean Byte Char CharSequence Double Float Int Long Nothing Short " +
                "String Unit List Map Set MutableList MutableMap MutableSet",
        ),
        lineCommentPrefixes = listOf("//"),
        blockCommentOpen = "/*",
        blockCommentClose = "*/",
        stringQuotes = setOf('"'),
        tripleQuotes = listOf("\"\"\""),
        charQuotes = setOf('\''),
        escapeChar = '\\',
        identifierExtraStart = setOf('$'),
        annotations = true,
        typeNameHeuristic = true,
    )

    /** Java: the C-family shape plus annotations and the capitalized-types heuristic. */
    val JAVA = SyntaxLanguage(
        id = "java",
        keywords = words(
            "abstract assert break byte case catch char class const continue default do double " +
                "else enum extends final finally float for goto if implements import instanceof " +
                "int interface long native new package private protected public return short " +
                "static strictfp super switch synchronized this throw throws transient try var " +
                "void volatile while true false null record sealed permits yield",
        ),
        builtins = words(
            "Boolean Byte Character CharSequence Class Double Float Integer Long Object Short " +
                "String StringBuffer StringBuilder List Map Set Optional Stream System",
        ),
        lineCommentPrefixes = listOf("//"),
        blockCommentOpen = "/*",
        blockCommentClose = "*/",
        stringQuotes = setOf('"'),
        charQuotes = setOf('\''),
        identifierExtraStart = setOf('$'),
        annotations = true,
        typeNameHeuristic = true,
    )

    /**
     * Python: no block comments, both quotes, both triple quotes (docstrings), and `@` decorators.
     * `self` rides along in builtins so method bodies read correctly.
     */
    val PYTHON = SyntaxLanguage(
        id = "python",
        keywords = words(
            "and as assert async await break class continue def del elif else except False " +
                "finally for from global if import in is lambda None nonlocal not or pass " +
                "raise return True try while with yield match case",
        ),
        builtins = words(
            "bool bytes dict float frozenset int list set str tuple type self print len range " +
                "enumerate zip sorted super",
        ),
        lineCommentPrefixes = listOf("#"),
        stringQuotes = setOf('"', '\''),
        tripleQuotes = listOf("\"\"\"", "'''"),
        annotations = true,
    )

    /** JavaScript: backticks are raw multi-line strings (template literals; `${}` stays unlexed). */
    val JAVASCRIPT = SyntaxLanguage(
        id = "javascript",
        keywords = words(
            "async await break case catch class const continue debugger default delete do else " +
                "export extends finally for function if import in instanceof let new of return " +
                "static super switch this throw try typeof var void while with yield true false " +
                "null undefined",
        ),
        builtins = words(
            "console document window Math JSON Promise Array Object String Number Boolean " +
                "Symbol Map Set WeakMap WeakSet",
        ),
        lineCommentPrefixes = listOf("//"),
        blockCommentOpen = "/*",
        blockCommentClose = "*/",
        stringQuotes = setOf('"', '\''),
        rawStringQuotes = setOf('`'),
        identifierExtraStart = setOf('$'),
    )

    /** TypeScript: JavaScript plus its type vocabulary. */
    val TYPESCRIPT = SyntaxLanguage(
        id = "typescript",
        keywords = words(
            "async await break case catch class const continue debugger declare default delete " +
                "do else enum export extends finally for from function if implements import in " +
                "infer instanceof interface is keyof let namespace never new of out override " +
                "package private protected public readonly return satisfies static super switch " +
                "this throw try type typeof var void while with yield true false null undefined " +
                "string number boolean any unknown bigint object",
        ),
        builtins = words(
            "console document window Math JSON Promise Array Object String Number Boolean " +
                "Symbol Map Set Partial Required Record Pick Omit Exclude Extract ReturnType",
        ),
        lineCommentPrefixes = listOf("//"),
        blockCommentOpen = "/*",
        blockCommentClose = "*/",
        stringQuotes = setOf('"', '\''),
        rawStringQuotes = setOf('`'),
        identifierExtraStart = setOf('$'),
    )

    /** JSON: no comments, no chars; the literals are the only keywords there are. */
    val JSON = SyntaxLanguage(
        id = "json",
        keywords = words("true false null"),
        stringQuotes = setOf('"'),
    )

    /** XML/HTML: switched into the markup lexer; no word lists apply there. */
    val XML = SyntaxLanguage(
        id = "xml",
        xmlMode = true,
    )

    /** YAML: key-value mode around `:`, `#` comments, both quote styles. */
    val YAML = SyntaxLanguage(
        id = "yaml",
        builtins = words("true false null yes no on off"),
        lineCommentPrefixes = listOf("#"),
        stringQuotes = setOf('"', '\''),
        escapeChar = '\\',
        keyValueMode = true,
        keySeparators = setOf(':'),
    )

    /** TOML: key-value mode around `=`, `#` comments, plus its multi-line string forms. */
    val TOML = SyntaxLanguage(
        id = "toml",
        builtins = words("true false inf nan"),
        lineCommentPrefixes = listOf("#"),
        stringQuotes = setOf('"', '\''),
        tripleQuotes = listOf("\"\"\"", "'''"),
        keyValueMode = true,
        keySeparators = setOf('='),
    )

    /**
     * Shell/Bash: `#` comments, both quote styles. Shell single quotes carry no escapes, but the
     * shared escape-aware string scanner is kept: the one divergence (a backslash before a
     * single quote) is rare enough that the simplification is cheaper than a per-language
     * string rule.
     */
    val SHELL = SyntaxLanguage(
        id = "shell",
        keywords = words(
            "if then else elif fi for while until do done case esac function return in select " +
                "time coproc local export readonly declare typeset shift trap exit break " +
                "continue eval exec source alias",
        ),
        builtins = words("echo printf read cd test set unset pushd popd"),
        lineCommentPrefixes = listOf("#"),
        stringQuotes = setOf('"', '\''),
        identifierExtraStart = setOf('$'),
    )

    /** C. */
    val C = SyntaxLanguage(
        id = "c",
        keywords = words(
            "auto break case char const continue default do double else enum extern float for " +
                "goto if inline int long register restrict return short signed sizeof static " +
                "struct switch typedef union unsigned void volatile while",
        ),
        builtins = words(
            "bool size_t ssize_t ptrdiff_t uint8_t uint16_t uint32_t uint64_t int8_t int16_t " +
                "int32_t int64_t FILE NULL EXIT_SUCCESS EXIT_FAILURE true false",
        ),
        lineCommentPrefixes = listOf("//"),
        blockCommentOpen = "/*",
        blockCommentClose = "*/",
        stringQuotes = setOf('"'),
        charQuotes = setOf('\''),
    )

    /** C++: C plus its class/template vocabulary; `std` types ride in builtins. */
    val CPP = SyntaxLanguage(
        id = "cpp",
        keywords = words(
            "alignas alignof auto bool break case catch char char8_t class concept const consteval " +
                "constexpr constinit const_cast continue co_await co_return co_yield decltype " +
                "default delete do double dynamic_cast else enum explicit export extern false " +
                "float for friend goto if inline int long mutable namespace new noexcept nullptr " +
                "operator private protected public register reinterpret_cast requires return " +
                "short signed sizeof static static_assert static_cast struct switch template " +
                "this thread_local throw true try typedef typeid typename union unsigned using " +
                "virtual void volatile wchar_t while",
        ),
        builtins = words(
            "std string string_view vector list map set unordered_map unordered_set pair " +
                "unique_ptr shared_ptr weak_ptr make_unique make_shared optional variant " +
                "size_t uint8_t uint32_t uint64_t int8_t int32_t int64_t",
        ),
        lineCommentPrefixes = listOf("//"),
        blockCommentOpen = "/*",
        blockCommentClose = "*/",
        stringQuotes = setOf('"'),
        charQuotes = setOf('\''),
    )

    /** Go: backtick raw strings, capitalized-type heuristic, no annotations. */
    val GO = SyntaxLanguage(
        id = "go",
        keywords = words(
            "break case chan const continue default defer else fallthrough for func go goto if " +
                "import interface map package range return select struct switch type var",
        ),
        builtins = words(
            "append bool byte cap close copy delete error false float32 float64 imag int int8 " +
                "int16 int32 int64 len make new nil panic print println real recover rune string " +
                "true uint uint8 uint16 uint32 uint64 uintptr any complex64 complex128",
        ),
        lineCommentPrefixes = listOf("//"),
        blockCommentOpen = "/*",
        blockCommentClose = "*/",
        stringQuotes = setOf('"'),
        rawStringQuotes = setOf('`'),
        typeNameHeuristic = true,
    )

    /**
     * Rust: the char-literal scanner's strict one-glyph shape is what makes lifetimes (`'a`)
     * fall through to operator-plus-identifier instead of being read as unterminated chars.
     */
    val RUST = SyntaxLanguage(
        id = "rust",
        keywords = words(
            "as async await break const continue crate dyn else enum extern false fn for if impl " +
                "in let loop match mod move mut pub ref return self static struct super trait " +
                "true type union unsafe use where while",
        ),
        builtins = words(
            "bool char str u8 u16 u32 u64 u128 usize i8 i16 i32 i64 i128 isize f32 f64 String " +
                "Vec Option Result Box Some None Ok Err println vec",
        ),
        lineCommentPrefixes = listOf("//"),
        blockCommentOpen = "/*",
        blockCommentClose = "*/",
        stringQuotes = setOf('"'),
        charQuotes = setOf('\''),
        typeNameHeuristic = true,
    )

    /** SQL: the one shipped case-insensitive language; single quotes are strings, `--` comments. */
    val SQL = SyntaxLanguage(
        id = "sql",
        keywords = words(
            "SELECT FROM WHERE INSERT INTO VALUES UPDATE DELETE SET CREATE TABLE DROP ALTER ADD " +
                "COLUMN PRIMARY KEY FOREIGN REFERENCES INDEX VIEW JOIN INNER LEFT RIGHT OUTER " +
                "FULL ON AS AND OR NOT NULL IS LIKE IN BETWEEN EXISTS UNION ALL DISTINCT GROUP " +
                "BY ORDER HAVING LIMIT OFFSET ASC DESC WITH CASE WHEN THEN ELSE END DEFAULT " +
                "CONSTRAINT UNIQUE CHECK CASCADE BEGIN COMMIT ROLLBACK TRANSACTION",
        ),
        builtins = words(
            "INT INTEGER SMALLINT BIGINT DECIMAL NUMERIC FLOAT REAL DOUBLE CHAR VARCHAR TEXT " +
                "DATE TIME TIMESTAMP BOOLEAN BLOB SERIAL COUNT SUM AVG MIN MAX COALESCE NULLIF " +
                "CAST",
        ),
        lineCommentPrefixes = listOf("--"),
        blockCommentOpen = "/*",
        blockCommentClose = "*/",
        stringQuotes = setOf('\''),
        caseSensitive = false,
    )

    /** PHP: `//` and `#` comments, `$` variables, both quote styles. */
    val PHP = SyntaxLanguage(
        id = "php",
        keywords = words(
            "abstract and array as break callable case catch class clone const continue declare " +
                "default do echo else elseif empty enddeclare endfor endforeach endif endswitch " +
                "endwhile enum extends final finally fn for foreach function global goto if " +
                "implements include include_once instanceof insteadof interface isset list match " +
                "namespace new or print private protected public readonly require require_once " +
                "return static switch throw trait try unset use var while xor yield true false " +
                "null int float bool string void mixed never self parent",
        ),
        lineCommentPrefixes = listOf("//", "#"),
        blockCommentOpen = "/*",
        blockCommentClose = "*/",
        stringQuotes = setOf('"', '\''),
        identifierExtraStart = setOf('$'),
    )

    /** Ruby: `#` comments; symbols (`:name`) stay plain, matching the minimal-data stance. */
    val RUBY = SyntaxLanguage(
        id = "ruby",
        keywords = words(
            "BEGIN END alias begin break case class def do else elsif end ensure for if in " +
                "module next not or redo rescue retry return self super then undef unless until " +
                "when while yield require require_relative attr_accessor attr_reader attr_writer",
        ),
        builtins = words("nil true false proc lambda raise puts print new let"),
        lineCommentPrefixes = listOf("#"),
        stringQuotes = setOf('"', '\''),
    )

    /** Markdown: the line-oriented special lexer. */
    val MARKDOWN = SyntaxLanguage(
        id = "markdown",
        markdownMode = true,
    )

    /** Properties: keys before `=` *or* `:`, `#` and `!` comments. Line continuations are not followed. */
    val PROPERTIES = SyntaxLanguage(
        id = "properties",
        builtins = words("true false"),
        lineCommentPrefixes = listOf("#", "!"),
        stringQuotes = setOf('"', '\''),
        escapeChar = null,
        keyValueMode = true,
        keySeparators = setOf('=', ':'),
    )

    /** Ini/Conf: keys before `=`, `#` and `;` comments, `[section]` headers. */
    val INI = SyntaxLanguage(
        id = "ini",
        builtins = words("true false"),
        lineCommentPrefixes = listOf("#", ";"),
        stringQuotes = setOf('"', '\''),
        escapeChar = null,
        keyValueMode = true,
        keySeparators = setOf('='),
    )

    /**
     * Dockerfile: keywords are stored in their canonical uppercase with case sensitivity ON, so
     * a lowercase `from` inside a value string argument stays plain instead of lighting up.
     */
    val DOCKERFILE = SyntaxLanguage(
        id = "dockerfile",
        keywords = words(
            "FROM RUN CMD LABEL MAINTAINER EXPOSE ENV ADD COPY ENTRYPOINT VOLUME USER WORKDIR " +
                "ARG ONBUILD STOPSIGNAL HEALTHCHECK SHELL AS",
        ),
        lineCommentPrefixes = listOf("#"),
        stringQuotes = setOf('"', '\''),
    )

    /** Makefile: key-value mode around `:`, so targets read as keys; recipes lex generically. */
    val MAKEFILE = SyntaxLanguage(
        id = "makefile",
        lineCommentPrefixes = listOf("#"),
        stringQuotes = setOf('"', '\''),
        escapeChar = null,
        keyValueMode = true,
        keySeparators = setOf(':'),
    )

    /** C#: close enough to Java's shape to share it entirely. */
    val CSHARP = SyntaxLanguage(
        id = "csharp",
        keywords = words(
            "abstract as base bool break byte case catch char checked class const continue " +
                "decimal default delegate do double else enum event explicit extern false " +
                "finally fixed float for foreach goto if implicit in int interface internal is " +
                "lock long namespace new null object operator out override params private " +
                "protected public readonly record ref return sbyte sealed short sizeof stackalloc " +
                "static string struct switch this throw true try typeof uint ulong unchecked " +
                "unsafe ushort using var virtual void volatile while init required get set value",
        ),
        builtins = words(
            "Console String Int32 Int64 Boolean Object List Dictionary Task Action Func " +
                "IEnumerable IList ICollection",
        ),
        lineCommentPrefixes = listOf("//"),
        blockCommentOpen = "/*",
        blockCommentClose = "*/",
        stringQuotes = setOf('"'),
        charQuotes = setOf('\''),
        identifierExtraStart = setOf('$'),
        annotations = true,
        typeNameHeuristic = true,
    )

    /** Lua: `--` comments, with `--[[ ]]` block comments winning by prefix length. */
    val LUA = SyntaxLanguage(
        id = "lua",
        keywords = words(
            "and break do else elseif end false for function goto if in local nil not or repeat " +
                "return then true until while",
        ),
        builtins = words("print pairs ipairs require table string math io os type tostring tonumber self"),
        lineCommentPrefixes = listOf("--"),
        blockCommentOpen = "--[[",
        blockCommentClose = "]]",
        stringQuotes = setOf('"', '\''),
    )

    /** Perl: `#` comments, `$` and `@` sigils as identifier starts. */
    val PERL = SyntaxLanguage(
        id = "perl",
        keywords = words(
            "my our local sub if elsif else unless while until for foreach do last next redo " +
                "return use require package and or not xor eq ne lt gt le ge cmp defined",
        ),
        builtins = words(
            "print printf say chomp chop split join push pop shift unshift keys values map grep " +
                "sort reverse scalar die warn open close",
        ),
        lineCommentPrefixes = listOf("#"),
        stringQuotes = setOf('"', '\''),
        identifierExtraStart = setOf('$', '@'),
    )
}
