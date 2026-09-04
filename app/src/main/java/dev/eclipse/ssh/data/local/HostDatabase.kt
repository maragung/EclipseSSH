package dev.eclipse.ssh.data.local

import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import dev.eclipse.ssh.data.TransferEntity
import dev.eclipse.ssh.data.TransferDao
import dev.eclipse.ssh.data.model.AuthMethod
import dev.eclipse.ssh.data.model.DEFAULT_AUTH_TIMEOUT_SECONDS
import dev.eclipse.ssh.data.model.DEFAULT_MAX_RECONNECT_ATTEMPTS
import dev.eclipse.ssh.data.model.INHERIT_RECONNECT_BACKOFF
import dev.eclipse.ssh.data.model.DEFAULT_SERVER_ALIVE_COUNT_MAX
import dev.eclipse.ssh.data.model.DEFAULT_TERMINAL_TYPE
import dev.eclipse.ssh.data.model.HostKeyPolicy
import dev.eclipse.ssh.data.model.HostProfile
import dev.eclipse.ssh.data.model.ProxyType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Entity(tableName = "host_profiles")
data class HostEntity(
    @PrimaryKey val id: String,
    val name: String,
    val host: String,
    val username: String,
    val port: Int,
    val authMethod: String,
    val groupName: String,
    val tags: String,
    val isFavorite: Boolean,
    val lastConnectedAt: Long?,
    val fingerprint: String?,
    val proxyType: String,
    val proxyJump: String?,
    val socksHost: String?,
    val socksPort: Int,
    val socksUsername: String?,
    val socksPassword: String?,
    val accentColor: Long?,
    val connectTimeoutSeconds: Int,
    val keepAliveSeconds: Int?,
    /**
     * NOT NULL with a default of true, matching [Migrations.MIGRATION_10_11]: every row that predates
     * the column behaves exactly as it did before, because the app used to open SFTP after every
     * successful connect.
     */
    val autoLoginSftp: Boolean = true,
    /*
     * The advanced per-host settings, added in [Migrations.MIGRATION_11_12].
     *
     * Every one is NOT NULL with a default that reproduces what the app did before the column
     * existed, except [legacyAlgorithms], which is nullable because "follow the global switch" is a
     * third state rather than a value - and is exactly what every upgraded row has to mean.
     */
    val compression: Boolean = false,
    val keepAliveEnabled: Boolean = true,
    val serverAliveCountMax: Int = DEFAULT_SERVER_ALIVE_COUNT_MAX,
    val authTimeoutSeconds: Int = DEFAULT_AUTH_TIMEOUT_SECONDS,
    val autoReconnect: Boolean = true,
    val maxReconnectAttempts: Int = DEFAULT_MAX_RECONNECT_ATTEMPTS,
    val reconnectBackoffSeconds: Int = INHERIT_RECONNECT_BACKOFF,
    val usePty: Boolean = true,
    val terminalType: String = DEFAULT_TERMINAL_TYPE,
    val terminalColumns: Int = 0,
    val terminalRows: Int = 0,
    val keyboardInteractiveAuth: Boolean = true,
    val legacyAlgorithms: Boolean? = null,
    val hostKeyPolicy: String = "ASK",
    /*
     * The rest of the advanced per-host settings, added in [Migrations.MIGRATION_12_13].
     *
     * The four algorithm lists are nullable for the same reason [legacyAlgorithms] is: null means
     * "whatever the library negotiates", which is not a list and is what every existing row means.
     * The three text columns are NOT NULL DEFAULT '' because empty already means "nothing to do" in
     * each of them, so an upgraded row needs no special case anywhere downstream.
     */
    val ciphers: String? = null,
    val kexAlgorithms: String? = null,
    val macs: String? = null,
    val hostKeyAlgorithms: String? = null,
    val startupCommand: String = "",
    val environment: String = "",
    val savedForwards: String = "",
)

class HostConverters {
    @TypeConverter
    fun tagsToString(tags: List<String>): String = tags.joinToString("\u001f")

    @TypeConverter
    fun stringToTags(value: String): List<String> = value.takeIf(String::isNotBlank)
        ?.split("\u001f")
        .orEmpty()
}

@androidx.room.Dao
interface HostDao {
    @androidx.room.Query("SELECT * FROM host_profiles ORDER BY isFavorite DESC, name ASC")
    fun observeAll(): Flow<List<HostEntity>>

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsert(host: HostEntity)

    @androidx.room.Delete
    suspend fun delete(host: HostEntity)

    @androidx.room.Query("SELECT COUNT(*) FROM host_profiles")
    suspend fun count(): Int
}

