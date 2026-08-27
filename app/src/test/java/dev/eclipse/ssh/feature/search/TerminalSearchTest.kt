package dev.eclipse.ssh.feature.search

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TerminalSearchTest {

    private val lines = listOf(
        "the quick brown fox",
        "jumps over the lazy dog",
        "the second the of the day",
        "no match here",
    )

    @Test
    fun `findNext from line 1 returns the first match`() {
        val m = TerminalSearch.findNext(lines, "the", 1)
        assertThat(m).isEqualTo(TerminalSearch.Match(1, 1))
    }

    @Test
    fun `findNext from a line after a match skips it`() {
        // Starting from line 2, find the next "the" — which is on
        // line 3 at column 12 (the "the" inside "the lazy dog"). The
        // earlier "the" on line 1 is at column 1; we are past line 1.
        val m = TerminalSearch.findNext(lines, "the", 2)
        assertThat(m).isEqualTo(TerminalSearch.Match(2, 12))
    }

    @Test
    fun `findNext wraps around to the top`() {
        val m = TerminalSearch.findNext(lines, "the", 4)
        assertThat(m).isEqualTo(TerminalSearch.Match(1, 1))
    }

    @Test
    fun `findNext with no matches returns null`() {
        val m = TerminalSearch.findNext(lines, "unicorn", 1)
        assertThat(m).isNull()
    }

    @Test
    fun `findPrevious from the last line goes up`() {
        val m = TerminalSearch.findPrevious(lines, "the", 4)
        // Line 3 has "the" at column 1 and "the" again at column 19.
        // findPrevious from line 4 returns the rightmost match on
        // the line where "the" last appears, which is line 3.
        // Within line 3, `lastIndexOf` returns the rightmost column
        // (the "the" at column 19, the second "the" on that line).
        assertThat(m).isEqualTo(TerminalSearch.Match(3, 19))
    }

    @Test
    fun `findPrevious wraps to the bottom`() {
        val m = TerminalSearch.findPrevious(lines, "the", 1)
        // From line 1, the previous "the" is on line 3 at the rightmost
        // position, then no more — it wraps to the bottom and finds
        // line 1 at column 1.
        assertThat(m).isEqualTo(TerminalSearch.Match(1, 1))
    }

    @Test
    fun `findNext is case-insensitive by default`() {
        val m = TerminalSearch.findNext(lines, "FOX", 1)
        assertThat(m).isEqualTo(TerminalSearch.Match(1, 17))
    }

    @Test
    fun `findNext with a case-sensitive search skips the uppercase line`() {
        val m = TerminalSearch.findNext(lines, "FOX", 1, ignoreCase = false)
        // The "fox" in line 1 is lowercase, so a case-sensitive search
        // misses it and returns null.
        assertThat(m).isNull()
    }

    @Test
    fun `count counts every line that contains the query`() {
        // "the" appears in 3 of the 4 lines (line 4 has "no match here").
        // The function returns the number of matching lines, not the
        // total number of occurrences.
        assertThat(TerminalSearch.count(lines, "the")).isEqualTo(3)
    }

    @Test
    fun `count with an empty query returns 0`() {
        assertThat(TerminalSearch.count(lines, "")).isEqualTo(0)
    }

    @Test
    fun `findNext with an empty scrollback returns null`() {
        assertThat(TerminalSearch.findNext(emptyList(), "the", 1)).isNull()
    }

    @Test
    fun `findNext with a clamped startLine still finds matches past it`() {
        // startLine=99 is beyond the end; we clamp it to the size and
        // search from there. Wraps around if needed.
        val m = TerminalSearch.findNext(lines, "fox", 99)
        assertThat(m).isEqualTo(TerminalSearch.Match(1, 17))
    }
}
