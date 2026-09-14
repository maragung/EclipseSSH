package dev.eclipse.ssh.linux

import dev.eclipse.ssh.ssh.TerminalChannel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The exit code and captured output of one completed proot command. */
data class ProotCommandResult(
    val exitCode: Int,
    /** Everything the command wrote to stdout and stderr, interleaved as it arrived. */
    val output: ByteArray,
) {
    /** The output as text, for callers comparing against markers. */
    fun outputText(): String = String(output, Charsets.UTF_8)
}

/**
 * The shared context every proot invocation is built from: where the pieces live on disk, and how
 * the argument vector and environment for a session are assembled.
 *
 * "Runtime" here means the *recipe*, not a long-lived process. proot has no daemon — each shell,
 * each setup step and each health probe is a fresh `fork+execve` of `libproot.so` — so this class
 * is the one place that knows the exec model, and everything else asks it for a command.
 *
 * The exec model, in full (validated end to end against the pinned proot fork; see
 * `linux/build.gradle.kts` for the pin and `linux/README.md` for the whole story):
 *
 *  - `libproot.so` and `libproot-loader.so` are exec'd from `nativeLibraryDir` — the only directory
 *    the system labels executable for a targetSdk 29+ app. `PROOT_LOADER` must point there because
 *    without it proot extracts an embedded loader into `PROOT_TMP_DIR` (inside `filesDir`, never
 *    executable) and dies with EACCES.
 *  - The rootfs itself lives under `filesDir` and is never exec'd by the kernel: proot's loader
 *    `mmap`s the rootfs binaries, and mmap-exec is permitted where execve is not.
 *  - Sessions run **without** `-0`. The shell runs as the app's own uid — which the setup pipeline
 *    registers as the `ubuntu` user in the rootfs `/etc/passwd`, so `whoami` says `ubuntu` with
 *    home `/home/ubuntu` and no fake root anywhere. This is deliberate: the default terminal
 *    account must never be root. Writing to the rootfs needs no privilege at all — on Android the
 *    app cannot `chown`, so every file under it is owned by the app uid — but *packaging* does:
 *    dpkg `chown`s the files it unpacks to `root:root`, and a real EPERM there aborts `apt-get
 *    install`. The setup pipeline therefore runs its scripted commands with `-0` (proot's fake
 *    root), under which proot swallows those ownership changes; the kernel-level owner stays the
 *    app uid either way, because proot cannot `chown` any more than the app can.
 *
 * @param rootDir the userspace root (`filesDir/linux`): rootfs, tmp, state and workspace live under it
 * @param nativeLibraryDir the APK's extracted native library directory
 * @param spawner the fork seam — [LinuxPtySpawner] in production, a fake in tests
 * @param storage the one owner of every userspace path; defaults to one for this root, but the
 *   graph passes its shared instance so runtime and installer can never disagree about where the
 *   rootfs lives
 */
