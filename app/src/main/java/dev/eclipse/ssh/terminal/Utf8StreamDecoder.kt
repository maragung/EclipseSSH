package dev.eclipse.ssh.terminal

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction

/**
 * Decodes a byte stream to text one arbitrary chunk at a time, carrying an incomplete UTF-8 sequence
 * across the boundary.
 *
 * This exists because a network read boundary has nothing to do with a character boundary. Apache
 * MINA hands over whatever arrived in a packet, and a multi-byte codepoint is split across two of
 * those reads whenever it happens to straddle the edge. `String(bytes, UTF_8)` per chunk turns each
 * half into U+FFFD, so the box-drawing characters `ncurses` uses for every dialog border, the arrows
 * in `git log --graph`, the powerline glyphs in a modern prompt and any non-Latin filename come out
 * as replacement characters at random intervals — the more traffic, the more often. Worse, the
 * corruption is *silent and permanent*: the mangled text is what goes into the scrollback, so it is
 * still wrong when the user scrolls back to read it.
 *
 * A stateful decoder is also the only way to be correct about the escape sequences the emulator
 * parses, which is a stronger requirement than looking right. The C1 8-bit controls and the bytes of
 * a UTF-8 continuation overlap numerically; feeding half a codepoint to a parser that is looking for
 * `ESC [` can leave it in a state the rest of the stream then has to escape from.
 *
 * Errors are replaced rather than reported. A terminal has no way to ask a remote host to re-send a
 * byte, and a shell that emits genuinely invalid UTF-8 (a filename in a legacy encoding, a binary
 * file `cat`-ed by mistake) must not be able to abort the session — it should just look wrong, in one
 * place, exactly as it does in every other terminal.
 *
 * Not thread-safe: one instance per session, confined to the coroutine that feeds the buffer.
 */
class Utf8StreamDecoder {
    private val decoder: CharsetDecoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)

    /**
     * The tail of the previous chunk that was a valid *prefix* of a codepoint but not a whole one.
     *
     * `CharsetDecoder.decode(..., endOfInput = false)` leaves at most `maxBytesPerChar - 1` bytes
     * unconsumed, which is 3 for UTF-8, so this can never need to grow. Sized to 8 anyway: the cost
     * is five bytes and it makes the bound impossible to get wrong if the charset ever changes.
     */
    private val carry = ByteArray(MAX_CARRY)
    private var carried = 0

    /**
     * Reused output buffer, so a stream of small chunks does not allocate one per read.
     *
     * Deliberately not grown to fit the largest chunk ever seen. A single `cat` of a large file can
     * arrive as one very big read, and permanently retaining twice that in chars — a `char` is two
     * bytes — is a real cost on a device with a 1 GB heap budget shared with the rest of the app. Past
     * [MAX_RETAINED_CHARS] the buffer for that chunk is a one-off that gets collected.
     */
    private var reusable = CharBuffer.allocate(INITIAL_CHARS)

    /** Decodes [length] bytes of [bytes] from [offset], holding back any partial trailing codepoint. */
    fun decode(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): String {
        if (length <= 0) return ""
        val input = if (carried == 0) {
            // The common case, and no copy: the decoder reads straight out of the caller's array.
            ByteBuffer.wrap(bytes, offset, length)
        } else {
            ByteBuffer.allocate(carried + length).apply {
                put(carry, 0, carried)
                put(bytes, offset, length)
                flip()
            }
        }
        carried = 0
        return drain(input, endOfInput = false)
    }

    /**
     * Emits whatever is still held back, as U+FFFD.
     *
     * Called when the channel closes. Without it a session that ends mid-codepoint — which is what a
     * dropped connection looks like — would silently swallow its last byte or two, and the final line
     * of output before a disconnect is often the one that says why.
     */
    fun flush(): String {
        if (carried == 0) return ""
        val input = ByteBuffer.wrap(carry, 0, carried)
        carried = 0
        return drain(input, endOfInput = true)
    }

    /** Drops any partial codepoint and resets the decoder, for reuse across a reconnect. */
    fun reset() {
        carried = 0
        decoder.reset()
    }

    private fun drain(input: ByteBuffer, endOfInput: Boolean): String {
        val out = bufferFor(input.remaining())
        // UTF-8 never produces more than one char per byte, so `out` cannot overflow and this loop
        // runs exactly once. The OVERFLOW branch is kept rather than asserted away because being
        // wrong about that would silently truncate a screenful of output, and handling it is a line.
        var spilled: StringBuilder? = null
        while (true) {
            out.clear()
            val result = decoder.decode(input, out, endOfInput)
            if (!result.isOverflow) break
            out.flip()
            spilled = (spilled ?: StringBuilder()).append(out)
        }
        if (endOfInput) {
            // UTF-8 has no deferred state, so this is a formality — but skipping it would make the
            // class quietly charset-specific in a way its name only implies.
            decoder.flush(out)
            decoder.reset()
        } else {
            keepRemainder(input)
        }
        out.flip()
        return spilled?.append(out)?.toString() ?: out.toString()
    }

    private fun keepRemainder(input: ByteBuffer) {
        val remaining = input.remaining()
        if (remaining <= 0) return
        // Bounded rather than trusted. `remaining > MAX_CARRY` is unreachable for UTF-8; if some
        // future charset made it reachable, losing the excess bytes shows up as one wrong glyph,
        // whereas an unchecked copy would be an ArrayIndexOutOfBoundsException on the pump thread —
        // and an exception there takes the whole session down, not one character.
        carried = minOf(remaining, carry.size)
        input.get(carry, 0, carried)
    }

    private fun bufferFor(bytes: Int): CharBuffer {
        val needed = bytes + 1
        if (needed > MAX_RETAINED_CHARS) return CharBuffer.allocate(needed)
        if (reusable.capacity() < needed) reusable = CharBuffer.allocate(maxOf(needed, INITIAL_CHARS))
        return reusable
    }

    private companion object {
        const val MAX_CARRY = 8
        const val INITIAL_CHARS = 8 * 1024
        const val MAX_RETAINED_CHARS = 64 * 1024
    }
}
