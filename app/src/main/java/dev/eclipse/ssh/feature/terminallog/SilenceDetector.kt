package dev.eclipse.ssh.feature.terminallog

/**
 * Decides when a long-running command has finished, for "Notify when done".
 *
 * The signal a terminal gives that a command is over is not an exit code - the app has no channel
 * for one - it is *silence*: the command's output stops and the prompt comes back. So the detector
 * is armed by the user, waits to see output arrive after the arming (proof that a command is
 * actually running rather than a session that was already idle), and reports "done" once that
 * output has stayed quiet for [SILENCE_MS].
 *
 * [SILENCE_MS] is 2 000 ms because a prompt's return is the user's signal that a command is over
 * - on an interactive terminal, two seconds of nothing after a burst is a finished command, not a
 * slow one - and because terminals flush line-buffered, so a program mid-thought does not hold its
 * output for long without a pause the user would call "waiting". Long enough not to fire between
 * two lines of a slow build, short enough that the notification lands while the user still cares.
 *
 * Arming is a one-shot: [poll] reports "done" at most once per [arm], and the caller is expected
 * to disarm (or simply drop the detector) after it fires or when the session ends - a session that
 * dies under a command must not be reported as "finished".
 *
 * Pure Kotlin with an injectable clock, so the tests can move time by hand. Thread-safe because
 * the terminal collector feeds it from its own coroutine while a watchdog polls it from another.
 */
class SilenceDetector(
    private val silenceMs: Long = SILENCE_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val lock = Any()
    private var armed = false
    private var sawOutputAfterArm = false
    private var lastOutputAt = 0L
    private var fired = false

    /** Whether an arming is in effect - armed, and not yet fired or disarmed. */
    val isArmed: Boolean
        get() = synchronized(lock) { armed }

    /**
     * Starts waiting. Forgets any previous firing, so a detector is re-armable: the user who asked
     * to be told about one command can ask again about the next.
     */
    fun arm(nowMs: Long = clock()) {
        synchronized(lock) {
            armed = true
            fired = false
            sawOutputAfterArm = false
            lastOutputAt = nowMs
        }
    }

    /** Records output at [atMs]. Output seen while armed is what makes a later silence meaningful. */
    fun onOutput(atMs: Long = clock()) {
        synchronized(lock) {
            if (armed) sawOutputAfterArm = true
            lastOutputAt = atMs
        }
    }

    /**
     * Whether the armed command has finished: output was seen after arming, and nothing has
     * arrived for [silenceMs]. True at most once per arming - the firing latches, so a polling
     * caller cannot be told twice about one command.
     */
    fun poll(nowMs: Long = clock()): Boolean = synchronized(lock) {
        if (!armed || fired || !sawOutputAfterArm) return@synchronized false
        if (nowMs - lastOutputAt < silenceMs) return@synchronized false
        fired = true
        armed = false
        true
    }

    /** Cancels any arming. A later [poll] reports nothing until [arm] is called again. */
    fun disarm() {
        synchronized(lock) {
            armed = false
            fired = false
        }
    }

    companion object {
        /**
         * How long output must stay quiet before an armed session is reported as finished. See the
         * class doc for why this is two seconds.
         */
        const val SILENCE_MS = 2_000L
    }
}
