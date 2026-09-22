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
     * bug reported as "every mirror is down" (audit F2). Exit codes 126 and 127 belong to the
     * launcher ("not executable", "not found"); [exitCode] is -1 when the launch never produced one
     * (a hung or killed smoke command), and 128 alone is read here too rather than as signal zero.
     * A child that was *killed* is not this: see [KilledBySignal].
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

    /**
     * A program inside the userspace was killed by a signal instead of exiting.
     *
     * Exit codes above 128 are a shell's encoding of "killed by signal (code - 128)", and the whole
     * family used to read as [ProotLaunchFailed] — the sentence that says the Linux runtime could
     * not start. That blamed the runtime for the device: a kill in the middle of a package install
     * is the low-memory killer taking the largest process on the phone, and a user told "the Linux
     * runtime failed to start" reinstalls an app that was never broken.
     *
     * [signal] is carried rather than folded into the sentence so the two that can be told apart
     * are told apart: 9 is a kill (memory, on this platform), 11 is a crash inside a program.
     * Nothing here is a rebuild's to fix — no rung creates memory or un-crashes a binary — which is
     * why the ladder refuses it rather than rewriting a working base system to reproduce the kill.
     */
    class KilledBySignal(
        val signal: Int,
        val tail: String,
        detail: String? = null,
        cause: Throwable? = null,
    ) : UserspaceFailure(killedSentence(signal, tail) + detail.inParentheses(), cause)

    /**
     * dpkg ran and refused: `E: Sub-process /usr/bin/dpkg returned an error code (1)`, or apt's
     * "Errors were encountered while processing:". The package being configured says what went
     * wrong above that block, and [detail] carries its own line.
     *
     * The most common failure this app sees, and one the ladder *can* fix: `dpkg --configure -a`
     * plus `apt-get -f install` is the first rung's own prologue, and a half-configured package
     * that only needed the pass run again is what most of these turn out to be.
     */
    class DpkgSubprocessFailed(
        val exitCode: Int?,
        detail: String? = null,
        cause: Throwable? = null,
    ) : UserspaceFailure(
        "the package manager failed while configuring a package" +
            (exitCode?.let { " (exit $it)" } ?: "") +
            " - Repair runs the repair pass again, and the package's own error is in the install log" +
            detail.inParentheses(),
        cause,
    )

    /**
     * Another package operation still holds dpkg's or apt's lock — the mark an interrupted or
     * killed run leaves behind, since a process killed by a signal never deletes its own lock file.
     * It is why every later command refuses in about a second, without contacting anything.
     *
     * [holderPid] is the process the lock file names, or null when it names none, and [locks] is
     * every lock file found. Both are carried because a lock can be legitimately live: the repair
     * path clears a lock only when nothing holds it, and this type is what is reported when
     * something does.
     *
     * Rebuildable: the lock is a file in the rootfs, not a fact about the archive, so the ladder's
     * local rung deals with it before the first step that needs anything else.
     */
    class PackageLocksHeld(
        val holderPid: Int?,
        val locks: List<String>,
        detail: String? = null,
        cause: Throwable? = null,
    ) : UserspaceFailure(
        "another package operation is still holding the package manager's lock" +
            (holderPid?.let { " (held by process $it)" } ?: "") +
            (locks.takeIf { it.isNotEmpty() }?.let { " - ${it.joinToString(", ")}" } ?: "") +
            " - wait for it to finish, then try again" +
            detail.inParentheses(),
        cause,
    )

    /**
     * The package database cannot be read and neither dpkg's own previous copy nor the archive's
     * could be put back. Every dpkg and apt command needs that file, and `dpkg --configure -a` —
     * the pass every other repair route begins with — cannot start without it.
     *
     * Raised by the ladder's package-database rung once both of its sources are exhausted, and
     * rebuildable on purpose: what is left is the deepest rung, which re-extracts the base system
     * from the pin, and a userspace nobody can run is worse than a rebuilt one. The price is real —
     * the packages installed on top of the base system go with it, though the user's own files do
     * not, because the workspace is parked and put back around a rebuild — so the sentence says so
     * rather than letting it be discovered. Nothing here is written in place: `var/lib/dpkg` is a
     * preserved member (see [RootfsInstaller.isPreservedMember]), which is why an in-place repair of
     * it cannot exist and a rebuild is the only route left.
     */
    class PackageDatabaseUnreadable(
        detail: String? = null,
        cause: Throwable? = null,
    ) : UserspaceFailure(
        "the package database cannot be read and no intact copy is left on the device - " +
            "Repair rebuilds Ubuntu from the archive, which removes the packages installed on top " +
            "of it; your own files are kept" +
            detail.inParentheses(),
        cause,
    )

    /**
     * A package index came back corrupted: apt's `Hash Sum mismatch`, or a file whose size is not
     * the one the `Release` file promised. The download is the suspect, never the archive's
     * contents, and the repair is local — drop the indexes and fetch them again, which the ladder's
     * local rung does without touching a single file the user wrote.
     */
    class IndexHashMismatch(
        val uri: String?,
        detail: String? = null,
        cause: Throwable? = null,
    ) : UserspaceFailure(
        (uri?.takeIf { it.isNotBlank() }?.let { "a package index from $it was corrupted in transit" }
            ?: "a package index was corrupted in transit") +
            " - the stale indexes are cleared and fetched again" +
            detail.inParentheses(),
        cause,
    )

    /**
     * The archive says its own metadata is not valid yet, or no longer is. The clock is the cause:
     * the guest's clock *is* the host's, and nothing inside the rootfs can move it — which is why
     * no rung, not even a reinstall, can fix this, and the sentence has to name the one thing that
     * can.
     */
    class ClockSkew(
        val expired: Boolean,
        detail: String? = null,
        cause: Throwable? = null,
    ) : UserspaceFailure(
        "the device's clock disagrees with the package archive's timestamps" +
            if (expired) {
                " - its index reads as expired, which a wrong date makes it say"
            } else {
                " - its index is not valid yet"
            } +
            " - turn on automatic date and time, then try again" +
            detail.inParentheses(),
        cause,
    )

    /**
     * The userspace's own temporary directory is not a writable directory — deleted, replaced by a
     * file, or left with modes nothing can write through. Every session and every package install
     * writes there, and the failures it produces name anything but the cause.
     *
     * Thrown only after the recreation *failed*: the ladder's local rung deletes the directory
     * NOFOLLOW, makes it again and sets the archive's modes — `tmp` being a preserved member is why
     * the archive's overlay cannot do that, not a reason no rung tries — so what arrives here is a
     * directory that is still unusable after being remade. The sentence promises a recreation
     * because that is what the ladder attempts first, cheaply, and the rebuild is what remains.
     */
    class GuestTmpUnwritable(
        val path: String,
        detail: String? = null,
        cause: Throwable? = null,
    ) : UserspaceFailure(
        "Ubuntu's temporary directory ($path) cannot be written to - Repair recreates it" +
            detail.inParentheses(),
        cause,
    )

    /**
     * The Linux runtime this app *ships* is missing or unusable: the proot binary or its loader is
     * absent from the app's own native library directory, which is what a half-finished update and
     * an APK split installed for the wrong ABI both look like.
     *
     * Named apart from [ProotLaunchFailed] because the fix is not in the userspace and the ladder
     * must not touch it: every rung would fail identically, and the expensive ones would rewrite a
     * rootfs that was never the problem. The Ubuntu files are intact, and [path] is what is
     * missing so the report says which half of the app is at fault.
     */
    class NativeRuntimeMissing(
        val component: String,
        val path: String,
        detail: String? = null,
        cause: Throwable? = null,
    ) : UserspaceFailure(
        "the Linux runtime this app ships ($component) is missing or unusable in this installation" +
            " - reinstall or update the app; the Ubuntu files are not the problem" +
            detail.inParentheses(),
        cause,
    )

    /**
     * Every terminal the app can hold open at once is already open — the native pty layer's own
     * cap, not a property of the userspace. A user who has hit it is told so rather than shown the
     * rootfs repair that cannot help: nothing about Ubuntu is wrong.
     */
    class TooManyTerminals(
        val limit: Int,
        detail: String? = null,
        cause: Throwable? = null,
    ) : UserspaceFailure(
        "too many Ubuntu terminals are open at once ($limit) - close one and try again" +
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
            systemFailure(uri, exitCode, output, dnsServers)?.let { return it }
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
            return systemFailure(null, exitCode, output, dnsServers)
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
         * The launcher's signature: exit 126/127 (exec failure, "command not found"), 128 alone,
         * or proot speaking for itself — both of which the pty used to swallow into an apt verdict.
         *
         * A *killed* child is deliberately not this any more, and the split is the whole point of
         * [KilledBySignal]: exit codes above 128 are the shell's encoding of "killed by signal",
         * which says something about the device (memory, most often) rather than about proot's
         * ability to start. 128 itself is not a signal death — it encodes signal zero — so it stays
         * on the launcher's side of the line, where it has always been read.
         */
        private fun launcherFailure(exitCode: Int?, output: String): UserspaceFailure? {
            if (exitCode != null && exitCode > 128) return KilledBySignal(exitCode - 128, output)
            val launcherExit = exitCode != null && (exitCode == 126 || exitCode == 127 || exitCode == 128)
            val prootSpoke = PROOT_ERROR.containsMatchIn(output) || output.contains("ptrace", ignoreCase = true)
            if (!launcherExit && !prootSpoke) return null
            return ProotLaunchFailed(exitCode ?: -1, output)
        }

        /**
         * Whole-system causes that no mirror choice can fix, checked most-specific first: a named
         * resolver failure says nothing about a clock, and a lock file's name is better evidence
         * than the word "dpkg" appearing somewhere in the same output.
         */
        private fun systemFailure(uri: String?, exitCode: Int?, output: String, dnsServers: List<String>): UserspaceFailure? {
            if (DNS_PATTERNS.any { it.containsMatchIn(output) }) return DnsUnresolved(dnsServers, detail = firstErrorLine(output))
            if (DISK_FULL_PATTERNS.any { it.containsMatchIn(output) }) return DiskFull(0, 0, detail = firstErrorLine(output))
            // Before the lock and database families: apt's own words for a wrong clock name nothing
            // else, and every one of these outputs also carries an `E:` line that would otherwise be
            // read as a repository or database problem the user cannot act on.
            clockSkew(output)?.let { return ClockSkew(it, detail = firstErrorLine(output)) }
            if (LOCK_PATTERNS.any { it.containsMatchIn(output) }) {
                return PackageLocksHeld(dpkgLockHolder(output), locksNamedIn(output), detail = firstErrorLine(output))
            }
            if (PACKAGE_DB_PATTERNS.any { it.containsMatchIn(output) }) return PackageDbBroken(detail = firstErrorLine(output))
            if (DPKG_SUBPROCESS_PATTERN.containsMatchIn(output)) {
                return DpkgSubprocessFailed(exitCode, detail = dpkgSubprocessLine(output))
            }
            if (HASH_MISMATCH_PATTERN.containsMatchIn(output)) return IndexHashMismatch(uri, detail = firstErrorLine(output))
            return null
        }

        /**
         * Which half of the clock problem the archive reported, or null when it said nothing about
         * one. Two sentences, because the two read very differently to a user: "not valid yet" is a
         * device whose date is behind, "expired" is one whose date is ahead, and only the first is
         * the common factory-reset case.
         */
        private fun clockSkew(output: String): Boolean? = when {
            NOT_YET_VALID_PATTERN.containsMatchIn(output) -> false
            EXPIRED_PATTERN.containsMatchIn(output) -> true
            else -> null
        }

        /**
         * Every lock file the output named, deduplicated. Empty is a real answer — apt sometimes
         * says only "is another process using it?" — which is why [LOCK_PATTERNS] is consulted
         * separately by the caller rather than folded in here.
         */
        private fun locksNamedIn(output: String): List<String> =
            LOCK_PATH_PATTERN.findAll(output).map { it.value }.distinct().take(MAX_LOCKS).toList()

        /** The pid a lock message named, or null when it named none. */
        private fun dpkgLockHolder(output: String): Int? = LOCK_HOLDER_PATTERN.find(output)?.groupValues?.get(1)?.toIntOrNull()

        /**
         * The line dpkg printed naming the package it could not configure, which is the one fact a
         * user can act on — "this package's own script failed" beats apt's summary of it. Falls back
         * to the `E:` line when dpkg named no package at all.
         */
        private fun dpkgSubprocessLine(output: String): String? =
            output.lineSequence()
                .map { it.trim() }
                .firstOrNull { DPKG_PACKAGE_PATTERN.containsMatchIn(it) }
                ?.take(MAX_TAIL)
                ?: firstErrorLine(output)

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

/**
 * What a signal says, in the words of the fix rather than of the kernel.
 *
 * 9 is the one worth explaining, because on this platform it has a cause a user can act on: Android's
 * low-memory killer SIGKILLs the largest process when the device is short, and a package install is
 * routinely the largest process. 11 and 6 are crashes inside a program, which a rebuild cannot
 * un-crash; the rest are rare enough to be named by number rather than given a story that may be
 * wrong. The tail is appended by the same function every other type uses, because proot's own last
 * words are often the only evidence of *which* program died.
 */
private fun killedSentence(signal: Int, tail: String): String =
    when (signal) {
        9 -> "Ubuntu was killed by the system, which happens when the device runs out of memory - close other apps and try again"
        11 -> "a program in Ubuntu crashed - this is a bug in the program, not in Ubuntu's files"
        6 -> "a program in Ubuntu aborted - this is a bug in the program, not in Ubuntu's files"
        15 -> "Ubuntu was stopped before the step finished"
        else -> "a program in Ubuntu was killed (signal $signal)"
    } + tailSummary(tail)

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

/** How many lock files one message will name before the list stops being worth reading. */
private const val MAX_LOCKS = 4

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
    // The database file itself, not the state it describes: dpkg cannot open the very file every
    // command reads, which the repair pass in the ladder exists to put back. Read as PackageDbBroken
    // rather than as a dpkg exit code, because the word "repair" in its sentence is the true answer.
    Regex("""failed to open package info file""", RegexOption.IGNORE_CASE),
    Regex("""parse error, in file '?/var/lib/dpkg/status""", RegexOption.IGNORE_CASE),
)

