package dev.eclipse.ssh.feature.vault

import javax.inject.Inject
import javax.inject.Singleton

/**
 * The process-wide record that a storage-access picker is in flight.
 *
 * This exists because of what a SAF picker *is*: `ACTION_OPEN_DOCUMENT` and friends are activities of
 * another app, so while one is on screen this app has no started activity at all and
 * `ProcessLifecycleOwner` reports the process as backgrounded - the same report it gives when the
 * user has actually put the phone down. The auto-lock clock in `MainActivity` starts on that report,
 * which is correct for a user who left and wrong for a picker they are still looking at. The
 * observer therefore suspends the countdown while this flag is held.
 *
 * It is a singleton rather than a `remember` because the pickers are no longer all launched from one
 * composition. The Settings rows that open one - generating a key pair, exporting an encrypted
 * backup, saving the connection log, exporting the userspace install log - are windows of their own
 * now, and a window cannot reach a `remember` owned by `MainActivity`. Left as it was, a picker
 * opened from one of those screens would start the countdown, and a user who took longer over it
 * than their configured auto-lock delay would come back to a lock screen with their choice
 * discarded. The flag is process-wide for the same reason the lifecycle report is.
 *
 * [pickerActive] is a plain `@Volatile` boolean and not a `mutableStateOf`: nothing composes from
 * it. It is written by whichever window opened the picker and read by a lifecycle observer, so what
 * it needs is visibility across threads, not recomposition.
 *
 * A boolean rather than a count, deliberately. The two are equally correct while every open is
 * matched by exactly one close, and they are not equally wrong when one is missed: a leaked count
 * keeps the clock suspended for the rest of the process's life, so the vault would quietly stop
 * re-locking and nothing would say so. A leaked `true` is cleared by the next picker that completes,
 * which makes the worst case a single mistimed lock rather than a disabled one.
 */
@Singleton
class VaultUnlockGate @Inject constructor() {

    /** True while a picker opened from any window of this app is on screen. */
    @Volatile
    var pickerActive: Boolean = false

    /** A picker is being launched, or a chain of them is still running. */
    fun hold() {
        pickerActive = true
    }

    /**
     * The picker came back, or its chain finished.
     *
     * Called from the launcher callback rather than from `onResume`: the callback is the moment the
     * result exists, and the write it triggers may itself open the next picker in a chain, which
     * holds the flag again before this release can be observed.
     */
    fun release() {
        pickerActive = false
    }
}
