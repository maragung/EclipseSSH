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
    fun `a rule whose fields merely hold their defaults writes the historical line`() {
        // The other half of the backward-compat guarantee: the new fields are compared against their
        // defaults, not remembered as "present", so a rule that says only the old things is written
        // exactly as an older build would have written it - whichever way round the entry was built.
        val local = ForwardEntry(
            type = ForwardType.LOCAL, localPort = 8080, remoteHost = "db", remotePort = 5432,
            listenHost = DEFAULT_FORWARD_LISTEN_HOST, enabled = true, autoStart = true, name = null,
        )
        val remote = ForwardEntry(
            type = ForwardType.REMOTE, localPort = 22, remotePort = 2222,
            listenHost = DEFAULT_FORWARD_LISTEN_HOST, localHost = DEFAULT_FORWARD_LISTEN_HOST,
        )

        assertThat(local.toRuleText()).isEqualTo("L:8080:db:5432")
        assertThat(remote.toRuleText()).isEqualTo("R:2222:22")
        assertThat(ForwardEntry(type = ForwardType.DYNAMIC, localPort = 1080, listenHost = DEFAULT_FORWARD_LISTEN_HOST).toRuleText())
            .isEqualTo("D:1080")
    }

    @Test
    fun `a remote rule that names the phone's own loopback is the legacy two-port rule`() {
        // `R:2222:22` decodes with a null destination host, and null and an explicit loopback are
        // the same rule as far as the wire is concerned - so both encode to the same line, and a
        // rule that names its own loopback comes back saying nothing, which is what it meant.
        val unsaid = ForwardEntry(type = ForwardType.REMOTE, localPort = 22, remotePort = 2222)
        val saidLoopback = unsaid.copy(localHost = DEFAULT_FORWARD_LISTEN_HOST)

        assertThat(unsaid.toRuleText()).isEqualTo("R:2222:22")
        assertThat(saidLoopback.toRuleText()).isEqualTo("R:2222:22")
        assertThat(decodeForwardRules(saidLoopback.toRuleText()).single().localHost).isNull()
    }

    @Test
    fun `a remote rule with a named destination round trips through the three-field form`() {
        // `R:2222:dbhost:22`: a port on the server tunnelling to a host the *phone* can reach, which
        // the two-port form cannot say (it can only mean this device's own loopback). The name is
        // written only when there is one, so this is the one shape that grew a field.
        val decoded = decodeForwardRules("R:2222:dbhost:22").single()

        assertThat(decoded.type).isEqualTo(ForwardType.REMOTE)
        assertThat(decoded.remotePort).isEqualTo(2222)
        assertThat(decoded.localHost).isEqualTo("dbhost")
        assertThat(decoded.localPort).isEqualTo(22)
        assertThat(encodeForwardRules(listOf(decoded))).isEqualTo("R:2222:dbhost:22")
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
    fun `an explicit bind interface round trips in the slot ssh itself gives it`() {
        // `ssh -L`'s own `[bind_address:]` slot, before the port. A forward bound to `0.0.0.0` is a
        // tunnel other apps on the phone - or other devices, on a shared network - may use, which
        // is exactly why the interface is written only when it is not the default: a rule that
        // never asked for it must not silently acquire it on the next save.
        val local = decodeForwardRules("L:0.0.0.0:8080:host.example:80").single()
        val socks = decodeForwardRules("D:eth0:1080").single()

        assertThat(local.listenHost).isEqualTo("0.0.0.0")
        assertThat(socks.listenHost).isEqualTo("eth0")
        assertThat(socks.localPort).isEqualTo(1080)
        assertThat(encodeForwardRules(listOf(local, socks)))
            .isEqualTo("L:0.0.0.0:8080:host.example:80\nD:eth0:1080")
    }

    @Test
    fun `a first field is a bind unless it is a port, and ipv6 colons cannot reach it`() {
        // The field before the port is read as a port when it parses as one and as a bind interface
        // when it does not, so a number outside the port range lands in the bind slot as a (strange)
        // interface name - and the line then still has to parse after it, or it is dropped.
        assertThat(decodeForwardRules("D:70000:1080").single().listenHost).isEqualTo("70000")
        assertThat(decodeForwardRules("D:70000:notaport")).isEmpty()
        // A bare IPv6 literal has no way into that slot: its first colon ends the field as an empty
        // string, which is neither a port nor a name. Naming a host that resolves to one is the
        // documented way around it.
        assertThat(decodeForwardRules("L:::1:8080:a:80")).isEmpty()
        assertThat(decodeForwardRules("D:::1:1080")).isEmpty()
    }

    @Test
    fun `blank lines and stray whitespace are not rules and are not errors either`() {
        val decoded = decodeForwardRules("\n  D:1080  \n\n   \n")

        assertThat(decoded).hasSize(1)
        assertThat(decoded.single().localPort).isEqualTo(1080)
    }

    @Test
    fun `the state markers round trip`() {
        // `#` is stored, listed, and never started - not by a connect, not by hand; `-` is started
        // by hand only. Both have to survive a save, because a disabled rule the sheet silently
        // re-enabled is a tunnel the user explicitly switched off coming back up on every connect.
        val disabled = decodeForwardRules("#D:1080").single()
        val manual = decodeForwardRules("-D:1080").single()

        assertThat(disabled.enabled).isFalse()
        assertThat(disabled.autoStart).isTrue()
        assertThat(manual.enabled).isTrue()
        assertThat(manual.autoStart).isFalse()
        assertThat(encodeForwardRules(listOf(disabled, manual))).isEqualTo("#D:1080\n-D:1080")
    }

    @Test
    fun `the kind letter is not case sensitive`() {
        // A rule pasted from a note may well be lower case, and rejecting it would look like the editor
        // refusing a rule that is plainly correct.
        assertThat(decodeForwardRules("l:8080:a:80\nr:2222:22\nd:1080").map { it.type })
            .containsExactly(ForwardType.LOCAL, ForwardType.REMOTE, ForwardType.DYNAMIC).inOrder()
    }

    @Test
    fun `a label may contain colons and spaces, because it is split off before anything else`() {
        // The label is cut on the line's first whitespace, before any colon is counted, so it can
        // hold anything - including words that look like the rule's own fields - without changing
        // what the rule part of the line means.
        val decoded = decodeForwardRules("L:8080:a:80 My label: yes").single()

        assertThat(decoded.name).isEqualTo("My label: yes")
        assertThat(decoded.remoteHost).isEqualTo("a")
        assertThat(decoded.remotePort).isEqualTo(80)
        assertThat(encodeForwardRules(listOf(decoded))).isEqualTo("L:8080:a:80 My label: yes")
    }

    @Test
    fun `a label is capped at sixty-four characters and carries nothing hidden`() {
        // One line in the sheet, so a label is one line: at the cap it survives intact, and past it
        // the whole line is dropped rather than saved with a label that lies about what was typed.
        // A control character is refused for the same reason the host validation refuses one.
        val sixtyFour = "x".repeat(64)

        assertThat(decodeForwardRules("D:1080 $sixtyFour").single().name).isEqualTo(sixtyFour)
        assertThat(decodeForwardRules("D:1080 ${"x".repeat(65)}")).isEmpty()
        assertThat(decodeForwardRules("D:1080 hidden\u0007bell")).isEmpty()
    }

    @Test
    fun `whitespace after a rule with nothing behind it costs the rule nothing`() {
        // The line is trimmed before the label is split off, so a rule followed by only spaces is a
        // rule with no label, not a dropped line: no ASCII input can reach the parser's blank-label
        // guard. (Whether a non-ASCII space such as U+00A0 survives that trim depends on which
        // `trim` `String::trim` binds to, so nothing here leans on it.)
        val decoded = decodeForwardRules("D:1080   ").single()

        assertThat(decoded.localPort).isEqualTo(1080)
        assertThat(decoded.name).isNull()
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
    fun `a hash with no rule behind it is a comment, and a disabled rule may carry a label`() {
        // The marker is not a comment syntax: only a real rule behind it survives the parse, which
        // is what keeps a hand-written `# note` from becoming a disabled forward nobody asked for.
        assertThat(decodeForwardRules("# a comment")).isEmpty()

        val disabled = decodeForwardRules("#D:1080 Proxy").single()
        assertThat(disabled.enabled).isFalse()
        assertThat(disabled.name).isEqualTo("Proxy")
        assertThat(encodeForwardRules(listOf(disabled))).isEqualTo("#D:1080 Proxy")
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
    fun `a device listen address exists exactly for the rules that hold a phone-side socket`() {
        // The address the sheet checks for a bind clash: local and dynamic rules both hold a port
        // on this device, so between them - and across hosts - the pair has to be unique or the
        // second bind fails. A remote rule's phone side is a dial-out and holds nothing.
        val local = ForwardEntry(type = ForwardType.LOCAL, localPort = 8080, remoteHost = "db", remotePort = 80)
        val socks = ForwardEntry(type = ForwardType.DYNAMIC, localPort = 1080)

        assertThat(local.deviceListenAddress()).isEqualTo("127.0.0.1:8080")
        assertThat(local.copy(listenHost = "0.0.0.0").deviceListenAddress()).isEqualTo("0.0.0.0:8080")
        assertThat(socks.deviceListenAddress()).isEqualTo("127.0.0.1:1080")
        assertThat(socks.copy(listenHost = "eth0").deviceListenAddress()).isEqualTo("eth0:1080")
        assertThat(ForwardEntry(type = ForwardType.REMOTE, localPort = 22, remotePort = 2222).deviceListenAddress())
            .isNull()
    }

    @Test
    fun `two rules conflict only when they would hold the same device socket`() {
        // What the sheet warns on: a second rule wanting the phone's `host:port` a first one already
        // holds. Ids are compared so a rule is never in conflict with itself - the sheet checks a
        // list against its own members - and remote rules never conflict locally, because what they
        // would collide on is a server-side port, and the server says so when the bind is refused.
        val local = ForwardEntry(type = ForwardType.LOCAL, localPort = 8080, remoteHost = "a", remotePort = 80)
        val socks = ForwardEntry(type = ForwardType.DYNAMIC, localPort = 8080)
        val onEthernet = socks.copy(listenHost = "eth0")
        val remote = ForwardEntry(type = ForwardType.REMOTE, localPort = 8080, remotePort = 2222)

        assertThat(local.conflictsWith(socks)).isTrue()
        assertThat(socks.conflictsWith(local)).isTrue()
        assertThat(local.conflictsWith(onEthernet)).isFalse()
        assertThat(socks.conflictsWith(socks.copy())).isFalse()
        assertThat(local.conflictsWith(remote)).isFalse()
        assertThat(remote.conflictsWith(remote.copy())).isFalse()
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
    fun `describe names the label and any interface that is not the default`() {
        // A rule with a label reads as its label first, because that is what the user named it and
        // the ports are the detail; an interface that is not loopback is named as written, because
        // "localhost" on a rule bound to `0.0.0.0` would say the opposite of what it does.
        assertThat(
            ForwardEntry(type = ForwardType.LOCAL, localPort = 8080, remoteHost = "db", remotePort = 5432, name = "Database").describe(),
        ).isEqualTo("Database: localhost:8080 → db:5432")
        assertThat(
            ForwardEntry(type = ForwardType.LOCAL, localPort = 8080, remoteHost = "db", remotePort = 5432, listenHost = "0.0.0.0").describe(),
        ).isEqualTo("0.0.0.0:8080 → db:5432")
        assertThat(
            ForwardEntry(type = ForwardType.REMOTE, localPort = 22, remotePort = 2222, listenHost = "0.0.0.0", localHost = "dbhost").describe(),
        ).isEqualTo("server 0.0.0.0:2222 → dbhost:22")
        assertThat(ForwardEntry(type = ForwardType.DYNAMIC, localPort = 1080, listenHost = "eth0").describe())
            .isEqualTo("SOCKS5 proxy on eth0:1080")
    }

    @Test
    fun `a remote rule names the server interface it binds, and writes nothing when it means loopback`() {
        // The contract this syntax now carries: a remote rule binds loopback on the server unless it
        // names another interface explicitly. The security posture did not go away, it moved into
        // the UI - a bind of all interfaces on a server configured `GatewayPorts yes` publishes the
        // phone's port to that server's whole network, so it is a choice the sheet makes the user
        // make deliberately, with a warning, rather than one an inexpressible syntax made for them
        // (and which made the safe choice inexpressible too, on the servers that want it).
        val decoded = decodeForwardRules("R:0.0.0.0:2222:22").single()

        assertThat(decoded.type).isEqualTo(ForwardType.REMOTE)
        assertThat(decoded.listenHost).isEqualTo("0.0.0.0")
        assertThat(decoded.remotePort).isEqualTo(2222)
        assertThat(decoded.localPort).isEqualTo(22)
        assertThat(encodeForwardRules(listOf(decoded))).isEqualTo("R:0.0.0.0:2222:22")
        // Nothing named is loopback, and loopback is the default - so the historical rule keeps its
        // historical two-port line.
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
