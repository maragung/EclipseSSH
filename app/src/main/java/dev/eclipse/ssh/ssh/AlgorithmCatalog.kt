package dev.eclipse.ssh.ssh

import org.apache.sshd.client.ClientBuilder
import org.apache.sshd.common.NamedFactory
import org.apache.sshd.common.NamedResource
import org.apache.sshd.common.OptionalFeature
import org.apache.sshd.common.cipher.BuiltinCiphers
import org.apache.sshd.common.cipher.Cipher
import org.apache.sshd.common.config.ListParseResult
import org.apache.sshd.common.kex.BuiltinDHFactories
import org.apache.sshd.common.kex.KeyExchangeFactory
import org.apache.sshd.common.mac.BuiltinMacs
import org.apache.sshd.common.mac.Mac
import org.apache.sshd.common.signature.BuiltinSignatures
import org.apache.sshd.common.signature.Signature

/**
 * Which crypto a host may be told to prefer, and whether a name the user typed is one of them.
 *
 * The point of this file is *where* an unsupported algorithm is rejected. A per-host list that were
 * handed straight to MINA would fail at the worst possible moment: `chacha20-poly1305` typed with a
 * capital P is accepted by the dialog, saved, and then the host stops connecting - with an error from
 * the middle of a key exchange, on a device that cannot be attached to a debugger, about a setting the
 * user configured days earlier. Validating here means the answer arrives under the field, while the
 * thing that is wrong is still on screen and still editable.
 *
 * Two questions get asked of every name, and both matter:
 *
 *  - does MINA *know* it - `parseCiphersList` and its siblings answer this, returning the names they
 *    could not resolve;
 *  - can this *device* do it - each builtin is an [OptionalFeature], and several are not available on
 *    every Android platform because the JCE provider underneath does not offer them. A name that
 *    parses but is unsupported here would be silently dropped from the factory list by
 *    [org.apache.sshd.common.NamedFactory] handling, so a list of exactly one such name would leave the
 *    session with nothing to offer for that role and fail as a negotiation error rather than as the
 *    configuration mistake it is.
 *
 * Both are reported as [AlgorithmListReview.unsupported], because to the person typing they are the
 * same problem: this name will not work here.
 *
 * A third question is asked only of ciphers, and it is not about capability at all: MINA implements the
 * protocol's `none` cipher and reports it as available, so without an explicit refusal this app would
 * list "no encryption" among the chips a user can tap. See [AlgorithmListReview.refused].
 *
 * ## Order is the instruction
 *
 * An SSH client sends its algorithms most-preferred first, and the server picks the first it shares.
 * So the parsed list is kept in exactly the order it was typed, never sorted and never deduplicated
 * against the library's own ordering - "I want aes128-ctr *first* because this router is slow" is a
 * legitimate and common reason to use this feature at all, and a helpful re-sort would quietly undo it.
 */
enum class AlgorithmKind(val label: String, val help: String) {
    CIPHERS(
        label = "Ciphers",
        help = "Comma-separated, most preferred first. Leave empty to let the library choose.",
    ),
    KEX(
        label = "Key exchange",
        help = "Comma-separated, most preferred first. Leave empty to let the library choose.",
    ),
    MACS(
        label = "MACs",
        help = "Ignored by the modern AEAD ciphers, which authenticate their own output.",
    ),
    HOST_KEYS(
        label = "Host key algorithms",
        help = "Which key types this server may identify itself with, most preferred first.",
    ),
}

/**
 * What [reviewAlgorithmList] made of one list.
 *
 * [names] holds what will actually be sent, in order. [unsupported] and [refused] hold every name that
 * will not, and their emptiness is the whole acceptance test - a list is either usable exactly as typed
 * or it is not saved, because a silently shortened list is a different instruction from the one given.
 *
 * The two rejections are separate because they are different facts and want different words: one is
 * about this device, the other is about this app. See [refused].
 */
