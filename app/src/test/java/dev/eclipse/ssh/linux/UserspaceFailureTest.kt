package dev.eclipse.ssh.linux

import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.ssh.SessionEnd
import java.io.IOException
import org.junit.Test

/**
 * The failure taxonomy's classifier, pinned evidence shape by evidence shape: what the pty
 * actually prints when DNS is down, when a mirror refuses, when proot cannot launch — and which
 * of those the old prose collapsed into "every mirror is down".
 *
 * Pure decision logic over strings, so every case is the real captured text (from the historical
 * failure log and apt's own wording), not a summary of it.
 */
class UserspaceFailureTest {

    // ------------------------------------------------------------------ the launcher (F2)

    @Test
    fun `a rung that dies with the launcher's exit code is a proot failure, not a mirror one`() {
        // The historical bug in one line: linuxpty.c _exit(127) on exec failure, empty output.
        val failure = UserspaceFailure.fromAptRun("http://ports.ubuntu.com/ubuntu-ports", 127, "")

        assertThat(failure).isInstanceOf(UserspaceFailure.ProotLaunchFailed::class.java)
        assertThat((failure as UserspaceFailure.ProotLaunchFailed).exitCode).isEqualTo(127)
        // The message must name the runtime, never a mirror.
        assertThat(failure.message).contains("proot")
        assertThat(failure.message).doesNotContain("mirror")
    }

    @Test
    fun `exit 126 is the launcher's, and a signal death is not`() {
        assertThat(UserspaceFailure.fromAptRun("http://x.example/ubuntu", 126, "")).isInstanceOf(UserspaceFailure.ProotLaunchFailed::class.java)
        // 128 is a shell's encoding of signal zero, which is not a death by signal: it has always
        // been read on the launcher's side of the line.
        assertThat(UserspaceFailure.fromAptRun("http://x.example/ubuntu", 128, "")).isInstanceOf(UserspaceFailure.ProotLaunchFailed::class.java)
        // 100 is apt's own generic failure — the ladder's business, not the launcher's.
        assertThat(UserspaceFailure.fromAptRun("http://x.example/ubuntu", 100, "E: Failed to fetch …")).isNull()
    }

    @Test
    fun `a child killed by a signal is named by the signal, not blamed on proot`() {
        // The low-memory killer's 137, in the shape the E2E runs produced it: proot started, ran,
        // and its child was SIGKILLed. The old taxonomy called this "proot failed to start", which
        // sent the user to reinstall an app whose runtime was working.
        val killed = UserspaceFailure.fromAptRun("http://x.example/ubuntu", 137, "Killed\n")
        assertThat(killed).isInstanceOf(UserspaceFailure.KilledBySignal::class.java)
        assertThat((killed as UserspaceFailure.KilledBySignal).signal).isEqualTo(9)
        // The sentence says what the user can do about it — memory, not mirrors.
        assertThat(killed.message).contains("memory")
        assertThat(killed.message).doesNotContain("proot failed")
        // 139 is a crash inside a program, which is a bug in that program rather than in Ubuntu.
        val crashed = UserspaceFailure.fromAptRun("http://x.example/ubuntu", 139, "Segmentation fault\n")
        assertThat((crashed as UserspaceFailure.KilledBySignal).signal).isEqualTo(11)
        assertThat(crashed.message).contains("crashed")
        // The last thing the command said still rides along: it usually names which program died.
        assertThat(crashed.message).contains("Segmentation fault")
    }

    // ------------------------------------------------------------------ locks and the database

    @Test
    fun `apt's own lock refusals name the files and the holder`() {
        val failure = UserspaceFailure.fromAptRun(
            "http://x.example/ubuntu",
            100,
            "E: Could not get lock /var/lib/dpkg/lock-frontend. It is held by process 4321 (apt-get)\n" +
                "E: Unable to acquire the dpkg frontend lock (/var/lib/dpkg/lock-frontend), is another process using it?\n",
        )

        assertThat(failure).isInstanceOf(UserspaceFailure.PackageLocksHeld::class.java)
        assertThat((failure as UserspaceFailure.PackageLocksHeld).holderPid).isEqualTo(4321)
        assertThat(failure.locks).contains("/var/lib/dpkg/lock-frontend")
        // Trailing punctuation is not part of the path a user would type.
        assertThat(failure.locks).doesNotContain("/var/lib/dpkg/lock-frontend.")
        assertThat(failure.message).contains("process 4321")
    }

    @Test
    fun `a database dpkg cannot open is the repair pass's subject, not a mirror's`() {
        val failure = UserspaceFailure.fromCommandOutput(
            "Installing base packages",
            2,
            "dpkg: failed to open package info file '/var/lib/dpkg/status' for reading: No such file or directory\n",
        )

        assertThat(failure).isInstanceOf(UserspaceFailure.PackageDbBroken::class.java)
        assertThat(failure!!.message).contains("repair pass")
    }

    // ------------------------------------------------------------------ the clock

