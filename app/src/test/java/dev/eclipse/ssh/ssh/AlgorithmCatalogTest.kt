package dev.eclipse.ssh.ssh

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The per-host algorithm preferences, and the two questions asked of every name in one.
 *
 * The reason this is worth testing away from a device is that both failure modes are invisible until a
 * connection is attempted. A name the form accepted and the engine then dropped leaves the session
 * proposing a shorter list than the user was shown; a list whose every name got dropped leaves it
 * proposing *nothing* for that role, which fails as a key-exchange error about no matching algorithm -
 * a message that names nothing the user typed and points at no setting.
 *
 * So the invariant the whole file turns on is that the form and the connect path answer identically,
 * and it is asserted directly rather than inferred from two separate lists of expected names.
 */
class AlgorithmCatalogTest {

    @Test
    fun `an empty field is acceptable and means the library's own list`() {
        // The state every existing host is in, and the state clearing the field returns it to. It has to
        // be acceptable, or no host that was never configured could be saved.
        AlgorithmKind.entries.forEach { kind ->
            listOf(null, "", "   ", ",", " , , ", "\n").forEach { text ->
                val review = reviewAlgorithmList(kind, text)
                assertThat(review.names).isEmpty()
                assertThat(review.unsupported).isEmpty()
                assertThat(review.refused).isEmpty()
                assertThat(review.isAcceptable).isTrue()
                assertThat(review.problem).isNull()
            }
        }
    }

    @Test
    fun `nothing this app offers is a name it then rejects`() {
        // The hint and the chip rows are built from `supportedAlgorithmNames`, and the field is validated
        // by `reviewAlgorithmList`. A name suggested by one and refused by the other would be worse than
        // no suggestion at all, and the two are computed from different MINA entry points - an enum walk
        // and a parser - so nothing but this makes them agree.
        AlgorithmKind.entries.forEach { kind ->
            val offered = supportedAlgorithmNames(kind)
            assertThat(offered).isNotEmpty()
            val review = reviewAlgorithmList(kind, offered.joinToString(","))
            assertThat(review.unsupported).isEmpty()
            assertThat(review.refused).isEmpty()
            assertThat(review.isAcceptable).isTrue()
            assertThat(review.names).isEqualTo(offered)
        }
    }

    @Test
    fun `the order typed is the order proposed`() {
        // Order is the instruction, not a detail: SSH sends its algorithms most-preferred first and the
        // server takes the first it shares, so "aes128-ctr first because this router is slow" is a real
        // reason to use this feature. A helpful sort would quietly undo the only thing the user asked for.
        AlgorithmKind.entries.forEach { kind ->
            val reversed = supportedAlgorithmNames(kind).take(3).reversed()
            assertThat(reviewAlgorithmList(kind, reversed.joinToString(",")).names).isEqualTo(reversed)
        }
    }

    @Test
    fun `a name nothing knows is reported, not dropped`() {
        val review = reviewAlgorithmList(AlgorithmKind.CIPHERS, "aes256-ctr,aes-1024-quantum")

        assertThat(review.unsupported).containsExactly("aes-1024-quantum")
        // The list is either usable exactly as typed or it is not saved. Saving the good half would be
        // saving a different instruction from the one given.
        assertThat(review.isAcceptable).isFalse()
        assertThat(review.problem).contains("aes-1024-quantum")
        assertThat(review.problem).contains("Not available on this device")
        // And the good name is still in `names`, because that is what the field would send if it were
        // saved - the review reports, it does not silently repair.
        assertThat(review.names).containsExactly("aes256-ctr")
    }

    @Test
    fun `a typo in the capitalisation of a real name is not a rejection`() {
        // MINA's parsers are the authority on what a name means, including the case they tolerate, which
        // is why the typed names are handed to them rather than matched by hand. A user pasting
        // `AES256-CTR` from a wiki page has not made a mistake this app needs to have an opinion about.
        assertThat(reviewAlgorithmList(AlgorithmKind.CIPHERS, "AES256-CTR").isAcceptable).isTrue()
    }

