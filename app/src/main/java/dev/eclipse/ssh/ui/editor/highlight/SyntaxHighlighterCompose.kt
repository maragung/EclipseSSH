package dev.eclipse.ssh.ui.editor.highlight

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The editor's syntax palette: one color per [TokenKind] the engine emits, resolved from the
 * app's [ColorScheme] so a dark and a light theme each get a palette that was designed together
 * with its background.
 *
 * The role choices are constrained by a fact about Material3 that is easy to get wrong: only a
 * handful of roles are readable as *text on the surface* in BOTH schemes. The `onPrimary`-style
 * roles are near-white in the light scheme (they are designed to sit on the dark containers, not
 * on the light background), and the `*Container` roles are near-background in whichever scheme
 * their container is pale. What remains is essentially primary, secondary, tertiary, error,
 * outline, onSurfaceVariant, inverseSurface and onSurface - so the palette below reuses those
 * deliberately, and each reuse says why in [fromScheme].
 *
 * [Immutable] so Compose can skip recomposition of anything holding a palette that has not
 * changed - the transformation is rebuilt per edit, and the palette should never be the reason.
 */
@Immutable
data class SyntaxColors(
    /** The color of unremarkable text - by contract the field's own TextStyle color, see [fromScheme]. */
    val plain: Color,
    /** Grammar words: `val`, `fun`, `return`. */
    val keyword: Color,
    /** The language's own vocabulary: types, constants, `self`. */
    val builtin: Color,
    /** Quoted strings, including raw and triple-quoted ones. */
    val string: Color,
    /** Character literals - a one-glyph string, and colored as its family. */
    val char: Color,
    /** Numeric literals. */
    val number: Color,
    /** Block comments. */
    val comment: Color,
    /** Line comments - conventionally slightly stronger than block ones. */
    val lineComment: Color,
    /** Operators and punctuation. */
    val operator: Color,
    /** `@`-annotations and Python decorators. */
    val annotation: Color,
    /** Capitalized identifiers the type-name heuristic lifted. */
    val typeName: Color,
) {

    /**
     * The color of a token kind. Exhaustive over [TokenKind] on purpose: a kind added to the
     * engine must fail this mapping's compilation, not silently fall back to plain - a missing
     * color is a bug to find in the build, not a subtle wrong color to find on a device.
     */
    fun colorOf(kind: TokenKind): Color = when (kind) {
        TokenKind.PLAIN -> plain
        TokenKind.KEYWORD -> keyword
        TokenKind.BUILTIN -> builtin
        TokenKind.STRING -> string
        TokenKind.CHAR -> char
        TokenKind.NUMBER -> number
        TokenKind.COMMENT -> comment
        TokenKind.LINE_COMMENT -> lineComment
        TokenKind.OPERATOR -> operator
        TokenKind.ANNOTATION -> annotation
        TokenKind.TYPE_NAME -> typeName
    }

    companion object {

        /**
         * Maps every [TokenKind] onto a role of [scheme]. The choices, and why each reused role
         * is reused:
         */
        fun fromScheme(scheme: ColorScheme): SyntaxColors = SyntaxColors(
            // Material's default content color, and the color the editor's TextStyle already
            // paints the field with - plain runs are therefore NOT spanned (see
            // [syntaxSpansFor]), which makes this value a contract: whatever calls
            // [syntaxTransformationFor] must keep the field's TextStyle color equal to it.
            plain = scheme.onSurface,
            // Grammar words carry the scheme's leading accent: they shape the code, and primary
            // is the one color every other element in the app defers to.
            keyword = scheme.primary,
            // The language's own vocabulary one step calmer than its grammar - loud enough to
            // read as "not my name", quiet enough that a line of `String` and `Int` does not
            // shout like a line of keywords.
            builtin = scheme.secondary,
            // The third accent, spent on the one thing in code that is genuinely "other" text.
            string = scheme.tertiary,
            // A char literal is a one-glyph string; giving it the fourth-safest role rather than
            // sharing string's would buy a distinction no reader of code has ever needed.
            char = scheme.tertiary,
            // A literal is the same class of fixed thing as the builtin constants (`true`,
            // `EXIT_SUCCESS`), so it shares their calm accent rather than inventing a fifth.
            number = scheme.secondary,
            // Block comments recede furthest: outline is the dimmest role that stays readable
            // on the surface in both schemes.
            comment = scheme.outline,
            // The engine split comment kinds because line comments are conventionally slightly
            // stronger; onSurfaceVariant is exactly one step above outline in both schemes.
            lineComment = scheme.onSurfaceVariant,
            // Punctuation sits one step off prose. It shares the line-comment role because the
            // two can never be mistaken for one another, and the set of both-scheme-readable
            // text roles is too small to spend a unique one here.
            operator = scheme.onSurfaceVariant,
            // An annotation is a declarative name, closer to vocabulary than to grammar - the
            // secondary calm keeps a heavily-annotated declaration from reading as all-keyword.
            annotation = scheme.secondary,
            // Builtins ARE type names (`Int`, `String`, `List`); the heuristic only catches the
            // ones the word list missed, so the two kinds share a color by identity, not thrift.
            typeName = scheme.secondary,
        )
    }
}

