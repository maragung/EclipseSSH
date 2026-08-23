package dev.eclipse.ssh.data.local

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.data.TransferEntity
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
 * The database has grown from version 2 to 12 through hand-written `ALTER TABLE` migrations.
 * Room validates the migrated schema against the entities when it opens, so a single missing
 * or mistyped column turns an app update into a crash on launch. The schema is not exported,
 * so these tests build the old database by hand rather than using [MigrationTestHelper].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationTest {

    private val context get() = RuntimeEnvironment.getApplication()
    private var database: EclipseDatabase? = null

    @After
    fun tearDown() {
        database?.close()
        databaseFile().delete()
    }

    @Test
    fun `a version 2 database migrates all the way to 12 with its rows intact`() = runTest {
        seedVersion2()

        val db = openWithMigrations()

        val hosts = db.hostDao().observeAll().first()
        assertThat(hosts).hasSize(1)
        val host = hosts.single()
        assertThat(host.id).isEqualTo("legacy-host")
        assertThat(host.name).isEqualTo("Legacy edge")
        assertThat(host.host).isEqualTo("old.example.com")
        assertThat(host.username).isEqualTo("admin")
        assertThat(host.port).isEqualTo(2022)
        assertThat(host.groupName).isEqualTo("Work")
        assertThat(host.isFavorite).isTrue()
        assertThat(host.fingerprint).isEqualTo("SHA256:legacy")
        // The unit-separator encoding predates every migration, so old tags must still decode.
        assertThat(host.toDomain().tags).containsExactly("legacy", "eu")

        val transfers = db.transferDao().observeAll().first()
        assertThat(transfers).hasSize(1)
        assertThat(transfers.single().name).isEqualTo("old.log")
    }

    @Test
    fun `columns added after version 2 arrive with safe defaults`() = runTest {
        seedVersion2()

        val db = openWithMigrations()

        val host = db.hostDao().observeAll().first().single()
        // proxyType/socksPort carry NOT NULL defaults; everything else must read as absent
        // rather than as an empty string the UI would show as a configured proxy.
        assertThat(host.proxyType).isEqualTo("NONE")
        assertThat(host.socksPort).isEqualTo(1080)
        assertThat(host.proxyJump).isNull()
        assertThat(host.socksHost).isNull()
        assertThat(host.socksUsername).isNull()
        assertThat(host.socksPassword).isNull()
        assertThat(host.accentColor).isNull()
        // The per-host connect timeout arrived in version 10 with a NOT NULL default equal to the
        // value the engine used to hard-code, so an upgraded profile keeps connecting exactly as it
        // did. keepAliveSeconds is null on purpose: null means "follow the global setting", which is
        // what every host predating the column was already doing.
        assertThat(host.connectTimeoutSeconds).isEqualTo(15)
        assertThat(host.keepAliveSeconds).isNull()
        // Version 11's column defaults to 1 - on - because connecting used to list the remote home
        // directory unconditionally. An upgrade that silently switched the file browser off would
        // read as the Files tab having broken, so an existing profile keeps the behaviour it had.
        assertThat(host.autoLoginSftp).isTrue()
        // Version 12's advanced settings. Every default reproduces what the engine did before the
        // column existed, so an upgraded profile connects identically until the user edits it: no
        // compression was ever offered, the heartbeat was always armed with three missed replies
        // allowed, MINA's own 30-second auth timeout applied, the reconnect ladder used 5 attempts
        // from a 5-second base, the pty request was unconditional at xterm-256color, the geometry came
        // from the view (the 0 sentinel), MINA's full authentication method list was offered, and the
        // verifier asked about an unknown key. legacyAlgorithms is null because "follow the global
        // switch" was the only behaviour there was.
        assertThat(host.compression).isFalse()
        assertThat(host.keepAliveEnabled).isTrue()
        assertThat(host.serverAliveCountMax).isEqualTo(3)
        assertThat(host.authTimeoutSeconds).isEqualTo(30)
        assertThat(host.autoReconnect).isTrue()
        assertThat(host.maxReconnectAttempts).isEqualTo(5)
        assertThat(host.reconnectBackoffSeconds).isEqualTo(0)
        assertThat(host.usePty).isTrue()
        assertThat(host.terminalType).isEqualTo("xterm-256color")
        assertThat(host.terminalColumns).isEqualTo(0)
        assertThat(host.terminalRows).isEqualTo(0)
        assertThat(host.keyboardInteractiveAuth).isTrue()
        assertThat(host.legacyAlgorithms).isNull()
        assertThat(host.hostKeyPolicy).isEqualTo("ASK")

        val transfer = db.transferDao().observeAll().first().single()
        assertThat(transfer.hostId).isNull()
        assertThat(transfer.remotePath).isNull()
        assertThat(transfer.localUri).isNull()
        assertThat(transfer.transferredBytes).isEqualTo(0L)
        assertThat(transfer.totalBytes).isNull()
        assertThat(transfer.retryCount).isEqualTo(0)
        assertThat(transfer.scheduledAt).isNull()
        assertThat(transfer.repeatMinutes).isNull()
    }

    @Test
    fun `the migrated database still accepts writes on every new column`() = runTest {
        seedVersion2()
        val db = openWithMigrations()

        db.transferDao().upsert(
            TransferEntity(
                id = "post-migration",
                name = "new.bin",
                direction = "DOWNLOAD",
                hostName = "Edge",
                progress = 0.25f,
                status = "RUNNING",
                sizeLabel = "10 MB",
                hostId = "legacy-host",
                remotePath = "/srv/new.bin",
                localUri = "content://docs/new.bin",
                transferredBytes = 2_621_440L,
                totalBytes = 10_485_760L,
                retryCount = 2,
                scheduledAt = 1_800_000_000_000L,
                repeatMinutes = 60L,
            ),
        )

        val stored = db.transferDao().observeAll().first().single { it.id == "post-migration" }
        assertThat(stored.transferredBytes).isEqualTo(2_621_440L)
        assertThat(stored.repeatMinutes).isEqualTo(60L)
        assertThat(stored.scheduledAt).isEqualTo(1_800_000_000_000L)
    }

    @Test
    fun `a database older than the first migration is rebuilt instead of crashing`() = runTest {
        // Production adds fallbackToDestructiveMigration for exactly this: a version with no
        // migration path must cost the user their queue, never a crash loop on launch.
        seedVersion2(userVersion = 1)

        val db = Room.databaseBuilder(context, EclipseDatabase::class.java, DB_NAME)
            .addMigrations(
                Migrations.MIGRATION_2_3, Migrations.MIGRATION_3_4, Migrations.MIGRATION_4_5,
                Migrations.MIGRATION_5_6, Migrations.MIGRATION_6_7, Migrations.MIGRATION_7_8,
                Migrations.MIGRATION_8_9,
                Migrations.MIGRATION_9_10, Migrations.MIGRATION_10_11,
                Migrations.MIGRATION_11_12,
            )
            .fallbackToDestructiveMigration(dropAllTables = true)
            .allowMainThreadQueries()
            .build()
            .also { database = it }

        assertThat(db.hostDao().count()).isEqualTo(0)
        assertThat(db.transferDao().count()).isEqualTo(0)
    }

    private fun openWithMigrations(): EclipseDatabase =
        Room.databaseBuilder(context, EclipseDatabase::class.java, DB_NAME)
            .addMigrations(
                Migrations.MIGRATION_2_3, Migrations.MIGRATION_3_4, Migrations.MIGRATION_4_5,
                Migrations.MIGRATION_5_6, Migrations.MIGRATION_6_7, Migrations.MIGRATION_7_8,
                Migrations.MIGRATION_8_9,
                Migrations.MIGRATION_9_10, Migrations.MIGRATION_10_11,
                Migrations.MIGRATION_11_12,
            )
            // No destructive fallback: a schema mismatch must fail the test, not wipe data.
            .allowMainThreadQueries()
            .build()
            .also { database = it }

    private fun databaseFile(): File = context.getDatabasePath(DB_NAME)

    /** Writes the version-2 schema and a row in each table, exactly as the shipped v2 did. */
    private fun seedVersion2(userVersion: Int = 2) {
        val file = databaseFile().apply { parentFile?.mkdirs(); delete() }
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `host_profiles` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                "`host` TEXT NOT NULL, `username` TEXT NOT NULL, `port` INTEGER NOT NULL, " +
                "`authMethod` TEXT NOT NULL, `groupName` TEXT NOT NULL, `tags` TEXT NOT NULL, " +
                "`isFavorite` INTEGER NOT NULL, `lastConnectedAt` INTEGER, `fingerprint` TEXT, " +
                "PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `transfer_queue` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                "`direction` TEXT NOT NULL, `hostName` TEXT NOT NULL, `progress` REAL NOT NULL, " +
                "`status` TEXT NOT NULL, `sizeLabel` TEXT NOT NULL, PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "INSERT INTO host_profiles VALUES ('legacy-host','Legacy edge','old.example.com'," +
                // Tags are joined with the unit separator, so rebuild it with char(31).
                "'admin',2022,'PASSWORD','Work','legacy' || char(31) || 'eu',1," +
                "1750000000000,'SHA256:legacy')",
        )
        db.execSQL(
            "INSERT INTO transfer_queue VALUES ('legacy-transfer','old.log','UPLOAD'," +
                "'Legacy edge',1.0,'COMPLETE','12 KB')",
        )
        db.execSQL("PRAGMA user_version = $userVersion")
        db.close()
    }

    private companion object {
        const val DB_NAME = "migration-test.db"
    }
}
