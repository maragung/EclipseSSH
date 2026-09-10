package dev.eclipse.ssh.ssh

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.client.SftpClient
import org.apache.sshd.sftp.client.SftpClientFactory
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.After
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test

/**
 * The cross-host transfer engine, end to end against two embedded SSH servers in this JVM.
 *
 * Two servers, because everything this class does happens *between* them: the collision policies
 * are decisions about the destination's existing contents, the failure list is about which entries
 * survived, and neither is provable with one server talking to itself over a loopback client. Both
 * are MINA servers with a real SFTP subsystem and a [VirtualFileSystemFactory] jail over their own
 * temp directory, so a path handed to the engine is an ordinary file this test can also seed with
 * `Files` and read back to compare byte for byte.
 *
 * No Robolectric: [CrossHostTransfer] has no Android imports, and driving it through a MINA client
 * directly is the whole of what it needs from the world. That keeps this suite off the main looper
 * entirely while it still shares one small JVM with every other test, which is why every session
 * opened here is closed in [endEverySession] - a live session is a GC root, and a suite that
 * accumulates them starts missing deadlines on a shared two-core box.
 */
class CrossHostTransferTest {

    // ---------------------------------------------------------------- the tests

    /** A single file arrives byte for byte, and the result says exactly that. */
    @Test
    fun aSingleFileIsCopiedByteForByte() = runBlocking {
        val bytes = ByteArray(300_000) { (it % 251).toByte() }
        Files.write(sourceRoot.resolve("payload.bin"), bytes)

        val result = engine.transfer(
            sourceSftp(), destSftp(), sourceRoot.resolve("payload.bin").toString(), destRoot.toString(),
        )

        assertThat(result.entriesCopied).isEqualTo(1)
        assertThat(result.entriesSkipped).isEqualTo(0)
        assertThat(result.entriesFailed).isEqualTo(0)
        assertThat(result.failures).isEmpty()
        assertThat(result.bytesTransferred).isEqualTo(bytes.size.toLong())
        assertSameBytes(bytes, Files.readAllBytes(destRoot.resolve("payload.bin")))
    }

    /** A folder is recreated with its nesting and its empty directories, nothing invented or lost. */
    @Test
    fun aNestedFolderIsRecreatedWithItsEmptyDirectories() = runBlocking {
        val a = "alpha\n".toByteArray()
        val b = ByteArray(150_000) { (it % 249).toByte() }
        Files.createDirectories(sourceRoot.resolve("site/sub"))
        Files.write(sourceRoot.resolve("site/a.txt"), a)
        Files.write(sourceRoot.resolve("site/sub/b.bin"), b)
        Files.createDirectories(sourceRoot.resolve("site/empty"))

        val result = engine.transfer(sourceSftp(), destSftp(), sourceRoot.resolve("site").toString(), destRoot.toString())

        // The folder itself, a.txt, sub, empty and sub/b.bin: five entries landed.
        assertThat(result.entriesCopied).isEqualTo(5)
        assertThat(result.entriesSkipped).isEqualTo(0)
        assertThat(result.entriesFailed).isEqualTo(0)
        assertThat(result.bytesTransferred).isEqualTo((a.size + b.size).toLong())
        assertSameBytes(a, Files.readAllBytes(destRoot.resolve("site/a.txt")))
        assertSameBytes(b, Files.readAllBytes(destRoot.resolve("site/sub/b.bin")))
        // An empty directory is a real entry a user made on purpose; a copy that dropped it would
        // read as files having gone missing.
        assertThat(Files.isDirectory(destRoot.resolve("site/empty"))).isTrue()
    }

