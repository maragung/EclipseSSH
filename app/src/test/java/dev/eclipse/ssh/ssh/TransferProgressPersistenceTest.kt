package dev.eclipse.ssh.ssh

import android.net.Uri
import androidx.room.Room
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.eclipse.ssh.background.TransferNotifier
import dev.eclipse.ssh.background.TransferRestorer
import dev.eclipse.ssh.background.TransferScheduler
import dev.eclipse.ssh.data.RoomTransferRepository
import dev.eclipse.ssh.data.TransferRepository
import dev.eclipse.ssh.data.local.EclipseDatabase
import dev.eclipse.ssh.data.model.TransferDirection
import dev.eclipse.ssh.data.model.TransferItem
import dev.eclipse.ssh.data.model.TransferStatus
import dev.eclipse.ssh.data.settings.SettingsRepository
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * What the transfer queue records about a transfer that has just ended.
 *
 * `TransferDao.upsert` is `@Insert(onConflict = REPLACE)`, so every `save` rewrites the whole row.
 * Both terminal paths — [TransferCoordinator.guarded] and [TransferRestorer.resumeForHost] — used to
 * build that final row from the snapshot the *caller* handed over before the transfer started, which
 * is zero bytes of an unknown total. Doing so undid every counter the progress callback had written
 * during the attempt: a completed 128 KiB download was stored as `progress = 1f` beside
 * `transferredBytes = 0`, and a download paused at 80% was stored with the byte count it began with.
 *
 * The distinction matters because it is invisible from inside the code: no byte was ever re-sent —
 * a resumed download takes its offset from the destination's real length and a resumed upload from
 * `sftp.stat` — so the only symptom was the transfer list, the completion notification and the
 * resume estimate all reporting numbers that were never true. Nothing but a test that reads the
 * persisted row after the fact can tell the two versions apart, which is why these run against a
 * real SFTP server and the real Room DAO rather than fakes: the REPLACE semantics are the bug.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TransferProgressPersistenceTest {

    private val context get() = RuntimeEnvironment.getApplication()

    private lateinit var database: EclipseDatabase
    private lateinit var repository: TransferRepository
    private lateinit var coordinator: TransferCoordinator
    private lateinit var connections: SshConnectionManager
    private lateinit var restorer: TransferRestorer
    private lateinit var sftp: SftpClient

    @Before
    fun setUp() {
        // WorkManager is initialised because `TransferScheduler` resolves it in its constructor, and
        // the scheduler is a constructor argument of both classes under test. The test
        // implementation also keeps the retry worker from actually running: `scheduleRetryOrFail`
        // enqueues on a genuinely failed attempt, and a worker that ran here would open a second
        // connection behind the test's back.
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        // In-memory, but the real generated DAO: `upsert` has to keep its REPLACE semantics for
        // these tests to mean anything.
        database = Room.inMemoryDatabaseBuilder(context, EclipseDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomTransferRepository(database.transferDao())
        val scheduler = TransferScheduler(context)
        val notifier = TransferNotifier(context)
        coordinator = TransferCoordinator(repository, SftpTransferManager(), scheduler, notifier)
        connections = SshConnectionManager(context, SettingsRepository(context))
        restorer = TransferRestorer(context, repository, connections, SftpTransferManager(), scheduler, notifier)
        sftp = SftpClientFactory.instance().createSftpClient(session)
    }

    @After
    fun tearDown() {
        coordinator.cancelAll()
        runCatching { sftp.close() }
        runCatching { connections.close() }
        database.close()
    }

    @Test(timeout = 120_000)
    fun `a completed download stores the bytes it actually moved`() = runBlocking {
        val item = downloadItem(id = "completed-download", remotePath = PAYLOAD)
        repository.save(item)
        val destination = ByteArrayOutputStream()

        coordinator.downloadAwait(item, sftp, PAYLOAD, destination)

        val row = row("completed-download")
        assertThat(row.status).isEqualTo(TransferStatus.COMPLETE)
        assertThat(row.progress).isEqualTo(1f)
        // The assertion the old code failed: it wrote `progress = 1f` onto the caller's snapshot, so
        // the row claimed a finished transfer of zero bytes and the list showed "0 B of unknown".
        assertWithMessage("a finished transfer must record the bytes it moved, not its starting count")
            .that(row.transferredBytes)
            .isEqualTo(PAYLOAD_SIZE)
        // Learned from `sftp.stat` during the attempt; the caller never knew it.
        assertThat(row.totalBytes).isEqualTo(PAYLOAD_SIZE)
        assertThat(destination.size().toLong()).isEqualTo(PAYLOAD_SIZE)
    }

    @Test(timeout = 120_000)
    fun `a completed upload stores the bytes it actually moved`() = runBlocking {
        val body = ByteArray(48 * 1024) { (it % 251).toByte() }
        val item = TransferItem(
            id = "completed-upload",
            name = "uploaded.bin",
            direction = TransferDirection.UPLOAD,
            hostName = HOST_NAME,
            progress = 0f,
            status = TransferStatus.QUEUED,
            sizeLabel = "48 KB",
            hostId = HOST_ID,
            remotePath = "uploaded.bin",
            // Set, because a local upload really does know its size up front. The byte count is
            // still what regressed: the old terminal write carried this snapshot's zero.
            totalBytes = body.size.toLong(),
        )
        repository.save(item)

        coordinator.uploadAwait(item, sftp, ByteArrayInputStream(body), "uploaded.bin", body.size.toLong())

        val row = row("completed-upload")
        assertThat(row.status).isEqualTo(TransferStatus.COMPLETE)
        assertThat(row.transferredBytes).isEqualTo(body.size.toLong())
        assertThat(row.totalBytes).isEqualTo(body.size.toLong())
        assertThat(sftp.stat("uploaded.bin").size).isEqualTo(body.size.toLong())
    }

    /**
     * The pause path, driven the way the app drives it: the session going away or "disconnect all"
     * cancels the job while bytes are still moving.
     *
     * The destination throttles itself so the transfer is guaranteed to still be in flight when the
     * cancellation lands, and the test waits for a persisted RUNNING row with a non-zero count
     * before cancelling — that row proves a progress callback has already been through, which is
     * what makes the assertion below a real comparison rather than a coincidence.
     */
    @Test(timeout = 120_000)
    fun `pausing a download keeps the progress it had already made`() = runBlocking {
        val item = downloadItem(id = "paused-download", remotePath = BIG, name = "big.bin")
        repository.save(item)

        val sink = ThrottledSink()
        coordinator.download(item, sftp, BIG, sink, ownsSftp = false)
        val running = awaitRow("paused-download") { it.status == TransferStatus.RUNNING && it.transferredBytes > 0 }

        coordinator.cancelAll()

        val paused = awaitRow("paused-download") { it.status == TransferStatus.PAUSED }
        assertWithMessage("pausing must not rewind the byte count a resume is estimated from")
            .that(paused.transferredBytes)
            .isAtLeast(running.transferredBytes)
        assertThat(paused.totalBytes).isEqualTo(BIG_SIZE)
        assertThat(paused.progress).isGreaterThan(0f)
        // Still mid-transfer: if the throttle failed and the file finished, the assertion above
        // would pass for the wrong reason.
        assertThat(paused.progress).isLessThan(1f)
        // Cancellation is not failure, so nothing is counted against the retry ceiling.
        assertThat(paused.retryCount).isEqualTo(0)
        // Every byte the row claims really did reach the destination: the progress callback
        // fires after the write, so the sink can only ever be ahead of the recorded count.
        assertThat(sink.written.get()).isAtLeast(paused.transferredBytes)
    }

    /**
     * The same terminal write in the restore path, which is the one that runs after process death
     * and is therefore the one nobody is watching.
     *
     * The destination is a registered stream rather than a document provider so the offset stays at
     * zero and no append semantics are involved; `localDocumentLength` reporting nothing is the
     * documented fallback to [TransferItem.transferredBytes], which is zero here.
     */
    @Test(timeout = 120_000)
    fun `a restored download records what it recovered instead of where it started`() = runBlocking {
        val destination = ByteArrayOutputStream()
        val uri = Uri.parse("content://dev.eclipse.ssh.test/restored.bin")
        shadowOf(context.contentResolver).registerOutputStream(uri, destination)
        repository.save(
            downloadItem(id = "restored-download", remotePath = PAYLOAD, name = "restored.bin").copy(
                status = TransferStatus.PAUSED,
                localUri = uri.toString(),
            ),
        )

        val resumed = restorer.resumeForHost(HOST_ID, session)

        assertThat(resumed).isEqualTo(1)
        val row = row("restored-download")
        assertThat(row.status).isEqualTo(TransferStatus.COMPLETE)
        assertThat(row.progress).isEqualTo(1f)
        assertWithMessage("a finished restore must record the bytes it recovered")
            .that(row.transferredBytes)
            .isEqualTo(PAYLOAD_SIZE)
        assertThat(row.totalBytes).isEqualTo(PAYLOAD_SIZE)
        assertThat(destination.size().toLong()).isEqualTo(PAYLOAD_SIZE)
    }

    private fun downloadItem(id: String, remotePath: String, name: String = "payload.bin") = TransferItem(
        id = id,
        name = name,
        direction = TransferDirection.DOWNLOAD,
        hostName = HOST_NAME,
        progress = 0f,
        status = TransferStatus.QUEUED,
        sizeLabel = "",
        hostId = HOST_ID,
        remotePath = remotePath,
    )

    private suspend fun row(id: String): TransferItem =
        repository.transfers.first().firstOrNull { it.id == id }
            ?: throw AssertionError("no transfer row was persisted for $id")

    /**
     * The row for [id] once it satisfies [predicate].
     *
     * Polled rather than collected: the writes come from the coordinator's own `Dispatchers.IO`
     * scope, and a Room flow collected from this coroutine would deliver them only when the test
     * suspends anyway. The deadline uses [System.nanoTime] because Robolectric leaves that alone —
     * a shadowed clock that only advances when the looper is pumped would never expire.
     */
    private suspend fun awaitRow(id: String, predicate: (TransferItem) -> Boolean): TransferItem {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        var seen: TransferItem? = null
        while (System.nanoTime() < deadline) {
            seen = repository.transfers.first().firstOrNull { it.id == id }
            if (seen != null && predicate(seen)) return seen
            delay(20)
        }
        throw AssertionError("transfer $id never reached the expected state; last seen: $seen")
    }

    /**
     * A destination that spends [WRITE_PAUSE_MS] on every buffer, so a 2 MiB download over loopback
     * takes seconds instead of milliseconds and a cancellation has somewhere to land.
     *
     * `Thread.sleep` and not `delay`: this is called from `SftpTransferManager.copy`, which is
     * ordinary blocking stream code on `Dispatchers.IO`. Blocking is what an
     * `OutputStream` is allowed to do there, and it is also what a real SAF destination on a slow
     * device does.
     */
    private class ThrottledSink : OutputStream() {
        /** Bytes handed to this stream, so a test can check the row against what really landed. */
        val written = AtomicLong()

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            written.addAndGet(len.toLong())
            runCatching { Thread.sleep(WRITE_PAUSE_MS) }
        }
    }

    companion object {
        private const val USER = "transferuser"
        private const val PASSWORD = "transferpass123"
        private const val HOST_ID = "transfer-host"
        private const val HOST_NAME = "Transfer host"
        private const val PAYLOAD = "payload.bin"
        private const val BIG = "big.bin"
        private const val PAYLOAD_SIZE = 128L * 1024
        private const val BIG_SIZE = 2L * 1024 * 1024
        private const val WRITE_PAUSE_MS = 20L

        private lateinit var root: Path
        private lateinit var server: SshServer
        private lateinit var client: SshClient
        private lateinit var session: ClientSession

        /**
         * One server and one authenticated session for the whole class. An ephemeral port, because a
         * fixed one collides with whatever else the suite happens to be running.
         */
        @JvmStatic
        @BeforeClass
        fun startServer() {
            root = Files.createTempDirectory("eclipse-transfer-it")
            Files.write(root.resolve(PAYLOAD), ByteArray(PAYLOAD_SIZE.toInt()) { (it % 251).toByte() })
            Files.write(root.resolve(BIG), ByteArray(BIG_SIZE.toInt()) { (it % 241).toByte() })

            server = SshServer.setUpDefaultServer().apply {
                port = 0
                keyPairProvider = SimpleGeneratorHostKeyProvider(
                    Files.createTempDirectory("transfer-host").resolve("hostkey.ser"),
                )
                passwordAuthenticator = PasswordAuthenticator { user, password, _ -> user == USER && password == PASSWORD }
                subsystemFactories = Collections.singletonList(SftpSubsystemFactory())
                fileSystemFactory = VirtualFileSystemFactory(root.toAbsolutePath())
                start()
            }

            // A plain MINA client, not `SshConnectionManager.connect`: these tests are about what the
            // queue records, and going through the app's connect path would drag in the host-key
            // challenge dance for no added coverage. The SFTP client itself is created through the
            // same `SftpClientFactory.instance()` call the app uses.
            client = SshClient.setUpDefaultClient().apply { start() }
            session = client.connect(USER, "127.0.0.1", server.port).verify(30, TimeUnit.SECONDS).session.apply {
                addPasswordIdentity(PASSWORD)
                auth().verify(30, TimeUnit.SECONDS)
            }
        }

        @JvmStatic
        @AfterClass
        fun stopServer() {
            runCatching { session.close(true) }
            runCatching { client.stop() }
            runCatching { server.stop(true) }
        }
    }
}