/**
 * The character budget the transformation will lex and span per filter call: 128 KiB of
 * characters, an order of magnitude under the editor's 512 KiB open-guard, because the two
 * budgets buy different things. The open-guard bounds what a file *load* may cost once; this
 * bounds what a *keystroke* may cost forever - `filter` re-runs on every edit, the engine is
 * O(n) but the span list is proportional to color changes, and rebuilding an AnnotatedString of
 * six figures of ranges per keystroke is where the IME starts dropping frames. Text past the
 * budget is left plain (the spans simply stop), never dropped from the document: a
 * multi-megabyte paste still edits, saves and undoes - it just stops being colored.
 */
private const val MAX_HIGHLIGHT_CHARS = 128 * 1024

/**
 * How long the text has to stand still before its colors are recomputed.
 *
 * A keystroke used to lex the document, build every span and hand Compose a fresh AnnotatedString
 * before the character could be drawn, which is why typing felt heavy in a large file. The wait is
 * short enough to be invisible at a pause and long enough that a burst of typing costs one lex
 * instead of one per character. It buys the echo of the character, which is what the eye is waiting
 * for; the color is a way of looking at the text and can arrive a moment later.
 */
internal const val HIGHLIGHT_DEBOUNCE_MS = 120L

/**
 * Colors the editor's text by the syntax of [language], leaving the value itself untouched.
 *
 * A [VisualTransformation] rather than styled text in the field's value, for the same reason the
 * find-match highlight is one: the value stays the plain text the user owns, so undo history,
 * save comparison and find offsets all keep working on unshifted offsets - the color is purely a
 * way of *looking* at the text. [OffsetMapping.Identity] because no character is inserted or
 * removed; only spans are layered on. A null [language] passes the text through unchanged, so a
 * caller that failed to resolve a name degrades to plain rather than crashing.
 *
 * [spans] is the escape hatch that keeps the lex off the keystroke path: when a caller has already
 * computed the coloring for this document - on a background dispatcher, for the text as it stood a
 * moment ago - it passes it here and `filter` does no work but the layering. See
 * [debouncedSyntaxTransformationFor].
 *
 * Spans that no longer fit are dropped rather than applied, and that is not only a guard: they are
 * kept while they fit, so a character typed at the end of a document keeps the coloring the rest of
 * it already had. The engine reads left to right, so appending cannot change how the text before the
 * caret was colored; it is only an edit in the middle that makes the old spans wrong, and that is
 * the one case where they are thrown away and the text is drawn plain until the next lex lands.
 */
class SyntaxHighlightTransformation(
    private val language: SyntaxLanguage?,
    private val colors: SyntaxColors,
    private val spans: List<AnnotatedString.Range<SpanStyle>>? = null,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (language == null) return TransformedText(text, OffsetMapping.Identity)
        val usable = spans ?: coloredSpans(text.text, language, colors)
        return TransformedText(
            AnnotatedString(text.text, if (usable.fitsWithin(text.text)) usable else emptyList()),
            OffsetMapping.Identity,
        )
    }
}

/**
 * Whether a span list computed for one text can be drawn over another.
 *
 * The spans arrive in document order from the engine, so the last one carries the furthest offset and
 * one comparison answers it. An empty list fits anything, which is what makes "not colored yet" and
 * "colored, and none of it survived the edit" the same answer here.
 */
private fun List<AnnotatedString.Range<SpanStyle>>.fitsWithin(text: String): Boolean =
    isEmpty() || last().end <= text.length

/**
 * The transformation for a document's file name: its language's colors when the registry knows
 * the name, [VisualTransformation.None] when it does not - an unknown extension falls back to
 * plain text rather than guessing, which is the registry's own stance.
 */
fun syntaxTransformationFor(fileName: String, colors: SyntaxColors): VisualTransformation {
    val language = SyntaxRegistry.forFileName(fileName)
    return if (language == null) VisualTransformation.None else SyntaxHighlightTransformation(language, colors)
}

