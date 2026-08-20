package dev.eclipse.ssh.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The decoder's contract, which is entirely about what happens at a chunk boundary.
 *
 * A terminal stream is split by the network, not by the content: a multi-byte character, an escape
 * sequence, even a CRLF can be cut in half between two reads. Decoding each read independently - what
 * the channel used to do - turned every such split into a replacement character, so a shell prompt
 * containing an arrow or a box-drawing rule degraded into rubbish at random intervals that depended on
 * packet timing and could never be reproduced.
 */
class Utf8StreamDecoderTest {

    @Test
    fun `plain ascii passes through unchanged`() {
        val decoder = Utf8StreamDecoder()
        assertThat(decoder.decode("hello".toByteArray())).isEqualTo("hello")
        assertThat(decoder.flush()).isEmpty()
    }

    @Test
    fun `a two byte codepoint split across chunks is reassembled`() {
        val decoder = Utf8StreamDecoder()
        val bytes = "é".toByteArray(Charsets.UTF_8)
        assertThat(bytes).hasLength(2)
        // The first half decodes to nothing at all rather than to a replacement character.
        assertThat(decoder.decode(bytes, 0, 1)).isEmpty()
        assertThat(decoder.decode(bytes, 1, 1)).isEqualTo("é")
    }

    @Test
    fun `a three byte codepoint split at either boundary is reassembled`() {
        val snowman = "☃".toByteArray(Charsets.UTF_8)
        assertThat(snowman).hasLength(3)
        for (split in 1..2) {
            val decoder = Utf8StreamDecoder()
            val first = decoder.decode(snowman, 0, split)
            val second = decoder.decode(snowman, split, snowman.size - split)
            assertThat(first + second).isEqualTo("☃")
        }
    }

    @Test
    fun `a four byte emoji arriving one byte at a time is reassembled`() {
        val decoder = Utf8StreamDecoder()
        val rocket = "🚀".toByteArray(Charsets.UTF_8)
        assertThat(rocket).hasLength(4)
        val out = StringBuilder()
        rocket.forEach { byte -> out.append(decoder.decode(byteArrayOf(byte))) }
        assertThat(out.toString()).isEqualTo("🚀")
    }

    @Test
    fun `text on both sides of a split codepoint is preserved in order`() {
        val decoder = Utf8StreamDecoder()
        val bytes = "aé b".toByteArray(Charsets.UTF_8)
        // Split inside the two-byte 'é', which sits at index 1.
        val out = decoder.decode(bytes, 0, 2) + decoder.decode(bytes, 2, bytes.size - 2)
        assertThat(out).isEqualTo("aé b")
    }

    @Test
    fun `invalid bytes become one replacement character each and do not stall the stream`() {
        val decoder = Utf8StreamDecoder()
        // 0xFF can never appear in UTF-8. What matters is that the surrounding text still arrives.
        val decoded = decoder.decode(byteArrayOf(0x61, 0xFF.toByte(), 0x62))
        assertThat(decoded).contains("a")
        assertThat(decoded).contains("b")
        assertThat(decoded).contains("�")
    }

    @Test
    fun `a truncated codepoint at the end of the stream is flushed as a replacement character`() {
        val decoder = Utf8StreamDecoder()
        val snowman = "☃".toByteArray(Charsets.UTF_8)
        assertThat(decoder.decode(snowman, 0, 2)).isEmpty()
        // The session ended mid-character: the bytes must not simply disappear.
        assertThat(decoder.flush()).isEqualTo("�")
        // And flushing twice must not repeat it.
        assertThat(decoder.flush()).isEmpty()
    }

    @Test
    fun `reset discards a partial codepoint so a reconnect starts clean`() {
        val decoder = Utf8StreamDecoder()
        val snowman = "☃".toByteArray(Charsets.UTF_8)
        decoder.decode(snowman, 0, 2)
        decoder.reset()
        // Without the reset, the carried two bytes would corrupt the first character of the new stream.
        assertThat(decoder.decode("ok".toByteArray())).isEqualTo("ok")
    }

    @Test
    fun `an empty chunk is not an end of stream`() {
        val decoder = Utf8StreamDecoder()
        assertThat(decoder.decode(ByteArray(0))).isEmpty()
        assertThat(decoder.decode("after".toByteArray())).isEqualTo("after")
    }

    @Test
    fun `a chunk larger than the retained buffer is decoded whole`() {
        val decoder = Utf8StreamDecoder()
        // Comfortably past the retained-buffer ceiling, so this takes the one-off allocation path
        // rather than the reused buffer. A `cat` of a large file arrives exactly like this.
        val long = "x".repeat(200_000)
        assertThat(decoder.decode(long.toByteArray())).hasLength(200_000)
    }

    @Test
    fun `a large chunk of multi byte text survives the one off buffer path`() {
        val decoder = Utf8StreamDecoder()
        val text = "☃é🚀".repeat(20_000)
        assertThat(decoder.decode(text.toByteArray(Charsets.UTF_8))).isEqualTo(text)
    }

    @Test
    fun `an offset and length select only the requested slice`() {
        val decoder = Utf8StreamDecoder()
        val bytes = "..payload..".toByteArray()
        assertThat(decoder.decode(bytes, 2, 7)).isEqualTo("payload")
    }

    @Test
    fun `control bytes are passed through untouched for the parser to interpret`() {
        val decoder = Utf8StreamDecoder()
        // The emulator, not the decoder, decides what an escape means - so every byte has to arrive.
        val decoded = decoder.decode(byteArrayOf(ESCAPE_BYTE, 0x5B, 0x33, 0x31, 0x6D, 0x41))
        assertThat(decoded).hasLength(6)
        assertThat(decoded[0].code).isEqualTo(0x1B)
        assertThat(decoded.substring(1)).isEqualTo("[31mA")
    }

    private companion object {
        const val ESCAPE_BYTE: Byte = 0x1B
    }
}
