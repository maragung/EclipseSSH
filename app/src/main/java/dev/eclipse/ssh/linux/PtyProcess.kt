package dev.eclipse.ssh.linux

import java.io.IOException

/**
 * One forked process with a controlling terminal — the seam between the userspace runtime and the
 * native PTY bridge.
 *
 * An interface, not a direct use of [dev.eclipse.ssh.linux.LinuxPty], because the runtime's tests
 * run on a JVM where the bridge's shared library cannot load. Every consumer — the terminal
 * channel, the setup pipeline, the health probe — talks to this type; only [LinuxPtySpawner]
 * produces the real thing, and only tests produce fakes.
 *
 * The contract mirrors `LinuxPty`'s JNI surface exactly (same blocking semantics, same fd-free
 * handle identity), so the production wrapper is a one-liner per method and nothing gains a second
 * layer of interpretation:
 *
 *  - [read] blocks until bytes arrive and returns them, or returns `-1` when the child side is gone
 *    (Linux pty semantics report EIO once every slave fd is closed);
 *  - [write] writes fully, looping over partial writes;
 *  - [awaitExit] blocks for the child, reaps it and returns the exit code — 0-255, or 128+signal;
 *    it also frees the pty, so it may be called exactly once and nothing else may follow it;
 *  - [close] tears the pty down without waiting (the child gets SIGHUP) and is the alternative to
 *    [awaitExit], not a step after it.
 */
interface PtyProcess {
    fun read(buffer: ByteArray, offset: Int, length: Int): Int

    fun write(buffer: ByteArray, offset: Int, length: Int): Int

    fun resize(rows: Int, columns: Int)

    fun awaitExit(): Int

    fun close()
}

/** Spawns [PtyProcess]es; injectable for the same reason [PtyProcess] is an interface. */
fun interface PtySpawner {
    /**
     * Forks [argv] with [envp] as the child's entire environment and [cwd] as its working directory
     * (an *outer* path — the child runs before any translation applies), with a pty of [rows] x
     * [columns] as its controlling terminal and stdio.
     *
     * @throws IOException when anything before the fork fails, or when too many terminals are open
     *   — the latter being [PTY_TABLE_FULL_MESSAGE], which [ProotRuntime] maps to a typed refusal
     *   rather than letting a bare native string reach the user
     */
    fun spawn(argv: List<String>, envp: List<String>, cwd: String, rows: Int, columns: Int): PtyProcess
}

/**
 * How many terminals the native bridge can hold at once (`MAX_PTYS` in `linuxpty.c`) and the message
 * it throws when that many are open.
 *
 * A cross-language contract: the C side cannot read these constants, so the number and the string
 * have to be kept in step by hand. The dependency is deliberately one-way — a bridge that grew more
 * slots and forgot this file would report one number too low, and a bridge that changed the message
 * would fall back to the raw `IOException`, which is what every caller did before the mapping
 * existed. Neither is a silent wrong answer.
 */
internal const val PTY_SLOTS = 16
internal const val PTY_TABLE_FULL_MESSAGE = "too many open local terminals"

/** The production spawner: forks through the native PTY bridge in `liblinuxpty.so`. */
object LinuxPtySpawner : PtySpawner {
    override fun spawn(
        argv: List<String>,
        envp: List<String>,
        cwd: String,
        rows: Int,
        columns: Int,
    ): PtyProcess = LinuxPtyProcess(
        LinuxPty.spawn(argv.toTypedArray(), envp.toTypedArray(), cwd, rows, columns),
    )
}

/** A [PtyProcess] over one master fd from the native bridge. */
private class LinuxPtyProcess(private val fd: Int) : PtyProcess {
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = LinuxPty.read(fd, buffer, offset, length)

    override fun write(buffer: ByteArray, offset: Int, length: Int): Int = LinuxPty.write(fd, buffer, offset, length)

    override fun resize(rows: Int, columns: Int) = LinuxPty.resize(fd, rows, columns)

    override fun awaitExit(): Int = LinuxPty.awaitExit(fd)

    override fun close() = LinuxPty.close(fd)
}
