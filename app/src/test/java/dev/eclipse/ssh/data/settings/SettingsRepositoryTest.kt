package dev.eclipse.ssh.data.settings

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.eclipse.ssh.data.model.TerminalTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * DataStore persistence for the settings a vault backup restores. Every field the backup
 * carries needs a setter that actually writes it — a missing one silently drops the value on
 * import, which is how `reconnectBaseSeconds` used to be lost.
 *
 * `preferencesDataStore` caches one store per delegate for the whole classloader, so the
 * store outlives each test method here. Names are numbered and the order fixed so the
 * pristine-defaults check runs before anything writes to it.
 */
@RunWith(RobolectricTestRunner::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Config(sdk = [35])
class SettingsRepositoryTest {

    private val repository get() = SettingsRepository(RuntimeEnvironment.getApplication())

    /**
     * Puts every setting this class writes back to its default, so the store it shares with the rest
     * of the suite is pristine on the way out as well as on the way in.
     *
     * The cache described above is not scoped to this class, and Gradle promises no order for test
     * classes: `02` writes ten settings and restores none, so a sibling class that happens to run
     * afterwards reads *those* values where it asserts the defaults. That is not hypothetical - it
     * failed exactly that way, as `MainActivitySecureWindowTest`'s "the window is not secured unless
     * the user asks for it" finding `blockScreenshots = true`, a value that test never sets and could
     * not see written. The two restores further down (`07`, `09`) were the same realisation applied one
     * field at a time; this covers the whole set, including the fields nobody had noticed were leaking.
     *
     * The values are the ones `01` asserts, deliberately: the pristine state and the reset to it are
     * one list in one file, so they cannot drift apart. `clearPin` is here because `04` sets a PIN, and
     * a PIN left in the store gates the *whole app* - a sibling class rendering `MainActivity` would
     * meet a lock screen it has no code to answer, and report that as its own UI never appearing.
     */
    @After
    fun restoreTheDefaults() {
        runBlocking {
            val repo = repository
            repo.setBiometricUnlock(true)
            repo.setDarkTheme(true)
            repo.setClipboardSeconds(30)
            repo.setKeepAliveSeconds(30)
            repo.setReconnectBaseSeconds(SettingsRepository.DEFAULT_RECONNECT_BASE_SECONDS)
            repo.setTerminalFontSize(SettingsRepository.DEFAULT_TERMINAL_FONT_SIZE)
            repo.setTerminalKeyRowVisible(true)
            repo.setTerminalMinColumns(SettingsRepository.DEFAULT_TERMINAL_MIN_COLUMNS)
            repo.setLegacyAlgorithms(false)
            repo.setTerminalTheme(TerminalTheme.DARK.name)
            repo.setBlockScreenshots(false)
            repo.clearPin()
        }
    }

    @Test
    fun `01 defaults are returned before anything is written`() = runTest {
        val settings = repository.settings.first()

        assertThat(settings.biometricUnlock).isTrue()
        assertThat(settings.darkTheme).isTrue()
        assertThat(settings.clearClipboardAfterSeconds).isEqualTo(30)
        assertThat(settings.keepAliveSeconds).isEqualTo(30)
        assertThat(settings.reconnectBaseSeconds).isEqualTo(SettingsRepository.DEFAULT_RECONNECT_BASE_SECONDS)
        assertThat(settings.terminalFontSize).isEqualTo(SettingsRepository.DEFAULT_TERMINAL_FONT_SIZE)
        // Shown by default: the row carries ESC, TAB and CTRL, which a phone keyboard does not, so a
        // first-run terminal that hid it would have no way to interrupt a command.
        assertThat(settings.terminalKeyRowVisible).isTrue()
        assertThat(settings.pinEnabled).isFalse()
        assertThat(settings.legacyAlgorithms).isFalse()
        assertThat(settings.terminalTheme).isEqualTo(TerminalTheme.DARK.name)
        // Off unless asked for: FLAG_SECURE blocks the user's own screenshots too.
        assertThat(settings.blockScreenshots).isFalse()
    }

    @Test
    fun `02 every backup carried setting survives a write and read`() = runTest {
        val repo = repository
        repo.setBiometricUnlock(false)
        repo.setDarkTheme(false)
        repo.setClipboardSeconds(90)
        repo.setKeepAliveSeconds(15)
        repo.setReconnectBaseSeconds(12)
        repo.setTerminalFontSize(18)
        repo.setTerminalKeyRowVisible(false)
        repo.setLegacyAlgorithms(true)
        repo.setTerminalTheme(TerminalTheme.entries.last().name)
        repo.setBlockScreenshots(true)

        val settings = repo.settings.first()

        assertThat(settings.biometricUnlock).isFalse()
        assertThat(settings.darkTheme).isFalse()
        assertThat(settings.clearClipboardAfterSeconds).isEqualTo(90)
        assertThat(settings.keepAliveSeconds).isEqualTo(15)
        assertThat(settings.reconnectBaseSeconds).isEqualTo(12)
        assertThat(settings.terminalFontSize).isEqualTo(18)
        assertThat(settings.terminalKeyRowVisible).isFalse()
        assertThat(settings.legacyAlgorithms).isTrue()
        assertThat(settings.terminalTheme).isEqualTo(TerminalTheme.entries.last().name)
        assertThat(settings.blockScreenshots).isTrue()
    }

    @Test
    fun `03 reconnect base delay is clamped so the service cannot busy spin`() = runTest {
        val repo = repository

        repo.setReconnectBaseSeconds(0)
        assertThat(repo.settings.first().reconnectBaseSeconds)
            .isEqualTo(SettingsRepository.MIN_RECONNECT_BASE_SECONDS)

        repo.setReconnectBaseSeconds(-42)
        assertThat(repo.settings.first().reconnectBaseSeconds)
            .isEqualTo(SettingsRepository.MIN_RECONNECT_BASE_SECONDS)

        repo.setReconnectBaseSeconds(Int.MAX_VALUE)
        assertThat(repo.settings.first().reconnectBaseSeconds)
            .isEqualTo(SettingsRepository.MAX_RECONNECT_BASE_SECONDS)
    }

    @Test
    fun `04 a PIN verifies only against the value that was set`() = runTest {
        val repo = repository

        repo.setPin("246813")

        assertThat(repo.settings.first().pinEnabled).isTrue()
        assertThat(repo.verifyPin("246813")).isTrue()
        assertThat(repo.verifyPin("246812")).isFalse()
        assertThat(repo.verifyPin("")).isFalse()
    }

    @Test
    fun `05 clearing the PIN disables the lock and rejects the old value`() = runTest {
        val repo = repository
        repo.setPin("135791")

        repo.clearPin()

        assertThat(repo.settings.first().pinEnabled).isFalse()
        assertThat(repo.verifyPin("135791")).isFalse()
    }

    @Test
    fun `06 verifying a PIN that was never set fails instead of throwing`() = runTest {
        assertThat(repository.verifyPin("000000")).isFalse()
    }

    /**
     * Every delay the settings screen offers stores as itself.
     *
     * [SettingsRepository.setReconnectBaseSeconds] clamps, so a choice outside
     * MIN..MAX would be quietly changed on the way in and the screen would redraw showing a value
     * the user never picked — the kind of mismatch that only shows up at the edges of the list, which
     * is exactly where an added or edited choice lands.
     */
    @Test
    fun `07 every reconnect delay the UI offers round-trips unchanged`() = runTest {
        val repo = repository

        SettingsRepository.RECONNECT_BASE_CHOICES.forEach { choice ->
            repo.setReconnectBaseSeconds(choice)
            assertWithMessage("the settings screen offers %s seconds", choice)
                .that(repo.settings.first().reconnectBaseSeconds)
                .isEqualTo(choice)
        }

        // Non-empty and containing the default, so the screen always has the current value to
        // highlight; an empty or default-less list would render a dialog with nothing selected.
        assertThat(SettingsRepository.RECONNECT_BASE_CHOICES)
            .contains(SettingsRepository.DEFAULT_RECONNECT_BASE_SECONDS)

        // Left as the default so this class's write does not leak into a sibling class through the
        // classloader-shared DataStore.
        repo.setReconnectBaseSeconds(SettingsRepository.DEFAULT_RECONNECT_BASE_SECONDS)
    }

    /**
     * `pinEnabled` gates the whole app, and `verifyPin` can only fail without a hash, so the flag
     * without its hash is an install nobody can get into: every PIN is rejected and the biometric
     * button needs both the setting on and something enrolled. `setPin` writes the pair together, so
     * the preferences are built by hand here — no public setter can produce this combination, which
     * is the reason the mapping is a separate function.
     */
    @Test
    fun `08 a pin flag left behind without its hash does not lock the app`() {
        val orphanedFlag = mutablePreferencesOf(Keys.pinEnabled to true)

        assertWithMessage("pin_enabled with no pin_hash would reject every PIN")
            .that(settingsFrom(orphanedFlag).pinEnabled)
            .isFalse()

        // The genuine pair still enables it, so the guard has not disabled the feature.
        val stored = mutablePreferencesOf(
            Keys.pinEnabled to true,
            Keys.pinHash to "hash",
            Keys.pinSalt to "salt",
        )
        assertThat(settingsFrom(stored).pinEnabled).isTrue()

        // And a hash with the flag off stays off: clearPin removes the hash and writes false, but a
        // half-applied clear must not leave the lock screen up either.
        assertThat(settingsFrom(mutablePreferencesOf(Keys.pinHash to "hash")).pinEnabled).isFalse()
    }

    /**
     * The font size is clamped on the way in *and* on the way out.
     *
     * Two callers can produce a size the terminal cannot draw. A vault backup is restored field by
     * field, so an edited or corrupted export can carry any integer at all; and the pinch-to-zoom
     * gesture multiplies the current size by a gesture factor, which is unbounded by nature. A 200 sp
     * cell is a grid one column wide - the same unusable terminal `terminalMinColumns` is clamped to
     * avoid - and a zero or negative size is a crash inside text measurement.
     */
    @Test
    fun `09 the terminal font size is clamped to the range the UI offers`() = runTest {
        val repo = repository
        val range = SettingsRepository.TERMINAL_FONT_SIZE_RANGE

        repo.setTerminalFontSize(0)
        assertThat(repo.settings.first().terminalFontSize).isEqualTo(range.first)

        repo.setTerminalFontSize(-8)
        assertThat(repo.settings.first().terminalFontSize).isEqualTo(range.first)

        repo.setTerminalFontSize(Int.MAX_VALUE)
        assertThat(repo.settings.first().terminalFontSize).isEqualTo(range.last)

        // Every size the slider and the pinch gesture can land on stores as itself, so neither ever
        // redraws showing a number the user did not choose.
        range.forEach { size ->
            repo.setTerminalFontSize(size)
            assertWithMessage("the font dialog offers %s sp", size)
                .that(repo.settings.first().terminalFontSize)
                .isEqualTo(size)
        }

        // A value already written by an older build, or by a hand-edited backup, is clamped on read
        // too - there is no setter that could have fixed it after the fact.
        assertThat(settingsFrom(mutablePreferencesOf(Keys.terminalFontSize to 200)).terminalFontSize)
            .isEqualTo(range.last)
        assertThat(settingsFrom(mutablePreferencesOf(Keys.terminalFontSize to 0)).terminalFontSize)
            .isEqualTo(range.first)

        repo.setTerminalFontSize(SettingsRepository.DEFAULT_TERMINAL_FONT_SIZE)
    }

    /**
     * The collapsed shortcut bar survives a restart.
     *
     * It is persisted rather than remembered per screen because the reason to collapse it - a short
     * screen, or a full-screen program that needs every row - does not change between sessions, and
     * re-collapsing it on every connect would be a chore.
     */
    @Test
    fun `10 the shortcut bar visibility round-trips both ways`() = runTest {
        val repo = repository

        repo.setTerminalKeyRowVisible(false)
        assertThat(repo.settings.first().terminalKeyRowVisible).isFalse()

        repo.setTerminalKeyRowVisible(true)
        assertThat(repo.settings.first().terminalKeyRowVisible).isTrue()
    }
}
