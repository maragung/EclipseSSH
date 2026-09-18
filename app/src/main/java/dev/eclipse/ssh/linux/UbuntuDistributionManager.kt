package dev.eclipse.ssh.linux

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turns a freshly extracted Ubuntu Base rootfs into the distribution the feature promises: an
 * `ubuntu` account, a persisted workspace, working DNS, a package manager pointed at the archive
 * that actually serves this device's architecture, and the base packages — bash and its
 * completions, apt, git, curl, wget, sudo and an SSH client — installed through apt itself, not
 * simulated.
 *
 * Deliberately minimal, by decision: the userspace is a real Ubuntu with a working `apt`, and
 * everything past that base (Python, Node.js, an editor, a compiler) is the user's own
 * `apt-get install` inside the terminal. The base is what makes that possible; guessing at the
 * rest would put a curated toolchain and its third-party registries in the install path for
 * everybody, and a `deb.nodesource.com` outage would then decide whether "Ubuntu" installed.
 *
 * Nothing here talks to the network except through the rootfs's own tools (`apt-get`, `curl`),
 * run inside proot — with one deliberate exception: the [mirror list feed][fetchMirrorList],
 * which the app fetches itself because apt cannot run before its own sources are configured, and
 * because the feed only ever *reorders* candidates, never bypasses a pin.
 *
 * The account model: the app's Android uid is registered as user `ubuntu` in `/etc/passwd` (see
 * [ProotRuntime] for why sessions never fake root). The setup commands themselves run with
 * proot's `-0` — dpkg `chown`s what it unpacks to `root:root`, and a real EPERM there would abort
 * every package install; under `-0` proot swallows the ownership change while the kernel-level
 * owner stays the app uid.
 *
 * @param distro the pinned distribution being set up; its architecture selects the apt archive
 *   and its release names the apt suites
 * @param runtime the proot context this manager runs its commands through
 * @param appUid the app's Android uid, registered as the `ubuntu` account
 * @param appGid the app's Android gid for the account's primary group
 * @param dnsServers supplies the resolvers written into the rootfs's `/etc/resolv.conf`. A
 *   function, resolved when the file is written, because the graph is built once while the
 *   device's network keeps changing — a captured list would freeze the rootfs to whatever
 *   network it was built on, and Repair could then never fix a DNS breakage
 * @param mirrorListUrl the always-current mirror feed used when the primary archive fails;
 *   injectable so tests point it at something that refuses instantly — or a `file:` URL —
 *   instead of touching the network
 * @param networkOnline consulted before the network-heavy phases; returning false fails the
 *   step as a typed [UserspaceFailure.Offline] instead of letting every rung fail for a reason
 *   that is not any mirror's
 * @param aptUpdateAttemptTimeoutMs one rung's whole budget; injectable so a test can time a
 *   rung out without waiting ten real minutes
 */
