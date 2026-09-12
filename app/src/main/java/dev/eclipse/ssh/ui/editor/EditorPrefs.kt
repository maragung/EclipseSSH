package dev.eclipse.ssh.ui.editor

import org.json.JSONException
import org.json.JSONObject

/**
 * The editor's options sheet, as one value: what the six toggles and numbers were last set to.
 *
 * This is the model behind the `editorPrefsJson` blob ([dev.eclipse.ssh.data.settings.SettingsRepository]
 * persists it as a JSON string under a single DataStore key) — word wrap, line numbers, tab size,
 * spaces-vs-tabs, and the auto-save pair. The defaults are the values a first-time reader wants:
 * line numbers on, wrap off (a wrapped line lies about what the file actually contains), a tab
 * every 4 columns, real tabs, and auto-save after 2 s of quiet.
 *
 * View state, not safety state, exactly as the blob's own doc argues: nothing here can lose data
 * on its own, so the codec below optimises for "always usable", not for "always intact".
 */
data class EditorPrefs(
    /** Whether long lines fold at the screen edge instead of scrolling horizontally. */
    val wordWrap: Boolean = false,
    /** Whether the gutter shows a number per line. */
    val showLineNumbers: Boolean = true,
    /** How many columns one tab occupies; clamped to [EditorPrefsCodec.TAB_SIZE_MIN]..[EditorPrefsCodec.TAB_SIZE_MAX]. */
    val tabSize: Int = 4,
    /** Whether the Tab key inserts spaces instead of the tab character. */
    val spacesInsteadOfTabs: Boolean = false,
    /** Whether auto-save runs at all; off turns the whole heartbeat off, delay included. */
    val autoSaveEnabled: Boolean = true,
    /** How long the text must stay quiet before auto-save fires; clamped to
     * [EditorPrefsCodec.AUTO_SAVE_DELAY_MIN_MILLIS]..[EditorPrefsCodec.AUTO_SAVE_DELAY_MAX_MILLIS]. */
    val autoSaveDelayMillis: Long = 2_000L,
)

/**
 * The encode/decode engine behind `editorPrefsJson`, the editor's single-setting preferences blob.
 *
 * The contract is asymmetric on purpose, and mirrors the file codec's:
 *
 * - [decode] never throws. A corrupt or hand-edited blob still yields a working [EditorPrefs]:
 *   unknown fields are dropped (which is what makes the format evolvable without a migration),
 *   wrong-typed or out-of-range fields fall back or clamp, and unparseable JSON yields the
 *   all-defaults instance. "Cannot open your editor because its settings are broken" is a lockout
 *   no preference is worth.
 * - [encode] writes every field, no exceptions, so an encode/decode round-trip is lossless. The
 *   encoder's inputs are always values this app produced (defaults, clamps, or the options sheet),
 *   so there is nothing for it to refuse.
 */
object EditorPrefsCodec {

    /** Below this a tab stop stops being a tab stop; a 0-width indent also breaks column math. */
    const val TAB_SIZE_MIN = 1

    /** Above this, half the editor's width is gone before the first character of a line appears. */
    const val TAB_SIZE_MAX = 16

    /**
     * Below 250 ms the autosave fires mid-typing-burst — saving on nearly every pause is a
     * network write per sentence, and on SFTP that is real round-trips.
     */
    const val AUTO_SAVE_DELAY_MIN_MILLIS = 250L

    /** Above a minute of quiet with changes unsaved, "auto" has stopped meaning automatic. */
    const val AUTO_SAVE_DELAY_MAX_MILLIS = 60_000L

