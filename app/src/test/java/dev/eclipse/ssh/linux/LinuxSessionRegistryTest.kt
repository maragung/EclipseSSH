package dev.eclipse.ssh.linux

import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.ssh.SessionEnd
import dev.eclipse.ssh.ssh.TerminalChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.junit.Test

/**
 * The session registry's lifecycle contract: what registers, what unregisters, and above all that
 * a session which ends on its own cannot linger as a corpse in the count.
 *
 * The registry supervises every channel it holds — it awaits the channel's ending and forgets it —
 * because the only unregister in the app is the tab's close path, and a shell the user exits with
 * `exit` never goes through it. The tests inject an [Dispatchers.Unconfined] supervision scope so
 * a supervisor runs its await inline and resumes on the very thread that ends the channel, making
 * each ending's bookkeeping synchronous for the assertions that follow it.
 */
class LinuxSessionRegistryTest {

    private fun newRegistry(): LinuxSessionRegistry = LinuxSessionRegistry(CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun `a session that ends on its own unregisters itself`() {
        val registry = newRegistry()
        val channel = FakeTerminalChannel()

        registry.register("local-1", channel)
        assertThat(registry.sessionCount.value).isEqualTo(1)
        assertThat(registry.channelFor("local-1")).isSameInstanceAs(channel)

        // The user typed exit: the channel reports its own ending and no app close path runs —
        // and still the registry forgets the session, so the count the settings screen and the
        // notification render stays the number of *live* sessions.
        channel.end(SessionEnd.ShellEnded(status = 0, signal = null))

        assertThat(registry.channelFor("local-1")).isNull()
        assertThat(registry.sessionCount.value).isEqualTo(0)
    }

    @Test
    fun `a replacement under the same key survives the old channel's ending`() {
        val registry = newRegistry()
        val first = FakeTerminalChannel()
        val second = FakeTerminalChannel()

        registry.register("local-1", first)
        registry.register("local-1", second) // a reconnect replaced the channel under this key

        first.end(SessionEnd.ShellEnded(status = 0, signal = null))

        // The supervisor forgets by identity, not by key: the old session's exit must not evict
        // its own replacement.
        assertThat(registry.channelFor("local-1")).isSameInstanceAs(second)
        assertThat(registry.sessionCount.value).isEqualTo(1)
    }

    @Test
    fun `an explicit unregister wins and the later ending is a no-op`() {
        val registry = newRegistry()
        val channel = FakeTerminalChannel()

        registry.register("local-1", channel)
        registry.unregister("local-1")
        assertThat(registry.sessionCount.value).isEqualTo(0)

        // The tab was closed first; the channel's ending arrives later and must not touch the
        // count, and must not throw.
        channel.end(SessionEnd.ShellEnded(status = 0, signal = null))

        assertThat(registry.sessionCount.value).isEqualTo(0)
        assertThat(registry.channelFor("local-1")).isNull()
    }

    @Test
    fun `closeAll closes every live session and empties the registry`() {
        val registry = newRegistry()
        val first = FakeTerminalChannel()
        val second = FakeTerminalChannel()
        registry.register("local-1", first)
        registry.register("local-2", second)

        val closed = registry.closeAll()

        assertThat(closed.sorted()).isEqualTo(listOf("local-1", "local-2"))
        assertThat(first.closeCount).isEqualTo(1)
        assertThat(second.closeCount).isEqualTo(1)
        assertThat(registry.sessionCount.value).isEqualTo(0)
        assertThat(registry.channelFor("local-1")).isNull()
        assertThat(registry.channelFor("local-2")).isNull()
    }

    @Test
    fun `the count follows registration and unregistration exactly`() {
        val registry = newRegistry()

        val a = FakeTerminalChannel()
        val b = FakeTerminalChannel()
        registry.register("local-1", a)
        registry.register("local-2", b)
        assertThat(registry.sessionCount.value).isEqualTo(2)

        registry.unregister("local-1")
        assertThat(registry.sessionCount.value).isEqualTo(1)

        // Registering the same key again replaces, it does not grow.
        registry.register("local-2", FakeTerminalChannel())
        assertThat(registry.sessionCount.value).isEqualTo(1)
    }

    @Test
    fun `the live count of other sessions excludes the named one by key, evicted or not`() {
        val registry = newRegistry()
        val judged = FakeTerminalChannel()

        registry.register("local-1", judged)

        // The session being judged is the only one registered, so nothing is left to speak for
        // the userspace and the question answers no.
        assertThat(registry.liveCountExcept("local-1")).isEqualTo(0)

        val sibling = FakeTerminalChannel()
        registry.register("local-2", sibling)

        // Two sessions are counted, and the answer is 1: the judged session is excluded by name,
        // and the sibling is the proof that one bad ending says nothing about the userspace.
        assertThat(registry.sessionCount.value).isEqualTo(2)
        val whileJudgedIsStillRegistered = registry.liveCountExcept("local-1")
        assertThat(whileJudgedIsStillRegistered).isEqualTo(1)

        // The caller is the collector reporting the ending, and the registry evicts the session
        // from its own supervisor coroutine — two threads with nothing ordering them — so the
        // named session may or may not still be in the table when the count is asked. Here it is
        // gone, and the answer must not have moved: this is an exclusion by key, and the same
        // question read as the count with one subtracted would now answer 0, which is the very
        // "no other session survives" claim this method exists not to make.
        judged.end(SessionEnd.ShellEnded(status = 0, signal = null))
        assertThat(registry.channelFor("local-1")).isNull()
        assertThat(registry.sessionCount.value).isEqualTo(1)

        val afterJudgedWasEvicted = registry.liveCountExcept("local-1")
        assertThat(afterJudgedWasEvicted).isEqualTo(whileJudgedIsStillRegistered)
        assertThat(afterJudgedWasEvicted).isEqualTo(1)
    }

    @Test
    fun `the earlier name still addresses the renamed registry`() {
        // The deprecated typealias exists so the graph provider and the manager keep compiling at
        // the central merge; this pins that the two names are one type, not two.
        @Suppress("DEPRECATION")
        val registry: LinuxProcessManager = newRegistry()
        val channel = FakeTerminalChannel()

        registry.register("local-1", channel)

        assertThat(registry.channelFor("local-1")).isSameInstanceAs(channel)
        assertThat(registry.sessionCount.value).isEqualTo(1)
    }
}

/**
 * The least [TerminalChannel] the registry needs: an ending the test controls and a count of the
 * closes. Nothing else in the contract matters to the registry — it holds channels, awaits their
 * endings and closes them — so the rest is inert.
 */
private class FakeTerminalChannel : TerminalChannel {
    private val ended = CompletableDeferred<SessionEnd>()

    var closeCount = 0
        private set

    override val output: SharedFlow<ByteArray> = MutableSharedFlow()

    override suspend fun awaitClosed(): SessionEnd = ended.await()

    override val hasEnded: Boolean get() = ended.isCompleted

    override val endedDeliberately: Boolean get() = false

    override val droppedChunks: Long get() = 0

    override val lastActivityAtMs: Long get() = 0

    override val openedAtMs: Long get() = 0

    override fun idleForMs(nowMs: Long): Long? = null

    override val ptyColumns: Int get() = 120

    override val ptyRows: Int get() = 40

    override val ptyLabel: String get() = "120x40"

    override val channelLabel: String get() = if (isOpen) "open" else "closed"

    override val isOpen: Boolean get() = !hasEnded

    override fun writeBytes(bytes: ByteArray) = Unit

    override fun write(value: String) = Unit

    override fun resize(columns: Int, rows: Int) = Unit

    override fun markDeliberate() = Unit

    override fun discard(reason: SessionEnd?) {
        ended.complete(SessionEnd.Released)
    }

    override fun close() {
        closeCount++
        ended.complete(SessionEnd.Released)
    }

    fun end(reason: SessionEnd) {
        ended.complete(reason)
    }
}