    @Test
    fun `commas, spaces and newlines all separate names`() {
        // Three shapes a pasted list arrives in: an ssh_config line uses commas, a shell command line
        // uses spaces, and a copy out of a document brings newlines.
        val expected = reviewAlgorithmList(AlgorithmKind.CIPHERS, "aes256-ctr,aes128-ctr").names
        assertThat(expected).hasSize(2)
        assertThat(reviewAlgorithmList(AlgorithmKind.CIPHERS, "aes256-ctr aes128-ctr").names).isEqualTo(expected)
        assertThat(reviewAlgorithmList(AlgorithmKind.CIPHERS, "aes256-ctr\naes128-ctr").names).isEqualTo(expected)
        assertThat(reviewAlgorithmList(AlgorithmKind.CIPHERS, " aes256-ctr , aes128-ctr ").names).isEqualTo(expected)
    }

    @Test
    fun `the none cipher is refused, whatever the library thinks of it`() {
        // The one name this app will not carry. MINA implements the protocol's null cipher and reports it
        // as available, so without this a settings screen would list "no encryption" among the chips,
        // beside aes256-gcm and indistinguishable from a performance tweak. There is no reading of
        // "secure by default" that survives shipping that.
        val review = reviewAlgorithmList(AlgorithmKind.CIPHERS, "none")

        assertThat(review.refused).containsExactly("none")
        assertThat(review.names).isEmpty()
        assertThat(review.isAcceptable).isFalse()
        // Not filed as "unavailable": that reads as the device's fault and would send the user looking
        // for a setting to change rather than deleting a name they typed on purpose.
        assertThat(review.unsupported).isEmpty()
        assertThat(review.problem).contains("unencrypted")
    }

    @Test
    fun `the refusal is not case sensitive and not escapable by hiding in a list`() {
        // MINA's parser resolves `None` to the same cipher, so a case-sensitive refusal would be no
        // refusal at all. And a list is refused whole, so "aes256-ctr first, none as a fallback" - which
        // is exactly how someone would arrive at this by accident - cannot be saved either.
        listOf("None", "NONE", "nOnE").forEach { spelling ->
            assertThat(reviewAlgorithmList(AlgorithmKind.CIPHERS, spelling).refused).hasSize(1)
        }
        val mixed = reviewAlgorithmList(AlgorithmKind.CIPHERS, "aes256-ctr,none")
        assertThat(mixed.refused).containsExactly("none")
        assertThat(mixed.isAcceptable).isFalse()
        assertThat(mixed.names).containsExactly("aes256-ctr")
    }

    @Test
    fun `nothing suggests the none cipher`() {
        // The other half: the chip rows append what they are given, so a name that stayed in this list
        // could be added to a host with one tap and no typing at all.
        assertThat(supportedAlgorithmNames(AlgorithmKind.CIPHERS)).doesNotContain("none")
        AlgorithmKind.entries.forEach { kind ->
            assertThat(supportedAlgorithmNames(kind)).doesNotContain("none")
        }
    }

    @Test
    fun `a stored none cipher cannot install itself at connect time`() {
        // Defence in depth, and not a hypothetical one: this column is restorable from a vault backup and
        // the importer deliberately does not validate algorithm names semantically, so a hand-edited file
        // is a real path from `none` in a JSON field to a session proposal. Dropping it here means the
        // worst such a file can do is send the host back to the library's default list.
        assertThat(cipherFactoriesFor("none")).isNull()
        assertThat(cipherFactoriesFor("None,NONE")).isNull()
        assertThat(cipherFactoriesFor("none,aes128-ctr")?.map { it.name }).containsExactly("aes128-ctr")
    }

