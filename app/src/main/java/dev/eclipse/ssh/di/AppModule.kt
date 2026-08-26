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
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): EclipseDatabase =
        Room.databaseBuilder(context, EclipseDatabase::class.java, "eclipse_ssh.db")
            .addMigrations(Migrations.MIGRATION_2_3, Migrations.MIGRATION_3_4, Migrations.MIGRATION_4_5, Migrations.MIGRATION_5_6, Migrations.MIGRATION_6_7, Migrations.MIGRATION_7_8, Migrations.MIGRATION_8_9, Migrations.MIGRATION_9_10, Migrations.MIGRATION_10_11, Migrations.MIGRATION_11_12, Migrations.MIGRATION_12_13)
            .fallbackToDestructiveMigration(dropAllTables = true)
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
