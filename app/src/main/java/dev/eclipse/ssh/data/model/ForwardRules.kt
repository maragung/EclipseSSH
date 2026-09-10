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
 * Three optional pieces extend that core, each written only when it is not the default so that
 * every rule ever saved before them round-trips byte for byte:
 *
 *  - an explicit *bind* interface after the kind letter, `ssh`'s own `[bind_address:]` slot -
 *    `L:0.0.0.0:8080:intranet.example:80`, `D:0.0.0.0:1080`. IPv6 literals are not accepted here
 *    (a bare `::1` field is indistinguishable from the colons the rule itself is made of); name a
 *    hostname that resolves to one, or accept loopback.
 *  - a *state* marker before the kind letter: `#` disables the rule - stored, listed, never
 *    started - and `-` keeps it enabled but out of every connect's auto-start. A hand-written
 *    comment that happens to start with `#` still falls out at the parse, because only a real
 *    rule behind the marker survives.
 *  - a *label* after the rule, set off by whitespace: `L:8080:db:5432 Database`. One label, no
 *    description slot - a rule that needs a paragraph is a rule that should be two hosts.
 *
 * The remote *bind* address spends its life in that explicit slot only. A rule that says nothing
 * binds loopback on whichever side it lands, and a remote forward that asks for `0.0.0.0` is one
 * the user wrote out themselves - on a server configured `GatewayPorts yes` that publishes the
 * phone's port to the server's whole network, which is exactly why the field is explicit,
 * defaulted to loopback, and warned about in the sheet rather than filled in by anything.
 *
 * Decoding is validation. This column can arrive from a hand-edited or truncated vault backup, so
 * anything that does not parse into a rule the app would have let the user save is dropped rather than
 * carried - a rule the engine cannot act on is not information, and a malformed line must not be able
 * to stop the other rules or the host itself from loading.
 */
fun encodeForwardRules(rules: List<ForwardEntry>): String =
    rules.take(MAX_SAVED_FORWARDS).joinToString("\n") { it.toRuleText() }

