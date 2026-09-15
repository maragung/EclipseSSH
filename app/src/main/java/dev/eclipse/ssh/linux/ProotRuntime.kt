package dev.eclipse.ssh.linux

import android.util.Log
import dev.eclipse.ssh.ssh.TerminalChannel
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
 * @param readerDrainTimeoutMs how long a timed-out command waits for its reader to wake after the
 *   pty close, before the reader is abandoned; injectable so a test can exercise the abandonment
 *   without sleeping the production budget
 */
class ProotRuntime(
    val rootDir: File,
    private val nativeLibraryDir: String,
    private val spawner: PtySpawner,
    private val storage: RuntimeStorageManager = RuntimeStorageManager(rootDir),
    private val readerDrainTimeoutMs: Long = READER_DRAIN_TIMEOUT_MS,
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
     * Storage is verified first: `PROOT_TMP_DIR` names [RuntimeStorageManager.tmpDir], and proot
     * that cannot write there dies with "can't create temporary directory: Permission denied" —
     * the historical failure this check exists to make impossible. An environment variable is not
     * a directory.
     *
     * The caller owns the returned [PtyProcess] — in production it goes straight into
     * [LocalTerminalChannel], which takes sole ownership of the fd pair.
     */
    fun spawnSession(rows: Int, columns: Int): PtyProcess {
        storage.requireReady()
        return spawner.spawn(sessionArgv(), baseEnv(), spawnCwd.absolutePath, rows, columns)
    }

    /**
     * Runs one proot command to completion and captures what it printed.
     *
     * The setup pipeline and the health probe both need "did this command work, and what did it
     * say" — an interactive terminal never does, which is why this lives here and the interactive
     * path is [LocalTerminalChannel]'s. The pty exists even for scripted commands because the
     * rootfs's tooling assumes a terminal is present, and proot is only ever started through one.
     *
     * The read runs as a child job and the timeout waits on it with `await()` — a coroutine parked
     * in the pty's blocking JNI read has no suspension point, so a timeout wrapped around the read
     * loop itself could never fire (the historical shape of this method: a wedged `apt-get update`
     * hung the whole install, pty slot and all). `await()` *is* cancellable, so the timeout fires
     * on schedule; what the timeout then does is [PtyProcess.close] — the master-side hangup wakes
     * the blocked read and SIGHUPs the child — which is why the close lives on the timeout path
     * and in the catch, not only in a finally the timeout could not reach.
     *
     * The wake itself is not assumed: a child that died before opening the slave never generates
     * the hangup, and a close on an fd never interrupts a read already parked on it — the native
     * read loop re-checks liveness so a teardown still ends the read, but the drain here carries
     * its own short budget too. A reader still parked past it is abandoned with a log line rather
     * than awaited forever (the 2026-09 emulator hang: a first-spawn child wedged inside
     * libsigchain's sigaction interposer left exactly such a reader, and the install sat on
     * "Configuring DNS" until the harness's own timeout). For that abandonment to be possible
     * at all, the reader runs *detached* from this method's scope — a child coroutine would make
     * the return itself wait on the read being abandoned. Bounded failure, never a silent wedge.
     *
     * Every spawn and outcome is logged under the shared [UserspaceDiagnostics.TAG], because the
     * one fact a wedged install cannot otherwise name is *which* command never answered.
     *
     * [onOutput], when given, sees each chunk as it arrives instead of only at the end. It exists
     * for the minutes-long setup commands: an `apt-get update` whose output nobody sees until it
     * finishes is indistinguishable from a wedged one on the install screen. Called on
     * [Dispatchers.IO] from the read loop, so it must be cheap — publish to a conflated flow and
     * return, nothing more.
     *
     * @param timeoutMs the whole command — output, exit, everything — must finish within this
     * @return the exit code and output, or null when the command did not finish in time (the
     *   child has been SIGHUPed by then, and the reader woken or abandoned)
     */
    suspend fun runCommand(
        argv: List<String>,
        env: List<String> = baseEnv(),
        timeoutMs: Long = DEFAULT_COMMAND_TIMEOUT_MS,
        onOutput: ((ByteArray) -> Unit)? = null,
    ): ProotCommandResult? = withContext(Dispatchers.IO) {
        storage.requireReady()
        // The tail of argv is the command itself for scripted runs (`… bash --login -c getent …`)
        // and the shell path for sessions — either way, the one token that names what this spawn is.
        val command = (argv.lastOrNull() ?: "proot").take(COMMAND_LOG_CHARS)
        val startedAt = System.currentTimeMillis()
        Log.i(UserspaceDiagnostics.TAG, "proot command started: $command")
        val process = spawner.spawn(argv, env, spawnCwd.absolutePath, rows = 24, columns = 80)
        liveScripted += process
        // The reader is deliberately NOT a child of this scope. It parks in a blocking JNI
        // read that no cancellation can reach, and structured concurrency would make this
        // whole call wait for it before returning - the exact wedge the drain budget below
        // exists to bound. Detached, the timeout path can abandon it and return; the close()
        // is what ends the read in every case the pty itself can cooperate with.
        val reader = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            .async { readToCompletion(process, onOutput) }
        try {
            val output = withTimeoutOrNull(timeoutMs) { reader.await() }
            if (output == null) {
                // Timed out: the reader is still parked in a blocking read no cancellation can
                // reach. Closing the pty is what unblocks it (master hangup) and SIGHUPs the
                // child. The reader's read then errors — the expected face of a torn-down pty,
                // which readToCompletion treats as end-of-stream.
                runCatching { process.close() }
                val drained = withTimeoutOrNull(readerDrainTimeoutMs) { reader.await() }
                if (drained == null) {
                    Log.w(
                        UserspaceDiagnostics.TAG,
                        "proot command abandoned: $command did not wake " +
                            "${readerDrainTimeoutMs / 1000}s after its ${timeoutMs / 1000}s timeout close",
                    )
                    // Cancelled, not left quietly half-alive: if the read ever does return,
                    // the job ends instead of idling, and debug tooling shows it as cancelled.
                    reader.cancel()
                }
                Log.w(UserspaceDiagnostics.TAG, "proot command timed out after ${timeoutMs / 1000}s: $command")
                null
            } else {
                // After end-of-stream the child has exited; the reap is quick and race-free by
                // design (see linuxpty.c: awaitExit waits on a child that has already terminated).
                val exitCode = process.awaitExit()
                Log.i(
                    UserspaceDiagnostics.TAG,
                    "proot command finished: exit=$exitCode" +
                        " dur=${(System.currentTimeMillis() - startedAt) / 1000}s: $command",
                )
                ProotCommandResult(exitCode, output)
            }
        } catch (t: Throwable) {
            // Cancellation and reader failure both land here: either way the child is SIGHUPed
            // rather than left holding a pty slot.
            runCatching { process.close() }
            throw t
        } finally {
            liveScripted -= process
        }
    }

    /**
     * Reads one child's output to end-of-stream and returns it as one buffer. A read that throws
     * ends the stream instead of the command: after a timeout-driven close(), the error IS the
     * end of the stream.
     */
    private fun readToCompletion(
        process: PtyProcess,
        onOutput: ((ByteArray) -> Unit)?,
    ): ByteArray {
        val collected = mutableListOf<ByteArray>()
        val chunk = ByteArray(READ_CHUNK)
        while (true) {
            val read = runCatching { process.read(chunk, 0, chunk.size) }.getOrDefault(-1)
            if (read < 0) break
            if (read > 0) {
                val part = chunk.copyOf(read)
                onOutput?.invoke(part)
                collected += part
            }
        }
        // ByteArray has no flatten(): the sizes are summed first so the single copy is
        // exact, not a grow-as-you-go buffer.
        val total = collected.sumOf { it.size }
        val output = ByteArray(total)
        var offset = 0
        for (part in collected) {
            part.copyInto(output, offset)
            offset += part.size
        }
        return output
    }

    companion object {
        /** Long enough for `apt-get update` over a slow link; most commands finish far sooner. */
        const val DEFAULT_COMMAND_TIMEOUT_MS = 10 * 60_000L

        /**
         * How long a timed-out command's reader gets to wake after the pty close before it is
         * abandoned. The native read loop ends a torn-down pty's read within a poll interval, so
         * the drain virtually always succeeds; the budget exists for whatever the kernel and the
         * child conspire to leave un-wakeable, where the choice is a leaked reader coroutine or
         * a wedged install — and a leaked coroutine is a line in the log, not a hang.
         */
        const val READER_DRAIN_TIMEOUT_MS = 5_000L

        /** The command token's cap for log lines: enough to name it, never enough to be a transcript. */
        private const val COMMAND_LOG_CHARS = 120

        private const val READ_CHUNK = 4096
    }

    // ------------------------------------------------------------------ scripted-process registry

    /** The in-flight runCommand children. */
    private val liveScripted = CopyOnWriteArrayList<PtyProcess>()

    /** Whether any scripted command is still in flight — consulted before reclaiming storage. */
    fun hasLiveScriptedProcesses(): Boolean = liveScripted.isNotEmpty()

    /**
     * SIGHUPs every in-flight scripted command. Each close is best-effort: a child that is
     * already gone is not an error. The interactive sessions are deliberately untouched — closing
     * the user's terminals is the process manager's call, not the runtime's.
     */
    fun killScriptedProcesses() {
        for (process in liveScripted) {
            runCatching { process.close() }
        }
    }
}
