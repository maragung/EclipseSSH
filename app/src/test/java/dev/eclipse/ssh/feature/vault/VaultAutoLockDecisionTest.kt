package dev.eclipse.ssh.feature.vault

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The rule that decides whether coming back from the background re-locks the vault.
 *
 * The decision runs in a lifecycle observer, where it cannot be exercised without putting a real
 * activity in the background for a real number of minutes, so it is a pure function taking the
 * timestamps as parameters — see [shouldRelockVault] — and the matrix is tested here directly.
 */
class VaultAutoLockDecisionTest {

    private val backgroundedAt = 1_000_000L

    private fun relockAfter(elapsedMs: Long, autoLockMinutes: Int = 5, lockConfigured: Boolean = true) =
        shouldRelockVault(
            autoLockMinutes = autoLockMinutes,
            lockConfigured = lockConfigured,
            backgroundedAtMs = backgroundedAt,
            nowMs = backgroundedAt + elapsedMs,
        )

    @Test
    fun `a return below the delay leaves the vault unlocked`() {
        // The glance-away-and-back this setting exists to tolerate: a notification answered, a
        // password copied from somewhere else, a file picker consulted. 4:59 of it with the default
        // 5 minutes must not ask again.
        assertThat(relockAfter(5 * 60_000L - 1_000L)).isFalse()
    }

    @Test
    fun `a return at or past the delay re-locks`() {
        assertThat(relockAfter(5 * 60_000L)).isTrue()
        assertThat(relockAfter(5 * 60_000L + 1)).isTrue()
        // An hour away on the 1-minute setting: far past, not edge.
        assertThat(relockAfter(60 * 60_000L, autoLockMinutes = 1)).isTrue()
    }

    @Test
    fun `never never re-locks`() {
        // 0 is the "Never" chip: any absence, however long, leaves the vault as it was.
        assertThat(relockAfter(24 * 60 * 60_000L, autoLockMinutes = 0)).isFalse()
    }

    @Test
    fun `a vault with no lock configured is never re-locked`() {
        // The PIN is the only lock this app has; biometric is a way through that screen, not a lock
        // of its own. With no PIN there is no lock screen to return to, and the delay — even a very
        // long absence — must not invent one.
        assertThat(relockAfter(60 * 60_000L, lockConfigured = false)).isFalse()
    }

    @Test
    fun `every delay the settings screen offers behaves at its own edge`() {
        // The chips the dialog offers, each pinned at its boundary: one millisecond below the
        // deadline stays unlocked, the deadline itself re-locks.
        for (minutes in listOf(1, 5, 15, 60)) {
            assertThat(relockAfter(minutes * 60_000L - 1, autoLockMinutes = minutes)).isFalse()
            assertThat(relockAfter(minutes * 60_000L, autoLockMinutes = minutes)).isTrue()
        }
    }
}
