package dev.eclipse.ssh.feature.search

/**
 * Find-in-terminal navigation.
 *
 * The terminal already has a search field that counts matches
 * (`terminalText.lines().count { ... }` in MainActivity). The missing
 * piece is "go to the next match" / "go to the previous match", which
 * is what every IDE and every other terminal does. This module is the
 * pure-logic half: a function that takes the scrollback, the query and
 * a starting position, and returns the next or previous line index.
 *
 * The UI half (a next/prev button or `F3`/`Shift-F3` hotkeys) is a
 * Compose surface in MainActivity that calls into here; the search
 * itself does not need an Activity to be testable.
 */
object TerminalSearch {

    /**
     * One match in the scrollback: a 1-based line number and the
     * column at which the query starts on that line. The terminal's
     * text-paint path uses the line number to scroll the view, the
     * column to highlight the matched run.
     */
    data class Match(
        val line: Int,
        val column: Int,
    )

    /**
     * The next match at or after [startLine].
     *
     * @param scrollback the full terminal text, line by line. Empty
     *   lines are included so the 1-based line number stays in sync
     *   with the terminal's own line counter.
     * @param query the literal text the user typed
     * @param startLine 1-based line number to start searching from
     * @param ignoreCase default true — a search for "Foo" finds "foo"
     * @return the next match, or `null` when there is none
     */
    fun findNext(
        scrollback: List<String>,
        query: String,
        startLine: Int,
        ignoreCase: Boolean = true,
    ): Match? {
        if (query.isEmpty()) return null
        if (scrollback.isEmpty()) return null
        val clamped = startLine.coerceIn(1, scrollback.size)
        for (i in clamped..scrollback.size) {
            val line = scrollback[i - 1]
            val col = line.indexOf(query, 0, ignoreCase)
            if (col >= 0) return Match(i, col + 1)
        }
        // Wrap around: search from line 1 to startLine - 1.
        for (i in 1 until clamped) {
            val line = scrollback[i - 1]
            val col = line.indexOf(query, 0, ignoreCase)
            if (col >= 0) return Match(i, col + 1)
        }
        return null
    }

    /**
     * The previous match at or before [startLine]. Wraps around the
     * scrollback in the other direction.
     */
    fun findPrevious(
        scrollback: List<String>,
        query: String,
        startLine: Int,
        ignoreCase: Boolean = true,
    ): Match? {
        if (query.isEmpty()) return null
        if (scrollback.isEmpty()) return null
        val clamped = startLine.coerceIn(1, scrollback.size)
        for (i in clamped downTo 1) {
            val line = scrollback[i - 1]
            val col = line.lastIndexOf(query, ignoreCase = ignoreCase)
            if (col >= 0) return Match(i, col + 1)
        }
        // Wrap around: search from size down to startLine + 1.
        for (i in scrollback.size downTo clamped + 1) {
            val line = scrollback[i - 1]
            val col = line.lastIndexOf(query, ignoreCase = ignoreCase)
            if (col >= 0) return Match(i, col + 1)
        }
        return null
    }

    /**
     * Counts the number of matches in [scrollback], ignoring case.
     * Returns 0 for an empty query, which the UI surfaces as "no
     * matches" rather than "0 matches".
     */
    fun count(scrollback: List<String>, query: String, ignoreCase: Boolean = true): Int {
        if (query.isEmpty()) return 0
        return scrollback.count { it.contains(query, ignoreCase) }
    }
}