    /**
     * Decodes the persisted blob. Never throws.
     *
     * Everything that can be wrong with the blob is wrong *somewhere* in the world: a JSON
     * array or bare number instead of an object, an unclosed brace, a field written by a newer
     * app version, a number past the sheet's slider. Each degrades on its own: shape and parse
     * errors return the defaults wholesale; per-field problems only cost that field.
     *
     * Type checking is done on the raw values (`is Boolean`, `is Int`/`is Long`) rather than
     * with [JSONObject.optBoolean]'s coercion, because org.json happily reads `"true"` (a string)
     * as `true`. A blob whose `wordWrap` is the string `"yes"` was not written by [encode], and
     * treating it as `false` — the default — is the predictable outcome; silently reinterpreting
     * it would be a guess dressed up as a read.
     */
    fun decode(json: String): EditorPrefs = try {
        val root = JSONObject(json)
        EditorPrefs(
            wordWrap = root.booleanOr("wordWrap", false),
            showLineNumbers = root.booleanOr("showLineNumbers", true),
            tabSize = root.intIn("tabSize", 4, TAB_SIZE_MIN, TAB_SIZE_MAX),
            spacesInsteadOfTabs = root.booleanOr("spacesInsteadOfTabs", false),
            autoSaveEnabled = root.booleanOr("autoSaveEnabled", true),
            autoSaveDelayMillis = root.longIn(
                "autoSaveDelayMillis", 2_000L, AUTO_SAVE_DELAY_MIN_MILLIS, AUTO_SAVE_DELAY_MAX_MILLIS,
            ),
        )
    } catch (error: JSONException) {
        // Not an object, or not parseable at all. Both mean "we never agreed on what this was",
        // so the defaults — not a half-parsed anything — are the answer.
        EditorPrefs()
    }

    /**
     * Encodes [prefs] as the persisted blob, writing all six fields.
     *
     * Field order is fixed and matches the class, which keeps an eyeballed blob readable and
     * makes diffs of successive saves line up. [JSONObject.put] with Boolean/Int/Long values
     * cannot fail and cannot lose precision, so there is no failure mode to handle — the one
     * asymmetry with [decode] is deliberate and documented there.
     */
    fun encode(prefs: EditorPrefs): String = JSONObject()
        .put("wordWrap", prefs.wordWrap)
        .put("showLineNumbers", prefs.showLineNumbers)
        .put("tabSize", prefs.tabSize)
        .put("spacesInsteadOfTabs", prefs.spacesInsteadOfTabs)
        .put("autoSaveEnabled", prefs.autoSaveEnabled)
        .put("autoSaveDelayMillis", prefs.autoSaveDelayMillis)
        .toString()

    /**
     * Reads a boolean field, or [fallback] when the field is absent or not a boolean. `opt` + a
     * type check, not [JSONObject.optBoolean], for the reason [decode]'s doc gives: no coercion
     * of strings that merely look like booleans.
     */
    private fun JSONObject.booleanOr(key: String, fallback: Boolean): Boolean =
        if (opt(key) is Boolean) getBoolean(key) else fallback

    /**
     * Reads an integer field clamped into [min]..[max], or [fallback] when absent or not an
     * integral number. A fractional value (org.json yields Double for `4.5`) is a wrong type,
     * not a wrong magnitude, so it falls back rather than being truncated. The fallbacks used by
     * [decode] sit inside their own range, so clamping never touches an absent field.
     */
    private fun JSONObject.intIn(key: String, fallback: Int, min: Int, max: Int): Int {
        val value = integralValueOf(key) ?: return fallback
        // Long throughout, Int only at the boundary: integralValueOf speaks Long (the delay
        // field needs it), and the Int bounds widen without loss — Int.MAX_VALUE fits a Long.
        return value.coerceIn(min.toLong(), max.toLong()).toInt()
    }

    /**
     * As [intIn], for the Long delay field. The bounds exist so a hand-edited or corrupt number
     * still lands on a delay the editor can act on; both ends of the clamp are documented at the
     * constants that define them.
     */
    private fun JSONObject.longIn(key: String, fallback: Long, min: Long, max: Long): Long {
        val value = integralValueOf(key) ?: return fallback
        return value.coerceIn(min, max)
    }

    /**
     * The field's value as a Long, or null when absent or not an integral number. org.json hands
     * small literals back as Int and large ones as Long — both are this format's numbers — while
     * a fractional literal (`4.5`) arrives as Double and is a wrong type, not a wrong magnitude,
     * so it is rejected rather than truncated.
     */
    private fun JSONObject.integralValueOf(key: String): Long? = when (val raw = opt(key)) {
        is Int -> raw.toLong()
        is Long -> raw
        else -> null
    }
}