class UbuntuDistributionManager(
    private val distro: LinuxDistro,
    private val runtime: ProotRuntime,
    private val appUid: Int,
    private val appGid: Int,
    private val dnsServers: () -> List<String> = { DEFAULT_DNS_SERVERS },
    private val mirrorListUrl: String = DEFAULT_MIRROR_LIST_URL,
    private val networkOnline: () -> Boolean = { true },
    private val aptUpdateAttemptTimeoutMs: Long = APT_UPDATE_ATTEMPT_TIMEOUT_MS,
    /**
     * The ring every event of an install is filed into, and the one the "Install log" row reads.
     * A constructor parameter rather than a property this class makes for itself, because the
     * install is more than this class: [RootfsInstaller] downloads, verifies and extracts the rootfs
     * before any of the work below starts, and one log has to hold both halves. The DI graph passes
     * a single instance to both; the default is for the tests that build one of the two alone.
     */
    val diagnostics: UserspaceDiagnostics = UserspaceDiagnostics(),
) {

    private val rootfs: File get() = runtime.rootfsDir

    /**
     * The mirror list fetched during this manager's lifetime, once [aptUpdate] has needed it.
     * Cached rather than re-fetched because Repair re-runs the same ladder over the same network.
     */
    private var fetchedMirrors: List<String>? = null

    /**
     * The resolvers [configureDns] actually wrote — what a DNS failure must name, and what the
     * taxonomy classifier receives. Kept even though the file could be read back, because the
     * written list is the filtered one and the file is the truth of the past.
     */
    private var writtenDnsServers: List<String> = emptyList()

    /**
     * How the mirror feed answered, once [aptUpdate] has needed it; null until then. Carried
     * separately from [fetchedMirrors] because "the feed was unreachable" and "the feed named no
     * usable mirror" are different facts, and the ladder's failure message must be able to say
     * which one it was.
     */
    private var mirrorFeedStatus: String? = null

    /**
     * The setup pipeline, in order. Each step either completes or throws — there is no partial
     * success, because the state machine only marks the userspace installed once everything below
     * (including [healthProbe]) has passed.
     *
     * Every step now comes from the pinned Ubuntu archive, reached through [aptUpdate]'s ladder, so
     * there is no step whose failure is tolerable: the two that were (a Node.js install from
     * NodeSource and two npm globals) are gone with the toolchain they brought, and [SetupReport]
     * carries warnings for the two softer things that remain inside a step — a base package apt
     * would only install on its own, and sudo.
     *
     * @param onStep invoked as each step begins, for the install screen's progress display
     * @param onProgress invoked with the newest output line of the long-running commands, so a
     *   slow-but-alive apt is visibly alive instead of looking wedged behind a step label
     * @return the warnings collected along the way (empty on a fully clean install)
     */
    suspend fun setup(
        onStep: suspend (SetupStep) -> Unit = {},
        onProgress: (String) -> Unit = {},
    ): SetupReport {
        val warnings = mutableListOf<String>()
        requireOnline("starting setup")
        // One run, one blocked-syscall log: the fork appends to it for as long as it runs, so
        // without this the evidence for a failure at minute twelve is buried under the traps of
        // every previous run this device has done.
        runtime.resetSigsysLog()
        onStep(SetupStep.REGISTER_USER)
        registerUbuntuUser()
        onStep(SetupStep.PREPARE_WORKSPACE)
        prepareWorkspace()
        onStep(SetupStep.CONFIGURE_DNS)
        configureDns()
        // Still inside the DNS step's label: resolution is verified from inside proot before a
        // single package byte is fetched, so a dead resolver is named as DNS — not discovered by
        // exhausting the whole mirror ladder ten minutes later.
        verifyResolution()
        onStep(SetupStep.CONFIGURE_APT)
        configureAptSources()
        onStep(SetupStep.UPDATE_PACKAGES)
        // The first proot execution used to be the apt rung itself, which is how a broken runtime
        // read as "every mirror is down". The smoke command answers before any archive is asked
        // anything, and its verdict is a [UserspaceFailure.ProotLaunchFailed].
        verifyRuntime()
        // The dpkg repair prologue: an interrupted earlier install leaves dpkg half-configured,
        // and apt refuses to proceed until `dpkg --configure -a` has run — the one state Repair
        // is routed to, so Repair itself must be able to clear it (tolerated here: the ladder is
        // the real verdict, this pass only clears what it can).
        repairPackageState(warnings, onProgress)
        requireOnline("updating package lists")
        // The installer's disk gate ran before the download; since then the tarball and the
        // extracted rootfs have consumed what it budgeted, and the apt phase is the next big
        // write. Re-checking here turns "apt dies on ENOSPC twenty minutes in" into a named
        // refusal before a single package byte is fetched.
        requireAptDiskSpace()
        aptUpdate(onProgress)
        onStep(SetupStep.INSTALL_BASE_PACKAGES)
        installBasePackages(onProgress, warnings)
        configureSudo(warnings)
        onStep(SetupStep.VERIFY)
        return SetupReport(warnings.toList())
    }

    /**
     * Whether setup has run to completion: the account exists, the workspace is in place and the
     * apt sources carry our marker. Cheap and file-based — [healthProbe] is the expensive truth.
     *
     * Never throws: it is called from the state machine's *constructor* to reconstruct state after
     * a crash, where a missing rootfs (the very thing it is checking for) must read as "not
     * configured", not as an exception nobody can catch there.
     */
    fun isConfigured(): Boolean {
        val passwd = File(rootfs, "etc/passwd")
        val sources = File(rootfs, "etc/apt/sources.list")
        if (!passwd.isFile || !sources.isFile) return false
        return passwd.readLines().any { it.startsWith(PASSWD_PREFIX) } &&
            File(rootfs, "home/ubuntu/workspace").isDirectory &&
            sources.readText().contains(APT_MARKER)
    }

    /**
     * The post-install and on-demand health check: can the user-facing session path (a shell
     * without fake root) actually run commands, is the account right, is DNS up, is the package
     * database consistent. The host-list card is shown only while this passes.
     */
    suspend fun healthProbe(): HealthReport {
        val shell = runSessionCommand("echo $PROBE_MARKER", PROBE_TIMEOUT_MS)
        val whoami = runSessionCommand("whoami", PROBE_TIMEOUT_MS)
        val network = runSessionCommand("getent hosts $networkHost", NETWORK_TIMEOUT_MS)
        val apt = runSessionCommand("apt-get check", APT_TIMEOUT_MS)
        val account = whoami?.outputText()?.trim()
        return HealthReport(
            shellWorks = shell != null && shell.exitCode == 0 && shell.outputText().contains(PROBE_MARKER),
            account = account,
            accountCorrect = account == ACCOUNT_NAME,
            networkUp = network?.exitCode == 0,
            aptUsable = apt?.exitCode == 0,
        )
    }

    // ------------------------------------------------------------------ steps

    /**
     * Registers the app uid as the `ubuntu` user by editing `/etc/passwd`, `/etc/group` and
     * `/etc/shadow` directly — no `useradd`, which would want to be root for real.
     *
     * The shadow entry's password field is `*`: locked. The account is entered by process identity
     * (the app's uid *is* the account), never by password, so there is no password to attack.
     */
    private fun registerUbuntuUser() {
        val passwd = File(rootfs, "etc/passwd")
        val group = File(rootfs, "etc/group")
        val shadow = File(rootfs, "etc/shadow")
        val passwdLine = "$ACCOUNT_NAME:x:$appUid:$appGid:Ubuntu:$HOME_DIR:/bin/bash"
        val groupLine = "$ACCOUNT_NAME:x:$appGid:"
        val shadowLine = "$ACCOUNT_NAME:*:19850:0:99999:7:::"
        rewriteKeepingOthers(passwd, PASSWD_PREFIX, passwdLine)
        rewriteKeepingOthers(group, PASSWD_PREFIX, groupLine)
        rewriteKeepingOthers(shadow, PASSWD_PREFIX, shadowLine)
    }

    /** Replaces any existing line starting with [prefix] with [line], preserving every other line. */
    private fun rewriteKeepingOthers(file: File, prefix: String, line: String) {
        check(file.isFile) { "the rootfs has no ${file.path}" }
        val kept = file.readLines().filterNot { it.startsWith(prefix) }
        writeAtomically(file, (kept + line).joinToString("\n", postfix = "\n"))
    }

    /**
     * Replaces [file]'s content in one step: the new bytes land in a sibling temporary file, which
     * is renamed onto the target. A rename within one directory is atomic on Linux, so whatever
     * reads these files next — the shell a repair spawns, apt mid-run, or the app itself after a
     * crash killed setup mid-write — sees either the old complete file or the new complete one,
     * never a truncated `/etc/passwd`. A torn account file is not a repairable state: registerUser
     * is idempotent only while the file parses.
     *
     * The rename also replaces a symlink *as a link* rather than writing through it, which is how
     * [configureDns] swaps Ubuntu Base's `resolv.conf -> /run/systemd/resolve` stub for a real
     * file without a delete-then-write window in which no resolv.conf exists at all.
     *
     * A crash between the write and the rename strands one `.name.new-<nanos>` file instead —
     * inert, unparseable by nothing, and swept by nothing because nothing reads that name.
     */
    private fun writeAtomically(file: File, content: String) {
        val parent = file.parentFile
        check(parent != null && parent.isDirectory) { "the rootfs has no ${parent?.path ?: file.path}" }
        val temp = File(parent, ".${file.name}.new-${System.nanoTime()}")
        try {
            temp.writeText(content)
            if (!temp.renameTo(file)) {
                throw IOException("could not replace ${file.path}: the rename in ${parent.path} failed")
            }
        } finally {
            // After a successful rename the temp is the target now; this only cleans up when the
            // rename never happened, so a failed write leaves no debris behind.
            temp.delete()
        }
    }

    private fun prepareWorkspace() {
        // The workspace is the user's persisted project directory; its lifecycle (sizes, clearing,
        // surviving an uninstall) is LinuxWorkspaceManager's, but its creation belongs to setup so
        // the first shell lands in a home that already has it.
        File(rootfs, "$HOME_DIR/workspace").mkdirs()
        File(rootfs, "tmp").mkdirs()
    }

    private fun configureDns() {
        val servers = filterDnsServers(dnsServers())
        writtenDnsServers = servers
        val resolv = File(rootfs, "etc/resolv.conf")
        // Ubuntu Base ships this as a symlink into /run/systemd/resolve, which does not exist
        // under proot; the atomic replace writes a real file over the symlink (a rename replaces
        // the link itself, not its target), so every name lookup does not fail.
        writeAtomically(
            resolv,
            buildString {
                servers.forEach { append("nameserver $it\n") }
                append("options timeout:2 attempts:3\n")
            },
        )
        diagnostics.record(
            UserspaceDiagnosticCategory.DNS,
            "resolv.conf written",
            detail = servers.joinToString(" "),
        )
    }

    /**
     * Proves resolution works from inside proot before anything network-heavy runs: `getent`
     * against the archive host the ladder's first rung will ask for. A failure here is a typed
     * [UserspaceFailure.DnsUnresolved] naming the resolvers that were written — not a mirror
     * verdict ten minutes later.
     *
     * Launcher-aware: if proot itself cannot run, `getent` never executed and the exit code is
     * the launcher's — that is a [UserspaceFailure.ProotLaunchFailed], not a DNS fact.
     */
    private suspend fun verifyResolution() {
        val startedAt = System.currentTimeMillis()
        val result = runRootCommand("getent hosts $networkHost", RESOLUTION_TIMEOUT_MS)
        val failure = UserspaceFailure.fromAptRun(
            networkHost,
            result?.exitCode,
            result?.outputText() ?: "",
            dnsServers = writtenDnsServers,
        )
        if (failure is UserspaceFailure.ProotLaunchFailed) throw failure
        if (result == null || result.exitCode != 0) {
            throw UserspaceFailure.DnsUnresolved(
                servers = writtenDnsServers,
                detail = if (result == null) {
                    "the check did not answer within ${RESOLUTION_TIMEOUT_MS / 1000} seconds"
                } else {
                    result.outputText().take(500).ifBlank { "getent hosts $networkHost failed without a word" }
                },
            )
        }
        diagnostics.record(
            UserspaceDiagnosticCategory.DNS,
            "resolution verified",
            detail = networkHost,
            exitCode = 0,
            durationMs = System.currentTimeMillis() - startedAt,
        )
    }

    /**
     * Writes the primary archive into sources.list, and retires every apt source the rootfs
     * shipped. Ubuntu Base's newer releases carry a deb822 `ubuntu.sources` pointed at the primary
     * archive: left alone, every rung would fetch the failing primary alongside the mirror under
     * test and the ladder could never succeed. All of them go, because every list in
     * `sources.list.d` is a shipped one — the app's own entry lives in `sources.list` itself (see
     * [writeSourcesList]) — so there is no entry here to make an exception for.
     */
    private fun configureAptSources() {
        disableShippedAptLists()
        writeSourcesList(primaryArchiveUrl(distro))
    }

    private fun disableShippedAptLists() {
        val dir = File(rootfs, "etc/apt/sources.list.d")
        val shipped = dir.listFiles() ?: return
        for (file in shipped) {
            val name = file.name
            val aptList = name.endsWith(".list") || name.endsWith(".sources")
            if (aptList && !name.endsWith(".disabled")) {
                if (file.renameTo(File(dir, "$name.disabled"))) {
                    diagnostics.record(
                        UserspaceDiagnosticCategory.APT,
                        "shipped source disabled",
                        detail = name,
                    )
                }
            }
        }
    }

    /**
     * `apt-get update`, made of retries and alternatives rather than one archive and a prayer.
     *
     * The ladder, in order (see [aptUpdateCandidates] for the pure version and the reasoning):
     * the primary archive as-is; the primary pinned to IPv4; then the live mirror list; then the
     * built-in fallback mirrors. The first rung that succeeds leaves its sources.list on disk, so
     * Repair — and every later `apt-get install` — reuses the mirror that actually worked on this
     * device, on this network.
     *
     * Every rung runs *scoped* — `Dir::Etc::sourcelist` pinned to the sources.list it just wrote,
     * `sourceparts` pointed at /dev/null — so a rung's verdict is a fact about the archive under
     * test, not about whatever else the rootfs's `sources.list.d` carries. The winner is then
     * confirmed by one unscoped update, which is what a later `apt-get install` sees.
     *
     * Three verdicts are not any mirror's, and the ladder treats them accordingly: a rung that
     * dies with the launcher's exit code (126/127/128+n) or with proot's own error text aborts
     * the whole step as a [UserspaceFailure.ProotLaunchFailed] — descending would only burn every
     * remaining rung against a broken runtime; a timed-out rung is recorded and the ladder moves
     * on; and when every rung has failed, the failure is classified from the evidence and thrown
     * typed, the last-known-good mirror is restored into sources.list first (an exhausted ladder
     * must not leave the last failed mirror as the rootfs's standing configuration), and the
     * message names every archive tried, what the mirror feed said, and the tail of the last
     * attempt.
     */
    private suspend fun aptUpdate(onProgress: (String) -> Unit) {
        val failures = mutableListOf<String>()
        // (baseUri, exitCode, output) per failed rung — the evidence the exhaustion classifier
        // walks, in ladder order.
        val evidence = mutableListOf<Triple<String, Int?, String>>()
        var lastTail = ""
        // The ladder starts with the two primary rungs alone; the fallback rungs join only once
        // both have failed, at the bottom of the loop.
        val rungs = ArrayDeque(aptUpdateCandidates(distro, emptyList()).take(PRIMARY_RUNGS))
        var extendedWithFallbacks = false
        while (rungs.isNotEmpty()) {
            val attempt = rungs.removeFirst()
            writeSourcesList(attempt.baseUri)
            val startedAt = System.currentTimeMillis()
            val result = runRootCommand(aptUpdateCommand(attempt), aptUpdateAttemptTimeoutMs, lineTracker(onProgress))
            val durationMs = System.currentTimeMillis() - startedAt
            if (result != null && result.exitCode == 0) {
                recordLastGoodMirror(attempt.baseUri)
                diagnostics.record(
                    UserspaceDiagnosticCategory.APT,
                    "rung won",
                    detail = attempt.baseUri,
                    exitCode = 0,
                    durationMs = durationMs,
                )
                // The scoped rung only refreshed the archive under test; one unscoped update
                // confirms the whole sources tree. Best-effort: the lists the install needs are
                // already on disk, and a third-party entry failing here must not undo a won rung.
                val unscoped = runRootCommand(
                    aptUpdateCommand(attempt, scoped = false),
                    aptUpdateAttemptTimeoutMs,
                    lineTracker(onProgress),
                )
                if (unscoped == null || unscoped.exitCode != 0) {
                    diagnostics.record(
                        UserspaceDiagnosticCategory.APT,
                        "unscoped confirmation failed",
                        detail = "after ${attempt.baseUri}",
                        exitCode = unscoped?.exitCode,
                    )
                }
                return
            }
            val output = result?.outputText() ?: ""
            failures +=
                if (result == null) {
                    "${attempt.baseUri}: timed out after ${aptUpdateAttemptTimeoutMs / 60000} minutes"
                } else {
                    "${attempt.baseUri}: exit ${result.exitCode}"
                }
            // A null result carries no signature at all, so it can never be mistaken for the
            // launcher's — the timeout is recorded, and the ladder moves on to the next rung.
            evidence += Triple(attempt.baseUri, result?.exitCode, output.take(2000))
            lastTail = output.take(2000).ifBlank { lastTail }
            if (result == null) {
                diagnostics.record(
                    UserspaceDiagnosticCategory.APT,
                    "rung timed out",
                    detail = attempt.baseUri,
                    durationMs = durationMs,
                )
            } else {
                diagnostics.record(
                    UserspaceDiagnosticCategory.APT,
                    "rung failed",
                    detail = "${attempt.baseUri}" +
                        (output.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("Err:") || it.startsWith("E:") }
                            ?.let { " $it" } ?: ""),
                    exitCode = result.exitCode,
                    durationMs = durationMs,
                )
            }
            // A failed rung is the one moment the proot fork's blocked-syscall log can explain
            // the failure (an unmapped SIGSYS reads as a bare ENOSYS in apt's output). The log
            // only grows while proot runs, so its tail right after the failure names every
            // syscall the handler could not downgrade. Absent, empty or unreadable means no
            // SIGSYS happened — not worth a diagnostic of its own.
            recordSigsysTail("rung failed: ${attempt.baseUri}")
            UserspaceFailure.fromAptRun(attempt.baseUri, result?.exitCode, output, dnsServers = writtenDnsServers)
                ?.let { classified ->
                    if (classified is UserspaceFailure.ProotLaunchFailed) {
                        // The launcher's exit codes and proot's own error text are facts about
                        // the runtime, not about this or any mirror: descend no further.
                        diagnostics.record(
                            UserspaceDiagnosticCategory.PROOT,
                            "ladder aborted",
                            detail = "rung ${attempt.baseUri}",
                            exitCode = result?.exitCode,
                        )
                        restoreLastGoodMirror()
                        throw classified
                    }
                }
            if (rungs.isEmpty() && !extendedWithFallbacks) {
                // Both primary rungs are gone: only now is the live mirror list worth a fetch —
                // a happy-path install must not pay for the fallbacks it never needs. The fetch
                // failing yields an empty list, and the built-in rungs still join below: the feed
                // itself must not become the new single point of failure. The guard flag stops
                // the loop from re-extending itself forever once the last fallback has failed.
                extendedWithFallbacks = true
                val fetched = fetchedMirrors ?: fetchMirrorList().also { fetchedMirrors = it }
                rungs += fallbackCandidates(distro, fetched)
            }
        }
        restoreLastGoodMirror()
        val feedNote = mirrorFeedStatus?.let { " (mirror feed: $it)" } ?: ""
        val summary =
            "every archive tried: ${failures.joinToString("; ")}$feedNote" +
                (lastTail.takeIf { it.isNotBlank() }?.let { " - last output: $it" } ?: "")
        // First rung whose evidence the taxonomy recognizes decides the type; the summary rides
        // along as the detail so no rung stops being named.
        throw evidence.firstNotNullOfOrNull { (uri, exitCode, output) ->
            UserspaceFailure.fromAptRun(uri, exitCode, output, dnsServers = writtenDnsServers, detail = summary)
        } ?: IOException(
            "Updating package lists failed on every archive tried: $summary",
        )
    }

    /**
     * `dpkg --configure -a` plus an `apt-get -f install`, run before the ladder: the one pass
     * that can clear a half-configured dpkg state. Tolerated — its failure is captured and
     * reported as a warning, because the ladder below is the step's real verdict and a fresh
     * rootfs has nothing to configure anyway.
     *
     * Tolerated is not the same as unexamined: this pass failing is what leaves dpkg interrupted
     * for every command after it (apt then exits 100 without touching a mirror, and the install
     * dies at the base packages with an error that names neither dpkg nor this pass). So its
     * failure is recorded with both ends of the evidence — the app's own error line, the dpkg
     * database the host sees, and the fork's log — and the warning carries dpkg's line, which is
     * the sentence the user can act on.
     */
    private suspend fun repairPackageState(warnings: MutableList<String>, onProgress: (String) -> Unit) {
        val startedAt = System.currentTimeMillis()
        val result = runRootCommand(DPKG_REPAIR_COMMAND, INSTALL_TIMEOUT_MS, lineTracker(onProgress))
        val ok = result != null && result.exitCode == 0
        diagnostics.record(
            UserspaceDiagnosticCategory.APT,
            "dpkg repair pass",
            exitCode = result?.exitCode,
            durationMs = System.currentTimeMillis() - startedAt,
            detail = failureTail(result?.outputText(), lines = 1, maxChars = 160),
        )
        // The fork's own record of what it did about hard links: on a device where the kernel
        // refuses them in app data, the emulation is the reason this pass can now succeed — and
        // where it still fails, the log says which decision it made before the failure.
        recordSigsysTail("dpkg repair pass")
        if (!ok) {
            // The state the failed pass leaves behind, which is what every later apt run trips
            // over, and the one reading that distinguishes "nothing to configure" from "the
            // database is interrupted and no mirror can help".
            diagnostics.record(
                UserspaceDiagnosticCategory.APT,
                "dpkg database",
                detail = dpkgDatabaseSummary(),
                exitCode = result?.exitCode,
            )
            warnings +=
                "the dpkg repair pass did not fully succeed" +
                    (failureTail(result?.outputText(), lines = 1)
                        ?.let { ": $it" } ?: ": the pass did not answer in time")
        }
    }

    /**
     * The end of a failed command's output, cleaned of terminal escapes and capped — where the
     * line that names a failure is. dpkg colours its errors whenever stderr is a terminal, and
     * under a pty it always is, so the same "error creating new backup file …: Permission denied"
     * arrives carrying `ESC[1m` sequences that would otherwise reach the diagnostics export and
     * the user's warning list. [lines] is how many of the last non-blank lines to join; the cap
     * keeps a wall of apt progress from becoming the message.
     */
    private fun failureTail(output: String?, lines: Int = 2, maxChars: Int = 300): String? =
        output
            ?.lineSequence()
            ?.map { stripEscapes(it).trim() }
            ?.filter { it.isNotBlank() }
            ?.toList()
            ?.takeLast(lines)
            ?.joinToString(" | ")
            ?.take(maxChars)
            ?.takeIf { it.isNotBlank() }

    /**
     * The up-to-three lines of a failed dpkg command that say *why*, for the diagnostics ring.
     * [dpkgFailureReason] is the whole of it, kept a top-level function so it can be tested against
     * transcripts this class can only produce on a device.
     */
    private fun failureReason(output: String?, maxChars: Int = UserspaceDiagnostics.MAX_DETAIL): String? =
        dpkgFailureReason(output, maxChars)

    /**
     * The runtime smoke step: `echo <marker>` under the same argv every scripted command uses.
     * The whole point is that this is the first proot execution in the pipeline — before it, the
     * ladder's apt rung was, and a runtime that could not launch at all reported itself as an
     * all-mirrors-failed apt error. Non-zero, silent, or over-budget: a typed
     * [UserspaceFailure.ProotLaunchFailed] that names the runtime, not any mirror.
     */
    private suspend fun verifyRuntime() {
        val startedAt = System.currentTimeMillis()
        val result = runRootCommand("echo $RUNTIME_SMOKE_MARKER", RUNTIME_SMOKE_TIMEOUT_MS)
        val ok = result != null && result.exitCode == 0 && result.outputText().contains(RUNTIME_SMOKE_MARKER)
        diagnostics.record(
            UserspaceDiagnosticCategory.PROOT,
            "smoke",
            exitCode = result?.exitCode,
            durationMs = System.currentTimeMillis() - startedAt,
            detail = if (ok) null else "echo marker not answered",
        )
        if (!ok) {
            throw UserspaceFailure.ProotLaunchFailed(
                exitCode = result?.exitCode ?: -1,
                tail = result?.outputText()?.take(2000) ?: "",
                detail = if (result == null) {
                    "the smoke command did not answer within ${RUNTIME_SMOKE_TIMEOUT_MS / 1000} seconds"
                } else {
                    null
                },
            )
        }
    }

    /** The offline gate for the network-heavy phases: one check, one typed verdict. */
    private fun requireOnline(phase: String) {
        if (!networkOnline()) {
            diagnostics.record(UserspaceDiagnosticCategory.APT, "offline gate", detail = phase)
            throw UserspaceFailure.Offline(detail = "checked before $phase")
        }
    }

    /**
     * The apt phase's own disk gate. The installer's gate ([RootfsInstaller.checkFreeSpace])
     * budgeted the tarball plus the unpacked system *and* apt's working space — but it ran before
     * any of that was written, and the disk may also have moved underneath the install since
     * (other apps, storage reclaim). The budget here is only what the packages themselves need;
     * 0 free bytes means "unknown" and never blocks, same contract as the installer's gate.
     *
     * The message starts with the [DISK_FULL_PREFIX] contract ("Ubuntu needs") so the error
     * taxonomy maps it to [UserspaceFailure.DiskFull] instead of a generic apt failure.
     */
    private fun requireAptDiskSpace() {
        val free = runtime.freeBytes()
        if (free <= 0L) return
        val needed = distro.rootfsSizeBytes * APT_TARBALL_MULTIPLE + APT_HEADROOM_BYTES
        // Recorded whether or not it blocks. The gate only ever fires when the disk is already
        // too small; the failure this reading exists for is the one that arrives with the gate
        // passed — packages growing past the estimate, or storage reclaim from another app
        // mid-install — and by the time apt dies of it the number that would prove it is gone.
        diagnostics.record(
            UserspaceDiagnosticCategory.STORAGE,
            "apt disk gate",
            detail = "free=${free / MIB}MB, needed=${needed / MIB}MB",
        )
        if (free < needed) {
            throw IOException(
                "Ubuntu needs about ${needed / MIB} MB of free storage to install the packages " +
                    "(the base system is already on disk), but only about ${free / MIB} MB is free. " +
                    "Free up storage and try again.",
            ).let { e ->
                // The same "Ubuntu needs" → DiskFull mapping runRootCommand applies to the
                // runtime's worded refusals, so both disk gates surface the same verdict.
                UserspaceFailure.fromMessage(e.message ?: "", e) ?: e
            }
        }
    }

    /**
     * Persists the winning mirror base to this manager's own sidecar — not the userspace state
     * file, whose lifecycle is the manager-above's — so a later exhausted ladder has something
     * known-good to restore.
     */
    private fun recordLastGoodMirror(baseUri: String) {
        runCatching { File(runtime.rootDir, LAST_GOOD_MIRROR_FILE).writeText(baseUri) }
            .onFailure {
                diagnostics.record(UserspaceDiagnosticCategory.APT, "last-good-mirror write failed", detail = it.message)
            }
    }

    /**
     * Clears the last-good-mirror sidecar. The uninstall path calls this: the memo describes an
     * install that no longer exists, and leaving it under the userspace root would make "removed
     * entirely" a lie a storage-usage read can see. A deleted memo is not a loss — the next
     * install's ladder re-probes and records a fresh winner.
     */
    fun clearLastGoodMirror() {
        runCatching { File(runtime.rootDir, LAST_GOOD_MIRROR_FILE).delete() }
            .onFailure {
                diagnostics.record(UserspaceDiagnosticCategory.APT, "last-good-mirror delete failed", detail = it.message)
            }
    }

    /** Restores the last-known-good mirror — or the primary, when none has ever won — into sources.list. */
    private fun restoreLastGoodMirror() {
        val lastGood = runCatching { File(runtime.rootDir, LAST_GOOD_MIRROR_FILE).takeIf { it.isFile }?.readText()?.trim() }
            .getOrNull()
        val base = lastGood?.takeIf { it.isNotBlank() } ?: primaryArchiveUrl(distro)
        writeSourcesList(base)
        diagnostics.record(
            UserspaceDiagnosticCategory.APT,
            "restored mirror",
            detail = base + (if (lastGood == null) " (primary; no last-known-good recorded)" else ""),
        )
    }

    /**
     * The base packages, and the whole of what the install adds: a shell and its completions, the
     * package manager, TLS roots, the two fetch tools, git, sudo and an SSH client. Everything
     * else — Python, Node.js, an editor, a compiler — is a `apt-get install` away inside the
     * terminal, which is the point of installing a real Ubuntu rather than a curated toolbox.
     *
     * Not all-or-nothing: the whole list is asked for first; a failure retries once with
     * `--fix-missing` (a partially-populated cache from an interrupted earlier run is exactly
     * what it exists for); a second failure clears the dpkg interruption the failure itself
     * caused and retries the list once more; and only then does it fall back to installing the
     * list package by package, so one unavailable package becomes a warning, not a failed
     * install. Only every single package failing ends the step — that is a broken apt, not a
     * missing one.
     */
    private suspend fun installBasePackages(onProgress: (String) -> Unit, warnings: MutableList<String>) {
        requireOnline("installing the base packages")
        val command = basePackagesCommand()
        val first = runRootCommand(command, INSTALL_TIMEOUT_MS, lineTracker(onProgress))
        if (first != null && first.exitCode == 0) return
        val retry = runRootCommand("$command --fix-missing", INSTALL_TIMEOUT_MS, lineTracker(onProgress))
        if (retry != null && retry.exitCode == 0) {
            warnings += "base packages needed a --fix-missing retry to install"
            return
        }
        // Both bulk attempts are down, and this is the moment to read the three things the failure
        // itself cannot report: why dpkg refused, the disk it was writing to, and the dpkg database
        // it left behind. The fallback below overwrites the last two — every apt run it starts
        // appends to updates/, and its output is what a later reading of the database would be
        // reading — so they are recorded before it, as separate events: two facts of 200 characters
        // each stay readable where one event carrying both would have been truncated into neither.
        diagnostics.record(
            UserspaceDiagnosticCategory.APT,
            "bulk base-package install failed",
            detail = failureReason(first?.outputText() ?: retry?.outputText())
                ?: failureTail(first?.outputText() ?: retry?.outputText(), lines = 2),
            exitCode = first?.exitCode ?: retry?.exitCode,
        )
        diagnostics.record(
            UserspaceDiagnosticCategory.STORAGE,
            "disk and dpkg state after the failed install",
            detail = "free=${freeSpaceReading()}, " + dpkgDatabaseSummary(),
        )
        recordSigsysTail("bulk base-package install failed")
        // A bulk install that failed is the usual cause of an *interrupted* dpkg: the packages it
        // was unpacking left their records in /var/lib/dpkg/updates, and apt refuses every command
        // while that state stands — in about a second, without contacting a mirror. The
        // per-package fallback below is exactly such a command, so without this pass it measures
        // the database rather than the packages: E2E run 35056615874 lost all 33 of them to 1-2s
        // refusals, while `apt-get check` exited 0 before and after. Clear the interruption
        // first, then retry `--fix-missing` — the one combination not yet tried, since the flag's
        // own attempt above was spent on the interrupted database — because one command that
        // installs the list is a better rung than 33 that each install one package.
        if (clearInterruptedDpkgState(onProgress)) {
            val afterRepair = runRootCommand("$command --fix-missing", INSTALL_TIMEOUT_MS, lineTracker(onProgress))
            if (afterRepair != null && afterRepair.exitCode == 0) {
                warnings += "the base packages needed a dpkg repair pass to install"
                return
            }
        }
        var installed = 0
        // The refusals, not just their count: what each one said is the evidence, and it belongs in
        // the ring rather than only in the warning sentence the UI shows one line of.
        val refusals = mutableListOf<Pair<String, ProotCommandResult?>>()
        for (pkg in BASE_PACKAGES) {
            val result = runRootCommand("apt-get install -y --no-install-recommends $pkg", INSTALL_TIMEOUT_MS, lineTracker(onProgress))
            if (result != null && result.exitCode == 0) {
                installed++
            } else {
                refusals += pkg to result
                warnings +=
                    "package '$pkg' was not installed" +
                        (failureTail(result?.outputText(), lines = 1)
                            ?.let { ": $it" } ?: ": the install did not answer in time")
            }
        }
        if (refusals.isNotEmpty()) {
            // Grouped by what apt said, not listed per package: a half-configured dependency makes
            // every remaining command refuse with the same sentence, so 33 identical entries would
            // be one fact written 33 times — and the ring is read by a person, not a counter. The
            // reason leads, because the cap trims the tail and the package list is the tail.
            refusals
                .groupBy { failureReason(it.second?.outputText()) ?: "no output before the timeout" }
                .forEach { (reason, group) ->
                    diagnostics.record(
                        UserspaceDiagnosticCategory.APT,
                        "base packages refused one by one",
                        detail = "$reason | ${group.size}/${BASE_PACKAGES.size} refused: " +
                            group.joinToString(", ") { it.first },
                        exitCode = group.first().second?.exitCode,
                    )
                }
        }
        if (installed == 0) {
            // Every package failing is apt itself refusing, not a missing package: record what
            // the apt phase cannot say about itself - dpkg's database as the host sees it (an
            // interrupted one is the common cause and the message the user can act on) and the
            // fork's log - before the verdict is thrown.
            val dpkgState = dpkgDatabaseSummary()
            diagnostics.record(
                UserspaceDiagnosticCategory.APT,
                "every base package failed",
                detail = dpkgState,
                exitCode = first?.exitCode ?: retry?.exitCode,
            )
            recordSigsysTail("base packages failed")
            throw UserspaceFailure.fromCommandOutput(
                "Installing base packages",
                first?.exitCode ?: retry?.exitCode,
                first?.outputText() ?: retry?.outputText() ?: "",
                dnsServers = writtenDnsServers,
            ) ?: IOException(
                "Installing base packages failed (exit ${first?.exitCode ?: retry?.exitCode}): " +
                    (failureTail(first?.outputText() ?: retry?.outputText(), lines = 3, maxChars = 2000)
                        ?: "") +
                    " (" + dpkgState + ")",
            )
        }
    }

    /**
     * The same repair pass [repairPackageState] runs as the prologue, run again *after* a failed
     * bulk install — because that failure is what interrupts the database, so the prologue cannot
     * have cleared it. Returns whether the pass exited 0, the only condition under which the
     * caller's bulk retry is worth spending.
     *
     * The database is read either side of it and recorded as one event: `updates=` going from
     * non-zero to zero is the pass having done its job, and the pair is what distinguishes "there
     * was nothing to configure and the packages are genuinely unavailable" from "the database was
     * interrupted, which is why nothing could be installed".
     */
    private suspend fun clearInterruptedDpkgState(onProgress: (String) -> Unit): Boolean {
        val before = dpkgDatabaseSummary()
        val startedAt = System.currentTimeMillis()
        val result = runRootCommand(DPKG_REPAIR_COMMAND, INSTALL_TIMEOUT_MS, lineTracker(onProgress))
        val ok = result != null && result.exitCode == 0
        diagnostics.record(
            UserspaceDiagnosticCategory.APT,
            if (ok) "recovery repair pass" else "recovery repair pass failed",
            exitCode = result?.exitCode,
            durationMs = System.currentTimeMillis() - startedAt,
            detail = "$before -> " + dpkgDatabaseSummary() +
                (failureTail(result?.outputText(), lines = 1)?.let { ": $it" } ?: ""),
        )
        recordSigsysTail("recovery repair pass")
        return ok
    }

    private fun basePackagesCommand(): String =
        "apt-get install -y --no-install-recommends ${BASE_PACKAGES.joinToString(" ")}"

    /**
     * Gives `ubuntu` passwordless sudo. Every file in the rootfs is already owned by the app uid,
     * so this changes little in practice — it exists because the spec asks for sudo "where
     * possible", and because scripts written for a real Ubuntu box expect it. Best-effort: sudo
     * refusing the file is a warning, not a failed install.
     */
    private fun configureSudo(warnings: MutableList<String>) {
        try {
            val dir = File(rootfs, "etc/sudoers.d")
            dir.mkdirs()
            // 0440, owner-only: sudo ignores a sudoers file it considers writable by others —
            // and refuses to parse a torn one, so the write is atomic like the other config files.
            val file = File(dir, "90-eclipse-ubuntu")
            writeAtomically(file, "$ACCOUNT_NAME ALL=(ALL) NOPASSWD: ALL\n")
            file.setExecutable(false, false)
            file.setWritable(false, false)
            file.setReadable(true, false)
        } catch (t: Throwable) {
            warnings += "sudo configuration skipped: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    // ------------------------------------------------------------------ command helpers

    /**
     * The environment for scripted root commands: the runtime's clean base plus the knobs apt
     * tooling needs to never stop and ask a question no pty user is there to answer, and two
     * deliberate adjustments of its own —
     *
     *  - `PATH` gains `$HOME/.local/bin` (first, where a login shell would put it), because
     *    `pip install --user` and friends land there and nothing found them;
     *  - `LC_ALL=C` pins apt's message text to English, which the failure classifier's regexes
     *    read. A locale-dependent "E:" line would classify as nothing; this pin makes the text a
     *    contract.
     */
    private fun rootEnv(): List<String> =
        runtime.baseEnv()
            .map { entry ->
                if (entry.startsWith("PATH=")) {
                    "PATH=$HOME_DIR/.local/bin:${entry.removePrefix("PATH=")}"
                } else {
                    entry
                }
            } + listOf("LC_ALL=C", "DEBIAN_FRONTEND=noninteractive")

    private suspend fun runRootCommand(
        command: String,
        timeoutMs: Long,
        onOutput: ((ByteArray) -> Unit)? = null,
    ): ProotCommandResult? = try {
        runtime.runCommand(runtime.commandArgv(command, asRoot = true), env = rootEnv(), timeoutMs = timeoutMs, onOutput = onOutput)
    } catch (e: IOException) {
        // Cross-fork contract: the runtime layer refuses to spawn with its own worded message
        // ("runtime storage not ready: …"); the taxonomy maps it so the user reads one verdict.
        throw UserspaceFailure.fromMessage(e.message ?: "", e) ?: e
    }

    /**
     * Runs a command through the *session* argv — no fake root — because [healthProbe] must prove
     * the user-facing path works, not the setup path.
     */
    private suspend fun runSessionCommand(command: String, timeoutMs: Long): ProotCommandResult? =
        runtime.runCommand(runtime.sessionArgv(command), env = runtime.baseEnv(), timeoutMs = timeoutMs)

    /**
     * Records the tail of the proot fork's blocked-syscall log ([ProotRuntime] points
     * `PROOT_SIGSYS_LOG` at it) as one diagnostic event, after a failed proot command. One line
     * per trapped syscall the fork could not downgrade — the difference between "apt failed with
     * ENOSYS" and "syscall 82 (rename) was trapped and unmapped". No event when the log is
     * missing or empty: most failures have no SIGSYS in them, and silence is not evidence.
     *
     * The fork also writes its *decisions* here, not only its traps: patch 0003 logs every link
     * it emulated and why, which is the one record of the platform refusing a hard link in app
     * data that no amount of guest-side output can carry.
     */
    private fun recordSigsysTail(when_: String) {
        val tail = runtime.sigsysLogTail()
        if (tail.isEmpty()) return
        diagnostics.record(
            UserspaceDiagnosticCategory.PROOT,
            "blocked-syscall log",
            detail = "$when_: " + tail.joinToString(" | "),
        )
    }

    /**
     * dpkg's database as the host filesystem holds it, in one line: the two files the status
     * backup rotates between, and how many update records are still pending. A non-empty
     * `updates/` is the exact state that makes every later apt run print "dpkg was interrupted,
     * you must manually run 'dpkg --configure -a'" and exit 100 — read here from the host side,
     * where it costs one directory listing and needs no working guest at all, which matters when
     * the failure under investigation is proot failing to write that directory.
     *
     * `status-old` is recorded with its size and not only its presence: the app-level fix for
     * Android's refusal of hard links recreates the backup as a copy, and a copy is what a
     * healthy install looks like here.
     */
    private fun dpkgDatabaseSummary(): String {
        val dpkgDir = File(rootfs, "var/lib/dpkg")
        fun describe(name: String): String {
            val file = File(dpkgDir, name)
            return if (file.isFile) "$name=${file.length()}B" else "$name=absent"
        }
        val pending = File(dpkgDir, "updates").listFiles()?.count { it.isFile } ?: 0
        val stuck = dpkgStuckPackages()
        return listOf(
            describe("status"),
            describe("status-old"),
            "updates=$pending pending record(s)",
            // The package names, not just the counts: a half-configured package is the thing every
            // later apt command dies on, and its name is the one fact that tells a reader where to
            // look — dpkg's database says it outright, so nothing has to be inferred from apt's
            // output. E2E run 35058820878 named the state ("status=169537B, updates=0") and not the
            // package, which left "which package, and therefore which script" unanswerable.
            if (stuck.isEmpty()) "no half-configured package" else "half-configured=" + stuck.joinToString(","),
        ).joinToString(", ")
    }

    /**
     * The packages dpkg is stuck on, read from its own database: a package whose `Status:` ends in
     * `half-configured` (its maintainer script failed, so the package is unpacked but not set up)
     * or `half-installed` (an unpack that was interrupted).
     *
     * The status file is plain text and each entry is a paragraph starting with `Package:`, so this
     * is a scan rather than a parser — bounded by [limit], and tolerant of a file that is missing or
     * being rewritten underneath it (a failure here yields no names, never an exception: this runs
     * while reporting another failure, and a diagnostic that could throw would replace the evidence
     * with itself).
     */
    private fun dpkgStuckPackages(limit: Int = 3): List<String> {
        val status = File(rootfs, "var/lib/dpkg/status")
        if (!status.isFile) return emptyList()
        val stuck = mutableListOf<String>()
        var current: String? = null
        runCatching {
            status.bufferedReader().use { reader ->
                reader.lineSequence().forEach { line ->
                    when {
                        line.startsWith("Package: ") -> current = line.removePrefix("Package: ").trim()
                        line.startsWith("Status: ") && stuck.size < limit -> {
                            val state = line.removePrefix("Status: ").trim()
                            val half = state.substringAfterLast(' ')
                            if (half == "half-configured" || half == "half-installed") {
                                // The state the database is in is the field's own name
                                // (half-configured), so it is not repeated per package; a
                                // half-*installed* one is marked, because it is a different fault
                                // (an unpack that was interrupted, not a script that refused).
                                stuck += (current ?: "?") +
                                    if (half == "half-installed") " (half-installed)" else ""
                            }
                        }
                    }
                }
            }
        }
        return stuck
    }

    /**
     * The userspace volume's free space in one word for a diagnostic: MB, or "unknown" when the
     * platform will not say — 0 is the runtime's "no answer" contract and never means "no space",
     * and a reading of "free=0MB" in an export would read as the opposite of what it is.
     */
    private fun freeSpaceReading(): String {
        val free = runtime.freeBytes()
        return if (free > 0L) "${free / MIB}MB" else "unknown"
    }

    /**
     * Wraps [ProotRuntime.runCommand]'s chunk callback into "the newest line of output", which is
     * the granularity the install screen can use. A chunk boundary can split a line; the half-line
     * then shows for one chunk and is replaced by its completion — acceptable for progress text,
     * where the alternative (buffering until a newline) would hide the very movement it exists to
     * show.
     */
    private fun lineTracker(onProgress: (String) -> Unit): (ByteArray) -> Unit = { chunk ->
        String(chunk, Charsets.UTF_8)
            .lineSequence()
            .lastOrNull { it.isNotBlank() }
            ?.let(onProgress)
    }

    private fun writeSourcesList(baseUri: String) {
        writeAtomically(File(rootfs, "etc/apt/sources.list"), sourcesListContent(distro, baseUri))
    }

    /**
     * The always-current official mirror feed, fetched once [aptUpdate] has exhausted the primary
     * archive, over HTTPS: this fetch runs on the Android side, through the platform's own trust
     * store, so the "guest apt has no CA store" argument for plain-HTTP mirrors does not apply to
     * it — and since targetSdk 28 the platform denies cleartext by default, which silently turned
     * the old http feed into a dead rung. A `file:` URL is served from disk, so tests inject a
     * feed without touching the network.
     *
     * Any failure reads as an empty list with a recorded outcome — the built-in fallbacks still
     * run, so the feed is never a new single point of failure, and the exhaustion message can say
     * whether the feed was unreachable or simply named nothing usable.
     */
    private suspend fun fetchMirrorList(): List<String> = withContext(Dispatchers.IO) {
        val outcome = runCatching { fetchFeedBody() }
        val body = outcome.getOrNull()
        val mirrors = body?.let { parseMirrorList(it, primaryArchiveUrl(distro)) } ?: emptyList()
        mirrorFeedStatus = when {
            // Not "unreachable": the endpoint answered, its answer just cannot be trusted to be a
            // mirror list — a misrired or hostile response serving an unbounded body.
            outcome.exceptionOrNull() is MirrorFeedTooLarge -> "too large (over ${MIRROR_FEED_MAX_BYTES / 1024} KiB)"
            outcome.isFailure ->
                "unreachable" + outcome.exceptionOrNull()
                    ?.let { " (${(it.message ?: it.javaClass.simpleName).take(100)})" }
            mirrors.isEmpty() -> "fetched, but it named no usable mirror"
            else -> "ok, ${mirrors.size} mirrors"
        }
        diagnostics.record(
            UserspaceDiagnosticCategory.APT,
            "mirror feed",
            detail = "$mirrorListUrl -> $mirrorFeedStatus",
        )
        mirrors
    }

    private fun fetchFeedBody(): String {
        if (mirrorListUrl.startsWith("file:")) {
            return File(URI(mirrorListUrl).path).inputStream().use { readBodyBounded(it) }
        }
        val connection = URL(mirrorListUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = MIRROR_FETCH_TIMEOUT_MS
        connection.readTimeout = MIRROR_FETCH_TIMEOUT_MS
        return try {
            val code = connection.responseCode
            check(code in 200..299) { "HTTP $code" }
            // Fail before reading a byte when the server names an oversized length; the streaming
            // bound below is the backstop for the chunked/no-length case.
            connection.contentLengthLong
                .takeIf { it > MIRROR_FEED_MAX_BYTES }
                ?.let { throw MirrorFeedTooLarge() }
            connection.inputStream.use { readBodyBounded(it) }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Reads at most [MIRROR_FEED_MAX_BYTES]: the feed is a list of plain-text URLs — a few KiB —
     * and anything past that is not a mirror list, so it is refused rather than held in memory.
     */
    private fun readBodyBounded(input: InputStream): String {
        val buffer = ByteArray(16 * 1024)
        val body = ByteArrayOutputStream()
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MIRROR_FEED_MAX_BYTES) throw MirrorFeedTooLarge()
            body.write(buffer, 0, read)
        }
        return body.toString(Charsets.UTF_8.name())
    }

    private val networkHost: String
        get() = if (distro.ubuntuArch == "amd64") "archive.ubuntu.com" else "ports.ubuntu.com"

    companion object {
        private const val ACCOUNT_NAME = "ubuntu"
        private const val HOME_DIR = "/home/ubuntu"
        private const val PASSWD_PREFIX = "ubuntu:"

        /**
         * Fallback resolvers, used when the wiring layer does not supply the device's live ones.
         * Public anycast DNS is the honest default: the app has no better universal answer, and
         * these two are the most widely operated.
         */
        val DEFAULT_DNS_SERVERS = listOf("1.1.1.1", "8.8.8.8")

        /**
         * Ubuntu's official mirror list, generated fresh per request and sorted for the asking
         * IP's region. This — not a hardcoded list — is what keeps the fallback mirrors current.
         * HTTPS because the fetch runs on the Android side through the platform trust store; the
         * guest apt's lack of a CA store constrains the *entries* (see [parseMirrorList]), not
         * the feed.
         */
        const val DEFAULT_MIRROR_LIST_URL = "https://mirrors.ubuntu.com/mirrors.txt"

        /**
         * The base packages the install adds, and the whole of the toolchain by decision: a shell
         * and its completions, the package manager, TLS roots, curl/wget, git, sudo and an SSH
         * client. Nothing here comes from outside the pinned Ubuntu archive, so a step that
         * installs them has no third-party registry to survive — which is what makes the userspace
         * minimal on purpose rather than by omission. Python, Node.js and everything else are one
         * `apt-get install` away inside the terminal, on the user's own terms.
         */
        private val BASE_PACKAGES = listOf(
            "bash-completion",
            "ca-certificates",
            "curl",
            "git",
            "openssh-client",
            "sudo",
            "wget",
        )

        /**
         * How many lines of a failed command's output [dpkgFailureReason] keeps — see its doc.
         *
         * Three, not two, because a failing maintainer script's own diagnosis is not one line.
         * shadow(1) reports a lock it could not take in two, and the second is the one that reads
         * as the cause while the first is the one that says which lock and whose:
         *
         *     groupadd: /etc/group.2500379: lock file already used
         *     groupadd: cannot lock /etc/group; try again later.
         *
         * With two kept, the pair lost its first line and the report read as a bare failure to
         * lock — the PID and the file named in the evidence were the ones dropped. The verdict dpkg
         * appends is the third.
         */
        internal const val FAILURE_REASON_LINES = 3

        /** How dpkg opens its verdict on a package it could not configure. */
        internal const val DPKG_VERDICT_PREFIX = "dpkg: error"

        /**
         * What dpkg says on the line under its verdict when the failure was the package's own
         * script rather than, say, an unpack or a dependency. This is the marker [dpkgFailureReason]
         * picks its verdict by: a script that failed is the cause, and every verdict after it —
         * "dependency problems prevent configuration of …" — is a package reporting the damage.
         */
        internal val SCRIPT_FAILURE_MARKERS = listOf(
            "post-installation script",
            "pre-installation script",
            "pre-removal script",
            "post-removal script",
            "maintainer script",
            "subprocess returned error exit status",
        )

        /**
         * The lines apt and dpkg emit around a failure without being it: their own progress
         * reports. Filtered out of [dpkgFailureReason]'s window so that the lines it keeps are the
         * failing script's own output and dpkg's verdict, which are the only ones that diagnose.
         */
        internal val FAILURE_REASON_NOISE = listOf(
            "Setting up ",
            "Preparing to unpack ",
            "Unpacking ",
            "Selecting previously unselected",
            "Processing triggers for ",
            "Reading database",
            "Reading package lists",
            "Building dependency tree",
            "Reading state information",
            "Get:",
            "Ign:",
            "Hit:",
            "Fetched ",
            "update-alternatives: using",
            "update-alternatives: warning",
        )

        private const val PROBE_MARKER = "eclipse-probe-ok"

        /** What the runtime smoke step echoes back; anything else — or nothing — is a launch failure. */
        private const val RUNTIME_SMOKE_MARKER = "eclipse-runtime-ok"

        /** The dpkg repair prologue, verbatim: configure half-installed packages, then fix broken deps. */
        private const val DPKG_REPAIR_COMMAND = "dpkg --configure -a && apt-get -y -f install"

        /** This manager's own sidecar for the last mirror that won a rung. */
        private const val LAST_GOOD_MIRROR_FILE = "last-good-mirror"

        private const val PROBE_TIMEOUT_MS = 2 * 60_000L
        private const val NETWORK_TIMEOUT_MS = 60_000L
        private const val APT_TIMEOUT_MS = 5 * 60_000L

        /** The in-proot resolution check: short, because a wedged resolver is a wedged resolver. */
        private const val RESOLUTION_TIMEOUT_MS = 15_000L

        /** The runtime smoke command must answer quickly or the runtime is not answering at all. */
        private const val RUNTIME_SMOKE_TIMEOUT_MS = 15_000L

        /**
         * One apt-update rung. Shorter than the step used to wait on a single archive, because a
         * rung that cannot finish in ten minutes is a network fact, and the ladder's whole point
         * is that there is another rung to try.
         */
        private const val APT_UPDATE_ATTEMPT_TIMEOUT_MS = 10 * 60_000L
        private const val INSTALL_TIMEOUT_MS = 45 * 60_000L

        /** The mirror feed must answer quickly or not participate at all. */
        // HttpURLConnection's timeouts are Int milliseconds, so the constant stays Int even though
        // every other timeout in this class is a Long.
        private const val MIRROR_FETCH_TIMEOUT_MS = 10_000

        /**
         * The feed body's hard ceiling. The real feed is a list of plain-text URLs — a few KiB —
         * so anything measured in MiB is not a mirror list (a misredirect, or a hostile endpoint),
         * and is refused rather than buffered.
         */
        private const val MIRROR_FEED_MAX_BYTES = 256 * 1024

        /**
         * The apt phase's disk budget: the base system is already on disk, so this is the
         * packages' unpacked size plus apt's own working space, as a multiple of the tarball the
         * same way the installer's gate budgets. The base packages are a few multiples of the
         * ~30 MB Base tarball in practice, and the multiple is deliberately generous now that the
         * list is seven packages: it is this gate's job to refuse before apt is asked anything, and
         * a budget that guesses low fails as ENOSPC twenty minutes in.
         */
        private const val APT_TARBALL_MULTIPLE = 4L

        /** Headroom above the packages: apt's lists and archives under /var. */
        private const val APT_HEADROOM_BYTES = 300L * 1024 * 1024

        private const val MIB = 1024L * 1024
    }
}

/** The mirror feed answered, but its body exceeds any plausible mirror list. */
private class MirrorFeedTooLarge : IOException("mirror feed body exceeds 256 KiB")

/**
 * The lines of a failed dpkg command that say *why*, for the diagnostics ring.
 *
 * [UbuntuDistributionManager]'s failure tail answers "what was the last thing apt said", and for a
 * failed maintainer script that is the summary block — `Errors were encountered while processing: |
 * openssh-client | E: Sub-process /usr/bin/dpkg returned an error code (1)`. All of it true, none of
 * it a diagnosis: dpkg prints the failing script's own message *above* that block, then its own
 * verdict. So the reason is the verdict line (`dpkg: error processing package <pkg> (--configure):
 * …`) and the non-noise lines in front of it, which are whatever the script said before dying — the
 * sentences that name the step Android refused.
 *
 * This is the difference E2E run 35058820878 turned on: the install died 70 seconds into the base
 * packages, every later apt command then failed in one to two seconds (dpkg retries the
 * half-configured package before anything else), and the report carried only the summary block —
 * leaving "which of the postinst's steps failed" unanswerable from the evidence.
 *
 * Which verdict matters is the whole subtlety, and it is not the last one. A failed maintainer
 * script makes dpkg fail *every* package that depends on it in the same run ("dependency problems
 * prevent configuration of openssh-sftp-server: … however: Package openssh-client is not configured
 * yet"), each with its own `dpkg: error processing package` line — so taking the last verdict
 * describes a consequence and buries the cause several lines above it. The verdict wanted is the
 * first one whose script failed, recognized by dpkg's own sentence for it; only when no verdict
 * names a script does the last one stand in, which is the shape of an error that is not a maintainer
 * script's at all (a bad dependency, an unpack failure).
 */
internal fun dpkgFailureReason(
    output: String?,
    maxChars: Int = UserspaceDiagnostics.MAX_DETAIL,
): String? {
    val lines = output
        ?.lineSequence()
        ?.map { stripEscapes(it).trim() }
        ?.filter { it.isNotBlank() }
        ?.toList()
        ?: return null
    val verdicts = lines.indices.filter { lines[it].startsWith(UbuntuDistributionManager.DPKG_VERDICT_PREFIX) }
    val scriptFailure = verdicts.firstOrNull { index ->
        val next = lines.getOrNull(index + 1)
        next != null && UbuntuDistributionManager.SCRIPT_FAILURE_MARKERS.any { next.contains(it) }
    }
    val verdict = scriptFailure ?: verdicts.lastOrNull() ?: -1
    // Everything up to that verdict, minus apt's and dpkg's progress chatter; the survivors at the
    // end are the failing script's own output and the verdict that follows it.
    val window = (if (verdict >= 0) lines.subList(0, verdict + 1) else lines)
        .filterNot { line -> UbuntuDistributionManager.FAILURE_REASON_NOISE.any { line.startsWith(it) } }
        .takeLast(UbuntuDistributionManager.FAILURE_REASON_LINES)
    return window
        .joinToString(" | ")
        .take(maxChars)
        .takeIf { it.isNotBlank() }
}

/**
 * One rung of the apt-update ladder. [forceIpv4] exists because the classic "apt update hangs on a
 * phone" is an IPv6 route that answers nothing: the archive has both A and AAAA records, apt tries
 * the IPv6 address first, and every attempt burns the full connect timeout before falling back —
 * unless IPv4 is forced, which is rung two's only difference from rung one.
 */
data class AptUpdateAttempt(
    val baseUri: String,
    val forceIpv4: Boolean,
)

/** The primary archive for [distro]'s architecture: `archive.ubuntu.com` carries only amd64. */
internal fun primaryArchiveUrl(distro: LinuxDistro): String =
    if (distro.ubuntuArch == "amd64") {
        "http://archive.ubuntu.com/ubuntu"
    } else {
        "http://ports.ubuntu.com/ubuntu-ports"
    }

/**
 * The built-in last-resort mirrors, used when both the primary archive and every fetched mirror
 * have failed. `mirrors.edge.kernel.org` serves the amd64 archive; for every other architecture the
 * only complete mirror family is a handful of long-running university mirrors, because Ubuntu
 * publishes no machine-readable feed for the ports archive — these two are the established ones.
 * Every suite the app pins (release, -updates, -security, for all four LTS series) was verified to
 * exist on both before they were listed.
 */
internal fun builtinMirrorUrls(distro: LinuxDistro): List<String> =
    if (distro.ubuntuArch == "amd64") {
        listOf("http://mirrors.edge.kernel.org/ubuntu")
    } else {
        listOf("http://mirrors.ustc.edu.cn/ubuntu-ports", "http://mirror.nju.edu.cn/ubuntu-ports")
    }

/**
 * The full apt-update ladder for [distro], in the order the rungs are tried: the primary archive,
 * the primary again with IPv4 forced, then the fetched mirrors, then the built-ins. Pure on
 * purpose — the retry policy is decision logic, and decision logic gets tested like everything
 * else.
 *
 * Rungs are deduplicated and compared to the primary after trimming trailing slashes, so a mirror
 * that differs from an earlier rung only by its slash is one rung, not two. [parseMirrorList]
 * already trims what it parses; this is the second line of defence for the mirrors that reach it
 * any other way.
 */
internal fun aptUpdateCandidates(distro: LinuxDistro, fetchedMirrors: List<String>): List<AptUpdateAttempt> {
    val primary = primaryArchiveUrl(distro).trimEnd('/')
    val fallbacks = (fetchedMirrors + builtinMirrorUrls(distro))
        .distinctBy { it.trimEnd('/') }
        .filter { it.trimEnd('/') != primary }
    return listOf(
        AptUpdateAttempt(primaryArchiveUrl(distro), forceIpv4 = false),
        AptUpdateAttempt(primaryArchiveUrl(distro), forceIpv4 = true),
    ) + fallbacks.map { AptUpdateAttempt(it, forceIpv4 = false) }
}

/** The fallback rungs — what joins the ladder once both primary rungs have failed. */
internal fun fallbackCandidates(distro: LinuxDistro, fetchedMirrors: List<String>): List<AptUpdateAttempt> =
    aptUpdateCandidates(distro, fetchedMirrors).drop(PRIMARY_RUNGS)

/** The number of rungs that run before the fetched mirrors join. */
private const val PRIMARY_RUNGS = 2

/**
 * How many fetched mirrors join the ladder. The feed lists dozens; three fresh, geo-sorted
 * alternates is already more network diversity than any one phone needs, and every extra rung is
 * another potential ten-minute wait on a bad network.
 */
private const val FETCHED_MIRROR_RUNGS = 3

/**
 * Parses the official mirror feed's body into ladder candidates. Pure on purpose — the rules are
 * decision logic and get tested like the ladder itself.
 *
 * The rules: one URL per line matching [MIRROR_ENTRY] exactly — plain `http://` with a bare
 * registered hostname, an optional port, and a path over the path-safe characters, nothing else.
 * The guest apt has no CA store until `ca-certificates` installs from the base packages this
 * update unlocks (so `https` entries stay out), and a hostile feed must not smuggle suite or
 * component fields, a `#` fragment, userinfo or anything else past `trim()` and into a generated
 * `deb` line. Trailing slashes are trimmed so `http://x/ubuntu/` and `http://x/ubuntu` dedupe;
 * the primary archive is dropped (it has already failed twice by the time this list is
 * consulted); and only the first [FETCHED_MIRROR_RUNGS] are kept, in the feed's order — the feed
 * is geo-sorted for the asking IP, so the first entries are the nearest ones.
 */
internal fun parseMirrorList(body: String, primaryArchiveUrl: String): List<String> =
    body.lineSequence()
        .map { it.trim().trimEnd('/') }
        .filter { MIRROR_ENTRY.matches(it) }
        .distinct()
        .filter { it != primaryArchiveUrl }
        .take(FETCHED_MIRROR_RUNGS)
        .toList()

/** The exact shape a feed entry may take: http, bare host, optional port, tame path — nothing else. */
private val MIRROR_ENTRY = Regex("""http://[A-Za-z0-9.-]+(:\d+)?(/[A-Za-z0-9._~/-]*)?""")

/**
 * Filters the platform's resolver list down to what `/etc/resolv.conf` can honestly carry. Pure
 * on purpose — the rules are decision logic and get tested like the ladder.
 *
 * The rules: parseable literals only (a scope-suffixed link-local like `fe80::1%wlan0` is
 * unparseable by glibc's `res_init` and would sit as a dead nameserver eating the lookup
 * budget); IPv4 before IPv6 (a rung can force IPv4, and a v6-only list then resolves nothing);
 * at most [MAX_DNS_SERVERS] entries kept, with one slot reserved for a public fallback
 * resolver appended last — unless the platform list already carries a public resolver, in which
 * case no slot is reserved — so a degenerate platform list degrades to slow instead of failing,
 * and never silently exceeds the three nameservers glibc reads.
 */
internal fun filterDnsServers(candidates: List<String>): List<String> {
    val ipv4 = candidates.filter { isIpv4Literal(it) }.distinct()
    val ipv6 = candidates.filter { isIpv6Literal(it) }.distinct()
    val all = ipv4 + ipv6
    // The reserved fallback slot exists to guarantee a public resolver; when the platform list
    // already carries one, the reservation is redundant — a third platform server (the LAN
    // resolver the device actually uses) serves the user better than a second public one.
    val fallbacks = UbuntuDistributionManager.DEFAULT_DNS_SERVERS
    if (fallbacks.any { it in all }) {
        return all.take(MAX_DNS_SERVERS)
    }
    val platform = all.take(MAX_DNS_SERVERS - 1)
    val fallback = fallbacks.firstOrNull { it !in platform } ?: return platform
    return platform + fallback
}

/** glibc reads at most three nameservers; a fourth would be silently dropped, not queued. */
private const val MAX_DNS_SERVERS = 3

/** A dotted quad with each octet in range — `res_init` parses exactly this and nothing more. */
private fun isIpv4Literal(candidate: String): Boolean {
    if (!candidate.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))) return false
    return candidate.split('.').all { it.toInt() in 0..255 }
}

