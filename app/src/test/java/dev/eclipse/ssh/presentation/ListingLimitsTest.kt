package dev.eclipse.ssh.presentation

import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.data.model.TransferDirection
import dev.eclipse.ssh.data.model.TransferItem
import dev.eclipse.ssh.data.model.TransferStatus
import org.junit.Test

/**
 * The transfer queue is drawn as a plain column of cards, so the number of them is bounded. These
 * pin *which* ones survive the bound, which is the part that is easy to get wrong and impossible to
 * see: the rows a user can act on have to be the rows that are drawn.
 */
class ListingLimitsTest {

    private fun item(name: String, status: TransferStatus) = TransferItem(
        id = name,
        name = name,
        direction = TransferDirection.DOWNLOAD,
        hostName = "host",
        progress = 0f,
        status = status,
        sizeLabel = "1 KB",
    )

    @Test
    fun `a queue that fits is returned whole`() {
        val queue = List(10) { item("f$it", TransferStatus.COMPLETE) }

        assertThat(transfersForDisplay(queue)).isEqualTo(queue)
    }

    @Test
    fun `nothing beyond the limit is drawn`() {
        val queue = List(MAX_LISTED_ENTRIES + 250) { item("f$it", TransferStatus.COMPLETE) }

        assertThat(transfersForDisplay(queue)).hasSize(MAX_LISTED_ENTRIES)
    }

    @Test
    fun `the running transfer survives a queue full of completed ones`() {
        // Exactly the shape a large directory sync leaves behind: the DAO orders by status name, so
        // every COMPLETE row sorts ahead of the RUNNING one. Cutting without sorting first would
        // drop the only row the user is watching, and the only one with a Pause button.
        val queue = List(MAX_LISTED_ENTRIES + 100) { item("done$it", TransferStatus.COMPLETE) } +
            item("in-flight", TransferStatus.RUNNING)

        val visible = transfersForDisplay(queue)

        assertThat(visible).hasSize(MAX_LISTED_ENTRIES)
        assertThat(visible.first().name).isEqualTo("in-flight")
        assertThat(visible.map { it.name }).contains("in-flight")
    }

    @Test
    fun `every unfinished status outranks completed ones`() {
        val queue = listOf(
            item("done", TransferStatus.COMPLETE),
            item("queued", TransferStatus.QUEUED),
            item("failed", TransferStatus.FAILED),
            item("paused", TransferStatus.PAUSED),
            item("running", TransferStatus.RUNNING),
        )

        assertThat(transfersForDisplay(queue).map { it.name })
            .isEqualTo(listOf("queued", "failed", "paused", "running", "done"))
    }

    @Test
    fun `order within a group is the order it arrived in`() {
        // The DAO sorts by name inside each status, and that ordering has to survive: a stable sort
        // is the whole reason this is sortedBy and not a partition rebuilt in some other order.
        val queue = listOf(
            item("a-done", TransferStatus.COMPLETE),
            item("b-queued", TransferStatus.QUEUED),
            item("c-done", TransferStatus.COMPLETE),
            item("d-queued", TransferStatus.QUEUED),
        )

        assertThat(transfersForDisplay(queue).map { it.name })
            .isEqualTo(listOf("b-queued", "d-queued", "a-done", "c-done"))
    }

    @Test
    fun `an empty queue is empty`() {
        assertThat(transfersForDisplay(emptyList())).isEmpty()
    }
}
