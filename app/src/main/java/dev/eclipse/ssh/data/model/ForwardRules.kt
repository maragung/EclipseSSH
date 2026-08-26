package dev.eclipse.ssh.data.model

/**
 * [HostProfile.savedForwards] as text, and back, in the syntax `ssh` itself uses.
 *
 * One rule per line, because that is the shape a user can read in a backup file and the shape the
 * editor lists:
 *
 *  - `L:8080:intranet.example:80` - `ssh -L 8080:intranet.example:80`, a local port on the phone
 *    tunnelling to a host reachable from the server;
 *  - `R:2222:22` - `ssh -R 2222:localhost:22`, a port on the server tunnelling back to this device;
 *  - `D:1080` - `ssh -D 1080`, a SOCKS5 proxy on the phone.
 *
 * The remote *bind* address is deliberately not encodable. Every rule this app opens binds loopback on
 * whichever side it lands, which is what `PortForwardingManager`'s callers already do and for the
 * reason documented there: a remote forward bound to all interfaces on a server configured
 * `GatewayPorts yes` publishes the phone's port to the server's whole network, and nothing in a
 * two-port form says that is what the user asked for. A syntax that could express it would be a
 * syntax that could do it by accident.
 *
 * Decoding is validation. This column can arrive from a hand-edited or truncated vault backup, so
 * anything that does not parse into a rule the app would have let the user save is dropped rather than
 * carried - a rule the engine cannot act on is not information, and a malformed line must not be able
 * to stop the other rules or the host itself from loading.
 */
fun encodeForwardRules(rules: List<ForwardEntry>): String =
    rules.take(MAX_SAVED_FORWARDS).joinToString("\n") { it.toRuleText() }

/** One rule as the line [decodeForwardRules] reads back. */
fun ForwardEntry.toRuleText(): String = when (type) {
    ForwardType.LOCAL -> "L:$localPort:${remoteHost.orEmpty()}:${remotePort ?: 0}"
    ForwardType.REMOTE -> "R:${remotePort ?: 0}:$localPort"
    ForwardType.DYNAMIC -> "D:$localPort"
}

/**
 * The rules in [text], dropping every line that is not one, tagged with [hostId].
 *
 * [hostId] is copied onto each entry because a running forward is looked up by the host that owns it -
 * `MainViewModel` keys its live handles that way - and a saved rule read back without it would be a
 * rule nobody could attribute to a session, or stop when that session went away.
 */
fun decodeForwardRules(text: String, hostId: String? = null): List<ForwardEntry> = text
    .lineSequence()
    .map(String::trim)
    .filter(String::isNotEmpty)
    .mapIndexedNotNull { index, line -> parseForwardRule(line, hostId, index) }
    .take(MAX_SAVED_FORWARDS)
    .toList()

/**
 * One line as a rule, or null.
 *
 * Split from the *right* for the two-part tail of a local rule, so an IPv6 literal in the middle
 * survives: `L:8080:::1:80` has to mean port 8080 to `::1` port 80, and splitting on every colon
 * would turn it into six meaningless fields. The port is always the last one, and everything between
 * the local port and it is the host however many colons it contains.
 */
private fun parseForwardRule(line: String, hostId: String?, index: Int): ForwardEntry? {
    val kind = line.substringBefore(':', missingDelimiterValue = "")
    val rest = line.substringAfter(':', missingDelimiterValue = "")
    if (rest.isEmpty()) return null
    // Derived from the rule rather than random, so decoding is a pure function of the column: the editor
    // compares the list it is holding against the list it just read, and an entry that got a fresh UUID
    // on every read would never compare equal to itself - a form that looked edited the moment it opened
    // and a recomposition that could not settle. It also means a forward keeps its identity across a
    // reconnect, which is what `MainViewModel` keys its live handles on.
    val id = savedForwardIdPrefix(hostId.orEmpty()) + "$index:$line"
    return when (kind.uppercase()) {
        "L" -> {
            val localPort = rest.substringBefore(':').toPortOrNull() ?: return null
            val target = rest.substringAfter(':', missingDelimiterValue = "")
            val remotePort = target.substringAfterLast(':', missingDelimiterValue = "").toPortOrNull() ?: return null
            val remoteHost = target.substringBeforeLast(':', missingDelimiterValue = "").trim()
            if (!remoteHost.isForwardHostName()) return null
            ForwardEntry(
                id = id,
                type = ForwardType.LOCAL,
                localPort = localPort,
                remoteHost = remoteHost,
                remotePort = remotePort,
                hostId = hostId,
            )
        }
        "R" -> {
            val remotePort = rest.substringBefore(':').toPortOrNull() ?: return null
            val localPort = rest.substringAfter(':', missingDelimiterValue = "").toPortOrNull() ?: return null
            ForwardEntry(
                id = id,
                type = ForwardType.REMOTE,
                localPort = localPort,
                remoteHost = null,
                remotePort = remotePort,
                hostId = hostId,
            )
        }
        "D" -> rest.toPortOrNull()?.let {
            ForwardEntry(id = id, type = ForwardType.DYNAMIC, localPort = it, hostId = hostId)
        }
        else -> null
    }
}

/**
 * What every id [decodeForwardRules] hands out for [hostId] begins with.
 *
 * The one way to tell a forward that came from the host's saved column from one the user opened by hand
 * in the forwarding sheet, and the two have to be told apart: a reconnect has to close and rebind the
 * saved rules, because their trackers still hold the local ports on a transport that has gone, while a
 * forward the user opened themselves is theirs to close and must survive. A `#` is what makes this
 * safe - a hand-opened forward gets a `UUID`, which never contains one - and it lives here rather than
 * in the caller so the shape is defined once, beside the code that produces it.
 */
internal fun savedForwardIdPrefix(hostId: String): String = "$hostId#"

/** [this] as a usable TCP port, or null - which covers blank, non-numeric, 0 and out of range alike. */
fun String.toPortOrNull(): Int? = trim().toIntOrNull()?.takeIf { it in PORT_RANGE }

/**
 * Whether this is a name a forward may be pointed at.
 *
 * Shape only - a hostname or a literal address, no whitespace, no control characters, nothing that
 * could smuggle a second field past the encoder and reappear as a different rule on the way back. What
 * it resolves to is the server's business, and a name that resolves to nothing fails the forward with
 * the server's own message, which is a better answer than this function guessing.
 */
fun String.isForwardHostName(): Boolean = isNotBlank() &&
    length <= MAX_FORWARD_HOST_LENGTH &&
    none { it.isWhitespace() || it.isISOControl() }

private const val MAX_FORWARD_HOST_LENGTH = 253

/** One rule as a line of English for the editor and for a failure message. */
fun ForwardEntry.describe(): String = when (type) {
    ForwardType.LOCAL -> "localhost:$localPort → ${remoteHost.orEmpty()}:${remotePort ?: 0}"
    ForwardType.REMOTE -> "server:${remotePort ?: 0} → localhost:$localPort"
    ForwardType.DYNAMIC -> "SOCKS5 proxy on localhost:$localPort"
}