/** One rule as the line [decodeForwardRules] reads back. */
fun ForwardEntry.toRuleText(): String {
    val marker = when {
        !enabled -> "#"
        !autoStart -> "-"
        else -> ""
    }
    val bind = if (listenHost == DEFAULT_FORWARD_LISTEN_HOST) "" else "$listenHost:"
    val core = when (type) {
        ForwardType.LOCAL -> "L:${bind}$localPort:${remoteHost.orEmpty()}:${remotePort ?: 0}"
        ForwardType.REMOTE -> {
            // The destination host is written only when it is not the phone's own loopback, so the
            // historical two-port form stays what a plain remote rule reads and writes.
            val destination =
                if (localHost.isNullOrBlank() || localHost == DEFAULT_FORWARD_LISTEN_HOST) "" else ":$localHost"
            "R:${bind}${remotePort ?: 0}$destination:$localPort"
        }
        ForwardType.DYNAMIC -> "D:${bind}$localPort"
    }
    val label = name?.trim()?.takeIf(String::isNotBlank)?.let { " $it" } ?: ""
    return marker + core + label
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
 * The optional label is split off *first*, on the line's first whitespace, because the rule itself
 * never contains one and the label may contain anything up to its own cap. The port-and-host tail
 * of a local rule is then split from the *right*, so an IPv6 literal in the middle survives:
 * `L:8080:::1:80` has to mean port 8080 to `::1` port 80, and splitting on every colon would turn
 * it into six meaningless fields. The port is always the last one, and everything between the
 * local port and it is the host however many colons it contains.
 */
private fun parseForwardRule(line: String, hostId: String?, index: Int): ForwardEntry? {
    val spaceAt = line.indexOfFirst { it.isWhitespace() }
    val rulePart: String
    val label: String?
    if (spaceAt < 0) {
        rulePart = line
        label = null
    } else {
        rulePart = line.substring(0, spaceAt)
        val candidate = line.substring(spaceAt + 1).trim()
        if (candidate.isEmpty()) return null
        if (candidate.length > MAX_FORWARD_NAME_LENGTH || candidate.any { it.isISOControl() }) return null
        label = candidate
    }

    var rest = rulePart
    var enabled = true
    var autoStart = true
    when (rest.firstOrNull()) {
        '#' -> {
            enabled = false
            rest = rest.substring(1)
        }
        '-' -> {
            autoStart = false
            rest = rest.substring(1)
        }
    }

    val kind = rest.substringBefore(':', missingDelimiterValue = "")
    val tail = rest.substringAfter(':', missingDelimiterValue = "")
    if (tail.isEmpty()) return null
    // A first field that is not a port number is an explicit bind interface - the slot ssh itself
    // puts before the port. Colons cannot reach here (they would have ended the field), which is
    // the whole of the no-IPv6-literals rule.
    val firstField = tail.substringBefore(':')
    val listenHost: String
    val body: String
    if (firstField.toPortOrNull() == null) {
        if (!firstField.isForwardHostName()) return null
        listenHost = firstField
        body = tail.substringAfter(':', missingDelimiterValue = "")
        if (body.isEmpty()) return null
    } else {
        listenHost = DEFAULT_FORWARD_LISTEN_HOST
        body = tail
    }

    // Derived from the rule rather than random, so decoding is a pure function of the column: the editor
    // compares the list it is holding against the list it just read, and an entry that got a fresh UUID
    // on every read would never compare equal to itself - a form that looked edited the moment it opened
    // and a recomposition that could not settle. It also means a forward keeps its identity across a
    // reconnect, which is what `MainViewModel` keys its live handles on.
    val id = savedForwardIdPrefix(hostId.orEmpty()) + "$index:$line"
    return when (kind.uppercase()) {
        "L" -> {
            val localPort = body.substringBefore(':').toPortOrNull() ?: return null
            val target = body.substringAfter(':', missingDelimiterValue = "")
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
                listenHost = listenHost,
                enabled = enabled,
                autoStart = autoStart,
                name = label,
            )
        }
        "R" -> {
            val remotePort = body.substringBefore(':').toPortOrNull() ?: return null
            val remainder = body.substringAfter(':', missingDelimiterValue = "")
            if (remainder.isEmpty()) return null
            // `R:bind:port:port` is the whole story unless the first thing after the server's port
            // is a name, in which case it is the phone-side destination and the port follows it.
            val destinationHead = remainder.substringBefore(':')
            val localHost: String?
            val localPort: Int
            if (destinationHead.toPortOrNull() == null) {
                if (!destinationHead.isForwardHostName()) return null
                localHost = destinationHead
                localPort = remainder.substringAfter(':', missingDelimiterValue = "").toPortOrNull() ?: return null
            } else {
                localHost = null
                localPort = destinationHead.toPortOrNull() ?: return null
            }
            ForwardEntry(
                id = id,
                type = ForwardType.REMOTE,
                localPort = localPort,
                remoteHost = null,
                remotePort = remotePort,
                hostId = hostId,
                listenHost = listenHost,
                localHost = localHost,
                enabled = enabled,
                autoStart = autoStart,
                name = label,
            )
        }
        "D" -> body.toPortOrNull()?.let {
            ForwardEntry(
                id = id,
                type = ForwardType.DYNAMIC,
                localPort = it,
                hostId = hostId,
                listenHost = listenHost,
                enabled = enabled,
                autoStart = autoStart,
                name = label,
            )
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

/** The longest label a rule may carry - the sheet shows it in one line, so a label is one line. */
private const val MAX_FORWARD_NAME_LENGTH = 64

/**
 * The `interface:port` this rule would listen on *on this device*, or null if it listens on the
 * server.
 *
 * A local and a dynamic rule both hold a phone-side socket, so between them - and across hosts -
 * the pair has to be unique or the second bind fails; a remote rule's phone side is a dial-out and
 * holds nothing.
 */
fun ForwardEntry.deviceListenAddress(): String? = when (type) {
    ForwardType.LOCAL, ForwardType.DYNAMIC -> "$listenHost:$localPort"
    ForwardType.REMOTE -> null
}

/**
 * Whether [this] and [other] would ask the device for the same listening socket.
 *
 * Ids are compared so an entry is not in conflict with itself, and remote rules never conflict
 * locally: what they would collide on is a server-side port, and the server is both the authority
 * on that and the one that says so when the bind is refused.
 */
fun ForwardEntry.conflictsWith(other: ForwardEntry): Boolean =
    id != other.id && deviceListenAddress() != null && deviceListenAddress() == other.deviceListenAddress()

/** One rule as a line of English for the editor and for a failure message. */
fun ForwardEntry.describe(): String {
    val label = name?.trim()?.takeIf(String::isNotBlank)?.let { "$it: " } ?: ""
    // Loopback reads as "localhost" rather than the literal it is stored as - the word is what the
    // rule means to a person, and every other interface is named as written.
    val bind = if (listenHost == DEFAULT_FORWARD_LISTEN_HOST) "localhost" else listenHost
    return label + when (type) {
        ForwardType.LOCAL -> "$bind:$localPort → ${remoteHost.orEmpty()}:${remotePort ?: 0}"
        ForwardType.REMOTE -> {
            val server = if (listenHost == DEFAULT_FORWARD_LISTEN_HOST) "server" else "server $listenHost"
            "$server:${remotePort ?: 0} → ${localHost ?: "localhost"}:$localPort"
        }
        ForwardType.DYNAMIC -> "SOCKS5 proxy on $bind:$localPort"
    }
}
