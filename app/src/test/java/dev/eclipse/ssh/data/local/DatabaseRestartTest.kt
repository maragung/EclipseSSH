package dev.eclipse.ssh.data.local

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.eclipse.ssh.data.model.AuthMethod
import dev.eclipse.ssh.data.model.HostProfile
import dev.eclipse.ssh.data.model.ProxyType
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * What a saved host looks like after the app is killed and started again.
 *
 * `MigrationTest` covers the schema surviving an *upgrade*; `HostMappingTest` covers a profile
 * surviving the trip through `toEntity`/`toDomain` in memory. Neither covers the plain case that every
 * user hits on every launch: the row was written by a process that no longer exists, and everything
 * the form collected has to come back off the disk byte for byte. A field that is written but never
 * read back — a converter that only round-trips in memory, a `NOT NULL` column with a default that
 * quietly replaces what was stored, a Boolean that goes to SQLite as an integer and comes back as the
 * wrong one — passes both of those suites and still loses the user's configuration overnight.
 *
 * The restart is real, not simulated: the database is closed, which releases the connection and flushes
 * the write-ahead log, and then reopened from the same file with a new Room instance. Nothing survives
 * in memory between the two halves except the file path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DatabaseRestartTest {

    private val context get() = RuntimeEnvironment.getApplication()
    private var database: EclipseDatabase? = null

    @After
    fun tearDown() {
        database?.close()
        databaseFile().delete()
    }

    /**
     * Every column of a fully populated profile, across a close and reopen.
     *
     * Deliberately maximal — a proxy jump, SOCKS credentials, an accent colour, tags, a per-host
     * timeout and keep-alive, and the SFTP switch off — because the fields that get lost are the
     * unusual ones nobody checks by hand.
     */
    @Test
    fun `a fully configured host comes back intact after the database is closed and reopened`() = runTest {
        val saved = HostProfile(
            id = "restart-full",
            name = "Restart edge",
            host = "10.1.2.3",
            username = "operator",
            port = 2222,
            authMethod = AuthMethod.SSH_KEY,
            group = "Production",
            tags = listOf("eu-west", "bastion"),
            isFavorite = true,
            lastConnectedAt = 1_750_000_000_000L,
            fingerprint = "SHA256:restart",
            proxyType = ProxyType.SOCKS5,
            proxyJump = "jump.example.com",
            socksHost = "127.0.0.1",
            socksPort = 9050,
            socksUsername = "socksuser",
            socksPassword = null,
            accentColor = 0xFF2196F3,
            connectTimeoutSeconds = 45,
            keepAliveSeconds = 30,
            // Off, which is not the column default: a default that overwrote the stored value would
            // silently turn this back on and the test would catch it here rather than a user later.
            autoLoginSftp = false,
            // Dashes rather than colons, because the column stores the text as typed: a restart that
            // normalised it would change what the form shows the next time it opens.
            wakeOnLanMac = "4C-2E-81-1A-02-F7",
        )

        write(saved)
        val restored = reopenAndReadHosts().single()

        assertThat(restored).isEqualTo(saved)
    }

    /**
     * Both positions of the SFTP switch, side by side, through a restart.
     *
     * Two rows in one database rather than two runs, because the failure this guards against is a
     * column default winning over the stored value — and a default only shows itself when one row
     * disagrees with it.
     */
    @Test
    fun `the SFTP auto login switch survives a restart in both positions`() = runTest {
        val on = HostProfile(id = "restart-on", name = "On", host = "a.example.com", username = "u", autoLoginSftp = true)
        val off = HostProfile(id = "restart-off", name = "Off", host = "b.example.com", username = "u", autoLoginSftp = false)

        write(on, off)
        val restored = reopenAndReadHosts().associateBy(HostProfile::id)

        assertThat(restored).hasSize(2)
        assertWithMessage("the on position").that(restored["restart-on"]?.autoLoginSftp).isTrue()
        assertWithMessage("the off position").that(restored["restart-off"]?.autoLoginSftp).isFalse()
    }

    /**
     * A host with nothing optional set: null stays null rather than becoming an empty string.
     *
     * The distinction is load-bearing. `proxyJump = ""` is not the same as no proxy jump — the connect
     * path checks for null to decide whether to route through anything at all — and an empty tag list
     * has to decode back to empty rather than to a list holding one blank string, which is what a
     * naive `split` on the unit separator produces.
     */
    @Test
    fun `an unconfigured host keeps its nulls and its empty tag list`() = runTest {
        val minimal = HostProfile(id = "restart-minimal", name = "Minimal", host = "min.example.com", username = "root")

        write(minimal)
        val restored = reopenAndReadHosts().single()

        assertThat(restored).isEqualTo(minimal)
        assertThat(restored.tags).isEmpty()
        assertThat(restored.proxyJump).isNull()
        assertThat(restored.keepAliveSeconds).isNull()
        assertThat(restored.lastConnectedAt).isNull()
        assertThat(restored.accentColor).isNull()
    }

    /**
     * An edit made before the restart is what comes back, not the row it replaced.
     *
     * `upsert` is `INSERT ... ON CONFLICT REPLACE`, so this is also the proof that editing a host does
     * not leave a duplicate behind under the same id — which on this table would make the Hosts list
     * show the same machine twice with different settings.
     */
    @Test
    fun `an edited host survives as the edit rather than as the original`() = runTest {
        val original = HostProfile(id = "restart-edit", name = "Before", host = "before.example.com", username = "u", port = 22)
        write(original)

        // Reopened, edited, closed again: the edit is written by a different connection from the one
        // that created the row, which is what an edit after a relaunch actually is.
        val edited = original.copy(name = "After", host = "after.example.com", port = 2200, autoLoginSftp = false)
        write(edited)

        val restored = reopenAndReadHosts()
        assertThat(restored).hasSize(1)
        assertThat(restored.single()).isEqualTo(edited)
    }

    /** A deleted host stays deleted: nothing resurrects from the file on the next launch. */
    @Test
    fun `a deleted host does not come back on the next launch`() = runTest {
        val keep = HostProfile(id = "restart-keep", name = "Keep", host = "keep.example.com", username = "u")
        val drop = HostProfile(id = "restart-drop", name = "Drop", host = "drop.example.com", username = "u")
        write(keep, drop)

        open().let { db ->
            db.hostDao().delete(drop.toEntity())
            close()
        }

        val restored = reopenAndReadHosts()
        assertThat(restored.map(HostProfile::id)).containsExactly("restart-keep")
    }

    // ---------------------------------------------------------------- the restart itself

    /** Writes [hosts] through a freshly opened database, then closes it — one simulated app run. */
    private suspend fun write(vararg hosts: HostProfile) {
        val db = open()
        hosts.forEach { db.hostDao().upsert(it.toEntity()) }
        close()
    }

    /** Opens the same file again and reads the host list back as domain objects. */
    private suspend fun reopenAndReadHosts(): List<HostProfile> =
        open().hostDao().observeAll().first().map(HostEntity::toDomain)

    /**
     * Opens the shipped database on a real file.
     *
     * No in-memory builder, on purpose: an in-memory database cannot be closed and reopened, so it
     * could not express the thing being tested. No destructive fallback either — a schema mismatch has
     * to fail this test rather than quietly hand back an empty table that every assertion below would
     * then blame on persistence.
     */
    private fun open(): EclipseDatabase {
        database?.close()
        return Room.databaseBuilder(context, EclipseDatabase::class.java, DB_NAME)
            .allowMainThreadQueries()
            .build()
            .also { database = it }
    }

    private fun close() {
        database?.close()
        database = null
    }

    private fun databaseFile(): File = context.getDatabasePath(DB_NAME)

    private companion object {
        const val DB_NAME = "restart-test.db"
    }
}
