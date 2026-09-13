package dev.eclipse.ssh.linux

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turns a freshly extracted Ubuntu Base rootfs into the distribution the feature promises: an
 * `ubuntu` account, a persisted workspace, working DNS, a package manager pointed at the archive
 * that actually serves this device's architecture, and the real toolchain the spec names — Node.js,
 * Python, git — installed through apt itself, not simulated.
 *
 * Nothing here talks to the network except through the rootfs's own tools (`apt-get`, `curl`,
 * `npm`), run inside proot — with one deliberate exception: the [mirror list feed][fetchMirrorList],
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
 * @param dnsServers resolvers written into the rootfs's `/etc/resolv.conf`; injectable so the
 *   wiring layer can derive them from the device's live network instead of the defaults
 * @param mirrorListUrl the always-current mirror feed used when the primary archive fails;
 *   injectable so tests point it at something that refuses instantly instead of touching the
 *   network
 */
class UbuntuDistributionManager(
    private val distro: LinuxDistro,
    private val runtime: ProotRuntime,
    private val appUid: Int,
    private val appGid: Int,
    private val dnsServers: List<String> = DEFAULT_DNS_SERVERS,
    private val mirrorListUrl: String = DEFAULT_MIRROR_LIST_URL,
) {
    private val rootfs: File get() = runtime.rootfsDir

    /**
     * The mirror list fetched during this manager's lifetime, once [aptUpdate] has needed it.
     * Cached rather than re-fetched because Repair re-runs the same ladder over the same network.
     */
    private var fetchedMirrors: List<String>? = null

    /**
     * The setup pipeline, in order. Each step either completes or throws — there is no partial
     * success, because the state machine only marks the userspace installed once everything below
     * (including [healthProbe]) has passed.
     *
     * The softer steps are deliberately softer: [SetupStep.INSTALL_NODEJS] and
     * [SetupStep.INSTALL_GLOBAL_TOOLS] depend on third-party registries outside the pinned Ubuntu
     * archive, so a failure there is reported as a warning rather than an error — an outage at
     * deb.nodesource.com or npmjs.com must not leave the user with "install failed" over a
     * toolchain the Repair button can add later.
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
        onStep(SetupStep.REGISTER_USER)
        registerUbuntuUser()
        onStep(SetupStep.PREPARE_WORKSPACE)
        prepareWorkspace()
        onStep(SetupStep.CONFIGURE_DNS)
        configureDns()
        onStep(SetupStep.CONFIGURE_APT)
        configureAptSources()
        onStep(SetupStep.UPDATE_PACKAGES)
        aptUpdate(onProgress)
        onStep(SetupStep.INSTALL_BASE_PACKAGES)
        installBasePackages(onProgress)
        configureSudo(warnings)
        onStep(SetupStep.INSTALL_NODEJS)
        installNodeJs(warnings, onProgress)
        onStep(SetupStep.INSTALL_GLOBAL_TOOLS)
        installGlobalTools(warnings)
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
        file.writeText((kept + line).joinToString("\n", postfix = "\n"))
    }

    private fun prepareWorkspace() {
        // The workspace is the user's persisted project directory; its lifecycle (sizes, clearing,
        // surviving an uninstall) is LinuxWorkspaceManager's, but its creation belongs to setup so
        // the first shell lands in a home that already has it.
        File(rootfs, "$HOME_DIR/workspace").mkdirs()
        File(rootfs, "tmp").mkdirs()
    }

    private fun configureDns() {
        val resolv = File(rootfs, "etc/resolv.conf")
        // Ubuntu Base ships this as a symlink into /run/systemd/resolve, which does not exist
        // under proot; replace it with a real file or every name lookup fails.
        resolv.delete()
        resolv.writeText(
            buildString {
                dnsServers.forEach { append("nameserver $it\n") }
                append("options timeout:2 attempts:3\n")
            },
        )
    }

    /**
     * Writes the primary archive into sources.list. The first `apt-get update` rung runs against
     * exactly this file; [aptUpdate] rewrites it only when a rung further down the ladder wins.
     */
    private fun configureAptSources() {
        writeSourcesList(primaryArchiveUrl(distro))
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
     * A rung that fails or times out is a fact about the network, not about the install; only
     * every rung failing ends the step, and then the error names every archive tried and the tail
     * of the last attempt, because "could not update" alone would hide which mirror said why.
     */
    private suspend fun aptUpdate(onProgress: (String) -> Unit) {
        val failures = mutableListOf<String>()
        var lastTail = ""
        // The ladder starts with the two primary rungs alone; the fallback rungs join only once
        // both have failed, at the bottom of the loop.
        val rungs = ArrayDeque(aptUpdateCandidates(distro, emptyList()).take(PRIMARY_RUNGS))
        var extendedWithFallbacks = false
        while (rungs.isNotEmpty()) {
            val attempt = rungs.removeFirst()
            writeSourcesList(attempt.baseUri)
            val result = runRootCommand(aptUpdateCommand(attempt), APT_UPDATE_ATTEMPT_TIMEOUT_MS, lineTracker(onProgress))
            if (result != null && result.exitCode == 0) return
            failures +=
                if (result == null) {
                    "${attempt.baseUri}: timed out after ${APT_UPDATE_ATTEMPT_TIMEOUT_MS / 60000} minutes"
                } else {
                    "${attempt.baseUri}: exit ${result.exitCode}"
                }
            lastTail = result?.outputText()?.take(2000) ?: lastTail
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
        throw IOException(
            "Updating package lists failed on every archive tried: ${failures.joinToString("; ")}" +
                (lastTail.takeIf { it.isNotBlank() }?.let { " - last output: $it" } ?: ""),
        )
    }

    /**
     * The apt-delivered toolchain. Node.js is *not* here: the Ubuntu archive's own `nodejs` lags
     * years behind (jammy ships 12.x, end-of-life since 2022), and the spec's toolchain — `pnpm`,
     * the OpenCode CLI — needs a modern runtime, so [installNodeJs] installs a current one from
     * the pinned NodeSource repository instead.
     */
    private suspend fun installBasePackages(onProgress: (String) -> Unit) {
        requireRoot(
            "Installing base packages",
            "apt-get install -y --no-install-recommends ${BASE_PACKAGES.joinToString(" ")}",
            INSTALL_TIMEOUT_MS,
            onOutput = lineTracker(onProgress),
        )
    }

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
            // 0440, owner-only: sudo ignores a sudoers file it considers writable by others.
            val file = File(dir, "90-eclipse-ubuntu")
            file.writeText("$ACCOUNT_NAME ALL=(ALL) NOPASSWD: ALL\n")
            file.setExecutable(false, false)
            file.setWritable(false, false)
            file.setReadable(true, false)
        } catch (t: Throwable) {
            warnings += "sudo configuration skipped: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    /**
     * Installs Node.js from the NodeSource repository, pinned to one major version in the sources
     * entry. The key is fetched over HTTPS and dearmored with the `gnupg` from the base packages,
     * then the entry is `signed-by` — the apt-native way to trust exactly one repository, not the
     * whole keyring.
     *
     * The suite is `nodistro`: NodeSource publishes one suite for every distribution it supports,
     * not one per Ubuntu codename — the entry this replaced named `jammy` and `apt-get update`
     * 404'd on it. The NodeSource update is scoped to the NodeSource list alone, because the
     * Ubuntu archive lists were fetched minutes ago and re-fetching them would re-run the entire
     * ladder's download for one new repository.
     *
     * Best-effort, like the npm tools below: NodeSource is a third-party registry outside the
     * pinned archive, and an outage there must not read as "Ubuntu install failed" — the warning
     * names it, and Repair retries it. armhf is skipped outright: NodeSource publishes amd64 and
     * arm64 only, and a 32-bit ARM phone cannot run its packages at all.
     */
    private suspend fun installNodeJs(warnings: MutableList<String>, onProgress: (String) -> Unit) {
        if (distro.ubuntuArch == "armhf") {
            warnings += "Node.js was not installed: NodeSource publishes no armhf packages"
            return
        }
        val command =
            listOf(
                "curl -fsSL $NODESOURCE_KEY_URL -o /tmp/nodesource.key",
                "gpg --dearmor -o /usr/share/keyrings/nodesource.gpg /tmp/nodesource.key",
                "echo 'deb [signed-by=/usr/share/keyrings/nodesource.gpg] " +
                    "$NODESOURCE_REPO $NODESOURCE_SUITE main' > /etc/apt/sources.list.d/nodesource.list",
                "apt-get update $NODESOURCE_SCOPED_UPDATE_FLAGS",
                "apt-get install -y nodejs",
            ).joinToString(" && ")
        val result = runRootCommand(command, INSTALL_TIMEOUT_MS, lineTracker(onProgress))
        if (result == null || result.exitCode != 0) {
            warnings +=
                "Node.js was not installed" +
                    (result?.outputText()?.lineSequence()?.lastOrNull { it.isNotBlank() }
                        ?.let { ": $it" } ?: ": the install timed out")
        }
    }

    /**
     * The npm-delivered toolchain, one package at a time so one failure does not take the other
     * down with it. Best-effort by design — see [setup].
     */
    private suspend fun installGlobalTools(warnings: MutableList<String>) {
        for (tool in GLOBAL_TOOLS) {
            val result = runRootCommand("npm install -g $tool", GLOBAL_TOOL_TIMEOUT_MS)
            if (result == null || result.exitCode != 0) {
                warnings +=
                    "npm package '$tool' was not installed" +
                        (result?.outputText()?.lineSequence()?.lastOrNull { it.isNotBlank() }
                            ?.let { ": $it" } ?: ": the install timed out")
            }
        }
    }

    // ------------------------------------------------------------------ command helpers

    /**
     * The environment for scripted root commands: the runtime's clean base plus the one knob apt
     * tooling needs to never stop and ask a question no pty user is there to answer.
     */
    private fun rootEnv(): List<String> = runtime.baseEnv() + "DEBIAN_FRONTEND=noninteractive"

    private suspend fun runRootCommand(
        command: String,
        timeoutMs: Long,
        onOutput: ((ByteArray) -> Unit)? = null,
    ): ProotCommandResult? =
        runtime.runCommand(runtime.commandArgv(command, asRoot = true), env = rootEnv(), timeoutMs = timeoutMs, onOutput = onOutput)

    /**
     * Runs a command through the *session* argv — no fake root — because [healthProbe] must prove
     * the user-facing path works, not the setup path.
     */
    private suspend fun runSessionCommand(command: String, timeoutMs: Long): ProotCommandResult? =
        runtime.runCommand(runtime.sessionArgv(command), env = runtime.baseEnv(), timeoutMs = timeoutMs)

    private suspend fun requireRoot(
        step: String,
        command: String,
        timeoutMs: Long,
        onOutput: ((ByteArray) -> Unit)? = null,
    ): ProotCommandResult {
        val result = runRootCommand(command, timeoutMs, onOutput)
            ?: throw IOException("$step timed out after ${timeoutMs / 60000} minutes")
        if (result.exitCode != 0) {
            // The tail, not the whole log: apt failures name the failing step near the end, and a
            // full npm transcript would bury it.
            throw IOException("$step failed (exit ${result.exitCode}): ${result.outputText().take(2000)}")
        }
        return result
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
        File(rootfs, "etc/apt/sources.list").writeText(sourcesListContent(distro, baseUri))
    }

    /**
     * The always-current official mirror feed, fetched once [aptUpdate] has exhausted the primary
     * archive. Plain HTTP on purpose: the guest apt has no CA store until `ca-certificates` installs
     * from the base packages this very update unlocks, and plain-HTTP mirrors are still
     * integrity-safe because apt verifies the GPG-signed Release files end to end.
     *
     * Any failure reads as an empty list — the built-in fallbacks still run, so the feed is never
     * a new single point of failure.
     */
    private suspend fun fetchMirrorList(): List<String> = withContext(Dispatchers.IO) {
        val body = runCatching {
            val connection = URL(mirrorListUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = MIRROR_FETCH_TIMEOUT_MS
            connection.readTimeout = MIRROR_FETCH_TIMEOUT_MS
            try {
                val code = connection.responseCode
                if (code !in 200..299) return@runCatching ""
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }.getOrDefault("")
        parseMirrorList(body, primaryArchiveUrl(distro))
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
         */
        const val DEFAULT_MIRROR_LIST_URL = "http://mirrors.ubuntu.com/mirrors.txt"

        /**
         * The apt-delivered toolchain: editors and file tools, certificates, git, Python, sudo and
         * the gnupg [installNodeJs] needs. Node/npm/pip are installed by [installNodeJs] /
         * arriving with their packages.
         */
        private val BASE_PACKAGES = listOf(
            "bash-completion",
            "ca-certificates",
            "curl",
            "git",
            "gnupg",
            "less",
            "openssh-client",
            "procps",
            "python3",
            "python3-pip",
            "sudo",
            "unzip",
            "wget",
            "zip",
        )

        /**
         * npm packages installed globally. `opencode-ai` is the OpenCode CLI's published npm name;
         * `pnpm` is fetched from npm exactly as its own docs install it.
         */
        private val GLOBAL_TOOLS = listOf("pnpm", "opencode-ai")

        private const val NODESOURCE_KEY_URL =
            "https://deb.nodesource.com/gpgkey/nodesource.gpg.key"

        /** One pinned major Node series; a bump is a deliberate change, not drift. */
        private const val NODESOURCE_REPO = "https://deb.nodesource.com/node_24.x"

        /**
         * NodeSource's one suite for every distribution it supports; a per-codename suite like
         * `jammy` no longer exists on their repository.
         */
        private const val NODESOURCE_SUITE = "nodistro"

        /**
         * Updates only the NodeSource list: the Ubuntu lists were fetched by [aptUpdate]'s ladder
         * minutes ago, and re-fetching them would re-download the whole archive for one new
         * repository. `List-Cleanup=0` stops apt from deleting the other lists it was told not to
         * look at.
         */
        private const val NODESOURCE_SCOPED_UPDATE_FLAGS =
            "-o Dir::Etc::sourcelist=/etc/apt/sources.list.d/nodesource.list " +
                "-o Dir::Etc::sourceparts=/dev/null -o APT::Get::List-Cleanup=0 " +
                "-o Acquire::Retries=3 -o Acquire::Languages=none -o Acquire::http::Timeout=30"

        private const val PROBE_MARKER = "eclipse-probe-ok"

        private const val PROBE_TIMEOUT_MS = 2 * 60_000L
        private const val NETWORK_TIMEOUT_MS = 60_000L
        private const val APT_TIMEOUT_MS = 5 * 60_000L

        /**
         * One apt-update rung. Shorter than the step used to wait on a single archive, because a
         * rung that cannot finish in ten minutes is a network fact, and the ladder's whole point
         * is that there is another rung to try.
         */
        private const val APT_UPDATE_ATTEMPT_TIMEOUT_MS = 10 * 60_000L
        private const val INSTALL_TIMEOUT_MS = 45 * 60_000L
        private const val GLOBAL_TOOL_TIMEOUT_MS = 15 * 60_000L

        /** The mirror feed must answer quickly or not participate at all. */
        private const val MIRROR_FETCH_TIMEOUT_MS = 10_000L
    }
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
 */
internal fun aptUpdateCandidates(distro: LinuxDistro, fetchedMirrors: List<String>): List<AptUpdateAttempt> {
    val primary = primaryArchiveUrl(distro)
    val fallbacks = (fetchedMirrors + builtinMirrorUrls(distro))
        .distinct()
        .filter { it != primary }
    return listOf(
        AptUpdateAttempt(primary, forceIpv4 = false),
        AptUpdateAttempt(primary, forceIpv4 = true),
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
 * The rules: one URL per line; plain `http://` only (the guest apt has no CA store until
 * `ca-certificates` installs from the base packages this update unlocks, while a plain-HTTP mirror
 * is still integrity-safe because apt verifies the GPG-signed Release files); trailing slashes
 * trimmed so `http://x/ubuntu/` and `http://x/ubuntu` dedupe; the primary archive dropped (it has
 * already failed twice by the time this list is consulted); and only the first
 * [FETCHED_MIRROR_RUNGS] kept, in the feed's order — the feed is geo-sorted for the asking IP, so
 * the first entries are the nearest ones.
 */
internal fun parseMirrorList(body: String, primaryArchiveUrl: String): List<String> =
    body.lineSequence()
        .map { it.trim().trimEnd('/') }
        .filter { it.startsWith("http://") }
        .distinct()
        .filter { it != primaryArchiveUrl }
        .take(FETCHED_MIRROR_RUNGS)
        .toList()

/**
 * The apt-get command for one rung of the ladder: three attempts per archive, no translation
 * indexes (a phone install never reads them and they are half the download), and a connect
 * timeout short enough that a black-holed address fails over instead of eating the rung's whole
 * budget. Rung two adds `-o Acquire::ForceIPv4=true` — see [AptUpdateAttempt]. Pure so the test
 * can assert the exact command the pty receives.
 */
internal fun aptUpdateCommand(attempt: AptUpdateAttempt): String = buildString {
    append("apt-get update -o Acquire::Retries=3 -o Acquire::Languages=none -o Acquire::http::Timeout=30")
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
    INSTALL_NODEJS,
    INSTALL_GLOBAL_TOOLS,
    VERIFY,
}

/** What [UbuntuDistributionManager.setup] has to say beyond "done". */
data class SetupReport(
    /** Non-fatal problems: global npm tools that did not install, sudo not configured. */
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
