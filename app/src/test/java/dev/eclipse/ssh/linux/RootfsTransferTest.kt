package dev.eclipse.ssh.linux

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Importing and exporting against a real state machine: the fixture's rootfs installed by the real
 * installer, a real setup pipeline over a scripted proot, and the real install lock.
 *
 * The promises these tests pin are the ones a user is told in the confirmation dialog: an import
 * replaces what is installed only once a complete, verified copy of the archive is on disk, and a
 * failed one leaves the installed userspace exactly as it was. The second half of every failure
 * test here is the assertion that nothing moved — a refusal that had already deleted the old tree
 * would be the one outcome the dialog exists to rule out.
 */
class RootfsTransferTest {

    private class Harness {
        /**
         * One private directory per harness, with the userspace root one level inside it: the
         * workspace backup sits *beside* the root by design, so a harness that rooted itself
         * directly in the JVM's temp directory would share that file with every other test.
         */
        val dir: File = Files.createTempDirectory("linux-transfer").toFile().apply { deleteOnExit() }
        val rootDir: File = File(dir, "linux")

        val spawner = ScriptedPtySpawner()
        var downloads = 0

        val distro = TestTarballs.fixtureDistro(
            "https://fixtures.invalid/rootfs.tar.gz",
            TestTarballs.sha256(FIXTURE),
        )
        val runtime = ProotRuntime(rootDir, fakeNativeLibraryDir(), spawner)

        /**
         * The one storage manager the whole harness shares, and the pid it records is this JVM's:
         * the lock's liveness check is `/proc`, which the default `android.os.Process.myPid()` —
         * 0 in a JVM test — would make look like a crashed holder and let any taker walk over.
         */
        val storage = RuntimeStorageManager(
            rootDir,
            pidProvider = { ProcessHandle.current().pid().toInt() },
        )
        val installer = RootfsInstaller(
            rootDir,
            distro,
            downloader = { _, target, onChunk ->
                downloads++
                TestTarballs.serving(FIXTURE).download(
                    "https://fixtures.invalid/rootfs.tar.gz",
                    target,
                    onChunk,
                )
            },
            storage = storage,
        )
        val distribution = UbuntuDistributionManager(
            distro,
            runtime,
            appUid = 10150,
            appGid = 10150,
            // Wired as the graph wires it, so the repair pass this harness exercises runs the
            // programme-restore step rather than reporting it as a warning it cannot act on.
            installer = installer,
        )
        val processes = LinuxProcessManager()
        val workspace = LinuxWorkspaceManager(runtime, storage)
        val manager = LinuxUserspaceManager(
            rootDir,
            distro,
            runtime,
            installer,
            distribution,
            processes,
            workspace,
            // Defaulted, so it resolves to the storage manager's own definition — the file this
            // harness isolates by rooting itself one directory down.
            storage = storage,
        )
        val transfer = RootfsTransfer(rootDir, manager, distro, storage = storage)

        val rootfs: File get() = storage.rootfsDir
    }

    companion object {
        private val FIXTURE: File by lazy {
            TestTarballs.writeRootfsFixture(
                Files.createTempDirectory("linux-transfer-fixture").toFile().resolve("rootfs.tar.gz"),
            )
        }

        /** No fixture here can reach this: a padded tree unpacks to far more than ten times itself. */
        private const val GENEROUS_BUDGET = 64L * 1024 * 1024
    }

    // ---------------------------------------------------------------- the archives under test

    /**
     * The pinned fixture unpacked to a tree — what the dressing helpers below start from, so the
     * archive they produce is one the setup pipeline can really be run over.
     */
    private suspend fun unpackedFixture(): File {
        val tree = File(
            Files.createTempDirectory("transfer-archive").toFile().apply { deleteOnExit() },
            "tree",
        )
        RootfsArchive(tree).extract(FIXTURE.inputStream(), GENEROUS_BUDGET)
        return tree
    }

    private suspend fun exported(tree: File): ByteArray =
        ByteArrayOutputStream().also { RootfsArchive(tree).export(it) }.toByteArray()

