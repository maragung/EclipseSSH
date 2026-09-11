package dev.eclipse.ssh.feature.terminallog

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SilenceDetectorTest {

    /** A clock the test moves by hand, in the `() -> Long` shape the detector injects. */
    private var now = 0L
    private val clock: () -> Long = { now }

    private fun detector(silenceMs: Long = SilenceDetector.SILENCE_MS) =
        SilenceDetector(silenceMs = silenceMs, clock = clock)

    @Test
    fun `fires after output then silence`() {
        val detector = detector()
        now = 1_000
        detector.arm()
        now = 1_500
        detector.onOutput() // the command's output arrives after the arming
        now = 3_400 // 1_900 ms of quiet - not yet
        assertThat(detector.poll()).isFalse()
        now = 3_501 // past 2 000 ms of quiet
        assertThat(detector.poll()).isTrue()
    }

    @Test
    fun `does not fire when no output followed the arming`() {
        val detector = detector()
        now = 1_000
        detector.arm()
        now = 100_000 // the session was idle before arming; still idle is not "finished"
        assertThat(detector.poll()).isFalse()
        assertThat(detector.isArmed).isTrue()
    }

    @Test
    fun `does not fire twice for one arming`() {
        val detector = detector()
        now = 0
        detector.arm()
        now = 100
        detector.onOutput()
        now = 5_000
        assertThat(detector.poll()).isTrue()
        now = 50_000
        assertThat(detector.poll()).isFalse()
        assertThat(detector.isArmed).isFalse()
    }

    @Test
    fun `is re-armable after firing`() {
        val detector = detector()
        now = 0
        detector.arm()
        now = 100
        detector.onOutput()
        now = 5_000
        assertThat(detector.poll()).isTrue()

        now = 6_000
        detector.arm() // the next command
        assertThat(detector.isArmed).isTrue()
        now = 6_100
        detector.onOutput()
        now = 7_000 // only 900 ms of quiet - too early
        assertThat(detector.poll()).isFalse()
        now = 8_101 // past the silence window again
        assertThat(detector.poll()).isTrue()
    }

    @Test
    fun `output before arming does not count`() {
        val detector = detector()
        now = 0
        detector.onOutput() // output from before the user asked
        now = 10
        detector.arm()
        now = 10_000 // no output *after* the arming
        assertThat(detector.poll()).isFalse()
    }

    @Test
    fun `output during the silence window restarts the wait`() {
        val detector = detector()
        now = 0
        detector.arm()
        now = 100
        detector.onOutput()
        now = 2_000
        detector.onOutput() // 1 900 ms in - a second burst resets the clock
        now = 3_900
        assertThat(detector.poll()).isFalse()
        now = 4_001
        assertThat(detector.poll()).isTrue()
    }

    @Test
    fun `disarmed when told`() {
        val detector = detector()
        now = 0
        detector.arm()
        now = 100
        detector.onOutput()
        detector.disarm()
        now = 100_000
        assertThat(detector.poll()).isFalse()
        assertThat(detector.isArmed).isFalse()
    }
}