data class AlgorithmListReview(
    val names: List<String>,
    val unsupported: List<String>,
    /**
     * Names this app will not carry however well supported they are.
     *
     * Today that is exactly one: the `none` cipher, which MINA implements and reports as available
     * because it is a legitimate part of the protocol - and which means no encryption at all. Offering
     * it in a settings list would be offering a switch that turns an SSH session into telnet, chosen
     * from a row of chips beside `aes256-gcm` and indistinguishable from a performance tweak. There is
     * no reading of "secure by default" that survives shipping that, so it is refused at the form, its
     * name is filtered out of [supportedAlgorithmNames] so nothing suggests it, and it is dropped again
     * at [cipherFactoriesFor] so a hand-edited backup carrying it cannot install it either.
     *
     * Deliberately *not* extended to the weak-but-real algorithms - `3des-cbc`, `arcfour`,
     * `hmac-md5`. Those still encrypt and still authenticate, connecting to the twelve-year-old switch
     * that only speaks them is a real job, and refusing them would be this app deciding the user may
     * not do that job. `none` is not on that spectrum: it is the absence of the property the whole
     * protocol exists to provide.
     */
    val refused: List<String> = emptyList(),
) {
    /**
     * Whether this list may be saved.
     *
     * An empty field is acceptable and means "no opinion" - that is how a host goes back to the
     * library's defaults, and it is the state every existing host is in. A field with content is
     * acceptable only when every name in it resolved and none of them was refused: see [unsupported]
     * and [refused].
     */
    val isAcceptable: Boolean get() = unsupported.isEmpty() && refused.isEmpty()

    /**
     * The complaint to show under the field, or null when there is none.
     *
     * The refusal comes first and says why, because it is the one the user can act on by deleting a
     * name they typed on purpose - "not available" reads as the device's fault and would send them
     * looking for a setting to change.
     */
    val problem: String?
        get() = listOfNotNull(
            refused.takeIf { it.isNotEmpty() }
                ?.let { "Not allowed: ${it.joinToString(", ")} would leave the session unencrypted" },
            unsupported.takeIf { it.isNotEmpty() }
                ?.let { "Not available on this device: ${it.joinToString(", ")}" },
        ).ifEmpty { null }?.joinToString(" · ")
}

/**
 * [text] read as a preference list of [kind].
 *
 * Empty in, empty out, with nothing unsupported - so a cleared field is always acceptable and always
 * means the library's default.
 */
fun reviewAlgorithmList(kind: AlgorithmKind, text: String?): AlgorithmListReview {
    val typed = text.orEmpty().split(',', '\n', ' ')
        .map(String::trim)
        .filter(String::isNotEmpty)
    if (typed.isEmpty()) return AlgorithmListReview(emptyList(), emptyList())

    // MINA's parsers are the authority on what a name means, including the aliases and the
    // capitalisation it tolerates, so the names are handed over as they were typed rather than matched
    // by hand against [supportedAlgorithmNames].
    return when (kind) {
        AlgorithmKind.CIPHERS -> BuiltinCiphers.parseCiphersList(typed).review()
        AlgorithmKind.KEX -> BuiltinDHFactories.parseDHFactoriesList(typed).review()
        AlgorithmKind.MACS -> BuiltinMacs.parseMacsList(typed).review()
        AlgorithmKind.HOST_KEYS -> BuiltinSignatures.parseSignatureList(typed).review()
    }
}

/**
 * One of MINA's parse results as an [AlgorithmListReview].
 *
 * The four `Builtin*.ParseResult` types do not agree on a nearer supertype - three extend
 * `NamedFactoriesListParseResult` and one extends `NamedResourceListParseResult`, each with its own
 * pair of accessor names for the same two lists - but all four are a [ListParseResult], and every
 * element of all four is both a [NamedResource] and an [OptionalFeature]. Those three facts are all
 * this needs, so it is written against them instead of against a cast.
 */
private fun <T> ListParseResult<T>.review(): AlgorithmListReview
    where T : NamedResource, T : OptionalFeature = AlgorithmListReview(
    names = parsedValues.usable().map { it.name },
    // A name MINA resolved but this platform cannot run is reported alongside the ones it could not
    // resolve at all, for the reason in this file's KDoc: to the user, both mean "not here".
    unsupported = unsupportedValues + parsedValues.filter { !it.isSupported && it.isPermitted }.map { it.name },
    refused = parsedValues.filterNot { it.isPermitted }.map { it.name },
)

/**
 * The subset of a parse result this app will actually propose.
 *
 * One definition of "usable" for the review that decides what may be saved and for the four factory
 * builders that decide what gets installed, because a caller that implemented only half of it would
 * either reject a name it then used, or - the direction that matters - accept a name it then refused
 * to install and leave the session proposing a shorter list than the user was shown.
 */