/** An IPv6 literal: hex and colons (IPv4-mapped tail allowed), at least two colons, no scope suffix. */
private fun isIpv6Literal(candidate: String): Boolean =
    candidate.count { it == ':' } >= 2 &&
        candidate.matches(Regex("""[0-9A-Fa-f:]{2,}(\.\d{1,3}){0,2}"""))

/**
 * The apt-get command for one rung of the ladder: three attempts per archive, no translation
 * indexes (a phone install never reads them and they are half the download), and a connect
 * timeout short enough that a black-holed address fails over instead of eating the rung's whole
 * budget. Rung two adds `-o Acquire::ForceIPv4=true` — see [AptUpdateAttempt]. Scoped by
 * default: the rung's verdict must be a fact about the archive under test, not about whatever
 * else the rootfs's `sources.list.d` carries. Pure so the test can assert the exact command the
 * pty receives.
 */
internal fun aptUpdateCommand(attempt: AptUpdateAttempt, scoped: Boolean = true): String = buildString {
    append("apt-get update -o Acquire::Retries=3 -o Acquire::Languages=none -o Acquire::http::Timeout=30")
    if (scoped) {
        append(" -o Dir::Etc::sourcelist=/etc/apt/sources.list -o Dir::Etc::sourceparts=/dev/null")
    }
    if (attempt.forceIpv4) append(" -o Acquire::ForceIPv4=true")
}

