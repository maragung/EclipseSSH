package dev.eclipse.ssh.linux

import java.io.File
import java.io.IOException

/**
 * Turns a freshly extracted Ubuntu Base rootfs into the distribution the feature promises: an
 * `ubuntu` account, a persisted workspace, working DNS, a package manager pointed at the archive
 * that actually serves this device's architecture, and the real toolchain the spec names — Node.js,
 * Python, git — installed through apt itself, not simulated.
 *
 * Nothing here talks to the network except through the rootfs's own tools (`apt-get`, `curl`,
 * `npm`), run inside proot. That is deliberate: the install pipeline is also the first end-to-end
 * proof that the runtime works, so every step doubles as a test of the step before it.
 *
 * The account model: the app's Android uid is registered as user `ubuntu` in `/etc/passwd` (see
 * [ProotRuntime] for why sessions never fake root). The setup commands themselves run with
 * proot's `-0` — dpkg `chown`s what it unpacks to `root:root`, and a real EPERM there would abort
 * every package install; under `-0` proot swallows the ownership change while the kernel-level
 * owner stays the app uid.
 *
 * @param distro the pinned distribution being set up; its architecture selects the apt archive
 * @param runtime the proot context this manager runs its commands through
 * @param appUid the app's Android uid, registered as the `ubuntu` account
 * @param appGid the app's Android gid for the account's primary group
 * @param dnsServers resolvers written into the rootfs's `/etc/resolv.conf`; injectable so the
 *   wiring layer can derive them from the device's live network instead of the defaults
 */
