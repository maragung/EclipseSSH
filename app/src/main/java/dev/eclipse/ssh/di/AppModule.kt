package dev.eclipse.ssh.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.eclipse.ssh.data.HostRepository
import dev.eclipse.ssh.data.RoomHostRepository
import dev.eclipse.ssh.data.TransferDao
import dev.eclipse.ssh.data.TransferRepository
import dev.eclipse.ssh.data.RoomTransferRepository
import dev.eclipse.ssh.data.local.EclipseDatabase
import dev.eclipse.ssh.data.local.HostDao
import dev.eclipse.ssh.data.local.Migrations
import dev.eclipse.ssh.data.settings.SettingsRepository
import dev.eclipse.ssh.security.SecretCipher
import dev.eclipse.ssh.security.SecureVault
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    /**
     * The database, migrated forward and — only on a downgrade — rebuilt.
     *
     * There is deliberately no `MIGRATION_1_2`, and the fallback is [fallbackToDestructiveMigrationOnDowngrade]
     * rather than the blanket [fallbackToDestructiveMigration]. The reason is what the history actually
     * shows: the first public release (1.0.0) already shipped at schema version 11, and every release
     * since has been 11 or newer, so no device in the field can be holding a database older than 11.
     * The `MIGRATION_2_3 … MIGRATION_10_11` steps predate 1.0.0 — they exist only so a pre-release or
     * developer database still opens — and versions 1 through 10 were never released and were never
     * exported to `app/schemas`, so there is nothing to write or verify a `MIGRATION_1_2` against.
     *
     * The blanket `fallbackToDestructiveMigration(dropAllTables = true)` this replaces would silently
     * drop every host and every queued transfer on *any* version step it had no migration for. That
     * includes the one gap that can still be introduced by a mistake: a future version bump whose
     * migration a developer forgets to add. Under the blanket fallback that ships as silent data loss on
     * the user's next launch; the audit kept the fallback only for a *downgrade* — a database written by
     * a newer build after a rollback, or a version this build has never heard of — where the sole
     * alternative is refusing to open the database at all (AUDIT-REPORT.md §5, §12.9). Narrowing to
     * downgrade-only keeps exactly that deliberate behaviour while turning a missing *upgrade* migration
     * back into a loud `IllegalStateException` at open time, caught in QA and CI before it can reach a
     * user. `MigrationTest` walks the full chain from the oldest committed schema (11) to current and
     * pins both halves of this fallback.
     */
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): EclipseDatabase =
        Room.databaseBuilder(context, EclipseDatabase::class.java, "eclipse_ssh.db")
            .addMigrations(Migrations.MIGRATION_2_3, Migrations.MIGRATION_3_4, Migrations.MIGRATION_4_5, Migrations.MIGRATION_5_6, Migrations.MIGRATION_6_7, Migrations.MIGRATION_7_8, Migrations.MIGRATION_8_9, Migrations.MIGRATION_9_10, Migrations.MIGRATION_10_11, Migrations.MIGRATION_11_12, Migrations.MIGRATION_12_13)
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()

    @Provides
    fun provideHostDao(database: EclipseDatabase): HostDao = database.hostDao()

    @Provides
    @Singleton
    fun provideHostRepository(dao: HostDao, vault: SecureVault): HostRepository = RoomHostRepository(dao, vault)

    @Provides
    fun provideTransferDao(database: EclipseDatabase): TransferDao = database.transferDao()

    @Provides
    @Singleton
    fun provideTransferRepository(dao: TransferDao): TransferRepository = RoomTransferRepository(dao)

    @Provides
    @Singleton
    fun provideSettingsRepository(@ApplicationContext context: Context): SettingsRepository =
        SettingsRepository(context)

    /**
     * The vault, behind the narrow interface everything that stores a secret depends on. Bound here
     * rather than by `@Binds` in an abstract module so that the one place a reader looks for the
     * app's wiring still names both halves.
     */
    @Provides
    @Singleton
    fun provideSecretCipher(vault: SecureVault): SecretCipher = vault

    /**
     * The credential file.
     *
     * Built by the factory rather than by a `Context.preferencesDataStore` delegate for the reason
     * spelled out on [dev.eclipse.ssh.data.credentials.HostCredentialStore]: the delegate caches one
     * instance per property per classloader, which makes the store impossible to isolate in a test.
     *
     * The corruption handler is the same trade the settings and session stores make. A file DataStore
     * that cannot parse its file throws `CorruptionException` from *every* read, permanently, and
     * nothing in the app would ever rewrite it because every write path reads first — so a single torn
     * write would leave the host list frozen and the app with no way to repair itself. Resetting loses
     * saved credentials and asks the user for them again, which is both recoverable and the safe
     * direction for secrets: it can only ever result in *more* prompting, never in a connection made
     * with something the user did not supply.
     */
    @Provides
    @Singleton
    @HostCredentialsDataStore
    fun provideHostCredentialsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            produceFile = { context.preferencesDataStoreFile("host_credentials") },
        )
}
