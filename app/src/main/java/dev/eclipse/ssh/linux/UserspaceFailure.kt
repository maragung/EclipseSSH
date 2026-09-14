package dev.eclipse.ssh.linux

import java.io.IOException

/**
 * The closed set of ways a userspace install or repair can fail, each carrying a human-readable
 * one-sentence cause — the controller renders `.message` verbatim — plus the typed facts a test or
 * a future UI can branch on, and the original exception as `cause`.
 *
 * Until this existed every failure collapsed into one prose string: DNS, a refused mirror, a
 * missing proot loader and a full disk all read as "every Ubuntu mirror is down" (see audit
 * finding F8). The types are the classifier's output: [Companion.fromAptRun] and
 * [Companion.fromCommandOutput] read the triple (exit code, apt's `Err:`/`E:` lines, probe
 * outcome) that the captured pty output already carries, and [Companion.fromMessage] maps the
 * message-prefix contracts the runtime layer throws by (see [RUNTIME_STORAGE_PREFIX] and
 * [DISK_FULL_PREFIX]).
 *
 * Extends [IOException] so existing catch sites keep compiling and callers that only care that
 * the step failed keep working.
 */
sealed class UserspaceFailure(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause) {

    /** The device had no usable network when a step needed one. */
    class Offline(
        detail: String? = null,
        cause: Throwable? = null,
    ) : UserspaceFailure("the device is offline - connect to a network and try again" + detail.inParentheses(), cause)

    /**
     * Name resolution inside the rootfs failed before a single package byte was fetched.
     * [servers] names the resolvers that were actually written to /etc/resolv.conf, because the
     * fix for this failure is almost always on the device side (a stale Wi-Fi resolver), not in
     * the archive list.
     */
    class DnsUnresolved(
        val servers: List<String>,
        detail: String? = null,
        cause: Throwable? = null,
    ) : UserspaceFailure(
        "DNS does not resolve" +
            (servers.takeIf { it.isNotEmpty() }?.let { " through the configured resolvers (${it.joinToString(", ")})" } ?: "") +
            " - check the network or try a different connection" +
            detail.inParentheses(),
        cause,
    )

    /** Why one archive could not be reached — the distinction the old prose flattened. */
    sealed interface Kind {
        /** What the "could not connect" family (timeouts, black-holed routes) collapses to. */
        object Timeout : Kind {
            override val describe: String get() = "connection timed out or unreachable"
        }

        object Refused : Kind {
            override val describe: String get() = "connection refused"
        }

        /** An HTTP-level refusal from a host that answered; apt's 404 on a missing suite is the common one. */
        data class Http(val code: Int) : Kind {
            override val describe: String get() = "HTTP $code"
        }

        object Tls : Kind {
            override val describe: String get() = "the TLS connection failed"
        }

        val describe: String
    }

    /** The archive under test could not be reached, and how it refused. */
    class MirrorUnreachable(
        val uri: String,
        val kind: Kind,
        detail: String? = null,
        cause: Throwable? = null,
    ) : UserspaceFailure("the package archive $uri could not be reached (${kind.describe})" + detail.inParentheses(), cause)

    /** The archive answered but its signature was missing or invalid — never silently trusted. */
    class RepositoryUnsigned(
        val uri: String,
        detail: String? = null,
        cause: Throwable? = null,
    ) : UserspaceFailure(
        "the package repository $uri could not be verified - its signature is missing or invalid" + detail.inParentheses(),
        cause,
    )

    /**
     * proot itself failed to launch — a missing loader, a ptrace refusal — which the historical
     * bug reported as "every mirror is down" (audit F2). Exit codes 126/127/128+n belong to the
     * launcher, not to apt; [exitCode] is -1 when the launch never produced one (a hung or
     * killed smoke command).
     */
    class ProotLaunchFailed(
        val exitCode: Int,
        val tail: String,
        detail: String? = null,
        cause: Throwable? = null,
    ) : UserspaceFailure(
        "the Linux runtime (proot) failed to start" +
            (exitCode.takeIf { it >= 0 }?.let { " - exit code $it" } ?: "") +
            tailSummary(tail) +
            detail.inParentheses(),
        cause,
    )

