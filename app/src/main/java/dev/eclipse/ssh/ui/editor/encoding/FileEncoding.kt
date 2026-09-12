package dev.eclipse.ssh.ui.editor.encoding

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * The encodings the editor's selector offers.
 *
 * The set is what a real user of SSH-edited files meets: UTF-8 (the default of every sane tool),
 * its BOM-carrying Windows variant, both UTF-16 orders (the Notepad heritage), and the three
 * single-byte workhorses. [label] is what the selector shows, and what
 * [FileEncodingCodec.encode] names in a refusal message, so it is spelled the way users see it
 * in other software, not the way Java spells the charset.
 */
enum class FileEncoding(val label: String) {
    UTF_8("UTF-8"),
    UTF_8_BOM("UTF-8 with BOM"),
    UTF_16_LE("UTF-16 LE"),
    UTF_16_BE("UTF-16 BE"),
    ASCII("ASCII"),
    ISO_8859_1("ISO-8859-1"),
    WINDOWS_1252("Windows-1252");

    companion object {
        /**
         * The entry the selector labels [label], or null when nothing spells itself that way.
         *
         * The editor's prefs blob stores the label (it is what the selector shows and what an
         * [FileEncodingCodec.encode] refusal names), so this lookup is how a persisted string
         * becomes an entry again — and how an unknown one is told apart from a known one before
         * it can reach the codec and crash there instead.
         */
        fun fromLabel(label: String): FileEncoding? = entries.firstOrNull { it.label == label }
    }
}

/**
 * What [FileEncodingCodec.decode] hands back: the text, and the encoding it was read as.
 *
 * The encoding half is a *suggestion* for the selector at first open, never a decision. Only an
 * explicit user choice ever reaches [FileEncodingCodec.encode]; that separation is what keeps
 * detection from silently re-encoding a file on save.
 */
data class DecodedText(val text: String, val encoding: FileEncoding)

/**
 * The encode/decode engine behind the editor's encoding selector.
 *
 * The contract is asymmetric on purpose:
 *
 * - [decode] never throws. A file that arrives in a state no rule recognises still opens, with
 *   undecodable bytes as U+FFFD, because "cannot open your file" is worse than "here is your
 *   file with a replacement character where the corruption was".
 * - [encode] refuses instead of guessing. A single-byte encoding that cannot hold a character
 *   in the text throws [IllegalArgumentException] naming the encoding and the character. The
 *   alternative - encoding with `?` via REPLACE - would save a file that looks fine in the
 *   editor and is silently corrupted on disk, which the user finds out about after the
 *   original is gone.
 */
object FileEncodingCodec {

