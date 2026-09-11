package dev.eclipse.ssh.feature.terminallog

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import org.junit.Test

class SessionLogTest {

    @Test
    fun `snapshot is empty before anything is appended`() {
        assertThat(SessionLog().snapshot()).isEmpty()
        assertThat(SessionLog().isEmpty()).isTrue()
    }

    @Test
    fun `snapshot returns oldest to newest`() {
        val log = SessionLog()
        log.append("first line\n")
        log.append("second line\n")
        log.append("third line\n")
        assertThat(log.snapshot()).isEqualTo("first line\nsecond line\nthird line\n")
    }

    @Test
    fun `wrapping at the cap drops the oldest content and keeps the newest`() {
        val log = SessionLog(capacityChars = 32)
        repeat(20) { index -> log.append("line $index\n") }
        val snapshot = log.snapshot()
        assertThat(snapshot).contains("line 19\n")
        assertThat(snapshot).doesNotContain("line 0\n")
        // The newest content is at the end, and the retained text is bounded by the cap.
        assertThat(snapshot.endsWith("line 19\n")).isTrue()
        assertThat(snapshot.length).isAtMost(32 + SessionLog.LINE_BOUNDARY_SLACK)
    }

    @Test
    fun `wrapping lands on a line boundary when one is nearby`() {
        val log = SessionLog(capacityChars = 16)
        repeat(10) { index -> log.append("line-$index\n") }
        // Eviction had newlines to use, so nothing retained starts mid-line.
        assertThat(log.snapshot().startsWith("line-")).isTrue()
    }

    @Test
    fun `wrapping does not split a surrogate pair`() {
        val log = SessionLog(capacityChars = 8)
        // 'X' plus a 4-character emoji string per iteration: the cap of 8 lands the cut somewhere
        // inside emoji, and the retained text must still be a well-formed string.
        repeat(50) { log.append("X🤖🐎") }
        val snapshot = log.snapshot()
        // No orphaned half of a surrogate pair anywhere in the retained text.
        snapshot.forEachIndexed { index, char ->
            if (Character.isLowSurrogate(char)) {
                assertThat(index).isNotEqualTo(0)
                assertThat(Character.isHighSurrogate(snapshot[index - 1])).isTrue()
            }
        }
        assertThat(log.snapshot()).isNotEmpty()
    }

    @Test
    fun `a stream with no newlines still evicts`() {
        val log = SessionLog(capacityChars = 16)
        log.append("a".repeat(10_000))
        assertThat(log.snapshot().length).isAtMost(16 + SessionLog.LINE_BOUNDARY_SLACK)
        // And the tail, not the head, is what survived.
        assertThat(log.snapshot()).isEqualTo("a".repeat(log.snapshot().length))
    }

    @Test
    fun `concurrent append and snapshot never throws`() {
        val log = SessionLog(capacityChars = 512)
        val failure = AtomicReference<Throwable?>(null)
        val start = CountDownLatch(1)
        val writers = (1..2).map { thread ->
            Thread {
                start.await()
                repeat(2_000) { index ->
                    runCatching { log.append("thread output line $index with some noise 😀\n") }
                        .onFailure { failure.set(it) }
                }
            }.apply { start() }
        }
        val reader = Thread {
            start.await()
            repeat(2_000) {
                runCatching { log.snapshot() }
                    .onFailure { failure.set(it) }
            }
        }.apply { start() }
        start.countDown()
        writers.forEach { it.join() }
        reader.join()
        // A null would be the failure; spelled as a Boolean so the nullable reference never has to
        // reach an assertion overload that may not accept it.
        assertThat(failure.get() == null).isTrue()
        // Whatever the interleaving produced, the survivor is a valid, bounded, ordered string.
        val snapshot = log.snapshot()
        assertThat(snapshot.length).isAtMost(512 + SessionLog.LINE_BOUNDARY_SLACK)
        assertThat(snapshot.endsWith("\n")).isTrue()
    }
}
