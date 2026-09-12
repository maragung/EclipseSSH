package dev.eclipse.ssh.ui.editor.highlight

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The syntax transformation layer, pinned through its pure seam.
 *
 * [syntaxSpansFor] is the whole transformation minus the VisualTransformation machinery, so what
 * these tests pin is exactly the contract the editor's text field will rest on: spans land on the
 * engine's exact offsets, unknown names and empty documents produce nothing, and the palette
 * derived from a scheme keeps its roles distinct in the ways the coloring depends on. The
 * engine's own offset promises are pinned by [SyntaxHighlighterTest]; this suite only checks the
 * layer that converts them to Compose spans, which is why it runs under Robolectric like the
 * other Compose-typed tests rather than as plain JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyntaxHighlighterComposeTest {

    @Test
    fun `a Kotlin keyword span lands on the exact keyword offsets`() {
        // The field applies a span by offset onto text it already holds, so the offsets - not
        // the words - are the contract: shift this by one and the space after `val` lights up
        // instead of the word. `val x = 1` is the smallest line with a keyword in it.
        val colors = SyntaxColors.fromScheme(darkColorScheme())
        val spans = syntaxSpansFor("val x = 1", "Editor.kt", colors)
        val keyword = spans.single { it.item.color == colors.keyword }
        assertThat(keyword.start).isEqualTo(0)
        assertThat(keyword.end).isEqualTo(3)
    }

    @Test
    fun `an unknown extension yields no spans`() {
        // The registry's stance, carried through the seam: a name with no evidence gets no
        // language, and no language means no spans at all - never a guess. The text below is
        // valid Kotlin, so this pins the name (not the content) as the deciding input.
        val colors = SyntaxColors.fromScheme(darkColorScheme())
        assertThat(syntaxSpansFor("val x = 1", "notes.xyz", colors)).isEmpty()
    }

    @Test
    fun `an empty document yields no spans`() {
        // An empty AnnotatedString built with an empty span list is exactly what the
        // transformation must produce for an empty field: no tokens, no allocations beyond the
        // list, and nothing for the IME's first keystroke to carry.
        val colors = SyntaxColors.fromScheme(darkColorScheme())
        assertThat(syntaxSpansFor("", "Editor.kt", colors)).isEmpty()
    }

    @Test
    fun `dark and light schemes keep keyword and comment colors distinct`() {
        // The palette's whole premise is that a reader tells "grammar" from "noise" by color
        // alone, in both themes; if a scheme ever mapped keyword and comment to the same role,
        // every file would look correct in the other theme and wrong in this one. The two
        // baseline schemes are used directly because the contract is about role *choices*, not
        // about any particular app customization of them.
        val dark = SyntaxColors.fromScheme(darkColorScheme())
        val light = SyntaxColors.fromScheme(lightColorScheme())
        assertThat(dark.keyword).isNotEqualTo(dark.comment)
        assertThat(light.keyword).isNotEqualTo(light.comment)
        // And the schemes are not quietly producing one palette: primary itself differs.
        assertThat(dark.keyword).isNotEqualTo(light.keyword)
    }
}
