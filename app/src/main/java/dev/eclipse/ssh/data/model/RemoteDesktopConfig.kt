package dev.eclipse.ssh.data.model

/**
 * One remote-desktop endpoint, as the SSH server sees it.
 *
 * The target is dialed *from the server* over an ad-hoc local forward - never directly from
 * the phone - which is what puts a VNC server on the office LAN behind nothing but SSH, and
 * what makes [host] the name the *server* resolves, not the phone. A host that says nothing
 * is the server's own loopback, where the textbook VNC setup listens.
 */
data class RemoteDesktopTarget(
    /** The address the SSH server dials - a name the *server* resolves, or its own loopback. */
    val host: String = DEFAULT_REMOTE_DESKTOP_HOST,
    /** The port the protocol's server listens on. */
    val port: Int,
    /** Stored but not offered in the menu - the user parked it, and the app should not forget it. */
    val enabled: Boolean = true,
    /** Input is ignored end-to-end: the viewer shows the desktop and sends nothing but heartbeats. */
    val viewOnly: Boolean = false,
)

/**
 * The remote-desktop endpoints saved on a host, one per protocol the app speaks.
 *
 * `null` means the protocol is not configured for the host - the menu entry answers that by
 * offering to configure it, not by hiding, because a user who knows the server runs VNC
 * should not have to find the settings screen first.
 */
data class RemoteDesktopConfig(
    /** One slot per protocol; null means the protocol is not configured for the host. */
    val vnc: RemoteDesktopTarget? = null,
    val rdp: RemoteDesktopTarget? = null,
)

/** The protocols the remote-desktop column speaks, one line kind letter each. */
private enum class RemoteDesktopProtocol(val kindLetter: String) {
    VNC("V"),
    RDP("R"),
}

/** One line read back: the protocol the target belongs to, and the target. */
private data class ParsedRemoteDesktopLine(
    val protocol: RemoteDesktopProtocol,
    val target: RemoteDesktopTarget,
)

/**
 * [HostProfile.remoteDesktop] as text, and back.
 *
 * The column is one line per protocol target, in the shape of the forward rules beside it:
 *
 *  - `V:5900` - VNC on the server's own loopback, the default every VNC server listens on;
 *  - `V:10.0.1.5:5900` - VNC on a host the *server* can reach, the same reachability a
 *    `ssh -L` rule's target has;
 *  - a `view-only` flag after the rule, set off by whitespace - `V:5900 view-only` - for a
 *    desktop the user wants to watch without a thumb landing in it;
 *  - a `#` before the kind letter keeps the target stored but not offered - the remote
 *    desktop menu greys it out rather than forgetting it, mirroring the forward rules'
 *    disabled marker.
 *
 * The `R:` line carries an RDP target with the same shape - `R:3389` - and is read exactly
 * like the V line. A kind this codec does not speak at all is skipped rather than fatal -
 * the same rule the forward codec lives by - so a future protocol can arrive without a
 * migration.
 *
 * Decoding is validation, for the same reason it is there in [decodeForwardRules]: the
 * column can arrive from a hand-edited vault backup, and a malformed line must not cost
 * the host its load.
 */
fun encodeRemoteDesktop(config: RemoteDesktopConfig): String {
    // VNC before RDP on every write, so the same config always encodes to the same text: the
    // round trip is stable, and a vault diff shows a change rather than a shuffle.
    return listOfNotNull(
        config.vnc?.let { remoteDesktopLine(RemoteDesktopProtocol.VNC, it) },
        config.rdp?.let { remoteDesktopLine(RemoteDesktopProtocol.RDP, it) },
    ).joinToString("\n")
}

/** One target as its protocol's line, every optional field written only when it is not the default. */
private fun remoteDesktopLine(protocol: RemoteDesktopProtocol, target: RemoteDesktopTarget): String {
    val marker = if (target.enabled) "" else "#"
    val host = if (target.host == DEFAULT_REMOTE_DESKTOP_HOST) "" else "${target.host}:"
    val flags = if (target.viewOnly) " $REMOTE_DESKTOP_VIEW_ONLY_FLAG" else ""
    return "$marker" + "${protocol.kindLetter}:${host}${target.port}" + flags
}

/**
 * The remote-desktop targets in [text], dropping every line that is not one.
 *
 * The first line of each protocol wins: a column with two V lines or two R lines was
 * hand-edited, and asking which one the user meant is a question no screen in this app can
 * ask. Unknown kinds and anything that does not parse are skipped, so the shape can grow
 * without the reader growing first.
 */
fun decodeRemoteDesktop(text: String): RemoteDesktopConfig {
    var vnc: RemoteDesktopTarget? = null
    var rdp: RemoteDesktopTarget? = null
    text.lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .forEach { line ->
            val parsed = parseRemoteDesktopLine(line) ?: return@forEach
            when (parsed.protocol) {
                RemoteDesktopProtocol.VNC -> if (vnc == null) vnc = parsed.target
                RemoteDesktopProtocol.RDP -> if (rdp == null) rdp = parsed.target
            }
        }
    return RemoteDesktopConfig(vnc = vnc, rdp = rdp)
}

/** One line as a target and the protocol it belongs to, or null for anything the app should not act on. */
private fun parseRemoteDesktopLine(line: String): ParsedRemoteDesktopLine? {
    var rest = line
    var enabled = true
    if (rest.startsWith("#")) {
        enabled = false
        rest = rest.substring(1)
    }
    // The flag, if any, is split off first on the line's whitespace - the slot the forward
    // rules use for their label - because the rule itself never contains one.
    val spaceAt = rest.indexOfFirst { it.isWhitespace() }
    val rulePart = if (spaceAt < 0) rest else rest.substring(0, spaceAt)
    val flagPart = if (spaceAt < 0) "" else rest.substring(spaceAt + 1).trim()
    if (flagPart.isNotEmpty() && flagPart != REMOTE_DESKTOP_VIEW_ONLY_FLAG) return null

    val kind = rulePart.substringBefore(':', missingDelimiterValue = "")
    val tail = rulePart.substringAfter(':', missingDelimiterValue = "")
    val protocol = RemoteDesktopProtocol.entries.firstOrNull { it.kindLetter == kind } ?: return null
    if (tail.isEmpty()) return null
    // A first field that is not a port is the target host - the same slot a local forward's
    // rule has, and for the same reason: the port is the field the line cannot do without.
    val firstField = tail.substringBefore(':')
    val host: String
    val portText: String
    if (firstField.toPortOrNull() == null) {
        if (!firstField.isForwardHostName()) return null
        host = firstField
        portText = tail.substringAfter(':', missingDelimiterValue = "")
    } else {
        host = DEFAULT_REMOTE_DESKTOP_HOST
        portText = tail
    }
    val port = portText.toPortOrNull() ?: return null
    return ParsedRemoteDesktopLine(
        protocol = protocol,
        target = RemoteDesktopTarget(
            host = host,
            port = port,
            enabled = enabled,
            viewOnly = flagPart == REMOTE_DESKTOP_VIEW_ONLY_FLAG,
        ),
    )
}

/** The interface every remote-desktop target is dialed at when the line says nothing. */
const val DEFAULT_REMOTE_DESKTOP_HOST = "127.0.0.1"

/** The port a VNC server is on when the line says nothing - display :0 in Xvnc's numbering. */
const val DEFAULT_VNC_PORT = 5900

/** The port an RDP server is on when the line says nothing. */
const val DEFAULT_RDP_PORT = 3389

private const val REMOTE_DESKTOP_VIEW_ONLY_FLAG = "view-only"