/**
 * Every way apt and dpkg say that another process holds their lock: the frontend lock (apt's), the
 * dpkg lock, the lists lock, and the two bare refusals that name no file at all.
 */
private val LOCK_PATTERNS = listOf(
    Regex("""Could not get lock""", RegexOption.IGNORE_CASE),
    Regex("""Unable to acquire the dpkg frontend lock""", RegexOption.IGNORE_CASE),
    Regex("""is another process using it""", RegexOption.IGNORE_CASE),
    Regex("""lock file already used""", RegexOption.IGNORE_CASE),
    Regex("""Could not open lock file""", RegexOption.IGNORE_CASE),
)

/** A lock path inside a sentence, trailing punctuation excluded so `/var/lib/dpkg/lock.` reads right. */
private val LOCK_PATH_PATTERN = Regex("""(/[^\s,()]*?lock[^\s,().]*)""")

/** The process a lock message named — `It is held by process 1234 (apt-get)`. */
private val LOCK_HOLDER_PATTERN = Regex("""held by process (\d+)""", RegexOption.IGNORE_CASE)

/** apt's own words for dpkg refusing on a package's behalf. */
private val DPKG_SUBPROCESS_PATTERN = Regex(
    """Sub-process /usr/bin/dpkg returned an error code|Errors were encountered while processing""",
    RegexOption.IGNORE_CASE,
)

/** The line that names the package dpkg could not configure. */
private val DPKG_PACKAGE_PATTERN = Regex(
    """dpkg: (error processing package|error: )|package is in a very bad inconsistent state""",
    RegexOption.IGNORE_CASE,
)

/**
 * `Hash Sum mismatch` and the size complaint beside it. The download is the suspect: apt compares
 * what arrived against what the signed `Release` file promised, and a mismatch is a corrupted or
 * truncated transfer far more often than a tampered archive.
 */
private val HASH_MISMATCH_PATTERN = Regex(
    """Hash Sum mismatch|Size mismatch|Filesize \d+ differs""",
    RegexOption.IGNORE_CASE,
)

/** The clock, in the archive's own words: an index that is not valid yet, or one that expired. */
private val NOT_YET_VALID_PATTERN = Regex(
    """not valid yet|Release file .* is not valid""",
    RegexOption.IGNORE_CASE,
)

private val EXPIRED_PATTERN = Regex(
    """Release file .* is expired|has expired|Valid-Until""",
    RegexOption.IGNORE_CASE,
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
