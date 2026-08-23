package dev.eclipse.ssh.presentation

import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.terminal.AnsiTerminalBuffer
import dev.eclipse.ssh.terminal.TerminalFrame
import org.junit.Test

/**
 * The rule that keeps a terminal from being blanked by a frame that was built before the one already
 * on screen.
 *
 * Two things publish frames for the same session and they run on different threads - the output
 * collector on a background dispatcher, and `attachTerminal`, session adoption, scrolling and
 * resizing on the main thread. Building a frame and writing it are two steps, so a writer that is
 * preempted between them writes a snapshot that has gone stale in the meantime. Reproducing that
 * interleaving on demand means winning a race deliberately, which no test can promise; the ordering
 * rule it depends on is a pure function, so the rule is tested here instead and the integration is
 * covered by the lifecycle suite.
 *
 * Real revisions from a real buffer rather than hand-built [TerminalFrame]s, because the property
 * being relied on belongs to the buffer: that `revision` advances on output and never goes backwards.
 * A test that invented those numbers would still pass if that stopped being true.
 */
class TerminalFramePublishOrderTest {

    private val buffer = AnsiTerminalBuffer(columns = 40, rows = 8)

    @Test
    fun `the first frame of a session is published`() {
        // Nothing to compare against: a session that has just opened its tab has no frame yet, and the
        // empty screen it starts with is what the user should see until output arrives.
        val first = buffer.frame()
        assertThat(newerTerminalFrame(null, first)).isSameInstanceAs(first)
    }

    @Test
    fun `a frame built before the published one does not replace it`() {
        // The bug this exists for: `attachTerminal` builds the empty frame of a session that has just
        // come up, the collector feeds the login banner and publishes it while that build is in
        // flight, and the empty build lands last. A shell at its prompt sends nothing more, so no
        // later frame corrects it - the terminal simply stays blank.
        val atAttach = buffer.frame()
        buffer.feed("login: welcome\r\n$ ")
        val withBanner = buffer.frame()
        assertThat(withBanner.revision).isGreaterThan(atAttach.revision)

        assertThat(newerTerminalFrame(published = withBanner, built = atAttach)).isSameInstanceAs(withBanner)
        assertThat(newerTerminalFrame(published = withBanner, built = atAttach).asDrawn()).contains("welcome")
    }

    @Test
    fun `a frame built after the published one replaces it`() {
        // The ordinary case, and the one that must not become collateral damage of the guard above:
        // output that arrived after the frame on screen is the whole point of publishing.
        val before = buffer.frame()
        buffer.feed("$ uname -a\r\n")
        val after = buffer.frame()
        assertThat(newerTerminalFrame(published = before, built = after)).isSameInstanceAs(after)
    }

    @Test
    fun `a rebuild of an unchanged buffer replaces the published frame`() {
        // Same revision, different viewport: scrolling back through the scrollback, a resize, and the
        // republish that happens when the app comes back to the foreground all rebuild a buffer that
        // has not changed. Rejecting those would freeze the terminal at whatever was last printed -
        // the user could not scroll - so equal revisions have to keep the incoming frame.
        buffer.feed((1..40).joinToString("\r\n") { "line $it" })
        val onScreen = buffer.frame()
        val scrolledBack = buffer.frame(scrollOffset = 12)
        assertThat(scrolledBack.revision).isEqualTo(onScreen.revision)
        assertThat(scrolledBack.firstLine).isLessThan(onScreen.firstLine)

        assertThat(newerTerminalFrame(published = onScreen, built = scrolledBack)).isSameInstanceAs(scrolledBack)
    }

    private fun TerminalFrame.asDrawn(): String =
        lines.joinToString(separator = "\n") { row -> row.joinToString(separator = "") { it.value.toString() } }
}