/** Marks a sources.list as ours, so [UbuntuDistributionManager.isConfigured] can tell setup from the shipped file. */
internal const val APT_MARKER = "managed by Eclipse SSH"

private const val COMPONENTS = "main restricted universe multiverse"

/**
 * The sources.list for one archive base: the release, -updates and -security suites, which every
 * mirror in the ladder serves from the same base URL. Marked with the setup marker so
 * [UbuntuDistributionManager.isConfigured] can tell our file from the rootfs's shipped one.
 */
internal fun sourcesListContent(distro: LinuxDistro, baseUri: String): String = buildString {
    append("# $APT_MARKER\n")
    append("deb $baseUri ${distro.release} $COMPONENTS\n")
    append("deb $baseUri ${distro.release}-updates $COMPONENTS\n")
    append("deb $baseUri ${distro.release}-security $COMPONENTS\n")
}

/** The named steps of [UbuntuDistributionManager.setup], in order, for the install screen. */
enum class SetupStep {
    REGISTER_USER,
    PREPARE_WORKSPACE,
    CONFIGURE_DNS,
    CONFIGURE_APT,
    UPDATE_PACKAGES,
    INSTALL_BASE_PACKAGES,
    VERIFY,
}

/** What [UbuntuDistributionManager.setup] has to say beyond "done". */
data class SetupReport(
    /** Non-fatal problems: base packages that would not install, sudo not configured. */
    val warnings: List<String>,
)

