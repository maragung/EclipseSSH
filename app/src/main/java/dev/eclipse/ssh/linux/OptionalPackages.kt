package dev.eclipse.ssh.linux

/**
 * One thing the install can add on top of the base system, as a checkbox on the install dialog.
 *
 * The base system is deliberately small — a shell, apt and the utilities every recipe assumes — and
 * for two years the answer to "where is Python" was "one `apt-get install` away, in the terminal".
 * That answer is still true and still the default: **nothing here is installed unless the user ticks
 * it**, so a plain install is byte-for-byte the install it always was, and no setup step acquires a
 * dependency it did not have. What the list adds is the other half of the same honesty — a user who
 * knows they want a compiler on the device should not have to discover the package names by hand,
 * over a phone keyboard, inside a terminal.
 *
 * An entry is not a synonym for one package. It is *the thing a user recognises* — "Node.js",
 * "Python 3", "build tools" — and [aptPackages] is what makes that thing true rather than
 * approximately true: `python3` without `python-is-python3` is a Python that answers to the wrong
 * name, and `nodejs` without `npm` is a Node.js that cannot install anything. The labels the user
 * named are all here, and [summary] is the line that names their packages back.
 *
 * ## The three sources, and why they are allowed here
 *
 * [aptPackages] come from the pinned Ubuntu archive — the same one the base install uses, reached
 * through the same verified ladder. [npmGlobals] do not: they come from npm's registry, which is a
 * third party, and that is why they are only ever reached for an entry the user ticked themselves.
 * [vendorInstallers] are a third party twice over: the tool's own maker publishes a shell script, and
 * ticking the box runs it inside the guest. It is the weakest of the three links, and the docs say so
 * — fetched over https, and checksummed by its own author against a manifest from the same origin,
 * which is integrity rather than authenticity: there is no signature, and no version to pin.
 *
 * The install's promise ("this device now has Ubuntu") must not depend on a source outside the
 * archive, and with this list it does not: every extra is a *warning* when it fails, never a failed
 * install, and a device whose extras did not land is one whose base system is still whole.
 *
 * [needsModernNode] is the one entry that requires a second source. Ubuntu's own `nodejs` is 12.22
 * on jammy, 18.19 on noble — and the tools this list offers are the reason a user wants a modern
 * one, so a Node.js checkbox that installed 18 and called it done would be a promise the *next*
 * checkbox breaks. See [UbuntuDistributionManager]'s modern-Node step for what happens instead.
 */
enum class OptionalPackage(
    /** The checkbox's own label — the thing the user recognises, not a package name. */
    val label: String,
    /** The line under the label: what lands, named, so the label is checkable against reality. */
    val summary: String,
    /** What apt is asked for, every one of them from the pinned Ubuntu archive. */
    val aptPackages: List<String> = emptyList(),
    /** npm globals, installed once Node.js is proven to be in place. */
    val npmGlobals: List<String> = emptyList(),
    /**
     * Other entries this one cannot work without, pulled into the install by [OptionalPackages.resolve]
     * whether or not they were ticked — the dialog says so rather than leaving the dependency to be
     * discovered as a failure. Every reference is to an entry declared *above* this one, which is what
     * makes the constant a legal enum argument.
     */
    val requires: List<OptionalPackage> = emptyList(),
    /**
     * Whether the user needs Node.js newer than the archive's. Only [NODE] carries it: the tools that
     * need a modern runtime all [requires] Node.js, so the question "does this selection need a
     * modern Node" is answered once, by the resolution, instead of by three entries agreeing.
     */
    val needsModernNode: Boolean = false,
    /**
     * Vendor installers: scripts the tool's own maker publishes, fetched and run inside the guest. A
     * third source beside [aptPackages] and [npmGlobals], and reached on the same terms — only for an
     * entry the user ticked, and never able to fail an install.
     *
     * A list rather than a single installer because the pipeline folds a selection's mechanisms the
     * same way it folds its packages, and because running a script is *visible* in a way fetching a
     * package is not: two entries naming one address must not run it twice.
     */
    val vendorInstallers: List<VendorInstaller> = emptyList(),
) {
    NODE(
        label = "Node.js and npm",
        summary = "node, npm and npx · the archive's, upgraded to the current LTS when that is " +
            "older than 20",
        aptPackages = listOf("nodejs", "npm"),
        needsModernNode = true,
    ),

    PYTHON(
        label = "Python 3",
        summary = "python3, python, pip and venv · python is the same interpreter by another name",
        aptPackages = listOf(
            "python3",
            "python3-pip",
            "python3-venv",
            "python3-dev",
            "python-is-python3",
        ),
    ),

    BUILD_TOOLS(
        label = "Build tools",
        summary = "make, cmake, gcc, g++, pkg-config, autoconf, automake and libtool",
        aptPackages = listOf(
            "build-essential",
            "cmake",
            "pkg-config",
            "autoconf",
            "automake",
            "libtool",
        ),
    ),

    TERMINAL_TOOLS(
        label = "Terminal utilities",
        summary = "ripgrep, fd-find, jq, tree, tmux, vim, nano, rsync, less, man, lsof, strace, " +
            "dig and netstat",
        aptPackages = listOf(
            "ripgrep",
            "fd-find",
            "jq",
            "tree",
            "tmux",
            "vim",
            "nano",
            "rsync",
            "less",
            "man-db",
            "lsof",
            "strace",
            "dnsutils",
            "net-tools",
        ),
    ),

    OPENCODE(
        label = "opencode",
        summary = "the opencode coding agent, run as the opencode command · an npm global",
        npmGlobals = listOf("opencode-ai"),
        requires = listOf(NODE),
    ),

    CLINE(
        label = "cline",
        summary = "the Cline coding agent, run as the cline command · an npm global, and the one " +
            "that needs Node.js 20 or newer",
        npmGlobals = listOf("cline"),
        requires = listOf(NODE),
    ),

    KILO(
        label = "Kilo Code CLI",
        summary = "Kilo Code, run as the kilo command · an npm global from @kilocode/cli",
        npmGlobals = listOf("@kilocode/cli"),
        requires = listOf(NODE),
    ),

    CLAUDE_CODE(
        label = "Claude Code CLI",
        summary = "the Claude Code CLI, run as the claude command · installed by Anthropic's own " +
            "installer, which ships amd64 and arm64 builds and none for 32-bit ARM",
        vendorInstallers = listOf(
            VendorInstaller(
                url = "https://claude.ai/install.sh",
                supportedArches = listOf("amd64", "arm64"),
                command = "claude",
            ),
        ),
    ),
}

