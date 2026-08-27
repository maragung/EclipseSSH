package dev.eclipse.ssh.feature.diff

/**
 * A line-level diff between two text files.
 *
 * Uses a standard LCS (longest common subsequence) dynamic program. O(N*M)
 * in time and space, which is fine for the file sizes the file browser
 * shows — a 5 000-line config or log file diffs in a few milliseconds.
 * The Myers algorithm would be asymptotically better for files that share
 * most of their content, but the LCS implementation is simpler to read
 * and to test, and the input the file browser feeds it is bounded by
 * the [ListingLimits] cap (5 000 rows per listing).
 *
 * The output is a list of [Hunk]s, each a contiguous run of changes with
 * surrounding equal context. The renderer is a Compose screen in the
 * Files tab; the algorithm has no Android dependencies and is fully
 * unit-tested.
 */
object Diff {

    /**
     * Computes the diff of [oldText] and [newText] line by line.
     *
     * Splits on `\n`, which means a trailing newline is significant: "a\n"
     * and "a" are different. The file browser feeds both sides through
     * the same `readText()` path so the convention is consistent.
     */
    fun lineDiff(
        oldText: String,
        newText: String,
        contextLines: Int = 3,
    ): List<Hunk> {
        require(contextLines >= 0) { "contextLines must be >= 0" }
        val oldLines = splitLines(oldText)
        val newLines = splitLines(newText)
        val ops = computeOps(oldLines, newLines)
        return buildHunks(ops, contextLines)
    }

    /**
     * One run of changes with surrounding equal context. [oldStart] and
     * [newStart] are 1-based line numbers; an equal line has the same
     * number on both sides. The hunk is anchored to a span in each file
     * and the [changes] list is what the renderer paints.
     */
    data class Hunk(
        val oldStart: Int,
        val newStart: Int,
        val changes: List<Change>,
    ) {
        /** Number of lines the hunk covers in the old file. */
        val oldCount: Int get() = changes.count { it !is Change.Insert }

        /** Number of lines the hunk covers in the new file. */
        val newCount: Int get() = changes.count { it !is Change.Delete }
    }

    /**
     * One line of the diff. The [text] is the *new* file's text for
     * [Equal] / [Insert] and the *old* file's text for [Delete]. The
     * renderer colours by kind.
     */
    sealed interface Change {
        data class Equal(val text: String) : Change
        data class Insert(val text: String) : Change
        data class Delete(val text: String) : Change
    }

    private sealed interface Op {
        data class Keep(val text: String) : Op
        data class Add(val text: String) : Op
        data class Remove(val text: String) : Op
    }

    /**
     * Standard LCS table, then backtrack. The table is `(oldSize + 1) x
     * (newSize + 1)` ints; for 5 000 lines that's 25 million cells —
     * 100 MB as `IntArray`. Realistic cap is 2 000 lines per file in
     * the file browser, so the worst case is 4 million cells / 16 MB.
     */
    private fun computeOps(old: List<String>, new: List<String>): List<Op> {
        val n = old.size
        val m = new.size
        if (n == 0 && m == 0) return emptyList()
        if (n == 0) return new.map { Op.Add(it) }
        if (m == 0) return old.map { Op.Remove(it) }

        // `lcs[i][j]` is the length of the LCS of old[0..i) and new[0..j).
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in 1..n) {
            for (j in 1..m) {
                lcs[i][j] = if (old[i - 1] == new[j - 1]) {
                    lcs[i - 1][j - 1] + 1
                } else {
                    maxOf(lcs[i - 1][j], lcs[i][j - 1])
                }
            }
        }
        // Backtrack from (n, m) to (0, 0), building the op list in reverse.
        val ops = ArrayDeque<Op>()
        var i = n
        var j = m
        while (i > 0 && j > 0) {
            if (old[i - 1] == new[j - 1]) {
                ops.addFirst(Op.Keep(old[i - 1]))
                i--
                j--
            } else if (lcs[i - 1][j] >= lcs[i][j - 1]) {
                ops.addFirst(Op.Remove(old[i - 1]))
                i--
            } else {
                ops.addFirst(Op.Add(new[j - 1]))
                j--
            }
        }
        while (i > 0) {
            ops.addFirst(Op.Remove(old[i - 1]))
            i--
        }
        while (j > 0) {
            ops.addFirst(Op.Add(new[j - 1]))
            j--
        }
        return ops.toList()
    }

    /**
     * Walks the op list and groups runs of changes that are within
     * `2 * contextLines` equal lines of each other. The pattern is the
     * same one `diff -U` uses: equal lines are emitted as context
     * around the change, the leading and trailing context are trimmed
     * to `contextLines` so the displayed hunk is compact.
     */
    private fun buildHunks(ops: List<Op>, contextLines: Int): List<Hunk> {
        if (ops.isEmpty()) return emptyList()
        val hunks = ArrayList<Hunk>()
        var i = 0
        var oldLine = 1
        var newLine = 1
        while (i < ops.size) {
            // Skip equal lines until we hit a change.
            while (i < ops.size && ops[i] is Op.Keep) {
                oldLine++
                newLine++
                i++
            }
            if (i >= ops.size) break
            // The change is at ops[i]. Walk back up to contextLines
            // equal lines for the leading context.
            var leadContext = 0
            while (leadContext < contextLines && i - leadContext - 1 >= 0 && ops[i - leadContext - 1] is Op.Keep) {
                leadContext++
            }
            val hunkOldStart = (oldLine - leadContext).coerceAtLeast(1)
            val hunkNewStart = (newLine - leadContext).coerceAtLeast(1)
            val changes = ArrayList<Change>(2 * contextLines + 4)
            // Emit the leading context (oldest first).
            for (k in leadContext - 1 downTo 0) {
                val text = (ops[i - k - 1] as Op.Keep).text
                changes.add(Change.Equal(text))
            }
            // Emit the change run, plus up to `contextLines` equal
            // lines for the trailing context.
            var j = i
            var trailingContext = 0
            var seenChange = false
            while (j < ops.size) {
                when (val op = ops[j]) {
                    is Op.Keep -> {
                        if (seenChange && trailingContext >= contextLines) break
                        changes.add(Change.Equal(op.text))
                        oldLine++
                        newLine++
                        trailingContext++
                    }
                    is Op.Add -> {
                        changes.add(Change.Insert(op.text))
                        newLine++
                        seenChange = true
                        trailingContext = 0
                    }
                    is Op.Remove -> {
                        changes.add(Change.Delete(op.text))
                        oldLine++
                        seenChange = true
                        trailingContext = 0
                    }
                }
                j++
            }
            // Trim trailing equal lines past the contextLines cap.
            while (trailingContext > contextLines && changes.last() is Change.Equal) {
                changes.removeAt(changes.size - 1)
                oldLine--
                newLine--
                trailingContext--
            }
            hunks.add(Hunk(hunkOldStart, hunkNewStart, changes))
            i = j
        }
        return hunks
    }

    private fun splitLines(s: String): List<String> {
        if (s.isEmpty()) return emptyList()
        // -1 limit on split keeps trailing empty strings ("a\n" -> ["a", ""]
        // rather than ["a"]). That matches the convention `wc -l` and
        // `git diff` use for the trailing newline.
        return s.split("\n")
    }
}
