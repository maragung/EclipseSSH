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
        assertThat(roundTripped.transferredBytes).isEqualTo(0L)
        assertThat(roundTripped.retryCount).isEqualTo(0)
        assertThat(roundTripped.scheduledAt).isNull()
        assertThat(roundTripped.repeatMinutes).isNull()
    }
}
