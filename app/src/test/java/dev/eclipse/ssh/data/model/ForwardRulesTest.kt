package dev.eclipse.ssh.data.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The one-rule-per-line codec behind [HostProfile.savedForwards].
 *
 * This column is the only per-host setting that is *structured* text rather than a number, a flag or a
 * name, and it is read in three places that cannot see each other: the editor in the Advanced section,
 * `MainViewModel` when a session comes up, and the vault importer. So the rules are stated once, here.
 *
 * Two of them are load-bearing beyond tidiness. Decoding has to be **total** - a line that does not
 * parse is dropped, never thrown - because the text can arrive from a hand-edited or truncated backup,
 * and an exception on one bad line would cost the user the host. And the ids have to be **derived**, so
 * that decoding the same column twice produces the same list: the editor compares the list it holds
 * against the list it just read, and `MainViewModel` tells a saved rule from a hand-opened forward by
 * that id alone.
 */
class ForwardRulesTest {

    @Test
    fun `each of the three kinds round trips through its own syntax`() {
        val rules = listOf(
            ForwardEntry(type = ForwardType.LOCAL, localPort = 8080, remoteHost = "intranet.example", remotePort = 80),
            ForwardEntry(type = ForwardType.REMOTE, localPort = 22, remotePort = 2222),
            ForwardEntry(type = ForwardType.DYNAMIC, localPort = 1080),
        )

        val text = encodeForwardRules(rules)

        assertThat(text.lines()).containsExactly("L:8080:intranet.example:80", "R:2222:22", "D:1080").inOrder()
        // Compared field by field rather than by object, because the ids are deliberately different:
        // an encoded rule loses the UUID it was built with and a decoded one is named by its text.
        val decoded = decodeForwardRules(text)
        assertThat(decoded.map { it.type }).isEqualTo(rules.map { it.type })
        assertThat(decoded.map { it.localPort }).isEqualTo(rules.map { it.localPort })
        assertThat(decoded.map { it.remoteHost }).isEqualTo(rules.map { it.remoteHost })
        assertThat(decoded.map { it.remotePort }).isEqualTo(rules.map { it.remotePort })
    }

    @Test
    fun `the text is the syntax ssh itself uses`() {
        // Not decoration: the point of borrowing `ssh -L`/`-R`/`-D` is that a user reading a backup file
        // already knows what these lines mean, and can paste one from a note straight into the editor.
        assertThat(ForwardEntry(type = ForwardType.LOCAL, localPort = 5432, remoteHost = "db", remotePort = 5432).toRuleText())
            .isEqualTo("L:5432:db:5432")
        assertThat(ForwardEntry(type = ForwardType.REMOTE, localPort = 8000, remotePort = 9000).toRuleText())
            .isEqualTo("R:9000:8000")
        assertThat(ForwardEntry(type = ForwardType.DYNAMIC, localPort = 1080).toRuleText()).isEqualTo("D:1080")
    }

    @Test
    fun `decoding the same column twice gives the same ids`() {
        // The reason the ids are derived from the rule text instead of generated. `AdvancedHostOptions.from`
        // decodes on every recomposition, and an entry that got a fresh UUID each time would never compare
        // equal to itself: a form that looks edited the moment it opens, and a recomposition that cannot
        // settle. `MainViewModel` also keys its live handles on these, so a rule has to keep its identity
        // across a reconnect.
        val text = "L:8080:a:80\nD:1080"

        assertThat(decodeForwardRules(text, "host-1").map { it.id })
            .isEqualTo(decodeForwardRules(text, "host-1").map { it.id })
    }

    @Test
    fun `a saved rule can be told apart from a forward the user opened by hand`() {
        // The distinction `MainViewModel.stopSavedForwards` turns on: a reconnect has to rebind the saved
        // rules, whose trackers belong to a transport that has gone, and must leave a SOCKS proxy the user
        // started themselves alone. A `#` is what makes that safe, because a UUID never contains one.
        val saved = decodeForwardRules("D:1080", "host-1").single()
        val byHand = ForwardEntry(type = ForwardType.DYNAMIC, localPort = 1080, hostId = "host-1")

        assertThat(saved.id).startsWith(savedForwardIdPrefix("host-1"))
        assertThat(byHand.id).doesNotContain("#")
        assertThat(byHand.id).doesNotContain(savedForwardIdPrefix("host-1"))
    }