/**
 * The same transformation, with the lexing moved off the composition and off every keystroke.
 *
 * The document is lexed on [Dispatchers.Default] once [text] has been still for
 * [HIGHLIGHT_DEBOUNCE_MS], and what it produced is handed to the field from a state a recomposition
 * can see. Until then the field draws the text with whatever spans still fit it, which is usually
 * all of them: typing at the end of a line appends past the last span and changes nothing else.
 *
 * The delay is skipped for the first coloring of a file, because that one is the document opening.
 * A tab the user just opened must come up colored, not flash plain for a tenth of a second - the
 * debounce exists for keystrokes, and there are none yet.
 */
@Composable
fun debouncedSyntaxTransformationFor(
    fileName: String,
    colors: SyntaxColors,
    text: String,
): VisualTransformation {
    val language = remember(fileName) { SyntaxRegistry.forFileName(fileName) }
    if (language == null) return VisualTransformation.None
    val spans = rememberDebouncedSpans(language, colors, text)
    return remember(language, spans) { SyntaxHighlightTransformation(language, colors, spans) }
}

/** The coloring of [text], computed off the main thread once the typing stops. See [debouncedSyntaxTransformationFor]. */
@Composable
private fun rememberDebouncedSpans(
    language: SyntaxLanguage,
    colors: SyntaxColors,
    text: String,
): List<AnnotatedString.Range<SpanStyle>> {
    val spans = remember(language, colors) { mutableStateOf(emptyList<AnnotatedString.Range<SpanStyle>>()) }
    // Whether this file has been colored once already: the first pass is an open, not an edit.
    val opened = remember(language, colors) { mutableStateOf(false) }
    LaunchedEffect(language, colors, text) {
        if (opened.value) delay(HIGHLIGHT_DEBOUNCE_MS)
        val computed = withContext(Dispatchers.Default) { coloredSpans(text, language, colors) }
        spans.value = computed
        opened.value = true
    }
    return spans.value
}

/**
 * The colored spans of [text] as the editor would apply them for [fileName] - the transformation
 * minus Compose's [VisualTransformation] machinery, so the offset and color contract is testable
 * without a text field.
 *
 * Returns only the spans that *change* color. PLAIN runs are skipped: they are the field's own
 * text color ([SyntaxColors.plain]), so spanning them would roughly double the span count for
 * zero visual change - and span count is the per-keystroke cost this layer exists to bound. An
 * unknown [fileName] or an empty [text] yields no spans at all.
 */
internal fun syntaxSpansFor(
    text: String,
    fileName: String,
    colors: SyntaxColors,
): List<AnnotatedString.Range<SpanStyle>> {
    val language = SyntaxRegistry.forFileName(fileName) ?: return emptyList()
    return coloredSpans(text, language, colors)
}

/**
 * Token list to color spans: the one place the engine's output meets Compose's input.
 *
 * Both halves are offset-only by design - the engine emits no substrings and Compose applies
 * spans by offset - so the whole conversion is a walk that cannot shift a character.
 */
private fun coloredSpans(
    text: String,
    language: SyntaxLanguage,
    colors: SyntaxColors,
): List<AnnotatedString.Range<SpanStyle>> {
    if (text.isEmpty()) return emptyList()
    val source = if (text.length <= MAX_HIGHLIGHT_CHARS) {
        text
    } else {
        // Cut at the last complete line inside the budget when one exists: the engine already
        // ends an unterminated string at its line's end, so a whole-line cut can only miscolor a
        // block comment or raw string that genuinely continues past the budget - which is the
        // same thing the reader sees either way. A document with no newline in its first 128 Ki
        // (one enormous line) cuts exactly at the budget instead.
        val newline = text.lastIndexOf('\n', MAX_HIGHLIGHT_CHARS)
        text.substring(0, if (newline >= 0) newline else MAX_HIGHLIGHT_CHARS)
    }
    val tokens = SyntaxHighlighter(language).highlight(source)
    val spans = ArrayList<AnnotatedString.Range<SpanStyle>>(tokens.size)
    for (token in tokens) {
        // The PLAIN skip: see [syntaxSpansFor]. Everything else becomes exactly one span,
        // because the engine already merged adjacent same-kind runs - its token count IS the
        // minimal color-change count, and second-guessing it here would only add allocations.
        if (token.kind == TokenKind.PLAIN) continue
        spans.add(AnnotatedString.Range(SpanStyle(color = colors.colorOf(token.kind)), token.start, token.end))
    }
    return spans
}