    @Test
    fun `an index that expired and one that is not valid yet are told apart`() {
        val expired = UserspaceFailure.fromAptRun(
            "http://x.example/ubuntu",
            100,
            "E: Release file for http://x.example/ubuntu/dists/jammy/InRelease is expired (invalid since 3d 4h).\n",
        )
        assertThat(expired).isInstanceOf(UserspaceFailure.ClockSkew::class.java)
        assertThat((expired as UserspaceFailure.ClockSkew).expired).isTrue()
        assertThat(expired.message).contains("automatic date and time")

        val behind = UserspaceFailure.fromAptRun(
            "http://x.example/ubuntu",
            100,
            "E: Release file for http://x.example/ubuntu/dists/jammy/InRelease is not valid yet (invalid for another 2h).\n",
        )
        assertThat((behind as UserspaceFailure.ClockSkew).expired).isFalse()
        // The clock is nobody's to fix inside the rootfs, and the sentence has to say so.
        assertThat(behind.message).contains("automatic date and time")
    }

    // ------------------------------------------------------------------ the regenerable state

    @Test
    fun `a hash mismatch is a corrupted download, and the indexes are what is cleared`() {
        val failure = UserspaceFailure.fromAptRun(
            "http://x.example/ubuntu",
            100,
            "E: Failed to fetch http://x.example/ubuntu/dists/jammy/main/binary-arm64/Packages.gz  Hash Sum mismatch\n",
        )

        assertThat(failure).isInstanceOf(UserspaceFailure.IndexHashMismatch::class.java)
        assertThat((failure as UserspaceFailure.IndexHashMismatch).uri).isEqualTo("http://x.example/ubuntu")
        assertThat(failure.message).contains("corrupted in transit")
    }

    @Test
    fun `proot's own error text is recognized even with a zero-free exit code`() {
        val failure = UserspaceFailure.fromAptRun("http://x.example/ubuntu", 100, "proot warning: can't sanitize binding …\n")

        assertThat(failure).isInstanceOf(UserspaceFailure.ProotLaunchFailed::class.java)
        assertThat(failure!!.message).contains("proot warning")
        // ptrace refusal (seccomp blocking the trace syscall) is the other proot-only signature.
        assertThat(UserspaceFailure.fromAptRun("http://x.example/ubuntu", 1, "ptrace: Operation not permitted"))
            .isInstanceOf(UserspaceFailure.ProotLaunchFailed::class.java)
    }

    // ------------------------------------------------------------------ DNS (F7, F6)

    @Test
    fun `apt's temporary-failure-resolving text is a DNS failure naming the written resolvers`() {
        val failure = UserspaceFailure.fromAptRun(
            "http://ports.ubuntu.com/ubuntu-ports",
            100,
            "Err:1 http://ports.ubuntu.com/ubuntu-ports jammy InRelease\n" +
                "  Temporary failure resolving 'ports.ubuntu.com'\n",
            dnsServers = listOf("192.168.1.1", "1.1.1.1"),
        )

        assertThat(failure).isInstanceOf(UserspaceFailure.DnsUnresolved::class.java)
        assertThat((failure as UserspaceFailure.DnsUnresolved).servers).containsExactly("192.168.1.1", "1.1.1.1")
        assertThat(failure.message).contains("192.168.1.1")
        // The Err: line is carried as the detail, not the whole transcript.
        assertThat(failure.message).contains("Err:1 http://ports.ubuntu.com/ubuntu-ports")
    }

    // ------------------------------------------------------------------ reachability (F8)

    @Test
    fun `connection refused, timeout, http and tls each keep their own kind`() {
        fun kindOf(output: String): UserspaceFailure.Kind {
            val failure = UserspaceFailure.fromAptRun("http://m.example/ubuntu", 100, output)
            assertThat(failure).isInstanceOf(UserspaceFailure.MirrorUnreachable::class.java)
            return (failure as UserspaceFailure.MirrorUnreachable).kind
        }

        assertThat(kindOf("Could not connect to m.example:80 (10.0.0.1), connection timed out"))
            .isEqualTo(UserspaceFailure.Kind.Timeout)
        assertThat(kindOf("E: Failed to fetch … - connect (111: Connection refused)"))
            .isEqualTo(UserspaceFailure.Kind.Refused)
        assertThat(kindOf("E: The repository '…' does not have a Release file. 404  Not Found"))
            .isEqualTo(UserspaceFailure.Kind.Http(404))
        assertThat(kindOf("Certificate verification failed: The certificate is not trusted."))
            .isEqualTo(UserspaceFailure.Kind.Tls)
    }

    @Test
    fun `an unsigned repository is never classified as unreachable`() {
        val failure = UserspaceFailure.fromAptRun(
            "http://m.example/ubuntu",
            100,
            "W: GPG error: http://m.example/ubuntu jammy InRelease: The following signatures couldn't be verified … NO_PUBKEY 1655A0AB68576280\n",
        )

        assertThat(failure).isInstanceOf(UserspaceFailure.RepositoryUnsigned::class.java)
        assertThat((failure as UserspaceFailure.RepositoryUnsigned).uri).isEqualTo("http://m.example/ubuntu")
    }

