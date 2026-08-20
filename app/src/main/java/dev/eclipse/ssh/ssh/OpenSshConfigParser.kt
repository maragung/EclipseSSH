package dev.eclipse.ssh.ssh

import dev.eclipse.ssh.data.model.AuthMethod
import dev.eclipse.ssh.data.model.CONNECT_TIMEOUT_RANGE
import dev.eclipse.ssh.data.model.DEFAULT_CONNECT_TIMEOUT_SECONDS
import dev.eclipse.ssh.data.model.HostProfile
import dev.eclipse.ssh.data.model.KEEP_ALIVE_RANGE
import dev.eclipse.ssh.data.model.ProxyType

/**
 * Minimal OpenSSH ssh_config parser. Supported keys: Host, HostName, User,
 * Port, ProxyJump, ConnectTimeout, ServerAliveInterval. Wildcard patterns are
 * skipped; IdentityFile lines are intentionally ignored (private keys stay
 * inside the app's encrypted vault).
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
        val hostName = this["hostname"]?.trim().orEmpty()
        if (hostName.isBlank()) return null
        val patterns = this["patterns"].orEmpty()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() && !it.contains('*') && !it.contains('?') }
        if (patterns.isEmpty()) return null

        var resolvedHost = hostName
        var resolvedPort = this["port"]?.toIntOrNull() ?: 22
        // OpenSSH allows "HostName host:2222" shorthand.
        val colonIndex = hostName.lastIndexOf(':')
        if (colonIndex > 0 && hostName.substringAfter(':').toIntOrNull() != null) {
            resolvedHost = hostName.substring(0, colonIndex)
            resolvedPort = hostName.substring(colonIndex + 1).toInt()
        }

        val user = this["user"]?.trim().orEmpty().ifBlank { "root" }
        val proxyJump = this["proxyjump"]?.trim()?.takeIf { it.isNotBlank() }
        return HostProfile(
            name = patterns.first(),
            host = resolvedHost,
            username = user,
            port = resolvedPort.coerceIn(1, 65535),
            authMethod = AuthMethod.PASSWORD,
            group = "Imported",
            tags = patterns.drop(1),
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
