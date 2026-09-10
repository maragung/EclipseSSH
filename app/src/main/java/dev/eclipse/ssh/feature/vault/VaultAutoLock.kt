package dev.eclipse.ssh.feature.vault

/**
 * Whether a return from the background re-locks the vault, as a pure function.
 *
 * The rule is one comparison, but it is the gate in front of every secret the app holds, so it is
 * stated here on its own and pinned by its own test rather than living inline in a lifecycle
 * observer where it cannot be exercised without putting a whole activity in the background. The
 * observer in `MainActivity` records when the app left the foreground and asks this on the way back
 * in; the timestamps are parameters so the test can move time freely.
 *
 * [lockConfigured] is the PIN lock: that is the only lock this app has. The lock screen composes
 * when a PIN is set, and biometric unlock is a second way *through* that screen rather than a lock
 * of its own — so with no PIN there is nothing to re-arm and the answer is false no matter how long
 * the app was away.
 */
fun shouldRelockVault(
    autoLockMinutes: Int,
    lockConfigured: Boolean,
    backgroundedAtMs: Long,
    nowMs: Long,
): Boolean {
    if (!lockConfigured || autoLockMinutes <= 0) return false
    return nowMs - backgroundedAtMs >= autoLockMinutes * 60_000L
}
