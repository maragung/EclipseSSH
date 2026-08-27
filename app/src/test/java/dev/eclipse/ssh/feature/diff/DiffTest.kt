package dev.eclipse.ssh.feature.diff

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DiffTest {

    @Test
    fun `identical texts produce no hunks`() {
        val hunks = Diff.lineDiff("a\nb\nc", "a\nb\nc")
        assertThat(hunks).isEmpty()
    }

    @Test
    fun `empty old produces only inserts`() {
        val hunks = Diff.lineDiff("", "a\nb\nc")
        assertThat(hunks).hasSize(1)
        val changes = hunks[0].changes
        assertThat(changes).containsExactly(
            Diff.Change.Insert("a"),
            Diff.Change.Insert("b"),
            Diff.Change.Insert("c"),
        ).inOrder()
    }

    @Test
    fun `empty new produces only deletes`() {
        val hunks = Diff.lineDiff("a\nb\nc", "")
        assertThat(hunks).hasSize(1)
        assertThat(hunks[0].changes).containsExactly(
            Diff.Change.Delete("a"),
            Diff.Change.Delete("b"),
            Diff.Change.Delete("c"),
        ).inOrder()
    }

    @Test
    fun `a single line replacement is one hunk`() {
        val hunks = Diff.lineDiff("a\nb\nc", "a\nB\nc")
        assertThat(hunks).hasSize(1)
        val h = hunks[0]
        // The "b" -> "B" change. The exact order of Insert vs Delete
        // depends on the backtrack path, but both must be present
        // and the surrounding lines are unchanged.
        assertThat(h.changes).contains(Diff.Change.Equal("a"))
        assertThat(h.changes).contains(Diff.Change.Delete("b"))
        assertThat(h.changes).contains(Diff.Change.Insert("B"))
        assertThat(h.changes).contains(Diff.Change.Equal("c"))
    }

    @Test
    fun `two separated changes form two hunks`() {
        val old = (1..20).joinToString("\n") { "line$it" } + "\n"
        val new = buildString {
            (1..20).forEach { i ->
                when (i) {
                    6 -> append("CHANGED-A\n")
                    13 -> append("CHANGED-B\n")
                    else -> append("line$i\n")
                }
            }
        }
        val hunks = Diff.lineDiff(old, new, contextLines = 3)
        assertThat(hunks).hasSize(2)
        // Each hunk has the change plus its surrounding context.
        assertThat(hunks[0].changes).contains(Diff.Change.Delete("line6"))
        assertThat(hunks[0].changes).contains(Diff.Change.Insert("CHANGED-A"))
        assertThat(hunks[1].changes).contains(Diff.Change.Delete("line13"))
        assertThat(hunks[1].changes).contains(Diff.Change.Insert("CHANGED-B"))
        for (h in hunks) {
            assertThat(h.changes.size).isAtMost(2 * 3 + 4)
        }
    }

    @Test
    fun `hunk oldStart and newStart are 1-based line numbers`() {
        val old = "a\nb\nc\nd\ne\nf\ng\nh\ni\nj"
        val new = "a\nb\nc\nd\nX\nf\ng\nh\ni\nj"
        val hunks = Diff.lineDiff(old, new, contextLines = 1)
        assertThat(hunks).hasSize(1)
        // The change is on line 5 of both files (1-based).
        assertThat(hunks[0].oldStart).isEqualTo(4) // 1-based with 1 line lead context
        assertThat(hunks[0].newStart).isEqualTo(4)
    }

    @Test
    fun `the lcs is preserved across changes`() {
        // The unchanged lines around the change must still appear in
        // the output as Equal entries; LCS guarantees the count.
        val old = listOf("a", "b", "c", "d", "e", "f", "g").joinToString("\n")
        val new = listOf("a", "b", "c", "X", "e", "f", "g").joinToString("\n")
        val hunks = Diff.lineDiff(old, new)
        val equals = hunks.flatMap { it.changes }.filterIsInstance<Diff.Change.Equal>()
        assertThat(equals.map { it.text }).containsExactly("a", "b", "c", "e", "f", "g")
    }

    @Test
    fun `a trailing newline is significant`() {
        // "a\n" and "a" differ in exactly one line: the empty line that
        // follows the "a" in the second input.
        val hunks = Diff.lineDiff("a", "a\n")
        assertThat(hunks).hasSize(1)
        val ch = hunks[0].changes
        // The "a" line is equal. The new file has an additional empty line.
        assertThat(ch).contains(Diff.Change.Equal("a"))
        assertThat(ch).contains(Diff.Change.Insert(""))
    }

    @Test
    fun `a 2000 line file diffs without blowing memory`() {
        val a = (1..2000).joinToString("\n") { "L$it" }
        val b = buildString {
            (1..2000).forEach { i ->
                if (i == 1000) append("CHANGED\n") else append("L$i\n")
            }
        }
        // The trailing newline adds an empty line to b's split, so
        // the diff has at most 3 hunks: the change on line 1000, the
        // trailing context, and the trailing empty line.
        val hunks = Diff.lineDiff(a, b, contextLines = 3)
        assertThat(hunks.size).isAtMost(3)
        // The first hunk is the change on line 1000.
        val first = hunks[0]
        assertThat(first.changes).contains(Diff.Change.Insert("CHANGED"))
        assertThat(first.changes).contains(Diff.Change.Delete("L1000"))
    }
}