private fun <T> List<T>.usable(): List<T> where T : NamedResource, T : OptionalFeature =
    filter { it.isSupported && it.isPermitted }

/**
 * Whether this app will carry [this] at all, whatever MINA and the device make of it.
 *
 * See [AlgorithmListReview.refused] for why there is exactly one name here and why the list stops
 * there. Matched case-insensitively because MINA's own parsers do, so `None` must not be a way past it.
 */
private val NamedResource.isPermitted: Boolean get() = !name.equals(NO_ENCRYPTION, ignoreCase = true)

/** The protocol's null cipher, by the name it is negotiated under. */
private const val NO_ENCRYPTION = "none"

/**
 * Every name of [kind] this device can actually use, for the field's hint and for a picker.
 *
 * Filtered by [OptionalFeature.isSupported] rather than listing the enum, so what the hint offers and
 * what [reviewAlgorithmList] accepts are the same set - a hint that suggested a name the validator
 * then rejected would be worse than no hint.
 */
fun supportedAlgorithmNames(kind: AlgorithmKind): List<String> = when (kind) {
    AlgorithmKind.CIPHERS -> BuiltinCiphers.values().supportedNames()
    AlgorithmKind.KEX -> BuiltinDHFactories.values().supportedNames()
    AlgorithmKind.MACS -> BuiltinMacs.values().supportedNames()
    AlgorithmKind.HOST_KEYS -> BuiltinSignatures.values().supportedNames()
}

private fun <T> Array<T>.supportedNames(): List<String> where T : OptionalFeature, T : NamedResource =
    toList().usable().map { it.name }

/**
 * [text] as cipher factories to install on a session, or null to leave the session's own list alone.
 *
 * Null for a blank field, which is the "no opinion" state - and also for a field whose every name
 * turned out to be unusable, which is the safety property this whole family of functions exists for:
 * installing the empty list would mean proposing *no* ciphers, and a proposal with an empty slot fails
 * key exchange with a protocol error rather than with anything a user could act on. Falling back to the
 * default list means a host whose preference has become impossible - a device updated out from under a
 * saved name, a backup restored onto different hardware - still connects, and still connects securely,
 * because the fallback is the library's own list and not a weakened one.
 *
 * The form is where a bad name gets reported ([reviewAlgorithmList]); this is where one gets survived.
 */
internal fun cipherFactoriesFor(text: String?): List<NamedFactory<Cipher>>? =
    text.parseNames { BuiltinCiphers.parseCiphersList(it).parsedValues.usable() }

/** [text] as MAC factories, or null. See [cipherFactoriesFor]. */
internal fun macFactoriesFor(text: String?): List<NamedFactory<Mac>>? =
    text.parseNames { BuiltinMacs.parseMacsList(it).parsedValues.usable() }

/**
 * [text] as signature factories, or null. See [cipherFactoriesFor].
 *
 * This is what "host key algorithms" means to MINA: the *signature* factory list is the set of key
 * types the client will accept a server's identity in, so `ssh_config`'s `HostKeyAlgorithms` maps onto
 * `session.signatureFactories` and not onto a list of its own.
 */
internal fun signatureFactoriesFor(text: String?): List<NamedFactory<Signature>>? =
    text.parseNames { BuiltinSignatures.parseSignatureList(it).parsedValues.usable() }

/**
 * [text] as key exchange factories, or null. See [cipherFactoriesFor].
 *
 * The extra hop through [ClientBuilder.DH2KEX] is MINA's: a `DHFactory` describes a Diffie-Hellman
 * group, and what a session proposes is a `KeyExchangeFactory` built around one for the client side.
 */
internal fun keyExchangeFactoriesFor(text: String?): List<KeyExchangeFactory>? =
    text.parseNames { names ->
        BuiltinDHFactories.parseDHFactoriesList(names).parsedValues
            .usable()
            .map { ClientBuilder.DH2KEX.apply(it) }
    }

/**
 * [parse] applied to the names in this text, or null when there is nothing usable to apply it to.
 *
 * One place for the two null cases - nothing typed, and nothing that survived - so no caller can
 * implement only the first and leave a session proposing an empty list.
 */
private fun <T> String?.parseNames(parse: (List<String>) -> List<T>): List<T>? {
    val names = orEmpty().split(',', '\n', ' ').map(String::trim).filter(String::isNotEmpty)
    if (names.isEmpty()) return null
    return parse(names).takeIf { it.isNotEmpty() }
}