    @Test
    fun `two identical rules on different lines are still two rules`() {
        // The index is in the id for this reason. Without it the second line would collide with the first,
        // and one of the two forwards would silently replace the other in the handle map.
        val decoded = decodeForwardRules("D:1080\nD:1080", "host-1")

        assertThat(decoded).hasSize(2)
        assertThat(decoded[0].id).isNotEqualTo(decoded[1].id)
    }

    @Test
    fun `the host id travels with every rule`() {
        // A running forward is looked up by the host that owns it, so a rule decoded without one would be
        // a rule nobody could attribute to a session or stop when that session went away.
        val decoded = decodeForwardRules("L:8080:a:80\nR:2222:22\nD:1080", "host-9")

        assertThat(decoded.map { it.hostId }).containsExactly("host-9", "host-9", "host-9")
    }

    @Test
    fun `an ipv6 target keeps its colons`() {
        // Split from the right, so the port is the last field and everything between is the host however
        // many colons it contains. Splitting on every colon would turn this into six meaningless fields.
        val decoded = decodeForwardRules("L:8080:::1:80").single()

        assertThat(decoded.remoteHost).isEqualTo("::1")
        assertThat(decoded.remotePort).isEqualTo(80)
        assertThat(decoded.localPort).isEqualTo(8080)
    }

    @Test
    fun `blank lines and stray whitespace are not rules and are not errors either`() {
        val decoded = decodeForwardRules("\n  D:1080  \n\n   \n")

        assertThat(decoded).hasSize(1)
        assertThat(decoded.single().localPort).isEqualTo(1080)
    }

    @Test
    fun `the kind letter is not case sensitive`() {
        // A rule pasted from a note may well be lower case, and rejecting it would look like the editor
        // refusing a rule that is plainly correct.
        assertThat(decodeForwardRules("l:8080:a:80\nr:2222:22\nd:1080").map { it.type })
            .containsExactly(ForwardType.LOCAL, ForwardType.REMOTE, ForwardType.DYNAMIC).inOrder()
    }

    @Test
    fun `every unparseable line is dropped rather than throwing`() {
        // Decoding is validation, and it has to be total: this text can come from a hand-edited or
        // truncated vault backup, so one bad line must not be able to stop the other rules - or the host
        // itself - from loading. Each of these is a shape a real file could contain.
        val hostile = listOf(
            "",                       // nothing
            "L",                      // a kind and no fields
            "L:",                     // a kind and an empty tail
            "X:1080",                 // a kind that does not exist
            "D:0",                    // port 0 is reserved and would fail to bind
            "D:70000",                // above the port range
            "D:eighty",               // not a number
            "D:-1",                   // negative
            "L:8080",                 // a local rule with no target
            "L:8080:host",            // a target with no port
            "L:8080::80",             // a target with no host
            "L:8080:  :80",           // a blank host
            "L:8080:has space:80",    // whitespace could smuggle a second field past the encoder
            "R:2222",                 // a remote rule with one port
            "R:2222:0",               // a remote rule whose local port is reserved
            "1080",                   // a bare port
            "# a comment",            // not a syntax this codec claims to support
        )

        assertThat(decodeForwardRules(hostile.joinToString("\n"))).isEmpty()
        // And a bad line beside a good one costs only itself.
        assertThat(decodeForwardRules("X:1\nD:1080\nL:8080").map { it.localPort }).containsExactly(1080)
    }

    @Test
    fun `a rule at the ends of the port range survives`() {
        // The bounds are what an off-by-one rejects, and 65535 is a port a user may legitimately pick.
        val decoded = decodeForwardRules("D:${PORT_RANGE.first}\nD:${PORT_RANGE.last}")

        assertThat(decoded.map { it.localPort }).containsExactly(PORT_RANGE.first, PORT_RANGE.last).inOrder()
    }

