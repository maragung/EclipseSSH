package dev.eclipse.ssh.data.model

/**
 * STOPGAP for the RDP target line, to be deleted whole when the remote-desktop codec branch
 * (which adds the `rdp` slot to [RemoteDesktopConfig] and reads the `R:` line in
 * [decodeRemoteDesktop]) lands. Everything here exists only so the RDP endpoint dialog and its
 * menu entry can ship against a codec that does not speak R yet; the follow-up is to swap each
 * call to `decodeRemoteDesktop(...).rdp` / `encodeRemoteDesktop(config.copy(rdp = ...))` and
 * delete this file. The line shapes are deliberately the ones the codec branch reads, so text
 * written here survives the merge unchanged.
 *
 * The flag literal repeats the codec's private `view-only` constant because the constant is
 * private on main; the codec branch keeps it private too, so this stays a literal for the life
 * of the stopgap.
 */

/** The port an RDP server listens on when the line says nothing. Mirrors the codec branch's constant. */
const val DEFAULT_RDP_PORT = 3389

/**
 * The `R:` line in [column] as a target, or null when the host has no RDP endpoint saved.
 *
 * Parses exactly the shape the V line has on main - an optional `#` marker, the `R:` kind, an
 * optional host before the port, and the `view-only` flag - because that is the shape the codec
 * branch's decoder will read back. The first R line wins, the rule the V reader already lives by;
 * anything that does not parse is skipped, which is the rule that let the R line arrive without
 * a migration in the first place.
 */
fun decodeRdpTarget(column: String): RemoteDesktopTarget? =
    column.lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .mapNotNull(::parseRdpLine)
        .firstOrNull()

/** One line as an RDP target, or null for anything that is not one. */
private fun parseRdpLine(line: String): RemoteDesktopTarget? {
    var rest = line
    var enabled = true
    if (rest.startsWith("#")) {
        enabled = false
        rest = rest.substring(1)
    }
    val spaceAt = rest.indexOfFirst { it.isWhitespace() }
    val rulePart = if (spaceAt < 0) rest else rest.substring(0, spaceAt)
    val flagPart = if (spaceAt < 0) "" else rest.substring(spaceAt + 1).trim()
    if (flagPart.isNotEmpty() && flagPart != "view-only") return null

    if (!rulePart.startsWith("R:")) return null
    val tail = rulePart.substringAfter(':', missingDelimiterValue = "")
    if (tail.isEmpty()) return null
    // A first field that is not a port is the target host - the same slot a local forward's rule
    // has, and for the same reason: the port is the field the line cannot do without.
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
        viewOnly = flagPart == "view-only",
    )
}

/**
 * [column] with its RDP line replaced by [target], or with none at all when [target] is null.
 *
 * Every other line is carried over verbatim, so a VNC target (or a line some future protocol
 * owns) survives an RDP save untouched. The new R line is appended, which keeps V before R - the
 * order the codec branch writes, so the column a user diffs across the merge does not shuffle.
 */
fun withRdpTarget(column: String, target: RemoteDesktopTarget?): String {
    val kept = column.lines().filterNot { it.isRdpLine() }
    val rdpLine = target?.let {
        val marker = if (it.enabled) "" else "#"
        val host = if (it.host == DEFAULT_REMOTE_DESKTOP_HOST) "" else "${it.host}:"
        val flags = if (it.viewOnly) " view-only" else ""
        "$marker" + "R:${host}${it.port}" + flags
    }
    return (kept + listOfNotNull(rdpLine)).filter(String::isNotBlank).joinToString("\n")
}

/** Whether [line] is an RDP line, well-formed or not - the slot [withRdpTarget] replaces. */
private fun String.isRdpLine(): Boolean =
    trim().removePrefix("#").substringBefore(':', missingDelimiterValue = "") == "R"