    /**
     * Not enough disk for the install. When the disk-space gate in the installer layer already
     * worded the message ("Ubuntu needs ~X MB free; Y MB available"), [detail] carries that
     * sentence verbatim; the byte counts are 0 when they were never measured.
     */
    class DiskFull(
        val neededBytes: Long,
        val freeBytes: Long,
        detail: String? = null,
        cause: Throwable? = null,
    ) : UserspaceFailure(
        detail ?: "not enough free disk space - the install needs about ${neededBytes / MIB} MiB but only ${freeBytes / MIB} MiB is available",
        cause,
    )

    /** dpkg's state is inconsistent; the repair pass (`dpkg --configure -a`) exists for exactly this. */
    class PackageDbBroken(
        detail: String? = null,
        cause: Throwable? = null,
    ) : UserspaceFailure(
        "the package database is inconsistent - the repair pass will run; if this repeats, uninstall and reinstall Ubuntu" +
            detail.inParentheses(),
        cause,
    )

    /** A named setup step blew its whole budget without finishing. */
    class StepTimedOut(
        val step: String,
        detail: String? = null,
        cause: Throwable? = null,
    ) : UserspaceFailure(
        "'$step' did not finish in time - the network may be too slow, or the step stopped making progress" +
            detail.inParentheses(),
        cause,
    )

    companion object {
        /**
         * Classifies one failed apt-get rung from its exit code and captured output, or null when
         * the evidence names nothing this taxonomy knows — the caller then falls back to its own
         * prose. Pure on purpose: the rules are decision logic and get tested like the ladder.
         *
         * The checks are ordered by how specific the evidence is: the launcher's exit codes and
         * proot's own error text first (they say nothing about any mirror), then whole-system
         * causes (DNS, disk, dpkg state), then the per-archive reachability and signature
         * families.
         */
        fun fromAptRun(
            uri: String,
            exitCode: Int?,
            output: String,
            dnsServers: List<String> = emptyList(),
            detail: String? = null,
        ): UserspaceFailure? {
            launcherFailure(exitCode, output)?.let { return it }
            systemFailure(exitCode, output, dnsServers)?.let { return it }
            if (UNSIGNED_PATTERN.containsMatchIn(output)) {
                return RepositoryUnsigned(uri, detail = detail ?: firstErrorLine(output))
            }
            mirrorKind(output)?.let { return MirrorUnreachable(uri, it, detail = detail ?: firstErrorLine(output)) }
            return null
        }

        /**
         * Classifies a failed scripted command (an `apt-get install`, a repair pass) — the same
         * evidence minus the per-archive kinds, which only mean something against a rung's
         * sources.list.
         */
        fun fromCommandOutput(
            step: String,
            exitCode: Int?,
            output: String,
            dnsServers: List<String> = emptyList(),
        ): UserspaceFailure? {
            launcherFailure(exitCode, output)?.let { return it }
            return systemFailure(exitCode, output, dnsServers)
        }

        /**
         * Maps the message-prefix contracts this layer's collaborators throw by: the runtime's
         * storage refusal and the installer's disk-space gate both word their own messages, and
         * rewording them here would fork one sentence into two. Returns null for anything else —
         * the original exception stays the answer.
         */
        fun fromMessage(message: String, cause: Throwable? = null): UserspaceFailure? = when {
            message.startsWith(RUNTIME_STORAGE_PREFIX) ->
                ProotLaunchFailed(-1, message.removePrefix(RUNTIME_STORAGE_PREFIX).trim(), cause = cause)
            message.startsWith(DISK_FULL_PREFIX) -> DiskFull(0, 0, detail = message, cause = cause)
            else -> null
        }

        /**
         * The launcher's signature: exit 126/127 (exec failure, "command not found") or 128+n
         * (killed by signal), or proot speaking for itself — both of which the pty used to
         * swallow into an apt verdict.
         */
        private fun launcherFailure(exitCode: Int?, output: String): ProotLaunchFailed? {
            val launcherExit = exitCode != null && (exitCode == 126 || exitCode == 127 || exitCode >= 128)
            val prootSpoke = PROOT_ERROR.containsMatchIn(output) || output.contains("ptrace", ignoreCase = true)
            if (!launcherExit && !prootSpoke) return null
            return ProotLaunchFailed(exitCode ?: -1, output)
        }

        /** Whole-system causes that no mirror choice can fix. */
        private fun systemFailure(exitCode: Int?, output: String, dnsServers: List<String>): UserspaceFailure? {
            if (DNS_PATTERNS.any { it.containsMatchIn(output) }) return DnsUnresolved(dnsServers, detail = firstErrorLine(output))
            if (DISK_FULL_PATTERNS.any { it.containsMatchIn(output) }) return DiskFull(0, 0, detail = firstErrorLine(output))
            if (PACKAGE_DB_PATTERNS.any { it.containsMatchIn(output) }) return PackageDbBroken(detail = firstErrorLine(output))
            return null
        }

        private fun mirrorKind(output: String): Kind? {
            if (REFUSED_PATTERN.containsMatchIn(output)) return Kind.Refused
            if (TIMEOUT_PATTERN.containsMatchIn(output)) return Kind.Timeout
            NOT_FOUND_PATTERN.find(output)?.let { return Kind.Http(it.groupValues[1].toIntOrNull() ?: 404) }
            if (TLS_PATTERN.containsMatchIn(output)) return Kind.Tls
            return null
        }

        /** The first apt line that names the problem — the `Err:`/`E:` line, not a transcript. */
        private fun firstErrorLine(output: String): String? =
            output.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("Err:") || it.startsWith("E:") }
                ?.take(MAX_TAIL)
                ?: output.lineSequence().map { it.trim() }.lastOrNull { it.isNotBlank() }?.take(MAX_TAIL)
    }
}