// exportSchema is on so `app/schemas` records what version 14 actually looks like: every
// migration from here has to be written against a known starting point, and Room's migration
// test helper reads those files. See the ksp `room.schemaLocation` argument in build.gradle.kts.
@Database(entities = [HostEntity::class, TransferEntity::class], version = 14, exportSchema = true)
@TypeConverters(HostConverters::class)
abstract class EclipseDatabase : RoomDatabase() {
    abstract fun hostDao(): HostDao
    abstract fun transferDao(): TransferDao
}

fun HostEntity.toDomain() = HostProfile(
    id = id,
    name = name,
    host = host,
    username = username,
    port = port,
    authMethod = AuthMethod.entries.firstOrNull { it.name == authMethod } ?: AuthMethod.PASSWORD,
    group = groupName,
    tags = if (tags.isBlank()) emptyList() else tags.split("\u001f"),
    isFavorite = isFavorite,
    lastConnectedAt = lastConnectedAt,
    fingerprint = fingerprint,
    proxyType = ProxyType.entries.firstOrNull { it.name == proxyType } ?: ProxyType.NONE,
    proxyJump = proxyJump,
    socksHost = socksHost,
    socksPort = socksPort,
    socksUsername = socksUsername,
    socksPassword = socksPassword,
    accentColor = accentColor,
    connectTimeoutSeconds = connectTimeoutSeconds,
    keepAliveSeconds = keepAliveSeconds,
    autoLoginSftp = autoLoginSftp,
    compression = compression,
    keepAliveEnabled = keepAliveEnabled,
    serverAliveCountMax = serverAliveCountMax,
    authTimeoutSeconds = authTimeoutSeconds,
    autoReconnect = autoReconnect,
    maxReconnectAttempts = maxReconnectAttempts,
    reconnectBackoffSeconds = reconnectBackoffSeconds,
    usePty = usePty,
    terminalType = terminalType,
    terminalColumns = terminalColumns,
    terminalRows = terminalRows,
    keyboardInteractiveAuth = keyboardInteractiveAuth,
    legacyAlgorithms = legacyAlgorithms,
    ciphers = ciphers,
    kexAlgorithms = kexAlgorithms,
    macs = macs,
    hostKeyAlgorithms = hostKeyAlgorithms,
    startupCommand = startupCommand,
    environment = environment,
    savedForwards = savedForwards,
    // An unrecognised name falls back to asking, the same way [authMethod] and [proxyType] fall back
    // to their safest value: a row written by a newer build, or edited by hand, must not silently
    // become the policy that trusts whatever key turns up.
    hostKeyPolicy = HostKeyPolicy.entries.firstOrNull { it.name == hostKeyPolicy } ?: HostKeyPolicy.ASK,
)

fun HostProfile.toEntity() = HostEntity(
    id = id,
    name = name,
    host = host,
    username = username,
    port = port,
    authMethod = authMethod.name,
    groupName = group,
    tags = tags.joinToString("\u001f"),
    isFavorite = isFavorite,
    lastConnectedAt = lastConnectedAt,
    fingerprint = fingerprint,
    proxyType = proxyType.name,
    proxyJump = proxyJump,
    socksHost = socksHost,
    socksPort = socksPort,
    socksUsername = socksUsername,
    socksPassword = socksPassword,
    accentColor = accentColor,
    connectTimeoutSeconds = connectTimeoutSeconds,
    keepAliveSeconds = keepAliveSeconds,
    autoLoginSftp = autoLoginSftp,
    compression = compression,
    keepAliveEnabled = keepAliveEnabled,
    serverAliveCountMax = serverAliveCountMax,
    authTimeoutSeconds = authTimeoutSeconds,
    autoReconnect = autoReconnect,
    maxReconnectAttempts = maxReconnectAttempts,
    reconnectBackoffSeconds = reconnectBackoffSeconds,
    usePty = usePty,
    terminalType = terminalType,
    terminalColumns = terminalColumns,
    terminalRows = terminalRows,
    keyboardInteractiveAuth = keyboardInteractiveAuth,
    legacyAlgorithms = legacyAlgorithms,
    ciphers = ciphers,
    kexAlgorithms = kexAlgorithms,
    macs = macs,
    hostKeyAlgorithms = hostKeyAlgorithms,
    startupCommand = startupCommand,
    environment = environment,
    savedForwards = savedForwards,
    hostKeyPolicy = hostKeyPolicy.name,
)

fun Flow<List<HostEntity>>.asDomain(): Flow<List<HostProfile>> = map { entities -> entities.map(HostEntity::toDomain) }
