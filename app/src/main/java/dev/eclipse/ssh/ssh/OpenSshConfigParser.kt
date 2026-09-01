package dev.eclipse.ssh.ssh

import dev.eclipse.ssh.data.model.AuthMethod
import dev.eclipse.ssh.data.model.CONNECT_TIMEOUT_RANGE
import dev.eclipse.ssh.data.model.DEFAULT_CONNECT_TIMEOUT_SECONDS
import dev.eclipse.ssh.data.model.DEFAULT_SSH_PORT
import dev.eclipse.ssh.data.model.HostProfile
import dev.eclipse.ssh.data.model.KEEP_ALIVE_RANGE
import dev.eclipse.ssh.data.model.ProxyType

/**
 * Minimal OpenSSH `ssh_config` importer: turns `~/.ssh/config` blocks into host drafts.
 * Supported keys: Host, HostName, User, Port, ProxyJump, ConnectTimeout, ServerAliveInterval.
 *
 * The result is saved straight into the host list (see `MainViewModel.importOpenSshConfig`) with no
 * per-entry review step, so the parser only ever emits a block it can turn into a *concrete* host —
 * one with a real hostname to dial. Everything below follows from that.
 *
 * ## `Host` patterns
 *
 * A `Host` line is one or more whitespace-separated patterns, where `*` and `?` are wildcards and a
 * leading `!` negates (excludes) a pattern. OpenSSH resolves those by matching the name typed on the
 * command line — `ssh web` — against every pattern, and there is no such name at import time. So the
 * importer reads a line for its *positive, literal* aliases only: the tokens that name a concrete host
 * rather than describe a family.
 *
 *  - Negated (`!…`) tokens are exclusions, never an identity, and are dropped.
 *  - The first concrete alias becomes the draft's [HostProfile.name]; any further concrete aliases
 *    become [HostProfile.tags], because several plain aliases on one line almost always mean several
 *    names for one machine (`Host web web.prod 10.0.0.5`), and one draft with the rest as searchable
 *    tags is the honest import of that — not one host per alias, which would be duplicates.
 *  - A wildcard token is a family, not a name. It is kept only as a fallback *label* when a line has no
 *    concrete alias at all, so a `Host *.example.com` block can still show a recognisable name on the
 *    draft it produces (see below).
 *
 * ## Which pattern blocks produce a host
 *
 * A block is imported only when it names a concrete place to connect, which `HostName` decides:
 *
 *  - `Host *.example.com` + `HostName bastion.example.com` **is** imported: the wildcard describes
 *    which names the rule matches, but `HostName` says where they all go, and that endpoint is
 *    concrete. This is the case the previous parser dropped outright.
 *  - `Host web-*` + `HostName %h` is **skipped**: `%h` is the matched name, so the block describes a
 *    whole family with no single host behind it and no command-line name to pin it to.
 *  - A block with no `HostName` at all (the usual `Host *` block of global defaults — `User`,
 *    `ForwardAgent`, and the like) is **skipped**: it configures other hosts, it is not one.
 *
 * ## `%h` and other percent tokens
 *
 * `HostName` may contain `%h` — the matched host — which OpenSSH expands to the name typed on the
 * command line. For a concrete alias that name *is* the alias, so `Host web` + `HostName %h.example.com`
 * imports `web.example.com`. `%%` becomes a literal `%`. Any other token (`%p`, `%r`, …) has no value
 * the importer can know, so a `HostName` that still needs one is left unresolved and the block is
 * skipped rather than saved with a `%` in its hostname that would only fail to resolve.
 *
 * ## `User`
 *
 * A block with no `User` line imports with a **blank** username, not a guess. OpenSSH's own default is
 * the local login name, which has no meaning on a device with no such account, and the previous default
 * — `root` — is the one wrong guess with real downside: silently dialling a locked-down or
 * `fail2ban`-watched root account. The app already treats a username as something it cannot invent a
 * default for (`HostFormDraft.identityValid`); a blank one shows plainly as `@host` in the list and is
 * refused by that same rule the moment the host is edited, so it surfaces as a fixable gap rather than a
 * dangerous default.
 *
 * ## `IdentityFile`
 *
 * Ignored on purpose. It points at a key file on the machine that wrote the config — `~/.ssh/id_…` — a
 * path that does not exist inside the Android app sandbox, so importing it would only record a location
 * that resolves to nothing here. A host's key is attached afterwards through the form, which stores it
 * in the app's encrypted vault.
 */
object OpenSshConfigParser {
    fun parse(content: String): List<HostProfile> {
        val blocks = mutableListOf<MutableMap<String, String>>()
        var current: MutableMap<String, String>? = null
        content.lineSequence().forEach { raw ->
            val line = raw.trim().substringBefore(" #").trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val parts = line.split(Regex("\\s+"), limit = 2)
            val key = parts[0].lowercase()
            val value = parts.getOrNull(1)?.trim().orEmpty()
            if (key == "host") {
                if (current != null) blocks += current
                current = mutableMapOf("patterns" to value)
            } else if (current != null && value.isNotBlank()) {
                current[key] = value
            }
        }
        if (current != null) blocks += current
        return blocks.mapNotNull { it.toProfile() }
    }

