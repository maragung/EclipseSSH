package dev.eclipse.ssh.ui.editor.encoding

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The codec's contract, which is that detection only ever suggests and saving only ever obeys.
 *
 * [FileEncodingCodec.decode] is total: whatever bytes arrive, the file opens - BOMs are read
 * and consumed, unknown single bytes become U+FFFD, nothing throws. The tests pin the ladder
 * that picks the suggestion, because a wrong suggestion shows up in the selector as a
 * confident claim about the user's file.
 *
 * [FileEncodingCodec.encode] is the mirror image: the single-byte encodings refuse a character
 * they cannot hold rather than silently writing `?` to disk. Those refusals are the feature -
 * the alternative corrupts a file that still looks fine in the editor.
 */
class FileEncodingTest {

    @Test
    fun `a utf 8 bom selects the bom variant and is consumed rather than kept as text`() {
        val decoded = FileEncodingCodec.decode(BOM_UTF8 + "héllo".toByteArray(Charsets.UTF_8))
        assertThat(decoded.encoding).isEqualTo(FileEncoding.UTF_8_BOM)
        // A leading U+FEFF character would make every "did the text change" comparison lie.
        assertThat(decoded.text).isEqualTo("héllo")
    }

    @Test
    fun `an ff fe bom selects little endian utf 16 and is consumed`() {
        val decoded = FileEncodingCodec.decode(BOM_UTF16_LE + "abc".toByteArray(Charsets.UTF_16LE))
        assertThat(decoded.encoding).isEqualTo(FileEncoding.UTF_16_LE)
        assertThat(decoded.text).isEqualTo("abc")
    }

    @Test
    fun `an fe ff bom selects big endian utf 16 and is consumed`() {
        val decoded = FileEncodingCodec.decode(BOM_UTF16_BE + "abc".toByteArray(Charsets.UTF_16BE))
        assertThat(decoded.encoding).isEqualTo(FileEncoding.UTF_16_BE)
        assertThat(decoded.text).isEqualTo("abc")
    }

    @Test
    fun `valid multibyte utf 8 without a bom decodes as utf 8`() {
        val decoded = FileEncodingCodec.decode("naïve ☃ text".toByteArray(Charsets.UTF_8))
        assertThat(decoded.encoding).isEqualTo(FileEncoding.UTF_8)
        assertThat(decoded.text).isEqualTo("naïve ☃ text")
    }

    @Test
    fun `an all ascii file is suggested as ascii even though ascii is also valid utf 8`() {
        // Both labels would round-trip; the narrowest one tells the user something specific
        // about the file, and the encode side still refuses loudly if they later type past it.
        val decoded = FileEncodingCodec.decode("plain ascii, nothing else".toByteArray())
        assertThat(decoded.encoding).isEqualTo(FileEncoding.ASCII)
        assertThat(decoded.text).isEqualTo("plain ascii, nothing else")
    }

    @Test
    fun `high bytes that break utf 8 but stay above 0x9F fall back to iso 8859 1`() {
        // "café £" as ISO-8859-1: 0xE9 and 0xA3, both malformed as UTF-8, neither in the
        // Windows-1252-only C1 window.
        val latin1 = "café £".toByteArray(Charsets.ISO_8859_1)
        val decoded = FileEncodingCodec.decode(latin1)
        assertThat(decoded.encoding).isEqualTo(FileEncoding.ISO_8859_1)
        assertThat(decoded.text).isEqualTo("café £")
    }

    @Test
    fun `a byte in the 0x80 to 0x9F window selects windows 1252`() {
        // "it's" with a typographic apostrophe: 0x92 is a C1 control in ISO-8859-1 but the
        // printable apostrophe in Windows-1252, so its presence identifies the Windows file.
        val windows = byteArrayOf(0x69, 0x74, 0x92.toByte(), 0x73)
        val decoded = FileEncodingCodec.decode(windows)
        assertThat(decoded.encoding).isEqualTo(FileEncoding.WINDOWS_1252)
        assertThat(decoded.text).isEqualTo("it’s")
    }

    @Test
    fun `a windows 1252 byte the map leaves undefined becomes a replacement character`() {
        // 0x81 is the classic hole in Windows-1252. The file still opens; the hole is visible.
        val decoded = FileEncodingCodec.decode(byteArrayOf(0x61, 0x81.toByte()))
        assertThat(decoded.encoding).isEqualTo(FileEncoding.WINDOWS_1252)
        assertThat(decoded.text).isEqualTo("a�")
    }

    @Test
    fun `an empty file is suggested as utf 8 because no byte carries evidence`() {
        val decoded = FileEncodingCodec.decode(ByteArray(0))
        assertThat(decoded.encoding).isEqualTo(FileEncoding.UTF_8)
        assertThat(decoded.text).isEmpty()
    }