class ProotRuntime(
    val rootDir: File,
    private val nativeLibraryDir: String,
    private val spawner: PtySpawner,
    private val storage: RuntimeStorageManager = RuntimeStorageManager(rootDir),
) {
    val rootfsDir: File get() = storage.rootfsDir
    val tmpDir: File get() = storage.tmpDir

    /** The proot binary, as the first argv element. */
    private val prootBinary: String get() = File(nativeLibraryDir, "libproot.so").path

    /** The standalone loader, as proot's `PROOT_LOADER` expects it. */
    private val prootLoader: String get() = File(nativeLibraryDir, "libproot-loader.so").path

    /** The user's home *inside* the rootfs. */
    val homePath: String = "/home/ubuntu"

    /** The persisted workspace *inside* the rootfs. */
    val workspacePath: String = "$homePath/workspace"

    /**
     * The argument vector for an interactive shell session — the user-facing path, never fake root.
     */
    fun sessionArgv(initialCommand: String? = null): List<String> =
        commandArgv(initialCommand, asRoot = false)

    /**
     * The argument vector for a scripted command. [asRoot] adds `-0`, proot's fake root, which the
     * setup pipeline needs for dpkg's `chown`s — see the class doc. Sessions never pass it.
     *
     * `/dev`, `/proc` and `/sys` are bound because the Ubuntu Base rootfs ships empty mount points
     * for them — without the binds, `ps` shows nothing, `/proc/self/exe` is missing, and anything
     * reading a device node fails. `-w` starts the shell in the user's home rather than wherever
     * the outer process happened to be standing. `bash --login` gives the account a real login
     * shell: `/etc/profile` and `~/.profile` run, so PATH and prompt behave exactly as they would
     * over SSH.
     */
    fun commandArgv(initialCommand: String?, asRoot: Boolean): List<String> {
        val argv = mutableListOf(
            prootBinary,
            "--rootfs=${rootfsDir.absolutePath}",
        )
        if (asRoot) argv += "-0"
        argv += listOf(
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-w", homePath,
            "/bin/bash", "--login",
        )
        // A command overrides the interactive shell: `bash --login -c '…'`. The setup pipeline uses
        // this for scripted steps; a session never does.
        if (initialCommand != null) {
            argv += listOf("-c", initialCommand)
        }
        return argv
    }

    /**
     * The environment every proot child is exec'd with. It *replaces* the app's environment — an
     * Android environment inside Ubuntu is a set of lies (`HOME` pointing at the outer filesDir,
     * `PATH` full of Android tooling), and env vars cross the execve boundary untouched by proot.
     */
    fun baseEnv(): List<String> = listOf(
        // Non-negotiable: without PROOT_LOADER proot extracts its embedded loader into
        // PROOT_TMP_DIR — under filesDir, not executable — and every start dies with EACCES.
        "PROOT_LOADER=$prootLoader",
        "PROOT_TMP_DIR=${tmpDir.absolutePath}",
        // Ubuntu-conventional values, not Android's: a login shell resolves HOME from /etc/passwd,
        // but everything non-login reads the variable.
        "HOME=$homePath",
        "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "TERM=xterm-256color",
        "LANG=C.UTF-8",
    )

    /** The outer directory a spawned child starts in: the userspace root, which always exists. */
    val spawnCwd: File get() = rootDir

    /**
     * Forks one interactive session: the argv of [sessionArgv] (no fake root — the user-facing
     * shell is the `ubuntu` account) and the environment of [baseEnv], on a pty of [rows] x
     * [columns].
     *
     * The caller owns the returned [PtyProcess] — in production it goes straight into
     * [LocalTerminalChannel], which takes sole ownership of the fd pair.
     */
    fun spawnSession(rows: Int, columns: Int): PtyProcess =
        spawner.spawn(sessionArgv(), baseEnv(), spawnCwd.absolutePath, rows, columns)

    /**
     * Runs one proot command to completion and captures what it printed.
     *
     * The setup pipeline and the health probe both need "did this command work, and what did it
     * say" — an interactive terminal never does, which is why this lives here and the interactive
     * path is [LocalTerminalChannel]'s. The pty exists even for scripted commands because the
     * rootfs's tooling assumes a terminal is present, and proot is only ever started through one.
     *
     * [onOutput], when given, sees each chunk as it arrives instead of only at the end. It exists
     * for the minutes-long setup commands: an `apt-get update` whose output nobody sees until it
     * finishes is indistinguishable from a wedged one on the install screen. Called on
     * [Dispatchers.IO] from the read loop, so it must be cheap — publish to a conflated flow and
     * return, nothing more.
     *
     * @param timeoutMs the whole command — output, exit, everything — must finish within this
     * @return the exit code and output, or null when the command did not finish in time
     */
    suspend fun runCommand(
        argv: List<String>,
        env: List<String> = baseEnv(),
        timeoutMs: Long = DEFAULT_COMMAND_TIMEOUT_MS,
        onOutput: ((ByteArray) -> Unit)? = null,
    ): ProotCommandResult? = withContext(Dispatchers.IO) {
        val process = spawner.spawn(argv, env, spawnCwd.absolutePath, rows = 24, columns = 80)
        try {
            withTimeoutOrNull(timeoutMs) {
                val collected = mutableListOf<ByteArray>()
                val chunk = ByteArray(READ_CHUNK)
                while (true) {
                    val read = process.read(chunk, 0, chunk.size)
                    if (read < 0) break
                    if (read > 0) {
                        val part = chunk.copyOf(read)
                        onOutput?.invoke(part)
                        collected += part
                    }
                }
                // After end-of-stream the child has exited; the reap is quick and race-free by
                // design (see linuxpty.c: awaitExit waits on a child that has already terminated).
                // ByteArray has no flatten(): the sizes are summed first so the single copy is
                // exact, not a grow-as-you-go buffer.
                val total = collected.sumOf { it.size }
                val output = ByteArray(total)
                var offset = 0
                for (part in collected) {
                    part.copyInto(output, offset)
                    offset += part.size
                }
                ProotCommandResult(process.awaitExit(), output)
            }
        } catch (t: Throwable) {
            runCatching { process.close() }
            throw t
        }
    }

    companion object {
        /** Long enough for `apt-get update` over a slow link; most commands finish far sooner. */
        const val DEFAULT_COMMAND_TIMEOUT_MS = 10 * 60_000L

        private const val READ_CHUNK = 4096
    }
}