    /** Progress climbs monotonically and ends exactly at the total the pre-walk promised. */
    @Test
    fun progressIsMonotonicAndEndsAtThePreWalkedTotal() = runBlocking {
        Files.createDirectories(sourceRoot.resolve("batch"))
        Files.write(sourceRoot.resolve("batch/one.bin"), ByteArray(150_000))
        // Zero bytes, deliberately: an empty file pumps no chunks, so without the closing report a
        // watcher could not distinguish a finished copy from one that died beside it.
        Files.write(sourceRoot.resolve("batch/empty.txt"), ByteArray(0))
        Files.write(sourceRoot.resolve("batch/two.bin"), ByteArray(50_000))

        val reports = mutableListOf<Pair<Long, Long>>()
        val result = engine.transfer(
            sourceSftp(), destSftp(), sourceRoot.resolve("batch").toString(), destRoot.toString(),
        ) { transferred, total -> reports += transferred to total }

        val total = 200_000L
        assertThat(result.bytesTransferred).isEqualTo(total)
        assertThat(reports).isNotEmpty()
        assertThat(reports.map { it.second }.toSet()).containsExactly(total)
        assertThat(reports.first().second).isEqualTo(total)
        var previous = 0L
        reports.forEach { (transferred, _) ->
            assertThat(transferred).isAtLeast(previous)
            previous = transferred
        }
        assertThat(reports.last().first).isEqualTo(total)
    }

    /**
     * A cancelled copy stops inside one chunk of the cancellation, and leaves a destination that is
     * still a directory anybody can list - a truncated file, not a corrupted filesystem view.
     */
    @Test
    fun cancellingMidCopyLeavesATruncatedButListableDestination() = runBlocking {
        val size = 16 * 1024 * 1024
        Files.write(sourceRoot.resolve("huge.bin"), ByteArray(size))

        val firstChunk = CompletableDeferred<Unit>()
        val job = launch {
            engine.transfer(sourceSftp(), destSftp(), sourceRoot.resolve("huge.bin").toString(), destRoot.toString()) { bytes, _ ->
                if (bytes > 0) firstChunk.complete(Unit)
            }
        }
        firstChunk.await()
        job.cancelAndJoin()

        assertThat(job.isCancelled).isTrue()
        val names = destSftp().readDir(destRoot.toString())
            .map { it.filename }
            .filter { it != "." && it != ".." }
        assertThat(names).containsExactly("huge.bin")
        val partial = Files.size(destRoot.resolve("huge.bin"))
        assertThat(partial).isGreaterThan(0L)
        // Anywhere short of the full file proves the cancellation was honoured between chunks rather
        // than after the copy; the exact offset is the race's, not the test's.
        assertThat(partial).isLessThan(size.toLong())
    }

    /** OVERWRITE replaces a file the destination already holds. */
    @Test
    fun overwriteReplacesAnExistingDestinationFile() = runBlocking {
        Files.write(sourceRoot.resolve("cfg.conf"), "new setting".toByteArray())
        Files.write(destRoot.resolve("cfg.conf"), "old setting".toByteArray())

        val result = engine.transfer(
            sourceSftp(), destSftp(), sourceRoot.resolve("cfg.conf").toString(), destRoot.toString(),
            collision = CrossHostTransfer.CollisionPolicy.OVERWRITE,
        )

        assertThat(result.entriesCopied).isEqualTo(1)
        assertThat(result.entriesSkipped).isEqualTo(0)
        assertThat(String(Files.readAllBytes(destRoot.resolve("cfg.conf")))).isEqualTo("new setting")
    }

    /** SKIP leaves the destination exactly as it was and reports the entry as skipped. */
    @Test
    fun skipLeavesAnExistingDestinationFileUntouched() = runBlocking {
        Files.write(sourceRoot.resolve("cfg.conf"), "new setting".toByteArray())
        Files.write(destRoot.resolve("cfg.conf"), "old setting".toByteArray())

        val result = engine.transfer(
            sourceSftp(), destSftp(), sourceRoot.resolve("cfg.conf").toString(), destRoot.toString(),
            collision = CrossHostTransfer.CollisionPolicy.SKIP,
        )

        assertThat(result.entriesCopied).isEqualTo(0)
        assertThat(result.entriesSkipped).isEqualTo(1)
        assertThat(result.entriesFailed).isEqualTo(0)
        assertThat(result.bytesTransferred).isEqualTo(0)
        assertThat(String(Files.readAllBytes(destRoot.resolve("cfg.conf")))).isEqualTo("old setting")
    }

