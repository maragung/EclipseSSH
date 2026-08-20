package dev.eclipse.ssh.presentation

import dev.eclipse.ssh.data.model.TransferItem
import dev.eclipse.ssh.data.model.TransferStatus

/**
 * How many rows of one list — a remote directory, a local folder, the transfer queue — are drawn.
 *
 * All three are plain `Column`s of rows, not `LazyColumn`s, because they sit inside the workspace's
 * own `verticalScroll` — a lazy list needs a bounded height there, and giving it one would turn the
 * page into nested scroll panes. The consequence is that every row is composed at once, and none of
 * the three sources is bounded: `readDir` returns whatever the server says (`/usr/bin`, a maildir, a
 * photo folder, `node_modules` — thousands of names), and a directory sync writes one transfer row
 * per file and never removes them. At six to fifteen layout nodes per row that is tens of thousands
 * of nodes built on the main thread for one tap, rebuilt in full whenever the selection changes
 * because every row reads the selection set. The screen froze, and past a large enough list Android
 * killed the app for not responding.
 *
 * 500 is chosen to be past any directory a person browses by hand while staying inside a single
 * frame's worth of work. Nothing is hidden when it bites: the count of what was left out is drawn
 * under the last row, subdirectories are still navigable, and Sync still walks the whole tree
 * (bounded separately by `SftpDirectoryService.MAX_TREE_ENTRIES`).
 */
internal const val MAX_LISTED_ENTRIES = 500

/**
 * The transfer rows the queue screen draws, in order.
 *
 * Sorted before it is cut. The queue arrives ordered by status *name*, and "COMPLETE" sorts first
 * alphabetically, so taking the first [MAX_LISTED_ENTRIES] of a large sync would show finished
 * uploads and hide the one still running — the only row on the screen the user can act on, and the
 * only one they are watching. Everything unfinished therefore comes first.
 *
 * [List.sortedBy] is stable, so the queue's own order survives inside each of the two groups.
 */
internal fun transfersForDisplay(transfers: List<TransferItem>): List<TransferItem> =
    transfers.sortedBy { if (it.status == TransferStatus.COMPLETE) 1 else 0 }.take(MAX_LISTED_ENTRIES)
