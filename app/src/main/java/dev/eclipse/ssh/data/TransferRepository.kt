package dev.eclipse.ssh.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import dev.eclipse.ssh.data.model.TransferDirection
import dev.eclipse.ssh.data.model.TransferItem
import dev.eclipse.ssh.data.model.TransferStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Entity(tableName = "transfer_queue")
data class TransferEntity(
    @PrimaryKey val id: String,
    val name: String,
    val direction: String,
    val hostName: String,
    val progress: Float,
    val status: String,
    val sizeLabel: String,
    val hostId: String?,
    val remotePath: String?,
    val localUri: String?,
    // The cross-host columns, added in Migrations.MIGRATION_15_16. Nullable with no backfill for
    // the same reason localUri is: every row that predates them was an ordinary upload or download
    // with no second host and no destination directory, and null is the only value that says so
    // without inventing a sentinel path an old row would be read as configuring.
    val sourceHostId: String? = null,
    val destPath: String? = null,
    val transferredBytes: Long,
    val totalBytes: Long?,
    val retryCount: Int = 0,
    val scheduledAt: Long? = null,
    val repeatMinutes: Long? = null,
    val errorMessage: String? = null,
)

@Dao
interface TransferDao {
    @Query("SELECT * FROM transfer_queue ORDER BY status ASC, name ASC")
    fun observeAll(): Flow<List<TransferEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: TransferEntity)

    @Query("DELETE FROM transfer_queue WHERE status = 'COMPLETE'")
    suspend fun clearCompleted()

    @Query("DELETE FROM transfer_queue WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM transfer_queue")
    suspend fun count(): Int
}

interface TransferRepository {
    val transfers: Flow<List<TransferItem>>
    suspend fun save(item: TransferItem)
    suspend fun seedIfEmpty()
    suspend fun clearCompleted()
    suspend fun delete(id: String)
}

class RoomTransferRepository(private val dao: TransferDao) : TransferRepository {
    override val transfers = dao.observeAll().map { rows -> rows.map(TransferEntity::toDomain) }
    override suspend fun save(item: TransferItem) = dao.upsert(item.toEntity())
    override suspend fun clearCompleted() = dao.clearCompleted()
    override suspend fun delete(id: String) = dao.delete(id)

    override suspend fun seedIfEmpty() {
        if (dao.count() == 0) {
            save(
                TransferItem(
                    id = "demo-download", name = "release-bundle.tar.gz", direction = TransferDirection.DOWNLOAD,
                    hostName = "Production edge", progress = 0.72f, status = TransferStatus.RUNNING, sizeLabel = "1.8 GB",
                    hostId = "eclipse-demo", remotePath = "/srv/releases/release-bundle.tar.gz", transferredBytes = 1_296_000_000L, totalBytes = 1_800_000_000L,
                ),
            )
            save(
                TransferItem(
                    id = "demo-upload", name = "deploy.sh", direction = TransferDirection.UPLOAD,
                    hostName = "Staging cluster", progress = 1f, status = TransferStatus.COMPLETE, sizeLabel = "24 KB",
                    hostId = "eclipse-staging", remotePath = "/home/ubuntu/deploy.sh", transferredBytes = 24_576L, totalBytes = 24_576L,
                ),
            )
        }
    }
}

internal fun TransferEntity.toDomain() = TransferItem(
    id = id,
    name = name,
    direction = TransferDirection.entries.firstOrNull { it.name == direction } ?: TransferDirection.DOWNLOAD,
    hostName = hostName,
    progress = progress,
    status = TransferStatus.entries.firstOrNull { it.name == status } ?: TransferStatus.QUEUED,
    sizeLabel = sizeLabel,
    hostId = hostId,
    remotePath = remotePath,
    localUri = localUri,
    sourceHostId = sourceHostId,
    destPath = destPath,
    transferredBytes = transferredBytes,
    totalBytes = totalBytes,
    retryCount = retryCount,
    scheduledAt = scheduledAt,
    repeatMinutes = repeatMinutes,
    errorMessage = errorMessage,
)

internal fun TransferItem.toEntity() = TransferEntity(
    id = id,
    name = name,
    direction = direction.name,
    hostName = hostName,
    progress = progress,
    status = status.name,
    sizeLabel = sizeLabel,
    hostId = hostId,
    remotePath = remotePath,
    localUri = localUri,
    sourceHostId = sourceHostId,
    destPath = destPath,
    transferredBytes = transferredBytes,
    totalBytes = totalBytes,
    retryCount = retryCount,
    scheduledAt = scheduledAt,
    repeatMinutes = repeatMinutes,
    errorMessage = errorMessage,
)
