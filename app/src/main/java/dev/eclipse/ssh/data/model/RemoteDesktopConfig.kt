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
    val vnc: RemoteDesktopTarget? = null,
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
 * RDP is a line this codec does not read yet (`R:...`). The menu entry exists because the
 * user asked for the feature knowing RDP follows; a future decoder reads that line without
 * a migration, because an unknown line is skipped rather than fatal - the same rule the
 * forward codec lives by.
 *
 * Decoding is validation, for the same reason it is there in [decodeForwardRules]: the
 * column can arrive from a hand-edited vault backup, and a malformed line must not cost
 * the host its load.
 */
fun encodeRemoteDesktop(config: RemoteDesktopConfig): String {
    val vnc = config.vnc ?: return ""
    val marker = if (vnc.enabled) "" else "#"
    val host = if (vnc.host == DEFAULT_REMOTE_DESKTOP_HOST) "" else "${vnc.host}:"
    val flags = if (vnc.viewOnly) " $REMOTE_DESKTOP_VIEW_ONLY_FLAG" else ""
    return "$marker" + "V:${host}${vnc.port}" + flags
}

/**
 * The remote-desktop targets in [text], dropping every line that is not one.
 *
 * The first VNC line wins: a column with two was hand-edited, and asking which one the user
 * meant is a question no screen in this app can ask. RDP lines and anything that does not
 * parse are skipped, so the shape can grow without the reader growing first.
 */
fun decodeRemoteDesktop(text: String): RemoteDesktopConfig {
    var vnc: RemoteDesktopTarget? = null
    text.lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .forEach { line ->
            if (vnc == null) vnc = parseRemoteDesktopLine(line)
        }
    return RemoteDesktopConfig(vnc = vnc)
}

/** One line as a VNC target, or null for anything the app should not act on. */
private fun parseRemoteDesktopLine(line: String): RemoteDesktopTarget? {
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
    if (kind != "V" || tail.isEmpty()) return null
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
    return RemoteDesktopTarget(
        host = host,
        port = port,
        enabled = enabled,
        viewOnly = flagPart == REMOTE_DESKTOP_VIEW_ONLY_FLAG,
    )
}

/** The interface every remote-desktop target is dialed at when the line says nothing. */
const val DEFAULT_REMOTE_DESKTOP_HOST = "127.0.0.1"

/** The port a VNC server is on when the line says nothing - display :0 in Xvnc's numbering. */
const val DEFAULT_VNC_PORT = 5900

private const val REMOTE_DESKTOP_VIEW_ONLY_FLAG = "view-only"