    /**
     * RENAME keeps what is already there and takes the first free number - and the second one the
     * next time, so repeating a copy never touches what an earlier copy landed.
     */
    @Test
    fun renameKeepsBothFilesAndPicksTheFirstFreeNumber() = runBlocking {
        Files.write(sourceRoot.resolve("cfg.conf"), "new setting".toByteArray())
        Files.write(destRoot.resolve("cfg.conf"), "old setting".toByteArray())

        val first = engine.transfer(
            sourceSftp(), destSftp(), sourceRoot.resolve("cfg.conf").toString(), destRoot.toString(),
            collision = CrossHostTransfer.CollisionPolicy.RENAME,
        )
        val second = engine.transfer(
            sourceSftp(), destSftp(), sourceRoot.resolve("cfg.conf").toString(), destRoot.toString(),
            collision = CrossHostTransfer.CollisionPolicy.RENAME,
        )

        assertThat(first.entriesCopied).isEqualTo(1)
        assertThat(second.entriesCopied).isEqualTo(1)
        val names = destSftp().readDir(destRoot.toString())
            .map { it.filename }
            .filter { it != "." && it != ".." }
        assertThat(names).containsExactly("cfg.conf", "cfg.conf (1)", "cfg.conf (2)")
        assertThat(String(Files.readAllBytes(destRoot.resolve("cfg.conf")))).isEqualTo("old setting")
        assertThat(String(Files.readAllBytes(destRoot.resolve("cfg.conf (1)")))).isEqualTo("new setting")
        assertThat(String(Files.readAllBytes(destRoot.resolve("cfg.conf (2)")))).isEqualTo("new setting")
    }

    /** RENAME moves the whole folder - and its subtree - when the folder's own name is taken. */
    @Test
    fun renameMovesAWholeFolderWhenItsNameIsTaken() = runBlocking {
        Files.createDirectories(sourceRoot.resolve("notes"))
        Files.write(sourceRoot.resolve("notes/a.txt"), "the new note".toByteArray())
        Files.createDirectories(destRoot.resolve("notes"))
        Files.write(destRoot.resolve("notes/older.txt"), "the old note".toByteArray())

        val result = engine.transfer(
            sourceSftp(), destSftp(), sourceRoot.resolve("notes").toString(), destRoot.toString(),
            collision = CrossHostTransfer.CollisionPolicy.RENAME,
        )

        assertThat(result.entriesCopied).isEqualTo(2) // the folder and a.txt
        // What was already there is untouched, and the copy landed whole under the renamed folder.
        assertThat(String(Files.readAllBytes(destRoot.resolve("notes/older.txt")))).isEqualTo("the old note")
        assertThat(String(Files.readAllBytes(destRoot.resolve("notes (1)/a.txt")))).isEqualTo("the new note")
    }

    /**
     * A file the source account cannot read fails alone: it is reported, the rest of the tree is
     * still copied, and the walk is not aborted the way `copyAcross` aborted it.
     *
     * The unreadability is real - POSIX mode 000 on the seeded file, so the denial comes from the
     * kernel through the server's own file system, not from anything staged in the client.
     */
    @Test
    fun anUnreadableFileFailsAloneAndTheWalkContinues() = runBlocking {
        Files.createDirectories(sourceRoot.resolve("pack/sub"))
        Files.write(sourceRoot.resolve("pack/first.txt"), "first".toByteArray())
        Files.write(sourceRoot.resolve("pack/secret.bin"), "classified".toByteArray())
        Files.setPosixFilePermissions(
            sourceRoot.resolve("pack/secret.bin"),
            PosixFilePermissions.fromString("---------"),
        )
        Files.write(sourceRoot.resolve("pack/sub/later.txt"), "later".toByteArray())

        val result = engine.transfer(sourceSftp(), destSftp(), sourceRoot.resolve("pack").toString(), destRoot.toString())

        // pack itself, first.txt, sub and sub/later.txt landed; secret.bin alone failed.
        assertThat(result.entriesCopied).isEqualTo(4)
        assertThat(result.entriesFailed).isEqualTo(1)
        assertThat(result.entriesSkipped).isEqualTo(0)
        val failure = result.failures.single()
        assertThat(failure.path).endsWith("secret.bin")
        // The server's own sentence, shown to a user one day: it must be something, and readable.
        assertThat(failure.reason).isNotEmpty()
        assertThat(String(Files.readAllBytes(destRoot.resolve("pack/first.txt")))).isEqualTo("first")
        assertThat(String(Files.readAllBytes(destRoot.resolve("pack/sub/later.txt")))).isEqualTo("later")
    }