    @Test
    fun `neither end of the codec will carry more rules than a host may have`() {
        // Both directions, because they defend different things: encoding bounds what the editor can save,
        // decoding bounds what an imported backup can ask the app to bind. A file naming ten thousand
        // rules must not turn a login into ten thousand listening sockets.
        val many = (1..MAX_SAVED_FORWARDS + 20).map {
            ForwardEntry(type = ForwardType.DYNAMIC, localPort = 1000 + it)
        }

        assertThat(encodeForwardRules(many).lines()).hasSize(MAX_SAVED_FORWARDS)
        assertThat(decodeForwardRules(many.joinToString("\n") { it.toRuleText() })).hasSize(MAX_SAVED_FORWARDS)
    }

    @Test
    fun `a rule the editor would accept survives the round trip unchanged`() {
        // What the editor relies on: it validates a typed rule by decoding it, so "a rule the editor
        // accepts" and "a rule a restored backup would accept" have to be the same set.
        val text = "L:8080:intranet.example:80\nR:2222:22\nD:1080"

        assertThat(encodeForwardRules(decodeForwardRules(text))).isEqualTo(text)
    }

    @Test
    fun `an empty column is no rules, and no rules is an empty column`() {
        assertThat(decodeForwardRules("")).isEmpty()
        assertThat(encodeForwardRules(emptyList())).isEmpty()
        assertThat(HostProfile(id = "h", name = "P", host = "p.example.com", username = "u").savedForwards).isEmpty()
    }

    @Test
    fun `describe says which way each tunnel points`() {
        // Shown in the editor and used in a failure message, so it has to name the direction: "8080 is
        // busy" on a rule the user cannot identify is not a diagnosis.
        assertThat(ForwardEntry(type = ForwardType.LOCAL, localPort = 8080, remoteHost = "db", remotePort = 5432).describe())
            .isEqualTo("localhost:8080 → db:5432")
        assertThat(ForwardEntry(type = ForwardType.REMOTE, localPort = 22, remotePort = 2222).describe())
            .isEqualTo("server:2222 → localhost:22")
        assertThat(ForwardEntry(type = ForwardType.DYNAMIC, localPort = 1080).describe())
            .isEqualTo("SOCKS5 proxy on localhost:1080")
    }

    @Test
    fun `the remote bind address cannot be expressed at all`() {
        // Deliberate, and the one thing this syntax is narrower than `ssh` on purpose: a remote forward
        // bound to all interfaces on a server configured `GatewayPorts yes` publishes the phone's port to
        // that server's whole network, and nothing in a two-port rule says the user asked for that. A
        // syntax that could express it would be a syntax that could do it by accident.
        assertThat(decodeForwardRules("R:0.0.0.0:2222:22")).isEmpty()
        assertThat(decodeForwardRules("R:*:2222:22")).isEmpty()
        assertThat(ForwardEntry(type = ForwardType.REMOTE, localPort = 22, remotePort = 2222).toRuleText())
            .isEqualTo("R:2222:22")
    }

    @Test
    fun `a host name at the length limit is accepted and one past it is not`() {
        assertThat("a".repeat(253).isForwardHostName()).isTrue()
        assertThat("a".repeat(254).isForwardHostName()).isFalse()
        assertThat("".isForwardHostName()).isFalse()
        assertThat("   ".isForwardHostName()).isFalse()
        assertThat("has space".isForwardHostName()).isFalse()
        assertThat("has\ttab".isForwardHostName()).isFalse()
        assertThat("has\nnewline".isForwardHostName()).isFalse()
    }

    @Test
    fun `a port is only a port inside the usable range`() {
        assertThat("22".toPortOrNull()).isEqualTo(22)
        assertThat("  22  ".toPortOrNull()).isEqualTo(22)
        assertThat("0".toPortOrNull()).isNull()
        assertThat("65536".toPortOrNull()).isNull()
        assertThat("-1".toPortOrNull()).isNull()
        assertThat("".toPortOrNull()).isNull()
        assertThat("22a".toPortOrNull()).isNull()
    }
}
