package dev.eclipse.ssh.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.MutablePreferences
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
        val terminalKeyRowVisible = booleanPreferencesKey("terminal_key_row_visible")
        val terminalMinColumns = intPreferencesKey("terminal_min_columns")
        val pinEnabled = booleanPreferencesKey("pin_enabled")
        val pinHash = stringPreferencesKey("pin_hash")
        val pinSalt = stringPreferencesKey("pin_salt")
    val vaultAutoLockMinutes = intPreferencesKey("vault_auto_lock_minutes")
    val legacyAlgorithms = booleanPreferencesKey("legacy_algorithms")
    val terminalTheme = stringPreferencesKey("terminal_theme")
    val blockScreenshots = booleanPreferencesKey("block_screenshots")
    val reconnectAskFirst = booleanPreferencesKey("reconnect_ask_first")
    val terminalScrollback = intPreferencesKey("terminal_scrollback")
    val terminalCursorStyle = stringPreferencesKey("terminal_cursor_style")
    val terminalKeepSystemBars = booleanPreferencesKey("terminal_keep_system_bars")
    val transferBytesPerSecond = androidx.datastore.preferences.core.longPreferencesKey("transfer_bytes_per_second")
    val editorPrefsJson = stringPreferencesKey("editor_prefs_json")
    val terminalKeyBarJson = stringPreferencesKey("terminal_key_bar_json")
    val localRootUri = stringPreferencesKey("local_root_uri")
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
    // Clamped on the way out as well as on the way in: this value also arrives from a restored
    // backup, where the number is whatever the file says, and a 200 sp grid is one cell wide.
    terminalFontSize = SettingsRepository.normalizeFontSize(prefs[Keys.terminalFontSize] ?: SettingsRepository.DEFAULT_TERMINAL_FONT_SIZE),
    terminalKeyRowVisible = prefs[Keys.terminalKeyRowVisible] ?: true,
    terminalMinColumns = prefs[Keys.terminalMinColumns] ?: SettingsRepository.DEFAULT_TERMINAL_MIN_COLUMNS,
    // Requires the hash to actually be present, not just the flag. `pinEnabled` alone gates the
    // entire app through MainActivity's lock screen, and `verifyPin` returns false when there is no
    // hash to compare against — so the flag surviving without its hash is a permanent lockout with
    // no way out from inside the app: every PIN is rejected, and the biometric button is only
    // offered when that setting is on and only works on a device with something enrolled. Only
    // `setPin` ever writes the two together, so this state is unreachable today; it is guarded
    // because the cost of being wrong is a bricked install and the check costs one map lookup.
    pinEnabled = prefs[Keys.pinEnabled] == true && prefs[Keys.pinHash] != null,
    // Normalized on the way out for the same reason as the font size: this number also arrives from
    // a restored backup, where it is whatever the file says, and a delay the dialog never offered
    // would show as a value nobody chose.
    vaultAutoLockMinutes = SettingsRepository.normalizeVaultAutoLockMinutes(
        prefs[Keys.vaultAutoLockMinutes] ?: SettingsRepository.DEFAULT_VAULT_AUTO_LOCK_MINUTES,
    ),
    legacyAlgorithms = prefs[Keys.legacyAlgorithms] ?: false,
    terminalTheme = prefs[Keys.terminalTheme] ?: TerminalTheme.DARK.name,
    blockScreenshots = prefs[Keys.blockScreenshots] ?: false,
    // Absent from every install that predates the setting, and the default here is the behaviour
    // those installs already had - automatic reconnect - rather than false-by-accident.
    reconnectAskFirst = prefs[Keys.reconnectAskFirst] ?: false,
    terminalScrollback = (prefs[Keys.terminalScrollback] ?: 2_000)
        .coerceIn(SettingsRepository.MIN_SCROLLBACK, SettingsRepository.MAX_SCROLLBACK),
    terminalCursorStyle = prefs[Keys.terminalCursorStyle] ?: "block",
    // Absent on every install that predates the setting; the default is the immersive behaviour
    // those installs already had.
    terminalKeepSystemBars = prefs[Keys.terminalKeepSystemBars] ?: false,
    transferBytesPerSecond = (prefs[Keys.transferBytesPerSecond] ?: 0L)
        .coerceIn(0L, SettingsRepository.MAX_BANDWIDTH_BYTES_PER_SECOND),
    // "{}" rather than null: the blob is only ever rewritten whole by the editor screen, and a
    // missing key means "never configured", which is the same JSON as "every toggle default".
    editorPrefsJson = prefs[Keys.editorPrefsJson] ?: "{}",
    // Same shape as the editor blob above: the shortcut bar's codec is the only reader, a missing
    // key means "never configured", and that is the same JSON as the default bar.
    terminalKeyBarJson = prefs[Keys.terminalKeyBarJson] ?: "{}",
    localRootUri = prefs[Keys.localRootUri],
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

    /**
     * Every write goes through here so none of them runs on the caller's dispatcher.
     *
     * DataStore invokes an `edit` transform with `withContext(callerContext)`, so a transform started
     * from `viewModelScope` runs on the main thread -- a file write on the UI thread. Worse, writes are
     * serialised through a single actor, so one transform parked on a stalled dispatcher blocks every
     * later write to this store for the life of the process.
     */
    private suspend fun editPrefs(block: suspend (MutablePreferences) -> Unit): Preferences =
        withContext(Dispatchers.IO) { context.settingsDataStore.edit(block) }

    suspend fun setBiometricUnlock(enabled: Boolean) = editPrefs { it[Keys.biometricUnlock] = enabled }
    suspend fun setBlockScreenshots(enabled: Boolean) = editPrefs { it[Keys.blockScreenshots] = enabled }
    suspend fun setReconnectAskFirst(enabled: Boolean) = editPrefs { it[Keys.reconnectAskFirst] = enabled }
    suspend fun setTerminalKeepSystemBars(enabled: Boolean) = editPrefs { it[Keys.terminalKeepSystemBars] = enabled }
    suspend fun setDarkTheme(enabled: Boolean) = editPrefs { it[Keys.darkTheme] = enabled }
    suspend fun setClipboardSeconds(seconds: Int) = editPrefs { it[Keys.clipboardSeconds] = seconds }
    suspend fun setKeepAliveSeconds(seconds: Int) = editPrefs { it[Keys.keepAliveSeconds] = seconds }

    /**
     * Base delay for the reconnect backoff, clamped to a range that keeps retries useful:
     * below 1 s the service would hammer an unreachable host, above 60 s the first retry
     * arrives long after the user gave up.
     */
    suspend fun setReconnectBaseSeconds(seconds: Int) = editPrefs {
        it[Keys.reconnectBaseSeconds] = seconds.coerceIn(MIN_RECONNECT_BASE_SECONDS, MAX_RECONNECT_BASE_SECONDS)
    }

    /**
     * Number of scrollback lines the terminal buffer holds.
     *
     * Clamped to a range that keeps the buffer from being uselessly small
     * (200 lines is less than two screens) or large enough to be a memory
     * problem on a low-end device (50 000 lines is about 6 MB at 120
     * columns × 4 bytes per cell).
     */
    suspend fun setTerminalScrollback(lines: Int) = editPrefs {
        it[Keys.terminalScrollback] = lines.coerceIn(MIN_SCROLLBACK, MAX_SCROLLBACK)
    }

    /** Cursor style. One of "block", "underline", "bar". */
    suspend fun setTerminalCursorStyle(style: String) = editPrefs {
        it[Keys.terminalCursorStyle] = when (style) {
            "underline" -> "underline"
            "bar" -> "bar"
            else -> "block"
        }
    }

    /**
     * Bytes per second cap on SFTP transfers. 0 = unlimited.
     *
     * The cap is per transfer, not per session: two simultaneous transfers
     * share the cap (one halves its effective rate for the other to use).
     * That is the simpler model and is what `scp -l` does.
     */
    suspend fun setTransferBytesPerSecond(bytesPerSecond: Long) = editPrefs {
        it[Keys.transferBytesPerSecond] = bytesPerSecond.coerceIn(0L, MAX_BANDWIDTH_BYTES_PER_SECOND)
    }

    /**
     * Remember (or forget) which SAF folder the Local file browser is pointed at.
     *
     * Null clears the key rather than storing an empty string, so a reader sees "no folder ever
     * picked" and "folder cleared" as the same absent value — there is no meaningful third state.
     */
    suspend fun setLocalRootUri(uri: String?) = editPrefs {
        if (uri == null) it.remove(Keys.localRootUri) else it[Keys.localRootUri] = uri
    }

    /**
     * Persist the editor's preferences blob, whole. The editor screen is the only writer: it
     * re-encodes every field on every change (see [EditorPrefsCodec.encode]), so this never
     * merges partial state — a write is either the new complete blob or the old one.
     */
    suspend fun setEditorPrefsJson(json: String) = editPrefs {
        it[Keys.editorPrefsJson] = json
    }

    /**
     * Persist the shortcut bar's configuration blob, whole. Same contract as the editor blob: the
     * settings screen is the only writer, it re-encodes every field on every change (see
     * [KeyBarPrefsCodec.encode]), so a write is either the new complete blob or the old one.
     */
    suspend fun setTerminalKeyBarJson(json: String) = editPrefs {
        it[Keys.terminalKeyBarJson] = json
    }

    /**
     * The terminal's text size in sp, clamped to what the app is willing to draw.
     *
     * Clamped here rather than only in the dialog because there are now two callers and one of them is
     * a gesture: a pinch accumulates a scale factor, and a factor bounded only by how far apart two
     * fingers can get would otherwise store a font size that leaves one column on screen with no way
     * back except the settings slider.
     */
    suspend fun setTerminalFontSize(size: Int) = editPrefs {
        it[Keys.terminalFontSize] = normalizeFontSize(size)
    }

    suspend fun setTerminalKeyRowVisible(visible: Boolean) = editPrefs {
        it[Keys.terminalKeyRowVisible] = visible
    }

    /**
     * The narrowest grid the pty may be given, or 0 for "fit the screen".
     *
     * Clamped to what the terminal itself accepts rather than trusted: [AppSettings.terminalMinColumns]
     * also arrives from a restored vault, where the number is whatever the file says. A width below
     * the buffer's own minimum would be silently raised at the far end, so the value the screen shows
     * would not be the value in force; above its maximum it would be silently lowered, and the user
     * would be panning across a grid the server never had.
     */
    suspend fun setTerminalMinColumns(columns: Int) = editPrefs {
        it[Keys.terminalMinColumns] = normalizeMinColumns(columns)
    }
    suspend fun setLegacyAlgorithms(enabled: Boolean) = editPrefs { it[Keys.legacyAlgorithms] = enabled }

    /**
     * How long the app may sit in the background before the vault re-locks, in minutes; 0 = never.
     *
     * Normalized rather than trusted for the same reason as every other stored number: only
     * [setVaultAutoLockMinutes] and a restored backup write it, and a backup is a file the user can
     * hand-edit.
     */
    suspend fun setVaultAutoLockMinutes(minutes: Int) = editPrefs {
        it[Keys.vaultAutoLockMinutes] = normalizeVaultAutoLockMinutes(minutes)
    }
    suspend fun setTerminalTheme(name: String) = editPrefs { it[Keys.terminalTheme] = name }

    suspend fun setPin(pin: String) {
        // PBKDF2 at 60k iterations is deliberately expensive — hundreds of milliseconds, more on a
        // slow device. `suspend` alone does not move it anywhere: both callers reach this from
        // viewModelScope, which is Dispatchers.Main, so it ran on the UI thread and froze the frame.
        val salt = PinHasher.newSalt()
        val hash = withContext(Dispatchers.Default) { PinHasher.hash(pin, salt) }
        editPrefs {
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

    suspend fun clearPin() = editPrefs {
        it.remove(Keys.pinHash)
        it.remove(Keys.pinSalt)
        it[Keys.pinEnabled] = false
    }

    companion object {
        const val DEFAULT_TERMINAL_MIN_COLUMNS = 80

        const val DEFAULT_TERMINAL_FONT_SIZE = 13

        /**
         * The text sizes the terminal may be drawn at, in sp.
         *
         * The lower bound is where monospace stops being legible on a phone; the upper is where an
         * 80-column line needs more panning than reading. Shared by the settings slider and by
         * pinch-to-zoom so both stop in the same place.
         */
        val TERMINAL_FONT_SIZE_RANGE = 10..20

        fun normalizeFontSize(size: Int): Int = size.coerceIn(TERMINAL_FONT_SIZE_RANGE)

        /** 0, meaning "fit the screen exactly", plus the widths worth offering above it. */
        val TERMINAL_MIN_COLUMN_CHOICES = listOf(0, 80, 100, 120, 132, 160)

        /** 0 stays 0; anything else is pulled inside the range the pty and the buffer share. */
        fun normalizeMinColumns(columns: Int): Int =
            if (columns <= 0) 0 else columns.coerceIn(TERMINAL_COLUMN_RANGE)

        const val DEFAULT_RECONNECT_BASE_SECONDS = 5
        const val MIN_RECONNECT_BASE_SECONDS = 1
        const val MAX_RECONNECT_BASE_SECONDS = 60

        // Scrollback bounds. 200 lines is "less than two screens" — the
        // minimum for a useful terminal — and 50_000 is the largest
        // buffer that is still ~6 MB at 120 columns. Beyond that the
        // buffer is a memory cost the user has not asked for.
        const val MIN_SCROLLBACK = 200
        const val MAX_SCROLLBACK = 50_000

        // Bandwidth cap. 0 = unlimited; the upper bound is 50 MiB/s,
        // which is faster than any consumer mobile network in 2026
        // and faster than what a typical USB 3 SSD can deliver for a
        // single file.
        const val MAX_BANDWIDTH_BYTES_PER_SECOND = 50L * 1024L * 1024L

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

        const val DEFAULT_VAULT_AUTO_LOCK_MINUTES = 5

        /**
         * The auto-lock delays the settings screen offers, in minutes; 0 = never re-lock.
         *
         * Here rather than inline in the UI for the same reason as [RECONNECT_BASE_CHOICES]: it sits
         * next to the clamp it has to respect, so a chip cannot offer a delay that
         * [setVaultAutoLockMinutes] would store as a different number than the one the user picked.
         */
        val VAULT_AUTO_LOCK_CHOICES = listOf(1, 5, 15, 60, 0)

        /** 0 stays 0; anything else is pulled inside the range of delays worth offering. */
        fun normalizeVaultAutoLockMinutes(minutes: Int): Int =
            if (minutes <= 0) 0 else minutes.coerceIn(1, 60)
    }
}
