package dev.eclipse.ssh.data.local

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.data.TransferEntity
import java.io.File
import org.json.JSONObject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The database has grown from version 2 to 16 through hand-written `ALTER TABLE` migrations.
 * Room validates the migrated schema against the entities when it opens, so a single missing
 * or mistyped column turns an app update into a crash on launch.
 *
 * The oldest database is built by hand, because version 2 predates the exported schemas. Every
 * version since is reconstructed from its own committed file in `schemas/`, so a test for one step
 * of the ladder starts from the schema that shipped rather than from a copy of it that could drift -
 * and a missing or edited schema file fails a test here instead of at the next release.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationTest {

    private val context get() = RuntimeEnvironment.getApplication()
    private var database: EclipseDatabase? = null

    @After
    fun tearDown() {
        // runCatching because one test opens a database whose migration is meant to throw; closing that
        // half-opened instance must not turn a passing assertion into a failing tearDown.
        runCatching { database?.close() }
        databaseFile().delete()
    }

    @Test
    fun `a version 2 database migrates all the way to 16 with its rows intact`() = runTest {
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
        // Version 13's four algorithm preferences are null for the same reason legacyAlgorithms is:
        // null means "no opinion, negotiate normally", which is the only behaviour that existed. An
        // empty string would be a different instruction entirely - propose nothing at all - and would
        // make an upgraded host unable to connect to anything.
        assertThat(host.ciphers).isNull()
        assertThat(host.kexAlgorithms).isNull()
        assertThat(host.macs).isNull()
        assertThat(host.hostKeyAlgorithms).isNull()
        // The other three are NOT NULL and empty, because "" is genuinely the absence of them: no
        // command to run at login, no environment to request, no forwarding rules to bind. Nullable
        // would have bought a second way to say the same thing at every read site.
        assertThat(host.startupCommand).isEmpty()
        assertThat(host.environment).isEmpty()
        assertThat(host.savedForwards).isEmpty()
        // Version 15's remote-desktop column is NOT NULL and empty for the same reason: "" is the
        // absence of an endpoint, so an upgraded host configures nothing until its user does.
        assertThat(host.remoteDesktop).isEmpty()

        val transfer = db.transferDao().observeAll().first().single()
        assertThat(transfer.hostId).isNull()
        assertThat(transfer.remotePath).isNull()
        assertThat(transfer.localUri).isNull()
        assertThat(transfer.transferredBytes).isEqualTo(0L)
        assertThat(transfer.totalBytes).isNull()
        assertThat(transfer.retryCount).isEqualTo(0)
        assertThat(transfer.scheduledAt).isNull()
        assertThat(transfer.repeatMinutes).isNull()
        // Version 14's column is null for the same reason the algorithm lists are: null is the only
        // value that can mean "no failure is recorded", which is true of every row that predates it.
        assertThat(transfer.errorMessage).isNull()
        // Version 16's cross-host columns, same reasoning: a transfer that predates them had no
        // second host and no destination directory, and both stay null until a cross-host copy
        // writes them.
        assertThat(transfer.sourceHostId).isNull()
        assertThat(transfer.destPath).isNull()
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
                errorMessage = "Connection reset by peer",
                // Version 16's columns, through the same door: a host id and a real path with a
                // space in it, the shape most likely to be mangled by a mis-typed column name or
                // an affinity that quietly truncates it.
                sourceHostId = "origin-host",
                destPath = "/srv/incoming/site backups",
            ),
        )

        val stored = db.transferDao().observeAll().first().single { it.id == "post-migration" }
        assertThat(stored.transferredBytes).isEqualTo(2_621_440L)
        assertThat(stored.repeatMinutes).isEqualTo(60L)
        assertThat(stored.scheduledAt).isEqualTo(1_800_000_000_000L)
        // The failure reason is the one new column a real failure writes, and a sentence with spaces
        // and lowercase is the shape most likely to be mangled by a mis-typed affinity or a NOT NULL
        // default that silently empties it.
        assertThat(stored.errorMessage).isEqualTo("Connection reset by peer")
        assertThat(stored.sourceHostId).isEqualTo("origin-host")
        assertThat(stored.destPath).isEqualTo("/srv/incoming/site backups")
    }

    @Test
    fun `a version 12 host keeps every configured value across the step to 13`() = runTest {
        // The step this release adds, on its own. The other tests here start at version 2, so every
        // value they can check is a default - which cannot catch the failure this one is for: an
        // `ALTER TABLE` that rebuilds the table instead of extending it, or a column list that drifts
        // out of order, loses settings the user chose and would look exactly like the app resetting
        // itself. Seeded from the committed version-12 schema rather than a copy of it, so this stays
        // honest as the ladder grows.
        seedVersion12()

        val host = openWithMigrations().hostDao().observeAll().first().single()

        // Every version-12 column, read back after the migration, none of them at its default.
        assertThat(host.id).isEqualTo("tuned-host")
        assertThat(host.name).isEqualTo("Tuned edge")
        assertThat(host.host).isEqualTo("edge.example.com")
        assertThat(host.username).isEqualTo("ops")
        assertThat(host.port).isEqualTo(2222)
        assertThat(host.authMethod).isEqualTo("KEY")
        assertThat(host.groupName).isEqualTo("Production")
        assertThat(host.toDomain().tags).containsExactly("eu", "edge")
        assertThat(host.isFavorite).isFalse()
        assertThat(host.lastConnectedAt).isEqualTo(1_750_000_000_000L)
        assertThat(host.fingerprint).isEqualTo("SHA256:tuned")
        assertThat(host.proxyType).isEqualTo("SOCKS5")
        assertThat(host.proxyJump).isEqualTo("bastion.example.com")
        assertThat(host.socksHost).isEqualTo("10.0.0.1")
        assertThat(host.socksPort).isEqualTo(9050)
        assertThat(host.socksUsername).isEqualTo("ops")
        assertThat(host.accentColor).isEqualTo(0xFF2196F3)
        assertThat(host.connectTimeoutSeconds).isEqualTo(25)
        assertThat(host.keepAliveSeconds).isEqualTo(45)
        assertThat(host.autoLoginSftp).isFalse()
        assertThat(host.compression).isTrue()
        assertThat(host.keepAliveEnabled).isFalse()
        assertThat(host.serverAliveCountMax).isEqualTo(6)
        assertThat(host.authTimeoutSeconds).isEqualTo(90)
        assertThat(host.autoReconnect).isFalse()
        assertThat(host.maxReconnectAttempts).isEqualTo(9)
        assertThat(host.reconnectBackoffSeconds).isEqualTo(12)
        assertThat(host.usePty).isFalse()
        assertThat(host.terminalType).isEqualTo("screen-256color")
        assertThat(host.terminalColumns).isEqualTo(132)
        assertThat(host.terminalRows).isEqualTo(50)
        assertThat(host.keyboardInteractiveAuth).isFalse()
        assertThat(host.legacyAlgorithms).isTrue()
        assertThat(host.hostKeyPolicy).isEqualTo("STRICT")

        // And the new ones arrive absent, so a host configured before this release connects the way
        // it did yesterday: negotiate normally, run nothing at login, bind nothing.
        assertThat(host.ciphers).isNull()
        assertThat(host.kexAlgorithms).isNull()
        assertThat(host.macs).isNull()
        assertThat(host.hostKeyAlgorithms).isNull()
        assertThat(host.startupCommand).isEmpty()
        assertThat(host.environment).isEmpty()
        assertThat(host.savedForwards).isEmpty()
    }

    @Test
    fun `the version 13 columns accept and return what the form can put in them`() = runTest {
        // The other half of the migration: a column Room can validate is not necessarily a column the
        // DAO round trips. An `ALTER TABLE` naming the right column with the wrong affinity passes the
        // schema check and then loses data, so the widest realistic value for each new column is
        // written through the DAO and read back.
        seedVersion2()
        val db = openWithMigrations()
        val migrated = db.hostDao().observeAll().first().single()

        val configured = migrated.copy(
            ciphers = "aes256-gcm@openssh.com,aes128-ctr",
            kexAlgorithms = "curve25519-sha256,diffie-hellman-group14-sha256",
            macs = "hmac-sha2-256-etm@openssh.com",
            hostKeyAlgorithms = "ssh-ed25519,rsa-sha2-512",
            startupCommand = "tmux attach || tmux new",
            // Newline separated, which is the shape most likely to be mangled by a text column.
            environment = "LANG=en_US.UTF-8\nTZ=Europe/Amsterdam",
            savedForwards = "L:8080:intranet.example:80\nD:1080",
            // The version-15 column through the same door: packed lines, flags set off by
            // whitespace, exactly the text the form will put there.
            remoteDesktop = "V:10.0.1.5:5900 view-only",
        )
        db.hostDao().upsert(configured)

        assertThat(db.hostDao().observeAll().first().single()).isEqualTo(configured)
    }

    @Test
    fun `a version 11 database - the oldest a device can hold - migrates to 16 with its row intact`() = runTest {
        // Eleven is the real floor. The first public release (1.0.0) shipped at exactly this version, so
        // it is the oldest schema any device in the field can be holding and the oldest upgrade a user
        // actually runs. `seedVersion2` walks the same 11->12->13 steps, but from a hand-written schema;
        // this one starts from the committed 11.json the shipped build validated against, and carries a
        // fully configured row so the climb proves it preserves the values a user chose, not just that a
        // fresh column lands on its default.
        seedVersion11()

        val db = openWithMigrations()

        val host = db.hostDao().observeAll().first().single()
        // Every value set at version 11 survives to 15, none of it reverted to a column default.
        assertThat(host.id).isEqualTo("v11-host")
        assertThat(host.port).isEqualTo(2211)
        assertThat(host.authMethod).isEqualTo("KEY")
        assertThat(host.groupName).isEqualTo("Prod")
        assertThat(host.toDomain().tags).containsExactly("eu", "edge")
        assertThat(host.isFavorite).isTrue()
        assertThat(host.fingerprint).isEqualTo("SHA256:v11")
        assertThat(host.proxyType).isEqualTo("SOCKS5")
        assertThat(host.proxyJump).isEqualTo("bastion11.example.com")
        assertThat(host.socksHost).isEqualTo("10.0.0.11")
        assertThat(host.socksPort).isEqualTo(9051)
        assertThat(host.socksUsername).isEqualTo("ops")
        assertThat(host.accentColor).isEqualTo(0xFF2196F3)
        assertThat(host.connectTimeoutSeconds).isEqualTo(33)
        assertThat(host.keepAliveSeconds).isEqualTo(44)
        // Off, and not the column's DEFAULT 1: a migration that rebuilt the table instead of extending
        // it would silently flip this back on, which is the failure a fully configured row exists to catch.
        assertThat(host.autoLoginSftp).isFalse()

        // The columns added at 12 and 13 arrive absent or at their safe defaults, so a version-11 host
        // connects exactly as it did before the upgrade.
        assertThat(host.compression).isFalse()
        assertThat(host.keepAliveEnabled).isTrue()
        assertThat(host.hostKeyPolicy).isEqualTo("ASK")
        assertThat(host.ciphers).isNull()
        assertThat(host.kexAlgorithms).isNull()
        assertThat(host.macs).isNull()
        assertThat(host.hostKeyAlgorithms).isNull()
        assertThat(host.startupCommand).isEmpty()
        assertThat(host.environment).isEmpty()
        assertThat(host.savedForwards).isEmpty()
        assertThat(host.remoteDesktop).isEmpty()

        // The transfer row and its version-11 columns survive the climb too, and version 16's
        // cross-host columns arrive null: an eleven-era transfer had no second host and no
        // destination directory, and null is the only value that says so without inventing a
        // sentinel path an old row would be read as configuring.
        val transfer = db.transferDao().observeAll().first().single()
        assertThat(transfer.id).isEqualTo("v11-transfer")
        assertThat(transfer.transferredBytes).isEqualTo(4_194_304L)
        assertThat(transfer.totalBytes).isEqualTo(8_388_608L)
        assertThat(transfer.retryCount).isEqualTo(3)
        assertThat(transfer.scheduledAt).isEqualTo(1_750_000_000_001L)
        assertThat(transfer.repeatMinutes).isEqualTo(15L)
        assertThat(transfer.sourceHostId).isNull()
        assertThat(transfer.destPath).isNull()
    }

    @Test
    fun `a version 14 host keeps every configured value across the steps to 16`() = runTest {
        // This release's step, on its own, for the same reason the 12->13 step test exists: the
        // v2 and v11 climbs above can only ever see the new column at its default, which cannot
        // catch the one failure an ALTER TABLE can commit without failing - rebuilding the table
        // instead of extending it, and losing everything the user configured. Seeded from the
        // committed 14.json with a fully configured row, so what survives is what was chosen.
        seedVersion14()

        val host = openWithMigrations().hostDao().observeAll().first().single()

        // Every version-14 column, read back after the step, none of them at its default.
        assertThat(host.id).isEqualTo("v14-host")
        assertThat(host.name).isEqualTo("V14 edge")
        assertThat(host.host).isEqualTo("edge14.example.com")
        assertThat(host.username).isEqualTo("ops")
        assertThat(host.port).isEqualTo(2214)
        assertThat(host.authMethod).isEqualTo("KEY")
        assertThat(host.groupName).isEqualTo("Prod")
        assertThat(host.toDomain().tags).containsExactly("eu", "edge")
        assertThat(host.isFavorite).isTrue()
        assertThat(host.lastConnectedAt).isEqualTo(1_750_000_000_002L)
        assertThat(host.fingerprint).isEqualTo("SHA256:v14")
        assertThat(host.proxyType).isEqualTo("SOCKS5")
        assertThat(host.proxyJump).isEqualTo("bastion14.example.com")
        assertThat(host.socksHost).isEqualTo("10.0.0.14")
        assertThat(host.socksPort).isEqualTo(9052)
        assertThat(host.socksUsername).isEqualTo("ops")
        assertThat(host.accentColor).isEqualTo(0xFF2196F3)
        assertThat(host.connectTimeoutSeconds).isEqualTo(21)
        assertThat(host.keepAliveSeconds).isEqualTo(42)
        assertThat(host.autoLoginSftp).isFalse()
        assertThat(host.compression).isTrue()
        assertThat(host.keepAliveEnabled).isFalse()
        assertThat(host.serverAliveCountMax).isEqualTo(4)
        assertThat(host.authTimeoutSeconds).isEqualTo(60)
        assertThat(host.autoReconnect).isFalse()
        assertThat(host.maxReconnectAttempts).isEqualTo(7)
        assertThat(host.reconnectBackoffSeconds).isEqualTo(11)
        assertThat(host.usePty).isFalse()
        assertThat(host.terminalType).isEqualTo("screen-256color")
        assertThat(host.terminalColumns).isEqualTo(120)
        assertThat(host.terminalRows).isEqualTo(40)
        assertThat(host.keyboardInteractiveAuth).isFalse()
        assertThat(host.legacyAlgorithms).isTrue()
        assertThat(host.hostKeyPolicy).isEqualTo("STRICT")
        assertThat(host.ciphers).isEqualTo("aes256-gcm@openssh.com")
        assertThat(host.kexAlgorithms).isEqualTo("curve25519-sha256")
        assertThat(host.macs).isEqualTo("hmac-sha2-256-etm@openssh.com")
        assertThat(host.hostKeyAlgorithms).isEqualTo("ssh-ed25519")
        assertThat(host.startupCommand).isEqualTo("tmux attach || tmux new")
        assertThat(host.environment).isEqualTo("LANG=en_US.UTF-8")
        assertThat(host.savedForwards).isEqualTo("L:8080:intranet.example:80")

        // And the one column that step added arrives empty: no endpoint configured, nothing bound.
        assertThat(host.remoteDesktop).isEmpty()
    }

    @Test
    fun `a version 15 transfer keeps every configured value across the step to 16`() = runTest {
        // This release's step, on its own, for the same reason the 14->15 step test exists: the v2
        // and v11 climbs can only ever see the new columns at their null default, which cannot
        // catch the one failure an ALTER TABLE can commit without failing - rebuilding the table
        // instead of extending it, and losing everything the user configured. Seeded from the
        // committed 15.json with a fully configured transfer row, so what survives is what was
        // chosen.
        seedVersion15()

        val transfer = openWithMigrations().transferDao().observeAll().first().single()

        // Every version-15 column, read back after the step, none of them at its default.
        assertThat(transfer.id).isEqualTo("v15-transfer")
        assertThat(transfer.name).isEqualTo("site-backup")
        assertThat(transfer.direction).isEqualTo("CROSS_HOST")
        assertThat(transfer.hostName).isEqualTo("Destination edge")
        assertThat(transfer.progress).isEqualTo(0.25f)
        assertThat(transfer.status).isEqualTo("RUNNING")
        assertThat(transfer.sizeLabel).isEqualTo("1.2 GB")
        assertThat(transfer.hostId).isEqualTo("dest-host")
        assertThat(transfer.remotePath).isEqualTo("/srv/incoming/site-backup")
        assertThat(transfer.localUri).isNull()
        assertThat(transfer.transferredBytes).isEqualTo(322_122_547L)
        assertThat(transfer.totalBytes).isEqualTo(1_288_490_188L)
        assertThat(transfer.retryCount).isEqualTo(1)
        assertThat(transfer.scheduledAt).isEqualTo(1_750_000_000_003L)
        assertThat(transfer.repeatMinutes).isNull()
        assertThat(transfer.errorMessage).isEqualTo("Connection reset by peer")

        // And the two columns this step adds arrive null: null is the absence of a second host and
        // of a destination directory, which is true of every row written before this release.
        assertThat(transfer.sourceHostId).isNull()
        assertThat(transfer.destPath).isNull()
    }

    @Test
    fun `an upgrade with no migration path fails loudly instead of silently wiping`() = runTest {
        // The reason the production fallback is downgrade-only. No release ever shipped below schema 11,
        // so a version this build has no *upgrade* path for can only come from a developer bumping the
        // version and forgetting the migration. Under the old blanket fallbackToDestructiveMigration that
        // reached the user as every host and transfer silently dropped on the next launch; version 1
        // stands in for that gap. Opening has to throw instead, so the mistake is caught in QA and CI.
        seedVersion2(userVersion = 1)

        // Room opens lazily, so the missing 1_2 step surfaces on first access rather than at build().
        val thrown = runCatching { openLikeProduction().hostDao().count() }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `a database written by a newer build is reset rather than refused`() = runTest {
        // The half the audit kept on purpose (AUDIT-REPORT.md sections 5 and 12.9): a downgrade - a file
        // left by a build one version ahead, e.g. after a Play Store rollback - has no migration path
        // back and never can, so refusing to open it would be a crash loop with no way out from inside
        // the app. Resetting is the recoverable direction. user_version 17 is that newer build; the row
        // shape beneath it is irrelevant, because a destructive downgrade drops every table first.
        seedVersion2(userVersion = 17)

        val db = openLikeProduction()

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
                Migrations.MIGRATION_11_12, Migrations.MIGRATION_12_13,
                Migrations.MIGRATION_13_14, Migrations.MIGRATION_14_15,
                Migrations.MIGRATION_15_16,
            )
            // No destructive fallback: a schema mismatch must fail the test, not wipe data.
            .allowMainThreadQueries()
            .build()
            .also { database = it }

    /**
     * Opens the database the way `AppModule.provideDatabase` does: the full migration chain and the
     * downgrade-only destructive fallback. The two fallback tests assert against this so they track the
     * production decision rather than a copy of it that could drift from the wiring it is meant to prove.
     */
    private fun openLikeProduction(): EclipseDatabase =
        Room.databaseBuilder(context, EclipseDatabase::class.java, DB_NAME)
            .addMigrations(
                Migrations.MIGRATION_2_3, Migrations.MIGRATION_3_4, Migrations.MIGRATION_4_5,
                Migrations.MIGRATION_5_6, Migrations.MIGRATION_6_7, Migrations.MIGRATION_7_8,
                Migrations.MIGRATION_8_9,
                Migrations.MIGRATION_9_10, Migrations.MIGRATION_10_11,
                Migrations.MIGRATION_11_12, Migrations.MIGRATION_12_13,
                Migrations.MIGRATION_13_14, Migrations.MIGRATION_14_15,
                Migrations.MIGRATION_15_16,
            )
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
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

    /**
     * Writes the version-11 schema from its committed file, with one fully configured host and one
     * transfer in it.
     *
     * Version 11 is what the first public release shipped, so `11.json` is the oldest schema in the
     * repository and the floor every real upgrade starts at or above. Reconstructed from that file
     * rather than from SQL pasted here, for the reason [seedVersion12] gives: a hand-copied `CREATE
     * TABLE` is a second definition that keeps passing after the shipped one is found to differ.
     */
    private fun seedVersion11() {
        val schema = JSONObject(schemaFile(11).readText()).getJSONObject("database")
        val file = databaseFile().apply { parentFile?.mkdirs(); delete() }
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        val entities = schema.getJSONArray("entities")
        for (index in 0 until entities.length()) {
            val entity = entities.getJSONObject(index)
            val table = entity.getString("tableName")
            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
        }
        db.execSQL(
            "INSERT INTO host_profiles (id, name, host, username, port, authMethod, groupName, tags, " +
                "isFavorite, lastConnectedAt, fingerprint, proxyType, proxyJump, socksHost, socksPort, " +
                "socksUsername, socksPassword, accentColor, connectTimeoutSeconds, keepAliveSeconds, " +
                "autoLoginSftp) " +
                "VALUES ('v11-host','V11 edge','edge11.example.com','ops',2211,'KEY','Prod'," +
                "'eu' || char(31) || 'edge',1,1750000000000,'SHA256:v11','SOCKS5'," +
                "'bastion11.example.com','10.0.0.11',9051,'ops'," +
                // socksPassword stays NULL for the same reason as seedVersion12: no fixture in this repo
                // carries anything shaped like a credential. autoLoginSftp is 0, not its DEFAULT 1.
                "NULL,4280391411,33,44,0)",
        )
        db.execSQL(
            "INSERT INTO transfer_queue (id, name, direction, hostName, progress, status, sizeLabel, " +
                "hostId, remotePath, localUri, transferredBytes, totalBytes, retryCount, scheduledAt, " +
                "repeatMinutes) " +
                "VALUES ('v11-transfer','v11.bin','DOWNLOAD','V11 edge',0.5,'RUNNING','8 MB'," +
                "'v11-host','/srv/v11.bin','content://docs/v11.bin',4194304,8388608,3,1750000000001,15)",
        )
        db.execSQL("PRAGMA user_version = 11")
        db.close()
    }

    /**
     * Writes the version-12 schema from its committed file, with one fully configured host in it.
     *
     * Reconstructed from `schemas/12.json` rather than from SQL pasted into this test, because a
     * hand-copied `CREATE TABLE` is a second definition of the same thing: it would keep passing
     * after the real version 12 was found to differ from it, which is the one failure a migration
     * test exists to catch. Room writes that file on every build and it is committed, so it is the
     * same text the shipped release validated against.
     */
    private fun seedVersion12() {
        val schema = JSONObject(schemaFile(12).readText()).getJSONObject("database")
        val file = databaseFile().apply { parentFile?.mkdirs(); delete() }
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        val entities = schema.getJSONArray("entities")
        for (index in 0 until entities.length()) {
            val entity = entities.getJSONObject(index)
            val table = entity.getString("tableName")
            // Room writes the table name as a placeholder so one schema can be reused for a temp
            // table during a destructive migration; substituting it is the documented way to run it.
            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
        }
        db.execSQL(
            "INSERT INTO host_profiles (id, name, host, username, port, authMethod, groupName, tags, " +
                "isFavorite, lastConnectedAt, fingerprint, proxyType, proxyJump, socksHost, socksPort, " +
                "socksUsername, socksPassword, accentColor, connectTimeoutSeconds, keepAliveSeconds, " +
                "autoLoginSftp, compression, keepAliveEnabled, serverAliveCountMax, authTimeoutSeconds, " +
                "autoReconnect, maxReconnectAttempts, reconnectBackoffSeconds, usePty, terminalType, " +
                "terminalColumns, terminalRows, keyboardInteractiveAuth, legacyAlgorithms, hostKeyPolicy) " +
                "VALUES ('tuned-host','Tuned edge','edge.example.com','ops',2222,'KEY','Production'," +
                "'eu' || char(31) || 'edge',0,1750000000000,'SHA256:tuned','SOCKS5'," +
                "'bastion.example.com','10.0.0.1',9050,'ops'," +
                // socksPassword stays NULL on purpose: no fixture in this repo carries anything
                // shaped like a credential, and the column's survival is covered by the ones beside it.
                "NULL,4280391411,25,45,0,1,0,6,90,0,9,12,0,'screen-256color',132,50,0,1,'STRICT')",
        )
        db.execSQL("PRAGMA user_version = 12")
        db.close()
    }

    /**
     * Writes the version-14 schema from its committed file, with one fully configured host in it -
     * the starting point of the 14->15 step test, and the last schema before the remote-desktop
     * column.
     *
     * Reconstructed from `schemas/14.json` for the same reason [seedVersion12] is: the committed
     * file is the definition the shipped release validated against, and a hand-copied CREATE
     * TABLE would be a second one that keeps passing after the first is found to differ.
     */
    private fun seedVersion14() {
        val schema = JSONObject(schemaFile(14).readText()).getJSONObject("database")
        val file = databaseFile().apply { parentFile?.mkdirs(); delete() }
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        val entities = schema.getJSONArray("entities")
        for (index in 0 until entities.length()) {
            val entity = entities.getJSONObject(index)
            val table = entity.getString("tableName")
            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
        }
        db.execSQL(
            "INSERT INTO host_profiles (id, name, host, username, port, authMethod, groupName, tags, " +
                "isFavorite, lastConnectedAt, fingerprint, proxyType, proxyJump, socksHost, socksPort, " +
                "socksUsername, socksPassword, accentColor, connectTimeoutSeconds, keepAliveSeconds, " +
                "autoLoginSftp, compression, keepAliveEnabled, serverAliveCountMax, authTimeoutSeconds, " +
                "autoReconnect, maxReconnectAttempts, reconnectBackoffSeconds, usePty, terminalType, " +
                "terminalColumns, terminalRows, keyboardInteractiveAuth, legacyAlgorithms, hostKeyPolicy, " +
                "ciphers, kexAlgorithms, macs, hostKeyAlgorithms, startupCommand, environment, savedForwards) " +
                "VALUES ('v14-host','V14 edge','edge14.example.com','ops',2214,'KEY','Prod'," +
                "'eu' || char(31) || 'edge',1,1750000000002,'SHA256:v14','SOCKS5'," +
                "'bastion14.example.com','10.0.0.14',9052,'ops'," +
                // socksPassword stays NULL for the same reason as every other seed: no fixture in
                // this repo carries anything shaped like a credential.
                "NULL,4280391411,21,42,0,1,0,4,60,0,7,11,0,'screen-256color',120,40,0,1,'STRICT'," +
                "'aes256-gcm@openssh.com','curve25519-sha256','hmac-sha2-256-etm@openssh.com'," +
                "'ssh-ed25519','tmux attach || tmux new','LANG=en_US.UTF-8','L:8080:intranet.example:80')",
        )
        db.execSQL("PRAGMA user_version = 14")
        db.close()
    }

    /**
     * Writes the version-15 schema from its committed file, with one fully configured transfer row
     * in it - the starting point of the 15->16 step test, and the last schema before the
     * cross-host columns.
     *
     * Reconstructed from `schemas/15.json` for the same reason [seedVersion14] is: the committed
     * file is the definition the shipped release validated against, and a hand-copied CREATE
     * TABLE would be a second one that keeps passing after the first is found to differ. The row
     * is a cross-host one in waiting - direction CROSS_HOST, no localUri - because that is the
     * shape the new columns were added for, and the step has to prove it carries that row across
     * without losing anything already configured.
     */
    private fun seedVersion15() {
        val schema = JSONObject(schemaFile(15).readText()).getJSONObject("database")
        val file = databaseFile().apply { parentFile?.mkdirs(); delete() }
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        val entities = schema.getJSONArray("entities")
        for (index in 0 until entities.length()) {
            val entity = entities.getJSONObject(index)
            val table = entity.getString("tableName")
            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
        }
        db.execSQL(
            "INSERT INTO transfer_queue (id, name, direction, hostName, progress, status, " +
                "sizeLabel, hostId, remotePath, localUri, transferredBytes, totalBytes, retryCount, " +
                "scheduledAt, repeatMinutes, errorMessage) " +
                "VALUES ('v15-transfer','site-backup','CROSS_HOST','Destination edge',0.25,'RUNNING'," +
                "'1.2 GB','dest-host','/srv/incoming/site-backup',NULL,322122547,1288490188,1," +
                "1750000000003,NULL,'Connection reset by peer')",
        )
        db.execSQL("PRAGMA user_version = 15")
        db.close()
    }

    /**
     * The committed schema for [version].
     *
     * Found by walking up from the working directory, because a unit test's is the Gradle project
     * and a run from the repository root is one directory above it - and a test that silently
     * skipped when it could not find the file would be a test that stopped running the day the
     * layout changed. Missing is a failure with the path in it, not a pass.
     */
    private fun schemaFile(version: Int): File {
        val relative = "schemas/dev.eclipse.ssh.data.local.EclipseDatabase/$version.json"
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            val candidate = File(directory, relative)
            if (candidate.isFile) return candidate
            directory = directory.parentFile
        }
        throw AssertionError("No committed Room schema for version $version: looked for $relative above ${File("").absolutePath}")
    }

    private companion object {
        const val DB_NAME = "migration-test.db"
    }
}