    @Test
    fun `the form and the connect path agree on every list`() {
        // The invariant the whole file exists for. `reviewAlgorithmList` decides what may be saved and
        // the factory builders decide what gets installed, from two different MINA entry points; if they
        // ever disagreed, a host would connect proposing something other than what its form displayed.
        val cases = listOf(
            "aes256-ctr,aes128-ctr",
            "aes256-ctr",
            "none",
            "none,aes256-ctr",
            "aes-1024-quantum",
            "aes256-ctr,aes-1024-quantum",
            "",
        )
        cases.forEach { text ->
            val promised = reviewAlgorithmList(AlgorithmKind.CIPHERS, text).names
            // Null means "install nothing, keep the session's own list", which is the right answer for an
            // empty promise and the only safe one - an empty list installed would propose no cipher at all.
            assertThat(cipherFactoriesFor(text)?.map { it.name }.orEmpty()).isEqualTo(promised)
        }
        listOf("hmac-sha2-256,hmac-sha1", "hmac-nonsense", "").forEach { text ->
            val promised = reviewAlgorithmList(AlgorithmKind.MACS, text).names
            assertThat(macFactoriesFor(text)?.map { it.name }.orEmpty()).isEqualTo(promised)
        }
        listOf("ssh-ed25519,rsa-sha2-512", "ssh-nonsense", "").forEach { text ->
            val promised = reviewAlgorithmList(AlgorithmKind.HOST_KEYS, text).names
            assertThat(signatureFactoriesFor(text)?.map { it.name }.orEmpty()).isEqualTo(promised)
        }
    }

    @Test
    fun `every builder answers null rather than an empty list`() {
        // The distinction the connect path depends on: null leaves the session's own list in place, an
        // empty list would replace it with nothing and fail key exchange with a protocol error. Both the
        // "nothing typed" and the "nothing survived" cases have to land on null, and it is the second one
        // that is easy to miss.
        listOf(null, "", "  ", ",", "\n , ").forEach { blank ->
            assertThat(cipherFactoriesFor(blank)).isNull()
            assertThat(macFactoriesFor(blank)).isNull()
            assertThat(signatureFactoriesFor(blank)).isNull()
            assertThat(keyExchangeFactoriesFor(blank)).isNull()
        }
        assertThat(cipherFactoriesFor("no-such-cipher")).isNull()
        assertThat(macFactoriesFor("no-such-mac")).isNull()
        assertThat(signatureFactoriesFor("no-such-signature")).isNull()
        assertThat(keyExchangeFactoriesFor("no-such-kex")).isNull()
    }

    @Test
    fun `a usable name in a list of nonsense still survives`() {
        // The other side of the previous test: "nothing survived" falls back, but "something survived"
        // must not - a host restored onto a device that lost one of its four ciphers should still connect
        // with the other three, in the order it asked for.
        assertThat(cipherFactoriesFor("no-such-cipher,aes256-ctr,also-not-real")?.map { it.name })
            .containsExactly("aes256-ctr")
    }

    @Test
    fun `key exchange names survive the extra hop through MINA's client builder`() {
        // KEX is the one kind that is not installed as the thing it was parsed into: a DHFactory
        // describes a group, and a session proposes a KeyExchangeFactory built around one. A hop that
        // renamed or reordered them would leave the form showing a list the session does not send.
        val names = supportedAlgorithmNames(AlgorithmKind.KEX).take(3)
        assertThat(names).hasSize(3)

        assertThat(keyExchangeFactoriesFor(names.joinToString(","))?.map { it.name }).isEqualTo(names)
        assertThat(keyExchangeFactoriesFor(names.reversed().joinToString(","))?.map { it.name })
            .isEqualTo(names.reversed())
    }

    @Test
    fun `every kind has words for the field it labels`() {
        // The four are shown as labelled fields with help text underneath. An empty label or help string
        // would ship as a field with no name, which is the one thing worse than an unexplained one.
        AlgorithmKind.entries.forEach { kind ->
            assertThat(kind.label).isNotEmpty()
            assertThat(kind.help).isNotEmpty()
        }
    }
}