/**
 * One vendor's own installer: a shell script the tool's maker publishes at a stable address, which the
 * install fetches inside the guest and runs.
 *
 * [url] and [supportedArches] are one object rather than two fields because they are two facts about
 * the same *script* rather than about the entry: a URL read without knowing which CPUs it has a build
 * for is how a device gets offered an install that cannot finish, and the vendor's own answer to a CPU
 * it does not ship for is to exit. [command] is what makes the tick checkable after the fact — the
 * script's exit status is its own word for what it did, while the command it leaves behind is the
 * measurement, the same distinction [UbuntuDistributionManager] draws when it runs `node --version`
 * rather than trusting that `apt-get install nodejs` meant a Node.js the tools can run.
 *
 * [supportedArches] is never empty: an empty list would mean "nobody decided" rather than "everywhere",
 * and a test pins that.
 */
data class VendorInstaller(
    /** The vendor's own documented address. The install fetches this and nothing else from there. */
    val url: String,
    /** The Ubuntu architectures the vendor ships a build for, in the catalogue's own spelling. */
    val supportedArches: List<String>,
    /** The command the installer leaves behind, read back with `command -v` once it reports success. */
    val command: String,
)

/**
 * The [OptionalPackage] list as the install pipeline consumes it: what a selection actually means
 * once its dependencies are folded in, and the three facts the pipeline needs out of it.
 *
 * Pure, and separated from both the dialog and the manager because all three read the same
 * selection and none of them owns it: the dialog draws it, the pipeline installs it, and the answer
 * to "what does ticking cline do" has to be one answer rather than three derivations of one.
 */
object OptionalPackages {

    /** Every entry, in the order the dialog shows them — the enum's own declaration order. */
    val all: List<OptionalPackage> get() = OptionalPackage.entries

    /**
     * The selection plus everything it depends on, in declaration order.
     *
     * Declaration order rather than selection order, because it is also install order: an entry's
     * `requires` are declared above it, so a resolved list installs Node.js before the npm global
     * that cannot run without it. Duplicates collapse, and a cycle would be a compile-time
     * impossibility rather than something this has to survive — an enum constant can only name the
     * entries above it.
     */
    fun resolve(selected: Collection<OptionalPackage>): List<OptionalPackage> {
        val wanted = mutableSetOf<OptionalPackage>()
        fun add(entry: OptionalPackage) {
            if (wanted.add(entry)) entry.requires.forEach(::add)
        }
        selected.forEach(::add)
        return OptionalPackage.entries.filter { it in wanted }
    }

    /**
     * What the tick pulls in that the user did not tick — the dialog's "this also installs Node.js"
     * line, and the reason [resolve] is not a private detail of the pipeline.
     */
    fun implied(selected: Collection<OptionalPackage>): List<OptionalPackage> =
        resolve(selected).minus(selected.toSet())

    /** Every apt package the selection needs, deduplicated, in declaration order. */
    fun aptPackages(selected: Collection<OptionalPackage>): List<String> =
        resolve(selected).flatMap { it.aptPackages }.distinct()

    /** Every npm global the selection needs, deduplicated, in declaration order. */
    fun npmGlobals(selected: Collection<OptionalPackage>): List<String> =
        resolve(selected).flatMap { it.npmGlobals }.distinct()

    /** Whether the selection needs a Node.js newer than the one the archive may carry. */
    fun needsModernNode(selected: Collection<OptionalPackage>): Boolean =
        resolve(selected).any { it.needsModernNode }

    /**
     * Whether every mechanism [entry] names has something to run on [ubuntuArch].
     *
     * `all` over an empty list is the whole of the apt and npm case, and it is load-bearing rather
     * than incidental: a package list carries no architecture of its own — it comes from the archive
     * already pinned for the architecture this device has — so an entry that names no vendor installer
     * is installable everywhere, and stays so as architectures come and go.
     *
     * Asked by both the dialog, which draws an unsupported row disabled, and the pipeline, which skips
     * it with a warning naming the architecture, so a row cannot look tickable where the pipeline would
     * refuse. The pipeline is the authority; this exists so the dialog can say so first.
     */
    fun runsOn(entry: OptionalPackage, ubuntuArch: String): Boolean =
        entry.vendorInstallers.all { ubuntuArch in it.supportedArches }
}