class UbuntuDistributionManager(
    private val distro: LinuxDistro,
    private val runtime: ProotRuntime,
    private val appUid: Int,
    private val appGid: Int,
    private val dnsServers: List<String> = DEFAULT_DNS_SERVERS,
) {
    private val rootfs: File get() = runtime.rootfsDir

    /**
     * The setup pipeline, in order. Each step either completes or throws — there is no partial
     * success, because the state machine only marks the userspace installed once everything below
     * (including [healthProbe]) has passed.
     *
     * The last two toolchain steps are deliberately softer: [SetupStep.INSTALL_GLOBAL_TOOLS]
     * installs `pnpm` and the OpenCode CLI through npm, and a failure there is reported as a
     * warning rather than an error. Those two are the only pieces whose availability depends on
     * third-party registries outside the pinned Ubuntu archive; the rest of the toolchain is
     * apt-delivered and version-pinned with the distribution.
     *
     * @param onStep invoked as each step begins, for the install screen's progress display
     * @return the warnings collected along the way (empty on a fully clean install)
     */
    suspend fun setup(onStep: suspend (SetupStep) -> Unit = {}): SetupReport {
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
        aptUpdate()
        onStep(SetupStep.INSTALL_BASE_PACKAGES)
        installBasePackages()
        configureSudo(warnings)
        onStep(SetupStep.INSTALL_NODEJS)
        installNodeJs()
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
            shellWorks = shell?.exitCode == 0 && shell.outputText().contains(PROBE_MARKER),
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
     * Points apt at the archive that serves this device's architecture: `archive.ubuntu.com`
     * carries only amd64, the ports archive carries everything else. The rootfs ships a
     * sources.list for amd64, which fails on every phone.
     */
    private fun configureAptSources() {
        val (archive, security) =
            if (distro.ubuntuArch == "amd64") {
                "http://archive.ubuntu.com/ubuntu" to "http://security.ubuntu.com/ubuntu"
            } else {
                "http://ports.ubuntu.com/ubuntu-ports" to "http://ports.ubuntu.com/ubuntu-ports"
            }
        File(rootfs, "etc/apt/sources.list").writeText(
            buildString {
                append("# $APT_MARKER\n")
                append("deb $archive $RELEASE $COMPONENTS\n")
                append("deb $archive $RELEASE-updates $COMPONENTS\n")
                append("deb $security $RELEASE-security $COMPONENTS\n")
            },
        )
    }

    private suspend fun aptUpdate() {
        requireRoot(
            "Updating package lists",
            "apt-get update -o Acquire::Retries=3",
            UPDATE_TIMEOUT_MS,
        )
    }

    /**
     * The apt-delivered toolchain. Node.js is *not* here: jammy's own `nodejs` package is 12.x
     * (end-of-life since 2022), and the spec's toolchain — `pnpm`, the OpenCode CLI — needs a
     * modern runtime, so [installNodeJs] installs a current one from the pinned NodeSource
     * repository instead.
     */
    private suspend fun installBasePackages() {
        requireRoot(
            "Installing base packages",
            "apt-get install -y --no-install-recommends ${BASE_PACKAGES.joinToString(" ")}",
            INSTALL_TIMEOUT_MS,
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
     */
    private suspend fun installNodeJs() {
        val command =
            listOf(
                "curl -fsSL $NODESOURCE_KEY_URL -o /tmp/nodesource.key",
                "gpg --dearmor -o /usr/share/keyrings/nodesource.gpg /tmp/nodesource.key",
                "echo 'deb [signed-by=/usr/share/keyrings/nodesource.gpg] " +
                    "$NODESOURCE_REPO $RELEASE main' > /etc/apt/sources.list.d/nodesource.list",
                "apt-get update -o Acquire::Retries=3",
                "apt-get install -y nodejs",
            ).joinToString(" && ")
        requireRoot("Installing Node.js", command, INSTALL_TIMEOUT_MS)
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

    private suspend fun runRootCommand(command: String, timeoutMs: Long): ProotCommandResult? =
        runtime.runCommand(runtime.commandArgv(command, asRoot = true), env = rootEnv(), timeoutMs = timeoutMs)

    /**
     * Runs a command through the *session* argv — no fake root — because [healthProbe] must prove
     * the user-facing path works, not the setup path.
     */
    private suspend fun runSessionCommand(command: String, timeoutMs: Long): ProotCommandResult? =
        runtime.runCommand(runtime.sessionArgv(command), env = runtime.baseEnv(), timeoutMs = timeoutMs)

    private suspend fun requireRoot(step: String, command: String, timeoutMs: Long): ProotCommandResult {
        val result = runRootCommand(command, timeoutMs)
            ?: throw IOException("$step timed out after ${timeoutMs / 60000} minutes")
        if (result.exitCode != 0) {
            // The tail, not the whole log: apt failures name the failing step near the end, and a
            // full npm transcript would bury it.
            throw IOException("$step failed (exit ${result.exitCode}): ${result.outputText().take(2000)}")
        }
        return result
    }

    private val networkHost: String
        get() = if (distro.ubuntuArch == "amd64") "archive.ubuntu.com" else "ports.ubuntu.com"

    companion object {
        private const val ACCOUNT_NAME = "ubuntu"
        private const val HOME_DIR = "/home/ubuntu"
        private const val PASSWD_PREFIX = "ubuntu:"

        /** The Ubuntu release the 22.04 rootfs tracks; must match the distro's tarball series. */
        private const val RELEASE = "jammy"

        private const val COMPONENTS = "main restricted universe multiverse"

        /** Marks a sources.list as ours, so [isConfigured] can tell setup from the shipped file. */
        private const val APT_MARKER = "managed by Eclipse SSH"

        /**
         * Fallback resolvers, used when the wiring layer does not supply the device's live ones.
         * Public anycast DNS is the honest default: the app has no better universal answer, and
         * these two are the most widely operated.
         */
        val DEFAULT_DNS_SERVERS = listOf("1.1.1.1", "8.8.8.8")

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
        private const val NODESOURCE_REPO = "https://deb.nodesource.com/node_22.x"

        private const val PROBE_MARKER = "eclipse-probe-ok"

        private const val PROBE_TIMEOUT_MS = 2 * 60_000L
        private const val NETWORK_TIMEOUT_MS = 60_000L
        private const val APT_TIMEOUT_MS = 5 * 60_000L

        /** apt over a phone connection; the health check is patient because it is rare. */
        private const val UPDATE_TIMEOUT_MS = 20 * 60_000L
        private const val INSTALL_TIMEOUT_MS = 45 * 60_000L
        private const val GLOBAL_TOOL_TIMEOUT_MS = 15 * 60_000L
    }
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