    /**
     * An installable archive dressed so an import's effect is visible afterwards: one marker file
     * and one workspace file that the tree already installed does not have.
     */
    private suspend fun dressedArchive(): ByteArray {
        val tree = unpackedFixture()
        File(tree, "etc/imported-marker").writeText("imported\n")
        File(tree, "home/ubuntu/workspace").mkdirs()
        File(tree, "home/ubuntu/workspace/imported.txt").writeText("from the archive\n")
        return exported(tree)
    }

    /** An archive of another Ubuntu release: the fixture with its os-release rewritten. */
    private suspend fun otherReleaseArchive(): ByteArray {
        val tree = unpackedFixture()
        File(tree, "etc/os-release").writeText("ID=ubuntu\nVERSION_CODENAME=noble\n")
        return exported(tree)
    }

    /**
     * What an archive unpacks to, summed from its own headers — passed as the archive's size.
     *
     * It is deliberately not the archive's length on disk: the fixture is a padded one, three
     * mebibytes of zeros that gzip to a few hundred bytes, so its compressed size says nothing about
     * the ratio the expansion budget is built on. A real Ubuntu Base tarball is 28 MB unpacking to
     * 66 MB, and this is the size a real archive of this tree would carry.
     */
    private fun expandedBytes(bytes: ByteArray): Long =
        openTarStream(ByteArrayInputStream(bytes), 64 * 1024).use { tar ->
            generateSequence { tar.nextTarEntry }.sumOf { if (it.size > 0) it.size else 0L }
        }

    // ---------------------------------------------------------------- export

    @Test
    fun `an export writes the installed tree out through the lock and reports its progress`() = runTest {
        val harness = Harness()
        harness.manager.install()
        val output = WatchedOutput { harness.transfer.state.value }

        harness.transfer.exportTo(output)

        // The first two bytes are what makes it a `.tar.gz` a user can hand back to Import — and
        // what the import's own gzip sniff looks for before it trusts the stream as tar.
        val bytes = output.toByteArray()
        // As unsigned ints: a Byte's own `isEqualTo` is not a Truth subject, and 0x8b is negative
        // as a signed byte, so the mask is what makes the byte read as the constant it is.
        assertThat(bytes[0].toInt() and 0xFF).isEqualTo(0x1f)
        assertThat(bytes[1].toInt() and 0xFF).isEqualTo(0x8b)
        val names = mutableListOf<String>()
        openTarStream(ByteArrayInputStream(bytes), 64 * 1024).use { tar ->
            while (true) {
                val entry = tar.nextTarEntry ?: break
                names += entry.name
            }
        }
        assertThat(names).containsAtLeast("bin/bash", "etc/passwd")

        // Every write happened under the transfer's own Exporting state, read from inside the write:
        // a StateFlow conflates, so a collector scheduled on the test dispatcher would see the
        // final Idle and nothing else. This is what the progress row reads.
        assertThat(output.seen).isNotEmpty()
        assertThat(output.seen.filterNot { it is RootfsTransferState.Exporting }).isEmpty()
        assertThat(harness.transfer.state.value).isEqualTo(RootfsTransferState.Idle)
        assertThat(harness.storage.readInstallLock()).isNull()
    }

    @Test
    fun `an export refuses while an install holds the lock, and leaves that lock alone`() = runTest {
        val harness = Harness()
        harness.manager.install()
        harness.storage.acquireInstallLock(harness.distro.id, "install")

        val failure = runCatching { harness.transfer.exportTo(ByteArrayOutputStream()) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure!!.message).contains("already in progress")
        // Taking a lock someone else holds is not permission to release theirs on the way out.
        assertThat(harness.storage.readInstallLock()?.phase).isEqualTo("install")
        assertThat(harness.transfer.state.value).isEqualTo(RootfsTransferState.Idle)
    }

    // ---------------------------------------------------------------- import