    /**
     * The depth ceiling stops the copy before anything lands, with the walk's own message.
     *
     * The entry-count ceiling is the same mechanism in the same pre-walk (a tree of more than
     * 50 000 entries is refused by `listTree`), and is not repeated here: proving it means creating
     * 50 001 real files, and a suite that shares one JVM with every other test should not spend
     * that to check a line the pre-walk already owns.
     */
    @Test
    fun aTreePastTheDepthCeilingIsRefusedBeforeAnythingIsCopied() = runBlocking {
        var deep = sourceRoot.resolve("deep")
        Files.createDirectories(deep)
        repeat(70) {
            deep = deep.resolve("d")
            Files.createDirectories(deep)
        }
        Files.write(deep.resolve("bottom.txt"), "never copied".toByteArray())

        val thrown = runCatching {
            engine.transfer(sourceSftp(), destSftp(), sourceRoot.resolve("deep").toString(), destRoot.toString())
        }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(IOException::class.java)
        assertThat(thrown?.message).contains("levels")
        // Refused *before* anything was copied: the destination has no half-built tree to clean up.
        assertThat(Files.exists(destRoot.resolve("deep"))).isFalse()
    }

    /** A source that has vanished since the user picked it is a result, not a crash. */
    @Test
    fun aMissingSourcePathIsACleanFailureResult() = runBlocking {
        val missing = sourceRoot.resolve("not-there.bin").toString()

        val result = engine.transfer(sourceSftp(), destSftp(), missing, destRoot.toString())

        assertThat(result.entriesCopied).isEqualTo(0)
        assertThat(result.entriesSkipped).isEqualTo(0)
        assertThat(result.entriesFailed).isEqualTo(1)
        assertThat(result.bytesTransferred).isEqualTo(0)
        val failure = result.failures.single()
        assertThat(failure.path).isEqualTo(missing)
        assertThat(failure.reason).isNotEmpty()
    }

    // ---------------------------------------------------------------- driving the servers

    /**
     * Ends every session this test opened, before the next one starts.
     *
     * SFTP client first, then its session: the channel is a child of the transport, and closing
     * them the other way round makes the channel close race a transport already tearing down. Both
     * halves matter to the suite - a session left open keeps the server's subsystem thread alive,
     * and a live thread is a GC root on a JVM this suite shares with every other test.
     */
    @After
    fun endEverySession() {
        openedClients.forEach { runCatching { it.close() } }
        openedSessions.forEach { runCatching { it.close(false) } }
        openedClients.clear()
        openedSessions.clear()
    }

    private fun sourceSftp(): SftpClient = openSftp(sourcePort)

    private fun destSftp(): SftpClient = openSftp(destPort)

