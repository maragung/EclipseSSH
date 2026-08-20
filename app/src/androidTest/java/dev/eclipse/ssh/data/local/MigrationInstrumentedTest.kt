package dev.eclipse.ssh.data.local

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The same version-2-to-11 upgrade the unit tests cover, run against the device's own SQLite.
 * Robolectric substitutes a host build of SQLite, so a migration can pass there and still fail
 * on a real Android version — and this one runs on every user's first launch after an update.
 */
@RunWith(AndroidJUnit4::class)
class MigrationInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var database: EclipseDatabase? = null

    @Before
    fun clearAnyLeftovers() {
        context.getDatabasePath(DB_NAME).parentFile
            ?.listFiles { f -> f.name.startsWith(DB_NAME) }
            ?.forEach { it.delete() }
    }

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun aVersion2DatabaseUpgradesToTheCurrentSchema() = runTest {
        seedVersion2()

        val db = open()

        // Room validates the migrated schema against the entities as it opens, so reaching
        // this line at all means every ALTER TABLE produced the column Room expects.
        val host = db.hostDao().observeAll().first().single()
        assertThat(host.id).isEqualTo("legacy-host")
        assertThat(host.port).isEqualTo(2022)
        assertThat(host.toDomain().tags).containsExactly("legacy", "eu")
        assertThat(host.proxyType).isEqualTo("NONE")
        assertThat(host.socksPort).isEqualTo(1080)
        assertThat(host.accentColor).isNull()
        // Version 11: on by default, so an upgraded profile still opens its file browser on connect.
        assertThat(host.autoLoginSftp).isTrue()

        val transfer = db.transferDao().observeAll().first().single()
        assertThat(transfer.name).isEqualTo("old.log")
        assertThat(transfer.transferredBytes).isEqualTo(0L)
        assertThat(transfer.retryCount).isEqualTo(0)
        assertThat(transfer.repeatMinutes).isNull()
    }

    @Test
    fun aFreshInstallOpensAtTheCurrentVersion() = runTest {
        // No seeding: the onCreate path, which is what a clean install actually runs.
        val db = open()

        assertThat(db.hostDao().count()).isEqualTo(0)
        assertThat(db.transferDao().count()).isEqualTo(0)
    }

    private fun open(): EclipseDatabase =
        Room.databaseBuilder(context, EclipseDatabase::class.java, DB_NAME)
            .addMigrations(
                Migrations.MIGRATION_2_3, Migrations.MIGRATION_3_4, Migrations.MIGRATION_4_5,
                Migrations.MIGRATION_5_6, Migrations.MIGRATION_6_7, Migrations.MIGRATION_7_8,
                Migrations.MIGRATION_8_9,
                Migrations.MIGRATION_9_10, Migrations.MIGRATION_10_11,
            )
            // Deliberately no destructive fallback: a broken migration must fail, not wipe data.
            .build()
            .also { database = it }

    private fun seedVersion2() {
        val file = context.getDatabasePath(DB_NAME).apply { parentFile?.mkdirs() }
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
        db.execSQL("PRAGMA user_version = 2")
        db.close()
    }

    private companion object {
        const val DB_NAME = "migration-instrumented.db"
    }
}