    @Test
    fun `an import replaces the installed userspace and hands it to the setup pipeline`() = runTest {
        val harness = Harness()
        harness.manager.install()
        assertThat(harness.downloads).isEqualTo(1)
        // Two things the imported archive must replace: the workspace the installed userspace holds,
        // and the snapshot a keep-workspace uninstall left parked for the next install to restore.
        File(harness.rootfs, "home/ubuntu/workspace/old.txt").writeText("the old workspace\n")
        harness.storage.workspaceBackupFile.writeText("a stale snapshot\n")
        val bytes = dressedArchive()

        harness.transfer.importFrom(ByteArrayInputStream(bytes), expandedBytes(bytes))

        assertThat(harness.manager.state.value).isEqualTo(LinuxUserspaceState.Stopped)
        assertThat(File(harness.rootfs, "etc/imported-marker").readText()).isEqualTo("imported\n")
        assertThat(File(harness.rootfs, "home/ubuntu/workspace/imported.txt").readText())
            .isEqualTo("from the archive\n")
        // The archive's workspace is what the user asked for by importing it; the parked snapshot is
        // what they left behind when they uninstalled the userspace that held the old one.
        assertThat(File(harness.rootfs, "home/ubuntu/workspace/old.txt").exists()).isFalse()
        assertThat(harness.storage.workspaceBackupFile.exists()).isFalse()
        // The handoff re-runs setup over the swapped-in tree — no download, no re-extraction, and the
        // health probe still the last word on whether it is installed.
        assertThat(harness.downloads).isEqualTo(1)
        assertThat(harness.spawner.commands).contains("whoami")
        // Nothing of the import is left behind, and the lock it took is released.
        assertThat(harness.transfer.stagingDir.exists()).isFalse()
        // The installed flag lives at the *userspace root*, never inside the rootfs — which is why an
        // archive carries no record of whether it was installed on the device it came from.
        assertThat(harness.storage.rootfsDir.resolve("state.properties").exists()).isFalse()
        assertThat(harness.rootDir.resolve("state.properties").isFile).isTrue()
        assertThat(harness.storage.readInstallLock()).isNull()
        assertThat(harness.transfer.state.value).isEqualTo(RootfsTransferState.Idle)
        // The installer's own staging directory was never involved: sharing it would have made the
        // installed tree report itself as not extracted for the length of the import.
        assertThat(harness.storage.stagingDir.exists()).isFalse()
    }

    @Test
    fun `an import onto a device with nothing installed installs it without downloading`() = runTest {
        val harness = Harness()
        assertThat(harness.manager.state.value).isEqualTo(LinuxUserspaceState.NotInstalled)
        val bytes = dressedArchive()

        harness.transfer.importFrom(ByteArrayInputStream(bytes), expandedBytes(bytes))

        // The one path that produces an installed userspace without downloading a byte of it: the
        // setup pipeline runs, the health probe passes, and the state machine ends where an install
        // ends.
        assertThat(harness.manager.state.value).isEqualTo(LinuxUserspaceState.Stopped)
        assertThat(harness.downloads).isEqualTo(0)
        assertThat(harness.spawner.commands).contains("whoami")
    }