/** `[detail]` as a trailing parenthetical, or nothing — every type's message ends this way. */
private fun String?.inParentheses(): String = this?.let { " ($it)" } ?: ""

/** The last thing the failed command said, as one line — proot's own error text lives here. */
private fun tailSummary(tail: String): String =
    tail.lineSequence()
        .map { it.trim() }
        .lastOrNull { it.isNotBlank() }
        ?.take(MAX_TAIL)
        ?.let { " - $it" }
        ?: ""

private const val MIB = 1024L * 1024
private const val MAX_TAIL = 200

/** Cross-fork contract: ProotRuntime refuses to spawn with this prefix when its storage is unusable. */
internal const val RUNTIME_STORAGE_PREFIX = "runtime storage not ready:"

/** Cross-fork contract: the installer's free-space gate words its refusal this way. */
internal const val DISK_FULL_PREFIX = "Ubuntu needs"

private val PROOT_ERROR = Regex("""(?m)^\s*proot(\s+\w+)?\s*:""", RegexOption.IGNORE_CASE)

private val DNS_PATTERNS = listOf(
    Regex("Temporary failure resolving", RegexOption.IGNORE_CASE),
    Regex("Temporary failure in resolution", RegexOption.IGNORE_CASE),
    Regex("""\bCould not resolve\b""", RegexOption.IGNORE_CASE),
    Regex("Name or service not known", RegexOption.IGNORE_CASE),
)

private val DISK_FULL_PATTERNS = listOf(
    Regex("No space left on device", RegexOption.IGNORE_CASE),
    Regex("""\bdon'?t have enough free space\b""", RegexOption.IGNORE_CASE),
    Regex("Not enough free disk space", RegexOption.IGNORE_CASE),
)

private val PACKAGE_DB_PATTERNS = listOf(
    Regex("""\bdpkg was interrupted\b""", RegexOption.IGNORE_CASE),
    Regex("very bad inconsistent state", RegexOption.IGNORE_CASE),
    Regex("unmet dependencies", RegexOption.IGNORE_CASE),
    Regex("""\bdpkg status database\b""", RegexOption.IGNORE_CASE),
)

private val UNSIGNED_PATTERN = Regex(
    """NO_PUBKEY|is not signed|signatures? (?:were invalid|couldn'?t be verified)|GPG error""",
    RegexOption.IGNORE_CASE,
)

private val REFUSED_PATTERN = Regex("""Connection refused|connect \(111""", RegexOption.IGNORE_CASE)

private val TIMEOUT_PATTERN = Regex(
    """Connection timed out|connect \(110|Could not connect|Unable to connect to""",
    RegexOption.IGNORE_CASE,
)

private val NOT_FOUND_PATTERN = Regex("""\b(\d{3})\s+Not Found\b""")

private val TLS_PATTERN = Regex(
    """Certificate verification failed|certificate is not trusted|SSL certificate problem""",
    RegexOption.IGNORE_CASE,
)
