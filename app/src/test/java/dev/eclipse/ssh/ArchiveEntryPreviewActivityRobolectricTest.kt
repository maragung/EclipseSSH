package dev.eclipse.ssh

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.archive.ArchiveEntry
import dev.eclipse.ssh.ui.archive.ArchiveEntryPreviewActivity
import dev.eclipse.ssh.ui.preview.ArchivePreviewRequest
import dev.eclipse.ssh.ui.preview.PreviewRequests
import java.time.Duration
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The archive entry preview's window, as against its content.
 *
 * The twin of [FilePreviewActivityRobolectricTest], and the difference between the two is the reason
 * this class exists separately: an entry's bytes come from the *archive's* own source through a
 * closure, never from a file system, so what the window has to get right here is carrying a reader
 * rather than a provider — including the case where there is no reader to carry, which must say so
 * rather than draw an empty body that reads as an empty file.
 *
 * Same one-shot handoff as the file preview: the entry waits in [PreviewRequests] and the intent
 * carries a token, because neither an [ArchiveEntry] nor a closure over an open archive survives a
 * `putExtra`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EclipseApp::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class ArchiveEntryPreviewActivityRobolectricTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private fun entry(path: String, contents: String) = ArchiveEntry(
        path = path,
        isDirectory = false,
        size = contents.length.toLong(),
        compressedSize = contents.length.toLong(),
        modifiedEpochMillis = null,
        method = 0,
        dataOffset = 0L,
        encrypted = false,
    )

    /** The intent the app itself builds, so the handoff's two halves are the shipped ones. */
    private fun intentFor(path: String, contents: String, readable: Boolean = true): Intent {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return ArchiveEntryPreviewActivity.intent(
            context = context,
            entry = entry(path, contents),
            readEntry = if (readable) ({ contents.toByteArray() }) else null,
        )
    }

    private fun intentCarrying(token: String?): Intent {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Intent(context, ArchiveEntryPreviewActivity::class.java)
            .also { intent -> token?.let { intent.putExtra(ArchiveEntryPreviewActivity.EXTRA_REQUEST_TOKEN, it) } }
    }

    /** As the file preview's: `idleFor`, because the entry is read on a coroutine that comes due. */
    private fun pumpUntil(timeoutMs: Long = 20_000, describe: () -> String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline && !condition()) {
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        }
        check(condition()) { "timed out after ${timeoutMs}ms: ${describe()}" }
    }

    /**
     * The window opens on the entry its intent names — and shows its *name*, not its path.
     *
     * The header is the entry's own name because the path is the archive browser's breadcrumb, and the
     * window was opened from the folder holding it; a preview headed by a long path would be a
     * narrower version of the breadcrumb the user just left. Text is what is read and drawn here, and
     * the raw bytes are read through the closure the request carried — so an entry whose name is on
     * screen with nothing under it would be a reader that arrived unusable.
     */
    @Test
    fun thePreviewOpensOnTheEntryItsIntentNamesAndReadsIt() {
        ActivityScenario.launch<ArchiveEntryPreviewActivity>(intentFor("logs/app.log", "boom")).use { scenario ->
            assertThat(scenario.state).isEqualTo(Lifecycle.State.RESUMED)
            pumpUntil(describe = { "the entry's name never reached the window" }) {
                compose.onAllNodesWithText("app.log").fetchSemanticsNodes().isNotEmpty()
            }
            pumpUntil(describe = { "the entry's bytes never arrived; the reader was not carried" }) {
                compose.onAllNodesWithText("boom").fetchSemanticsNodes().isNotEmpty()
            }
        }
    }

    /**
     * An entry with no reader says so, rather than drawing an empty body.
     *
     * This is the state a compressed TAR's entry arrives in, and the reason the reader is nullable: the
     * format cannot seek to the entry's bytes, so the honest answer is that this cannot be previewed
     * in-archive and extraction is the way on. An empty body would be indistinguishable from an empty
     * file — a lie about an entry that may hold everything the user is looking for.
     */
    @Test
    fun anEntryWithNoReaderSaysSoRatherThanShowingNothing() {
        ActivityScenario.launch<ArchiveEntryPreviewActivity>(intentFor("logs/app.log", "boom", readable = false))
            .use { scenario ->
                assertThat(scenario.state).isEqualTo(Lifecycle.State.RESUMED)
                pumpUntil(describe = { "the refusal never reached the window" }) {
                    compose.onAllNodesWithText("No preview for this file type inside the archive.")
                        .fetchSemanticsNodes().isNotEmpty()
                }
                // And the bytes are nowhere near it: the closure it does not have was never invented.
                assertThat(compose.onAllNodesWithText("boom").fetchSemanticsNodes()).isEmpty()
            }
    }

    /** As the file preview's: a re-delivered intent finds nothing, so nothing is shown for it. */
    @Test
    fun anIntentWithNoLiveTokenFinishesRatherThanShowingNothing() {
        ActivityScenario.launch<ArchiveEntryPreviewActivity>(intentCarrying(token = null)).use { scenario ->
            val settled = runCatching { scenario.state }.getOrNull()
            assertThat(settled).isNotEqualTo(Lifecycle.State.RESUMED)
        }
    }

    /**
     * A second entry delivered to the live window replaces the one on screen, without recreating it.
     *
     * The archive browser walks folders inside one archive, so "open the next entry" is the common
     * case rather than the exotic one: what must not happen is a window per glance.
     */
    @Test
    fun aSecondEntryReplacesTheSubjectWithoutRecreatingTheWindow() {
        var secondToken: String? = null
        ActivityScenario.launch<ArchiveEntryPreviewActivity>(intentFor("logs/first.log", "one")).use { scenario ->
            assertThat(scenario.state).isEqualTo(Lifecycle.State.RESUMED)
            pumpUntil(describe = { "the first entry never reached the window" }) {
                compose.onAllNodesWithText("first.log").fetchSemanticsNodes().isNotEmpty()
            }

            val second = PreviewRequests.putArchiveEntry(
                ArchivePreviewRequest(entry("logs/second.log", "two")) { "two".toByteArray() },
            )
            secondToken = second
            scenario.onActivity { activity -> activity.onPreviewToken(second) }

            pumpUntil(describe = { "the second entry never replaced the first" }) {
                compose.onAllNodesWithText("second.log").fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithText("first.log").fetchSemanticsNodes().isEmpty()
            }
            assertThat(scenario.state).isEqualTo(Lifecycle.State.RESUMED)
        }
        assertThat(PreviewRequests.takeArchiveEntry(secondToken)).isNull()
    }
}