    private fun MutableMap<String, String>.toProfile(): HostProfile? {
        val line = importableHostLine(this["patterns"].orEmpty())
        // A line with no positive alias names nothing to import (it was empty, or only negations).
        val name = line.label ?: return null

        val rawHostName = this["hostname"]?.trim().orEmpty()
        if (rawHostName.isBlank()) return null
        // A HostName the importer cannot fully resolve (a family's %h, or a token with no value here)
        // is not a place this app can dial, so the whole block is skipped rather than half-imported.
        val hostName = expandHostName(rawHostName, line.matchedHost) ?: return null

        var resolvedHost = hostName
        var resolvedPort = this["port"]?.toIntOrNull() ?: DEFAULT_SSH_PORT
        // OpenSSH allows "HostName host:2222" shorthand.
        val colonIndex = hostName.lastIndexOf(':')
        if (colonIndex > 0 && hostName.substringAfter(':').toIntOrNull() != null) {
            resolvedHost = hostName.substring(0, colonIndex)
            resolvedPort = hostName.substring(colonIndex + 1).toInt()
        }
        // A hostname that is still a pattern (or empty) is not something that resolves.
        if (resolvedHost.isBlank() || resolvedHost.contains('*') || resolvedHost.contains('?')) return null

        val user = this["user"]?.trim().orEmpty()
        val proxyJump = this["proxyjump"]?.trim()?.takeIf { it.isNotBlank() }
        return HostProfile(
            name = name,
            host = resolvedHost,
            username = user,
            port = resolvedPort.coerceIn(1, 65535),
            authMethod = AuthMethod.PASSWORD,
            group = "Imported",
            tags = line.aliases.drop(1),
            proxyType = if (proxyJump != null) ProxyType.PROXY_JUMP else ProxyType.NONE,
            proxyJump = proxyJump,
            // OpenSSH spells these ConnectTimeout and ServerAliveInterval; both map exactly onto the
            // per-host fields, so an imported profile connects the way the user's ssh_config says.
            // Out-of-range values fall back to the default rather than being clamped, because a
            // ConnectTimeout of 0 means "use the system default" in ssh_config, not "give up at once".
            connectTimeoutSeconds = this["connecttimeout"]?.trim()?.toIntOrNull()
                ?.takeIf { it in CONNECT_TIMEOUT_RANGE } ?: DEFAULT_CONNECT_TIMEOUT_SECONDS,
            keepAliveSeconds = this["serveraliveinterval"]?.trim()?.toIntOrNull()
                ?.takeIf { it in KEEP_ALIVE_RANGE },
        )
    }
}

/**
 * The importable identity of a `Host` line, with OpenSSH pattern syntax understood rather than taken
 * literally. See [OpenSshConfigParser] for why only positive, literal aliases can be taken from a line
 * at import time.
 *
 * @property aliases the positive, wildcard-free aliases, in order — the concrete names of a host. The
 *  first is the draft name and the rest its tags.
 * @property label the draft name to use: the first concrete alias, or, when the line has none, its
 *  first positive token (a wildcard such as `*.example.com`) so a redirect-only block still shows a
 *  recognisable name; null when the line names nothing to import (empty, or only negations).
 * @property matchedHost what `%h` expands to for this line: its first concrete alias, or null when the
 *  line is a pure family and `%h` therefore has no value the importer can know.
 */
internal data class ImportableHostLine(
    val aliases: List<String>,
    val label: String?,
    val matchedHost: String?,
)

/** Classifies a `Host` line's whitespace-separated patterns into its [ImportableHostLine]. */
internal fun importableHostLine(patternLine: String): ImportableHostLine {
    val positive = patternLine.split(Regex("\\s+")).filter { it.isNotBlank() && !it.startsWith("!") }
    val concrete = positive.filter { !it.contains('*') && !it.contains('?') }
    return ImportableHostLine(
        aliases = concrete,
        label = concrete.firstOrNull() ?: positive.firstOrNull(),
        matchedHost = concrete.firstOrNull(),
    )
}

/**
 * Expands the `%` tokens OpenSSH allows in `HostName`, or null when the value cannot be resolved at
 * import time.
 *
 * Only `%h` (the matched host) and `%%` (a literal `%`) have a value here — see [OpenSshConfigParser].
 * `%h` needs [matchedHost]; when the line is a pure family it is null and a `HostName` that uses `%h` is
 * unresolvable. Any other `%` token, or a trailing `%`, is likewise unresolvable: returning null makes
 * the caller skip the block rather than dial a hostname with a literal `%` in it.
 */
private fun expandHostName(raw: String, matchedHost: String?): String? {
    if (!raw.contains('%')) return raw
    val out = StringBuilder(raw.length)
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        if (c != '%') {
            out.append(c)
            i++
            continue
        }
        when (raw.getOrNull(i + 1)) {
            '%' -> out.append('%')
            'h' -> out.append(matchedHost ?: return null)
            else -> return null
        }
        i += 2
    }
    return out.toString()
}