    /** The UTF-8 BOM, as it appears at the head of a file: EF BB BF. */
    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    /** The UTF-16 LE BOM (FF FE), which is also how a little-endian decoder spells U+FEFF. */
    private val UTF16LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())

    /** The UTF-16 BE BOM (FE FF). */
    private val UTF16BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

    /**
     * Windows-1252 has no constant on [Charsets] (Java only blesses US-ASCII and ISO-8859-1
     * there), so it is looked up once and held. The charset exists on both Android and the
     * JVM, so the same code serves the app and its unit tests.
     */
    private val WINDOWS_1252: Charset = Charset.forName("windows-1252")

    /**
     * Decodes with BOM detection, then an all-ASCII check, then a strict-UTF-8 probe, then
     * single-byte fallbacks.
     *
     * The ladder, in order:
     *
     * 1. A byte-order mark wins outright, because a BOM is the one piece of self-description a
     *    byte stream can carry. The mark is consumed and never becomes text: a leading U+FEFF
     *    character would sit invisibly at the start of the text and make "did the user change
     *    anything" unanswerable.
     * 2. An empty file is suggested as [FileEncoding.UTF_8]: with no bytes there is no evidence
     *    for anything narrower, and UTF-8 is the one choice whose save path can never refuse.
     * 3. Bytes that never exceed 0x7F are suggested as [FileEncoding.ASCII]. Pure ASCII is
     *    technically valid UTF-8 too, but naming the narrowest label tells the user something
     *    true and specific, and the suggestion is safe: ASCII-encode only refuses once the user
     *    has actually typed a non-ASCII character, and the refusal message then says exactly
     *    what to switch to.
     * 4. Otherwise the bytes must prove they are UTF-8, strictly (malformed input reported, not
     *    replaced). A permissive probe here would accept mojibake as "UTF-8" and lock the file
     *    into the wrong suggestion forever.
     * 5. Not valid UTF-8, with a byte in 0x80..0x9F: [FileEncoding.WINDOWS_1252]. That window
     *    is undefined control territory in ISO-8859-1 but printable in Windows-1252 (curly
     *    quotes, dashes, ellipsis), so its presence means a Windows editor wrote the file.
     * 6. Not valid UTF-8, high bytes only: [FileEncoding.ISO_8859_1], the encoding that maps
     *    every single byte and therefore the only remaining candidate that is not a guess.
     *
     * A BOM-less UTF-16 file is this ladder's blind spot: its 00 xx 00 xx bytes look like
     * NUL-riddled ASCII. That is accepted - detecting it reliably needs frequency analysis,
     * and a wrong guess is worse than the visible NUL characters the user can act on. The
     * selector still lets the user override by hand.
     */
    fun decode(bytes: ByteArray): DecodedText {
        if (bytes.startsWithAt(0, UTF8_BOM)) {
            return DecodedText(decodeReplacing(bytes, UTF8_BOM.size, Charsets.UTF_8), FileEncoding.UTF_8_BOM)
        }
        if (bytes.startsWithAt(0, UTF16LE_BOM)) {
            return DecodedText(decodeReplacing(bytes, UTF16LE_BOM.size, Charsets.UTF_16LE), FileEncoding.UTF_16_LE)
        }
        if (bytes.startsWithAt(0, UTF16BE_BOM)) {
            return DecodedText(decodeReplacing(bytes, UTF16BE_BOM.size, Charsets.UTF_16BE), FileEncoding.UTF_16_BE)
        }
        if (bytes.isEmpty()) {
            return DecodedText("", FileEncoding.UTF_8)
        }
        if (bytes.all { it.toInt() >= 0 }) {
            return DecodedText(decodeReplacing(bytes, 0, Charsets.US_ASCII), FileEncoding.ASCII)
        }
        decodeStrictUtf8(bytes)?.let { return DecodedText(it, FileEncoding.UTF_8) }
        return if (bytes.any { (it.toInt() and 0xFF) in 0x80..0x9F }) {
            // The C1 window is the discriminating evidence between the two single-byte
            // candidates: printable in Windows-1252, undefined in ISO-8859-1.
            DecodedText(decodeReplacing(bytes, 0, WINDOWS_1252), FileEncoding.WINDOWS_1252)
        } else {
            DecodedText(decodeReplacing(bytes, 0, Charsets.ISO_8859_1), FileEncoding.ISO_8859_1)
        }
    }

    /**
     * Encodes; throws [IllegalArgumentException] with a message naming the encoding and the
     * offending character when the text cannot round-trip.
     *
     * The UTF-8 and UTF-16 arms cannot fail - those charsets hold every valid Kotlin string -
     * so they go straight through. Both UTF-16 arms write a BOM even though the decode side
     * does not require one: a BOM-less UTF-16 file has no readable direction, and every
     * consumer that opens one has to guess.
     *
     * The single-byte arms run a strict encoder (unmappable character reported, not replaced)
     * so that a character the encoding cannot hold stops the save with a readable message
     * rather than landing on disk as `?`.
     */
    fun encode(text: String, encoding: FileEncoding): ByteArray = when (encoding) {
        FileEncoding.UTF_8 -> text.toByteArray(Charsets.UTF_8)
        FileEncoding.UTF_8_BOM -> UTF8_BOM + text.toByteArray(Charsets.UTF_8)
        FileEncoding.UTF_16_LE -> UTF16LE_BOM + text.toByteArray(Charsets.UTF_16LE)
        FileEncoding.UTF_16_BE -> UTF16BE_BOM + text.toByteArray(Charsets.UTF_16BE)
        FileEncoding.ASCII -> encodeStrict(text, Charsets.US_ASCII, encoding)
        FileEncoding.ISO_8859_1 -> encodeStrict(text, Charsets.ISO_8859_1, encoding)
        FileEncoding.WINDOWS_1252 -> encodeStrict(text, WINDOWS_1252, encoding)
    }

    /**
     * Decodes [bytes] as exactly [encoding] — the chosen-encoding read behind the editor's
     * selector — strictly, returning null when the bytes are not valid in that encoding.
     *
     * This is the counterpart of [encode]'s refusal, and the deliberate opposite of [decode]'s
     * totality: [decode] only *suggests* (a guess the selector may override), while this *obeys*
     * (the user has already decided). Because the choice is explicit, the failure is loud: the
     * editor's load path turns null into a Failed state naming the encoding, rather than opening
     * replacement characters that the next save would refuse anyway (no single-byte set holds
     * U+FFFD).
     *
     * A BOM matching the encoding is consumed rather than kept as text, for the same reason
     * [decode] consumes it: a leading U+FEFF would make every "did the text change" comparison
     * lie. The prefix is stripped only for the entries whose own encode writes one — plain UTF-8
     * keeps a stray BOM as a visible U+FEFF the user can delete.
     */
    fun decodeStrict(bytes: ByteArray, encoding: FileEncoding): String? {
        val body = when (encoding) {
            FileEncoding.UTF_8_BOM -> bytes.skipPrefix(UTF8_BOM)
            FileEncoding.UTF_16_LE -> bytes.skipPrefix(UTF16LE_BOM)
            FileEncoding.UTF_16_BE -> bytes.skipPrefix(UTF16BE_BOM)
            else -> bytes
        }
        val charset = when (encoding) {
            FileEncoding.UTF_8, FileEncoding.UTF_8_BOM -> Charsets.UTF_8
            FileEncoding.UTF_16_LE -> Charsets.UTF_16LE
            FileEncoding.UTF_16_BE -> Charsets.UTF_16BE
            FileEncoding.ASCII -> Charsets.US_ASCII
            FileEncoding.ISO_8859_1 -> Charsets.ISO_8859_1
            FileEncoding.WINDOWS_1252 -> WINDOWS_1252
        }
        return try {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body))
                .toString()
        } catch (error: CharacterCodingException) {
            null
        }
    }

    /**
     * Decodes UTF-8 strictly, returning null when the bytes are not valid UTF-8. REPORT is
     * spelled out for both error kinds even though a fresh decoder already reports malformed
     * input: the probe's meaning must not depend on remembering the platform defaults.
     */
    private fun decodeStrictUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (error: CharacterCodingException) {
        null
    }

    /**
     * Decodes leniently: undecodable input becomes U+FFFD instead of throwing. Every decode
     * path except the strict UTF-8 probe goes through here, which is what makes [decode] total -
     * a truncated or C1-carrying file still opens.
     */
    private fun decodeReplacing(bytes: ByteArray, from: Int, charset: Charset): String {
        val body = if (from == 0) bytes else bytes.copyOfRange(from, bytes.size)
        return charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(ByteBuffer.wrap(body))
            .toString()
    }

    /**
     * Encodes with REPORT and turns a [CharacterCodingException] into an [IllegalArgumentException]
     * that names the encoding and the character. The message is what the save-failed dialog
     * shows, so it has to let the user act without reading a stack trace: which encoding
     * refused, which character was the problem, and where it is.
     */
    private fun encodeStrict(text: String, charset: Charset, encoding: FileEncoding): ByteArray {
        val encoder = charset.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        try {
            val out = encoder.encode(CharBuffer.wrap(text))
            // A ByteBuffer handed back by encode() may over-allocate its backing array, so the
            // bytes are read out by remaining() rather than trusting array().size.
            val bytes = ByteArray(out.remaining())
            out.get(bytes)
            return bytes
        } catch (error: CharacterCodingException) {
            val offender = firstUnencodable(text, charset)
            throw IllegalArgumentException(
                "${encoding.label} cannot encode $offender; save as UTF-8 to keep it",
                error,
            )
        }
    }

    /**
     * Finds the first thing in [text] that [charset] cannot hold, described for a refusal
     * message. Walks code points, not chars, so an emoji is reported as itself ("U+1F680")
     * rather than as the two surrogates it is stored as. An unpaired surrogate - the one input
     * that every charset rejects, including ISO-8859-1 which otherwise maps all 256 bytes - is
     * named as such, because printing the raw surrogate would put an unrenderable character in
     * the dialog.
     */
    private fun firstUnencodable(text: String, charset: Charset): String {
        val probe = charset.newEncoder()
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val charCount = Character.charCount(codePoint)
            val piece = text.substring(index, index + charCount)
            if (!probe.canEncode(piece)) {
                // A charCount of 1 with a surrogate means the pair was broken in the string
                // itself; a charCount of 2 is a real supplementary character (an emoji) whose
                // two chars merely happen to be surrogates.
                return if (charCount == 1 && Character.isSurrogate(text[index])) {
                    "the unpaired surrogate at index $index"
                } else {
                    "'$piece' (U+" + Integer.toHexString(codePoint).uppercase() + ") at index $index"
                }
            }
            index += charCount
        }
        // Unreachable when encodeStrict threw - something must have been unencodable - but a
        // message builder must degrade to something true rather than crash of its own.
        return "a character at the end of the text"
    }

    /** Whether [bytes] begins with [prefix] at [offset]. */
    private fun ByteArray.startsWithAt(offset: Int, prefix: ByteArray): Boolean {
        if (size - offset < prefix.size) return false
        for (i in prefix.indices) {
            if (this[offset + i] != prefix[i]) return false
        }
        return true
    }

    /** [bytes] without a leading [prefix] when it carries one, and unchanged when it does not. */
    private fun ByteArray.skipPrefix(prefix: ByteArray): ByteArray =
        if (startsWithAt(0, prefix)) copyOfRange(prefix.size, size) else this
}