    private fun openSftp(port: Int): SftpClient {
        val session = client.connect(USER, LOOPBACK, port).verify(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS).session
        session.addPasswordIdentity(PASSWORD)
        session.auth().verify(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        openedSessions += session
        return SftpClientFactory.instance().createSftpClient(session).also { openedClients += it }
    }

    /**
     * Compares two file bodies and reports *where* they differ.
     *
     * Not `assertThat(actual).isEqualTo(expected)`: Truth renders a byte array by printing every
     * element, so a mismatch on a 300 KB file produces a failure message megabytes long. The first
     * differing offset is the whole diagnosis - a hole starts where the copy restarted, a
     * duplicated block one buffer later - so that is what this prints.
     */
    private fun assertSameBytes(expected: ByteArray, actual: ByteArray) {
        val firstDifference = (0 until minOf(expected.size, actual.size))
            .firstOrNull { expected[it] != actual[it] }
        if (firstDifference == null && expected.size == actual.size) return
        val detail = when {
            firstDifference != null ->
                "first differs at byte $firstDifference (expected 0x%02X, got 0x%02X)"
                    .format(expected[firstDifference], actual[firstDifference])
            else -> "identical up to the shorter length, then truncated or padded"
        }
        throw AssertionError(
            "destination does not match the source: expected ${expected.size} bytes, got " +
                "${actual.size}; $detail",
        )
    }

    private val openedSessions = mutableListOf<ClientSession>()
    private val openedClients = mutableListOf<SftpClient>()

    companion object {
        /**
         * The engine under test, with the same collaborator it is constructed with in production.
         * No DI container here: the class has no other dependencies, and a hand-built pair says
         * the same thing a Hilt module would.
         */
        private val engine = CrossHostTransfer(SftpDirectoryService())

        /**
         * Local test credentials, and only local: both servers below are bound to loopback inside
         * this JVM, authenticate nothing but this pair, and die with the process.
         */
        private const val LOOPBACK = "127.0.0.1"
        private const val USER = "testuser"
        private const val PASSWORD = "testpass123"

        /**
         * Generous, because the suite shares one JVM: a loopback handshake that takes milliseconds
         * alone should not fail a test on a loaded machine, and nothing here is relaxed by the
         * extra time since every wait ends the moment its condition holds.
         */
        private const val CONNECT_TIMEOUT_SECONDS = 30L

        private lateinit var sourceRoot: Path
        private lateinit var destRoot: Path
        private var sourcePort = 0
        private var destPort = 0
        private lateinit var sourceServer: SshServer
        private lateinit var destServer: SshServer
        private lateinit var client: SshClient

        @JvmStatic
        @BeforeClass
        fun startServers() {
            sourceRoot = Files.createTempDirectory("eclipse-cross-host-source")
            destRoot = Files.createTempDirectory("eclipse-cross-host-dest")
            sourceServer = server(sourceRoot)
            destServer = server(destRoot)
            sourcePort = (sourceServer.boundAddresses.first() as InetSocketAddress).port
            destPort = (destServer.boundAddresses.first() as InetSocketAddress).port
            client = SshClient.setUpDefaultClient().apply { start() }
        }

        @JvmStatic
        @AfterClass
        fun stopServers() {
            runCatching { client.stop() }
            runCatching { sourceServer.stop(true) }
            runCatching { destServer.stop(true) }
        }

        /**
         * A started SFTP-only server on a kernel-assigned port with its own host key.
         *
         * Port 0 because Gradle runs the debug and release unit-test tasks in separate JVMs and
         * they overlap: a fixed port means the loser of the bind runs its whole suite against a
         * server that never started. Its own key file per server so trusting one cannot disturb
         * the other's identity. No shell factory: nothing in this suite opens one, and a server
         * without one is exactly what both halves of the transfer need it to be - a file system.
         */
        private fun server(root: Path): SshServer =
            SshServer.setUpDefaultServer().apply {
                port = 0
                keyPairProvider = SimpleGeneratorHostKeyProvider(
                    Files.createTempDirectory("cross-host-hostkey").resolve("hostkey.ser"),
                )
                passwordAuthenticator = PasswordAuthenticator { user, password, _ ->
                    user == USER && password == PASSWORD
                }
                fileSystemFactory = VirtualFileSystemFactory(root.toAbsolutePath())
                subsystemFactories = Collections.singletonList(SftpSubsystemFactory())
                start()
            }
    }
}