/** The outcome of [UbuntuDistributionManager.healthProbe], field by field. */
data class HealthReport(
    /** A shell ran and printed our marker. */
    val shellWorks: Boolean,
    /** What `whoami` printed, for the settings screen's detail line. */
    val account: String?,
    /** `whoami` printed `ubuntu` — the never-root-by-default contract holds. */
    val accountCorrect: Boolean,
    /** A hostname in the device's apt archive resolved. */
    val networkUp: Boolean,
    /** `apt-get check` passed: the package database is consistent and usable. */
    val aptUsable: Boolean,
) {
    /** Healthy — and therefore card-worthy — only when every field passes. */
    val healthy: Boolean get() = shellWorks && accountCorrect && networkUp && aptUsable

    /**
     * The failing fields as one sentence, for NeedsRepair's detail and the settings screen. Names
     * what is wrong, not what to do — the way out (Repair) is the same regardless.
     */
    fun describe(): String =
        buildList {
            if (!shellWorks) add("the shell does not run")
            if (!accountCorrect) add("the account is '${account ?: "unknown"}' instead of 'ubuntu'")
            if (!networkUp) add("DNS does not resolve")
            if (!aptUsable) add("the package database is inconsistent")
        }.joinToString(", ").ifEmpty { "healthy" }
}
