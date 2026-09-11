package dev.eclipse.ssh.feature.terminallog

/**
 * A bounded transcript of one terminal session's raw output, for "Save session log".
 *
 * Holds the last [DEFAULT_CAPACITY_CHARS] characters of what the session's channel produced,
 * decoded: ANSI escape sequences included, exactly as `script(1)` records a typescript. That is
 * deliberate — the plain-text view of the screen is what "Save text" and "Save logs" already
 * export from the terminal buffer, so a *session* log earns its name by being the stream itself,
 * with the colours and cursor movement the far end sent, rather than a third copy of the same
 * rendered text.
 *
 * The capacity is 256 KiB of characters: enough for the whole tail of a long build, bounded for a
 * phone. Terminal output is overwhelmingly ASCII, where one character is one byte, so counting
 * characters is both the bound on retained memory and an honest reading of "256 KiB" for the
 * output that actually fills it; text beyond ASCII only ever makes the true byte count *larger*
 * than the character count, so the buffer never holds more than it was promised.
 *
 * Thread-safe by design: the terminal collector appends from its own coroutine while the main
 * thread reads a snapshot for the export picker, so both operations synchronise on the buffer.
 *
 * Eviction drops from the front, and prefers to land on a line boundary: a saved log that starts
 * mid-command is harder to read than one missing a few of its oldest characters. A surrogate
 * pair is never split, so the snapshot is always a valid string no matter where the cut lands.
 */
class SessionLog(private val capacityChars: Int = DEFAULT_CAPACITY_CHARS) {

    init {
        require(capacityChars > 0) { "capacityChars must be positive" }
    }

    private val lock = Any()
    private val text = StringBuilder()

    /**
     * Appends [chunk] of decoded output and evicts from the front if the buffer is over capacity.
     *
     * A whole chunk goes in or none of it does — there is no partial append — so [snapshot] can
     * never observe a chunk torn in half from the writer's side; only the *oldest* content is
     * ever truncated, by eviction.
     */
    fun append(chunk: String) {
        if (chunk.isEmpty()) return
        synchronized(lock) {
            text.append(chunk)
            trimLocked()
        }
    }

    /** The retained output, oldest first. Empty when the session has produced nothing. */
    fun snapshot(): String = synchronized(lock) { text.toString() }

    /** Whether anything has been recorded. */
    fun isEmpty(): Boolean = synchronized(lock) { text.isEmpty() }

    /**
     * Brings the buffer back under [capacityChars], dropping the oldest characters.
     *
     * The cut is extended forward to the next newline when one is close by, so the retained text
     * starts at a line boundary "where practical" — bounded by [LINE_BOUNDARY_SLACK] so a stream
     * with no newlines at all (a single enormous progress-bar line) still evicts promptly rather
     * than growing without limit while searching for a boundary that does not exist.
     */
    private fun trimLocked() {
        if (text.length <= capacityChars) return
        var cut = text.length - capacityChars
        val newline = text.indexOf('\n', cut - 1)
        if (newline in cut until cut + LINE_BOUNDARY_SLACK) cut = newline + 1
        // A cut between a high and a low surrogate would leave half a codepoint at the front of
        // every snapshot. Moving it one character forward keeps the pair in the evicted half.
        if (cut in 1 until text.length &&
            Character.isHighSurrogate(text[cut - 1]) && Character.isLowSurrogate(text[cut])
        ) {
            cut++
        }
        text.delete(0, cut)
    }

    companion object {
        /**
         * How much output one session keeps: 256 KiB of characters. Large enough to hold the
         * interesting tail of a long build log, small enough that a phone holds one per open
         * session without noticing. See the class doc for why the count is of characters.
         */
        const val DEFAULT_CAPACITY_CHARS = 256 * 1024

        /** How far past the capacity point eviction will look for a line boundary. */
        const val LINE_BOUNDARY_SLACK = 8 * 1024
    }
}