    @Test
    fun `a failed import leaves the installed userspace exactly as it was`() = runTest {
        val harness = Harness()
        harness.manager.install()
        val passwd = File(harness.rootfs, "etc/passwd").readText()
        val escaping = TestTarballs.writeEscapingFixture(File(harness.dir, "escaping.tar.gz"))

        val failure = runCatching {
            harness.transfer.importFrom(escaping.inputStream(), escaping.length())
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(failure!!.message).contains("escapes the extraction directory")
        // The tree that is installed is byte-for-byte the one that was there: the archive never got
        // as far as the swap, so there is nothing to repair.
        assertThat(harness.manager.state.value).isEqualTo(LinuxUserspaceState.Stopped)
        assertThat(File(harness.rootfs, "etc/passwd").readText()).isEqualTo(passwd)
        assertThat(harness.rootfs.resolve("bin/bash").isFile).isTrue()
        assertThat(harness.downloads).isEqualTo(1)
        // The escape target was one directory above the staging tree, which is inside the userspace
        // root — and nothing was written there.
        assertThat(File(harness.rootDir, "escaped.txt").exists()).isFalse()
        assertThat(harness.transfer.stagingDir.exists()).isFalse()
        assertThat(harness.storage.readInstallLock()).isNull()
    }

    @Test
    fun `an import refuses an archive that is not a userspace`() = runTest {
        val harness = Harness()
        harness.manager.install()
        val sparse = TestTarballs.writeSparseFixture(File(harness.dir, "sparse.tar.gz"))

        val failure = runCatching {
            harness.transfer.importFrom(sparse.inputStream(), sparse.length())
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(failure!!.message).contains("not a Linux userspace")
        assertThat(harness.manager.state.value).isEqualTo(LinuxUserspaceState.Stopped)
        assertThat(harness.downloads).isEqualTo(1)
    }

    @Test
    fun `an import refuses an archive of another Ubuntu release, before anything is swapped`() = runTest {
        val harness = Harness()
        harness.manager.install()
        val passwd = File(harness.rootfs, "etc/passwd").readText()
        val bytes = otherReleaseArchive()

        val failure = runCatching {
            harness.transfer.importFrom(ByteArrayInputStream(bytes), expandedBytes(bytes))
        }.exceptionOrNull()

        // The setup pipeline rewrites apt sources for *this* device's release, so a tree of another
        // one becomes a rootfs that installs nothing and cannot say why.
        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(failure!!.message).contains("noble")
        assertThat(harness.manager.state.value).isEqualTo(LinuxUserspaceState.Stopped)
        assertThat(File(harness.rootfs, "etc/passwd").readText()).isEqualTo(passwd)
        assertThat(harness.transfer.stagingDir.exists()).isFalse()
    }

    @Test
    fun `an import refuses while another process holds the install lock`() = runTest {
        val harness = Harness()
        harness.manager.install()
        // A live holder: the lock is this JVM's own pid, which is what the app records too.
        harness.storage.acquireInstallLock(harness.distro.id, "install")
        val bytes = dressedArchive()

        val failure = runCatching {
            harness.transfer.importFrom(ByteArrayInputStream(bytes), expandedBytes(bytes))
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure!!.message).contains("already in progress")
        assertThat(harness.manager.state.value).isEqualTo(LinuxUserspaceState.Stopped)
        assertThat(harness.downloads).isEqualTo(1)
        // The refusal happened before the staging tree was even created — and the holder's lock is
        // still theirs.
        assertThat(harness.transfer.stagingDir.exists()).isFalse()
        assertThat(harness.storage.readInstallLock()?.phase).isEqualTo("install")
    }

    // ---------------------------------------------------------------- cancellation

    @Test
    fun `a cancelled import leaves the installed userspace alone`() = runTest {
        val harness = Harness()
        harness.manager.install()
        val passwd = File(harness.rootfs, "etc/passwd").readText()
        val bytes = dressedArchive()
        // Cancelled from inside the archive's first read: the request is raised while the archive is
        // still unpacking, which is the side of the swap a Cancel tap from the screen can reach. A
        // one-shot, so the transfer sees exactly one cancellation and not one per buffer.
        var onFirstRead: (() -> Unit)? = null
        val source = object : ByteArrayInputStream(bytes) {
            // Both read forms, because the byte-at-a-time one is not routed through the ranged one:
            // an override that missed the shape the tar reader happens to use would quietly turn this
            // into a test of an import nobody cancelled.
            override fun read(): Int {
                onFirstRead?.invoke()
                onFirstRead = null
                return super.read()
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                onFirstRead?.invoke()
                onFirstRead = null
                return super.read(buffer, offset, length)
            }
        }

        val running = launch { harness.transfer.importFrom(source, expandedBytes(bytes)) }
        onFirstRead = { running.cancel() }
        // Joined, not cancelled from here: the cancellation is raised from inside the import, and a
        // `cancelAndJoin` on the next line would cancel a coroutine the test dispatcher has not
        // started yet. The body would never run, the trigger would never fire, and every assertion
        // below would hold for an import that did nothing at all.
        running.join()

        // Nothing moved: the tree that was installed is the tree that is installed, and the archive
        // never reached the swap, which is what the "the flag stays false" assertion is about — it is
        // the fact the screen's cancellation notice is built from.
        assertThat(harness.transfer.importSwapped).isFalse()
        assertThat(harness.manager.state.value).isEqualTo(LinuxUserspaceState.Stopped)
        assertThat(File(harness.rootfs, "etc/passwd").readText()).isEqualTo(passwd)
        assertThat(File(harness.rootfs, "etc/imported-marker").exists()).isFalse()
        assertThat(harness.downloads).isEqualTo(1)
        // Nor is anything left behind to notice: no half-unpacked staging tree, no lock held by a
        // coroutine that no longer exists.
        assertThat(harness.transfer.stagingDir.exists()).isFalse()
        assertThat(harness.storage.readInstallLock()).isNull()
        assertThat(harness.transfer.state.value).isEqualTo(RootfsTransferState.Idle)
    }

    @Test
    fun `a cancellation that lands after the swap does not undo the import`() = runTest {
        val harness = Harness()
        harness.manager.install()
        val bytes = dressedArchive()
        // Cancelled from inside the setup pipeline the import handed off to — the far side of the
        // swap, where the tree on disk is already the archive's and there is nothing to roll back to.
        var importJob: Job? = null
        val answering = harness.spawner.respond
        harness.spawner.respond = { command ->
            if (command == "whoami") importJob?.cancel()
            answering(command)
        }

        val running = launch { harness.transfer.importFrom(ByteArrayInputStream(bytes), expandedBytes(bytes)) }
        importJob = running
        // Joined, not cancelled from here: this cancellation is raised by the setup pipeline the
        // import hands off to — the far side of the swap — and cancelling the job from the test would
        // stop the import before it unpacked anything, which is the ending the test above covers.
        running.join()

        // The import stands: the archive's files are installed, the handoff ran to the end despite the
        // cancellation, and the flag says so — which is the one thing that lets the screen tell this
        // ending from the one above.
        assertThat(harness.transfer.importSwapped).isTrue()
        assertThat(harness.manager.state.value).isEqualTo(LinuxUserspaceState.Stopped)
        assertThat(File(harness.rootfs, "etc/imported-marker").readText()).isEqualTo("imported\n")
        assertThat(harness.spawner.commands).contains("whoami")
        assertThat(harness.downloads).isEqualTo(1)
        assertThat(harness.transfer.stagingDir.exists()).isFalse()
        assertThat(harness.storage.readInstallLock()).isNull()
        assertThat(harness.transfer.state.value).isEqualTo(RootfsTransferState.Idle)
    }

    @Test
    fun `the expansion budget is a multiple of the archive, and an archive past it is refused`() = runTest {
        val harness = Harness()
        // No pin behind this path, so the archive's own reported size is the basis — the same shape
        // the pinned install's budget has, at the same multiple.
        assertThat(harness.transfer.importBudgetBytes(1024L * 1024)).isEqualTo(10L * 1024 * 1024)
        // A provider that will not report a size leaves the fixed ceiling standing. That is no
        // protection against a bomb, and the honest failure there is the write that runs out of
        // space, with the staging tree reclaimable.
        assertThat(harness.transfer.importBudgetBytes(0L)).isEqualTo(8L * 1024 * 1024 * 1024)
        assertThat(harness.transfer.importBudgetBytes(-1L)).isEqualTo(8L * 1024 * 1024 * 1024)

        val bytes = dressedArchive()
        val failure = runCatching {
            harness.transfer.importFrom(ByteArrayInputStream(bytes), archiveBytes = 1024L)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(failure!!.message).contains("expands beyond the expected size")
        assertThat(harness.manager.state.value).isEqualTo(LinuxUserspaceState.NotInstalled)
        assertThat(harness.transfer.stagingDir.exists()).isFalse()
        assertThat(harness.storage.readInstallLock()).isNull()
    }
}

/**
 * An output stream that reads the transfer's own state on every write, and keeps the bytes.
 *
 * The only way to see the progress an export publishes *while* it writes: a StateFlow conflates, so
 * a collector on the test dispatcher observes the final `Idle` and nothing in between; reading the
 * state from inside the write is the same read the settings screen's bar makes, taken at the same
 * moment. It is a `ByteArrayOutputStream` so the same object also carries what was written — the
 * archive's own bytes, which the gzip header and the entry names are read back out of.
 */
private class WatchedOutput(private val state: () -> RootfsTransferState) : ByteArrayOutputStream() {
    val seen = mutableListOf<RootfsTransferState>()

    override fun write(b: Int) {
        seen += state()
        super.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        seen += state()
        super.write(b, off, len)
    }
}
