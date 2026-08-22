package dev.eclipse.ssh.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import dev.eclipse.ssh.data.model.AppSettings
import dev.eclipse.ssh.data.model.TerminalTheme
import dev.eclipse.ssh.security.PinHasher
import dev.eclipse.ssh.terminal.TERMINAL_COLUMN_RANGE
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * The settings store.
 *
 * The corruption handler is the difference between "the preferences reset" and "the app is finished".
 * Without one, a file DataStore cannot parse — a write torn in half by a battery pull, a partial
 * restore — makes *every* read throw `CorruptionException`, permanently: nothing in the app rewrites
 * the file, because every path that would write it reads first. Settings, the terminal theme, the
 * screenshot block and the reconnect delay all come from here, and `MainViewModel` reads them on
 * construction, so the app would open to an error and stay there through every relaunch and every
 * clear-cache. Replacing an unparseable file with defaults is recoverable and, for the one
 * security-relevant flag in it, fails in the safe direction: `blockScreenshots` defaults to on being
 * *asked for*, not to being silently dropped — see the FLAG_SECURE effect in `MainActivity`.
 */
private val Context.settingsDataStore by preferencesDataStore(
    name = "eclipse_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * The preference keys, `internal` so a test can build a combination the setters cannot produce —
 * `pin_enabled` present without `pin_hash` is the one that matters, and only [setPin] ever writes
 * the pair together.
 */
internal object Keys {
        val biometricUnlock = booleanPreferencesKey("biometric_unlock")
        val darkTheme = booleanPreferencesKey("dark_theme")
        val clipboardSeconds = intPreferencesKey("clipboard_seconds")
        val keepAliveSeconds = intPreferencesKey("keep_alive_seconds")
        val reconnectBaseSeconds = intPreferencesKey("reconnect_base_seconds")
        val terminalFontSize = intPreferencesKey("terminal_font_size")
        val terminalMinColumns = intPreferencesKey("terminal_min_columns")
        val pinEnabled = booleanPreferencesKey("pin_enabled")
        val pinHash = stringPreferencesKey("pin_hash")
        val pinSalt = stringPreferencesKey("pin_salt")
        val legacyAlgorithms = booleanPreferencesKey("legacy_algorithms")
        val terminalTheme = stringPreferencesKey("terminal_theme")
    val blockScreenshots = booleanPreferencesKey("block_screenshots")
}

/**
 * The stored preferences as an [AppSettings]. Pure, and separate from the flow below, so the one
 * piece of real logic in it — the [Keys.pinEnabled] guard — can be tested against a preference set
 * no public setter is able to write.
 */
internal fun settingsFrom(prefs: Preferences) = AppSettings(
    biometricUnlock = prefs[Keys.biometricUnlock] ?: true,
    darkTheme = prefs[Keys.darkTheme] ?: true,
    clearClipboardAfterSeconds = prefs[Keys.clipboardSeconds] ?: 30,
    keepAliveSeconds = prefs[Keys.keepAliveSeconds] ?: 30,
    reconnectBaseSeconds = prefs[Keys.reconnectBaseSeconds] ?: SettingsRepository.DEFAULT_RECONNECT_BASE_SECONDS,
    terminalFontSize = prefs[Keys.terminalFontSize] ?: 13,
    terminalMinColumns = prefs[Keys.terminalMinColumns] ?: SettingsRepository.DEFAULT_TERMINAL_MIN_COLUMNS,
    // Requires the hash to actually be present, not just the flag. `pinEnabled` alone gates the
    // entire app through MainActivity's lock screen, and `verifyPin` returns false when there is no
    // hash to compare against — so the flag surviving without its hash is a permanent lockout with
    // no way out from inside the app: every PIN is rejected, and the biometric button is only
    // offered when that setting is on and only works on a device with something enrolled. Only
    // `setPin` ever writes the two together, so this state is unreachable today; it is guarded
    // because the cost of being wrong is a bricked install and the check costs one map lookup.
    pinEnabled = prefs[Keys.pinEnabled] == true && prefs[Keys.pinHash] != null,
    legacyAlgorithms = prefs[Keys.legacyAlgorithms] ?: false,
    terminalTheme = prefs[Keys.terminalTheme] ?: TerminalTheme.DARK.name,
    blockScreenshots = prefs[Keys.blockScreenshots] ?: false,
)

class SettingsRepository(private val context: Context) {

    /**
     * Every setting, re-read whenever one changes.
     *
     * The [catch] is not defensive padding. DataStore reports an unreadable file — corrupted by a
     * half-finished write, or simply not there yet during direct boot before the user has unlocked
     * the device — by throwing [IOException] on this flow, and a flow that throws is a flow that has
     * *ended*. This one is combined into the single `MainUiState` the whole UI renders from, so
     * letting the exception through did not degrade the settings screen: it terminated the combine
     * and froze hosts, tabs, transfers and terminal output at their initial values, permanently, with
     * nothing on screen to say why. Falling back to the defaults keeps the app usable and lets the
     * next successful write repair the file. This is also what the DataStore documentation asks
     * callers to do.
     *
     * Anything that is not an [IOException] is a programming error in the mapping below and is left
     * to propagate.
     */
    val settings: Flow<AppSettings> = context.settingsDataStore.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.map(::settingsFrom)

    suspend fun setBiometricUnlock(enabled: Boolean) = context.settingsDataStore.edit { it[Keys.biometricUnlock] = enabled }
    suspend fun setBlockScreenshots(enabled: Boolean) = context.settingsDataStore.edit { it[Keys.blockScreenshots] = enabled }
    suspend fun setDarkTheme(enabled: Boolean) = context.settingsDataStore.edit { it[Keys.darkTheme] = enabled }
    suspend fun setClipboardSeconds(seconds: Int) = context.settingsDataStore.edit { it[Keys.clipboardSeconds] = seconds }
    suspend fun setKeepAliveSeconds(seconds: Int) = context.settingsDataStore.edit { it[Keys.keepAliveSeconds] = seconds }

    /**
     * Base delay for the reconnect backoff, clamped to a range that keeps retries useful:
     * below 1 s the service would hammer an unreachable host, above 60 s the first retry
     * arrives long after the user gave up.
     */
    suspend fun setReconnectBaseSeconds(seconds: Int) = context.settingsDataStore.edit {
        it[Keys.reconnectBaseSeconds] = seconds.coerceIn(MIN_RECONNECT_BASE_SECONDS, MAX_RECONNECT_BASE_SECONDS)
    }
    suspend fun setTerminalFontSize(size: Int) = context.settingsDataStore.edit { it[Keys.terminalFontSize] = size }

    /**
     * The narrowest grid the pty may be given, or 0 for "fit the screen".
     *
     * Clamped to what the terminal itself accepts rather than trusted: [AppSettings.terminalMinColumns]
     * also arrives from a restored vault, where the number is whatever the file says. A width below
     * the buffer's own minimum would be silently raised at the far end, so the value the screen shows
     * would not be the value in force; above its maximum it would be silently lowered, and the user
     * would be panning across a grid the server never had.
     */
    suspend fun setTerminalMinColumns(columns: Int) = context.settingsDataStore.edit {
        it[Keys.terminalMinColumns] = normalizeMinColumns(columns)
    }
    suspend fun setLegacyAlgorithms(enabled: Boolean) = context.settingsDataStore.edit { it[Keys.legacyAlgorithms] = enabled }
    suspend fun setTerminalTheme(name: String) = context.settingsDataStore.edit { it[Keys.terminalTheme] = name }

    suspend fun setPin(pin: String) {
        // PBKDF2 at 60k iterations is deliberately expensive — hundreds of milliseconds, more on a
        // slow device. `suspend` alone does not move it anywhere: both callers reach this from
        // viewModelScope, which is Dispatchers.Main, so it ran on the UI thread and froze the frame.
        val salt = PinHasher.newSalt()
        val hash = withContext(Dispatchers.Default) { PinHasher.hash(pin, salt) }
        context.settingsDataStore.edit {
            it[Keys.pinSalt] = Base64.getEncoder().encodeToString(salt)
            it[Keys.pinHash] = hash
            it[Keys.pinEnabled] = true
        }
    }

    suspend fun verifyPin(pin: String): Boolean {
        val prefs = context.settingsDataStore.data.first()
        val hash = prefs[Keys.pinHash] ?: return false
        val salt = prefs[Keys.pinSalt]?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() } ?: return false
        // Same cost as setPin, and this one is worse: it runs on the lock screen that gates app
        // startup, and again on every wrong attempt. Dispatchers.Default because it is pure CPU.
        return withContext(Dispatchers.Default) { PinHasher.verify(pin, salt, hash) }
    }

    suspend fun clearPin() = context.settingsDataStore.edit {
        it.remove(Keys.pinHash)
        it.remove(Keys.pinSalt)
        it[Keys.pinEnabled] = false
    }

    companion object {
        const val DEFAULT_TERMINAL_MIN_COLUMNS = 80

        /** 0, meaning "fit the screen exactly", plus the widths worth offering above it. */
        val TERMINAL_MIN_COLUMN_CHOICES = listOf(0, 80, 100, 120, 132, 160)

        /** 0 stays 0; anything else is pulled inside the range the pty and the buffer share. */
        fun normalizeMinColumns(columns: Int): Int =
            if (columns <= 0) 0 else columns.coerceIn(TERMINAL_COLUMN_RANGE)

        const val DEFAULT_RECONNECT_BASE_SECONDS = 5
        const val MIN_RECONNECT_BASE_SECONDS = 1
        const val MAX_RECONNECT_BASE_SECONDS = 60

        /**
         * The reconnect delays the settings screen offers.
         *
         * Here rather than inline in the UI so that it sits next to the clamp it has to respect:
         * [setReconnectBaseSeconds] coerces into
         * [MIN_RECONNECT_BASE_SECONDS]..[MAX_RECONNECT_BASE_SECONDS], so a chip outside that range
         * would store a different number than the one the user picked and the screen would then
         * redraw showing a value nobody chose. Pinned by
         * `SettingsRepositoryTest.07 every reconnect delay the UI offers round-trips unchanged`.
         */
        val RECONNECT_BASE_CHOICES = listOf(1, 2, 5, 10, 30, 60)
    }
}