    @Test
    fun `utf 8 round trips multibyte text`() {
        val text = "naïve ☃ é"
        val decoded = FileEncodingCodec.decode(FileEncodingCodec.encode(text, FileEncoding.UTF_8))
        assertThat(decoded.encoding).isEqualTo(FileEncoding.UTF_8)
        assertThat(decoded.text).isEqualTo(text)
    }

    @Test
    fun `utf 8 with bom round trips and re emits the bom`() {
        val text = "héllo"
        val bytes = FileEncodingCodec.encode(text, FileEncoding.UTF_8_BOM)
        assertThat(bytes[0]).isEqualTo(0xEF.toByte())
        assertThat(bytes[1]).isEqualTo(0xBB.toByte())
        assertThat(bytes[2]).isEqualTo(0xBF.toByte())
        val decoded = FileEncodingCodec.decode(bytes)
        assertThat(decoded.encoding).isEqualTo(FileEncoding.UTF_8_BOM)
        assertThat(decoded.text).isEqualTo(text)
    }

    @Test
    fun `utf 16 le round trips through its bom`() {
        val text = "héllo ☃"
        val bytes = FileEncodingCodec.encode(text, FileEncoding.UTF_16_LE)
        // The BOM is written by the encoder: a BOM-less UTF-16 file has no readable direction.
        assertThat(bytes[0]).isEqualTo(0xFF.toByte())
        assertThat(bytes[1]).isEqualTo(0xFE.toByte())
        val decoded = FileEncodingCodec.decode(bytes)
        assertThat(decoded.encoding).isEqualTo(FileEncoding.UTF_16_LE)
        assertThat(decoded.text).isEqualTo(text)
    }

    @Test
    fun `utf 16 be round trips through its bom`() {
        val text = "héllo ☃"
        val bytes = FileEncodingCodec.encode(text, FileEncoding.UTF_16_BE)
        assertThat(bytes[0]).isEqualTo(0xFE.toByte())
        assertThat(bytes[1]).isEqualTo(0xFF.toByte())
        val decoded = FileEncodingCodec.decode(bytes)
        assertThat(decoded.encoding).isEqualTo(FileEncoding.UTF_16_BE)
        assertThat(decoded.text).isEqualTo(text)
    }

    @Test
    fun `iso 8859 1 round trips latin 1 text`() {
        val text = "café £ à"
        val decoded = FileEncodingCodec.decode(FileEncodingCodec.encode(text, FileEncoding.ISO_8859_1))
        assertThat(decoded.encoding).isEqualTo(FileEncoding.ISO_8859_1)
        assertThat(decoded.text).isEqualTo(text)
    }

    @Test
    fun `windows 1252 round trips pounds and smart quotes`() {
        val text = "it costs £5 — “quoted”"
        val decoded = FileEncodingCodec.decode(FileEncodingCodec.encode(text, FileEncoding.WINDOWS_1252))
        assertThat(decoded.encoding).isEqualTo(FileEncoding.WINDOWS_1252)
        assertThat(decoded.text).isEqualTo(text)
    }

    @Test
    fun `ascii round trips plain ascii text`() {
        val text = "plain ascii 42"
        val decoded = FileEncodingCodec.decode(FileEncodingCodec.encode(text, FileEncoding.ASCII))
        assertThat(decoded.encoding).isEqualTo(FileEncoding.ASCII)
        assertThat(decoded.text).isEqualTo(text)
    }

    @Test
    fun `saving as ascii refuses a non ascii character instead of corrupting it`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            FileEncodingCodec.encode("café", FileEncoding.ASCII)
        }
        // The message is the whole feature: it must name the encoding and the character so the
        // user can act on it from the dialog alone.
        assertThat(error).hasMessageThat().contains("ASCII")
        assertThat(error).hasMessageThat().contains("é")
    }

    @Test
    fun `saving as windows 1252 refuses an emoji`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            FileEncodingCodec.encode("launch 🚀", FileEncoding.WINDOWS_1252)
        }
        assertThat(error).hasMessageThat().contains("Windows-1252")
        assertThat(error).hasMessageThat().contains("U+1F680")
    }

    @Test
    fun `a truncated odd length utf 16 body decodes with a replacement character, not a crash`() {
        // BOM + "a" + "b"'s first byte: the dangling 0x62 cannot form a code unit.
        val truncated = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x61, 0x00, 0x62)
        val decoded = FileEncodingCodec.decode(truncated)
        assertThat(decoded.encoding).isEqualTo(FileEncoding.UTF_16_LE)
        assertThat(decoded.text).isEqualTo("ab�")
    }

    private companion object {
        val BOM_UTF8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val BOM_UTF16_LE = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val BOM_UTF16_BE = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
    }
}
