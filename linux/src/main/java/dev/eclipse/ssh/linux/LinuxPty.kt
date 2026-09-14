package dev.eclipse.ssh.linux

/**
 * The JNI surface over liblinuxpty.so: fork a child with a controlling
 * terminal (a real PTY pair, so interactive programs behave exactly like they
 * do over SSH) and hand the master side to the JVM.
 *
 * The userspace runtime spawns proot through this bridge:
 *
 * ```
 * val fd = LinuxPty.spawn(
 *     argv = arrayOf("$nativeLibraryDir/libproot.so", "-r", rootfs, ...),
 *     envp = arrayOf("PROOT_LOADER=$nativeLibraryDir/libproot-loader.so", "HOME=/home/ubuntu", ...),
 *     cwd = "/home/ubuntu",
 *     rows = 24, cols = 80,
 * )
 * ```
 *
 * Threading contract: [read] blocks (there is no poll - a dedicated reader
 * thread owns the fd's read side), [resize] and [write] are safe from any
 * thread, and exactly one caller runs [awaitExit] or [close] per pty, after
 * the read loop has seen end-of-stream or as an explicit teardown.
 *
 * All methods throw [java.io.IOException] on failure via the native side.
 */
public object LinuxPty {

    init {
        // Packaged as a jniLib; System.loadLibrary finds it in
        // nativeLibraryDir. The object is deliberately not lazier than this:
        // a missing library should fail the first userspace operation loudly,
        // not surface as a puzzling UnsatisfiedLinkError deep in a session.
        System.loadLibrary("linuxpty")
    }

    /**
     * Opens a pty pair, forks and execs [argv] with [envp] and [cwd] in the
     * child; the slave becomes the child's controlling terminal and stdio.
     * Returns the master fd. If the exec itself fails, the child exits 127,
     * which surfaces here as an immediate end-of-stream on [read] plus
     * [awaitExit] returning 127.
     */
    public external fun spawn(
        argv: Array<String>,
        envp: Array<String>,
        cwd: String,
        rows: Int,
        cols: Int,
    ): Int

    /**
     * Blocking read from the pty master into [buffer] at [offset], at most
     * [length] bytes (short reads are normal stream behavior). Returns the
     * byte count, or -1 on end-of-stream - on a pty that means the child
     * side is gone (EIO), which is the exit signal for the reader loop. A
     * pty that was already closed or reaped also reports -1, never reading
     * through a descriptor number the process may have reused.
     */
    public external fun read(
        fd: Int,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int

    /**
     * Writes [length] bytes of [buffer] at [offset] to the pty master,
     * looping over partial writes. Returns the count written, or -1 when the
     * child is gone - or the pty was already closed or reaped, in which case
     * the write is refused rather than landing on a reused descriptor.
     */
    public external fun write(
        fd: Int,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int

    /**
     * Resizes the pty and delivers SIGWINCH to the child's foreground
     * process group, like resizing a terminal window. A no-op on a pty that
     * was already closed or reaped.
     */
    public external fun resize(fd: Int, rows: Int, cols: Int)

    /**
     * Blocks until the child exits, closes the master fd and returns the
     * wait status: the exit code (0-255) or 128+signal. Call once, after
     * [read] reported end-of-stream. Returns -1 if the child could not be
     * reaped, or the pty was already closed or reaped by another thread.
     */
    public external fun awaitExit(fd: Int): Int

    /**
     * Tears the pty down without waiting: closes the master (which delivers
     * SIGHUP to the child session), reaps the child non-blockingly and
     * leaves no zombie behind. Idempotent - a pty that was already closed or
     * reaped is left untouched, so however many ending paths race, the
     * descriptor is closed exactly once. Terminals dropped without a clean
     * exit go through here; terminals that ended on their own go through
     * [awaitExit].
     */
    public external fun close(fd: Int)
}