    @Test
    fun `evidence nothing recognizes yields null, not a guess`() {
        assertThat(UserspaceFailure.fromAptRun("http://m.example/ubuntu", 100, "E: Some sub-process returned an error\n")).isNull()
    }

    // ------------------------------------------------------------------ whole-system causes

    @Test
    fun `disk and dpkg states are named from install output too`() {
        val disk = UserspaceFailure.fromCommandOutput("Installing base packages", 100, "dpkg: error processing … No space left on device\n")
        assertThat(disk).isInstanceOf(UserspaceFailure.DiskFull::class.java)

        val db = UserspaceFailure.fromCommandOutput(
            "Installing base packages",
            100,
            "E: dpkg was interrupted, you must manually run 'dpkg --configure -a' to correct the problem. \n",
        )
        assertThat(db).isInstanceOf(UserspaceFailure.PackageDbBroken::class.java)
        assertThat(db!!.message).contains("dpkg was interrupted")
    }

    // ------------------------------------------------------------------ the cross-fork message contracts

    @Test
    fun `the runtime storage refusal maps by its message prefix`() {
        val failure = UserspaceFailure.fromMessage("runtime storage not ready: the tmp probe could not write")

        assertThat(failure).isInstanceOf(UserspaceFailure.ProotLaunchFailed::class.java)
        assertThat(failure!!.message).contains("the tmp probe could not write")
        assertThat(failure.message).contains("proot")
    }

    @Test
    fun `the installer's disk-space sentence is carried verbatim`() {
        val failure = UserspaceFailure.fromMessage("Ubuntu needs ~900 MB free; 200 MB available")

        assertThat(failure).isInstanceOf(UserspaceFailure.DiskFull::class.java)
        // The gate already worded it well; the taxonomy must not fork one sentence into two.
        assertThat(failure!!.message).isEqualTo("Ubuntu needs ~900 MB free; 200 MB available")
    }

    @Test
    fun `any other message stays the original exception's`() {
        assertThat(UserspaceFailure.fromMessage("some unrelated IOException")).isNull()
    }

    // ------------------------------------------------------------------ the session-ending rule

    @Test
    fun `only a 127 with no signal makes a session's ending evidence about the userspace`() {
        // The one ending that is: proot ran, and the program it was asked to run was not in the
        // rootfs — a missing `bash`, a missing `sh`, a startup program dpkg needs that is gone. That
        // is precisely the damage the ladder's deeper rungs exist for, and the tab cannot see it: the
        // shell "ran and exited", so it says DISCONNECTED while every new terminal fails the same way.
        assertThat(sessionEndBlamesUserspace(SessionEnd.ShellEnded(GUEST_SHELL_MISSING, null))).isTrue()

        // Deliberately not the fault flag, which is the tempting shortcut: a signal death is that
        // same flag, and 137 is the low-memory killer taking the largest process on the phone. Acting
        // on it would send the user to Repair over an intact userspace — and the probe that
        // adjudicates forks another proot into the same shortage of memory.
        assertThat(sessionEndBlamesUserspace(SessionEnd.ShellEnded(137, "KILL"))).isFalse()
        assertThat(sessionEndBlamesUserspace(SessionEnd.ShellEnded(null, "SEGV"))).isFalse()

        // An ordinary exit is a session doing what it was asked, and `exit 127` typed by hand is
        // indistinguishable from the damage by the status alone — which is why this predicate is only
        // the first of the three conditions the manager requires, and the probe is the second.
        assertThat(sessionEndBlamesUserspace(SessionEnd.ShellEnded(0, null))).isFalse()
        assertThat(sessionEndBlamesUserspace(SessionEnd.ShellEnded(1, null))).isFalse()
        assertThat(sessionEndBlamesUserspace(SessionEnd.ShellEnded(null, null))).isFalse()

        // Every other ending is about a transport, and a local pty has none — these are the remote
        // SSH endings arriving on a host id that happens to be local, which the caller also checks.
        assertThat(sessionEndBlamesUserspace(SessionEnd.Disconnected(11, "bye", byPeer = true))).isFalse()
        assertThat(sessionEndBlamesUserspace(SessionEnd.TransportFailed(IOException("reset")))).isFalse()
        assertThat(sessionEndBlamesUserspace(SessionEnd.NetworkLost)).isFalse()
        assertThat(sessionEndBlamesUserspace(SessionEnd.TransportClosed)).isFalse()
        assertThat(sessionEndBlamesUserspace(SessionEnd.Released)).isFalse()
    }

    // ------------------------------------------------------------------ the base type

    @Test
    fun `every failure is an IOException carrying its cause`() {
        val cause = IOException("the original")
        val failure = UserspaceFailure.ProotLaunchFailed(127, "", cause = cause)

        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(failure.cause).isSameInstanceAs(cause)
        // The controller renders .message verbatim; it must read as a sentence, not a code.
        assertThat(failure.message).contains("failed to start")
    }
}
