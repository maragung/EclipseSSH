package dev.eclipse.ssh.linux

import com.google.common.truth.Truth.assertThat
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
    fun `exit 126 and signal deaths are the launcher's too`() {
        assertThat(UserspaceFailure.fromAptRun("http://x.example/ubuntu", 126, "")).isInstanceOf(UserspaceFailure.ProotLaunchFailed::class.java)
        assertThat(UserspaceFailure.fromAptRun("http://x.example/ubuntu", 137, "")).isInstanceOf(UserspaceFailure.ProotLaunchFailed::class.java)
        // 100 is apt's own generic failure — the ladder's business, not the launcher's.
        assertThat(UserspaceFailure.fromAptRun("http://x.example/ubuntu", 100, "E: Failed to fetch …")).isNull()
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
