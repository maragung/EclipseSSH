package dev.eclipse.ssh.data

import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.data.model.TransferDirection
import dev.eclipse.ssh.data.model.TransferItem
import dev.eclipse.ssh.data.model.TransferStatus
import org.junit.Test

class TransferMappingTest {
    @Test
    fun `transfer items round trip through Room entity with resume metadata`() {
        val original = TransferItem(
            id = "transfer-1",
            name = "backup.tar.gz",
            direction = TransferDirection.DOWNLOAD,
            hostName = "Production edge",
            progress = 0.5f,
            status = TransferStatus.RUNNING,
            sizeLabel = "2.0 GB",
            hostId = "host-1",
            remotePath = "/srv/backup.tar.gz",
            localUri = "content://documents/backup.tar.gz",
            transferredBytes = 1_073_741_824L,
            totalBytes = 2_147_483_648L,
            retryCount = 3,
            scheduledAt = 1_800_000_000_000L,
            repeatMinutes = 1_440L,
        )

        assertThat(original.toEntity().toDomain()).isEqualTo(original)
    }

    @Test
    fun `legacy transfer without resume metadata maps to null fields`() {
        val legacy = TransferItem(
            id = "transfer-2",
            name = "old.log",
            direction = TransferDirection.UPLOAD,
            hostName = "Staging",
            progress = 1f,
            status = TransferStatus.COMPLETE,
            sizeLabel = "1 KB",
        )

        val roundTripped = legacy.toEntity().toDomain()

        assertThat(roundTripped.hostId).isNull()
        assertThat(roundTripped.remotePath).isNull()
        assertThat(roundTripped.localUri).isNull()
        assertThat(roundTripped.sourceHostId).isNull()
        assertThat(roundTripped.destPath).isNull()
        assertThat(roundTripped.transferredBytes).isEqualTo(0L)
        assertThat(roundTripped.retryCount).isEqualTo(0)
        assertThat(roundTripped.scheduledAt).isNull()
        assertThat(roundTripped.repeatMinutes).isNull()
    }

    @Test
    fun `cross host transfer items round trip with both hosts and the destination directory`() {
        // A cross-host row names *two* hosts - hostId the destination, sourceHostId the origin -
        // and the directory it lands in. All three have to survive the entity, because the retry
        // and restart paths of later stages read the row alone: nothing else remembers where the
        // copy came from.
        val original = TransferItem(
            id = "transfer-3",
            name = "site-backup",
            direction = TransferDirection.CROSS_HOST,
            hostName = "Destination edge",
            progress = 0.25f,
            status = TransferStatus.RUNNING,
            sizeLabel = "1.2 GB",
            hostId = "dest-host",
            remotePath = "/srv/incoming/site-backup",
            localUri = null,
            sourceHostId = "origin-host",
            destPath = "/srv/incoming",
            transferredBytes = 322_122_547L,
            totalBytes = 1_288_490_188L,
            retryCount = 1,
        )

        assertThat(original.toEntity().toDomain()).isEqualTo(original)
    }

    @Test
    fun `every direction survives the entity round trip and the fallback is still download`() {
        // The direction is stored as its name and read back by name, so a value that fails to come
        // home means a typo in either mapping - and CROSS_HOST is the one with no synonym to catch
        // it later, since nothing else in the row says which kind of transfer this is.
        TransferDirection.entries.forEach { direction ->
            val item = TransferItem(
                name = "probe.bin",
                direction = direction,
                hostName = "Edge",
                progress = 0f,
                status = TransferStatus.QUEUED,
                sizeLabel = "1 KB",
            )
            assertThat(item.toEntity().toDomain().direction).isEqualTo(direction)
        }

        // A row written by a newer build (or by hand) with an unknown direction still maps to a
        // usable value rather than crashing the list.
        val future = TransferItem(
            name = "future.bin",
            direction = TransferDirection.CROSS_HOST,
            hostName = "Edge",
            progress = 0f,
            status = TransferStatus.QUEUED,
            sizeLabel = "1 KB",
        ).toEntity().copy(direction = "SOME_FUTURE_DIRECTION")

        assertThat(future.toDomain().direction).isEqualTo(TransferDirection.DOWNLOAD)
    }
}
