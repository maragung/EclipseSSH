package dev.eclipse.ssh.linux

import java.io.File

/**
 * The two pids the tests that plant proot's leftover scratch directories have to name: one that
 * nothing is running, and one that certainly is.
 *
 * The sweep identifies litter by the pid proot put in the directory's own name — a directory whose
 * process is still running belongs to a live session and must survive — so a test that plants one
 * has to know which kind of pid it is using. It may not simply write a number and hope: on a busy
 * machine (a CI runner, a shared host) a hardcoded pid is a live process sooner or later, and the
 * test would then pass by sweeping nothing at all.
 */
internal object TestPids {

    /**
     * A pid naming no running process here: the first hole found scanning down from [CEILING].
     *
     * A hole rather than a guess, and a stable one for the length of a test: Linux hands pids out
     * upwards and wraps at `/proc/sys/kernel/pid_max`, so a number below the hole is not reached
     * again without millions of forks in between. The scan costs one `stat` per pid it passes, and
     * in practice it stops on the first or second — a machine's used pids are either well above
     * [CEILING] or scattered with the holes every exited process leaves.
     */
    fun nothingRuns(): Int = (CEILING downTo 1).first { !File("/proc/$it").exists() }

    /**
     * This JVM's own pid — the live session whose directory the sweep must leave alone. Read from
     * the running process rather than assumed, so it is alive by construction.
     */
    fun thisProcess(): Int = ProcessHandle.current().pid().toInt()

    private const val CEILING = 40_000
}
