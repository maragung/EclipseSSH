package dev.eclipse.ssh.ui.editor

import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The codec's contract, which is that the editor always opens with usable options.
 *
 * [EditorPrefsCodec.decode] is total: an empty, corrupt, wrong-shaped, or hand-edited blob still
 * produces an [EditorPrefs], because "your settings are broken so you have no editor" is a
 * lockout no preference justifies. The tests pin each way a blob can be wrong, because each is
 * one someone will eventually ship or hand-write.
 *
 * [EditorPrefsCodec.encode] is the mirror image: it writes every field, unconditionally, so a
 * round-trip loses nothing — the options sheet's state this run is the sheet's state next run.
 *
 * Runs under Robolectric because the codec parses with `org.json`, which is a throwing stub on
 * the bare host JVM (the same reason VaultBackupTest runs under it).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EditorPrefsTest {

    @Test
    fun `an empty string decodes to the defaults`() {
        // The DataStore default is "{}" but a wiped or migrated key can surface as "".
        assertThat(EditorPrefsCodec.decode("")).isEqualTo(EditorPrefs())
    }

    @Test
    fun `an empty object decodes to the defaults`() {
        assertThat(EditorPrefsCodec.decode("{}")).isEqualTo(EditorPrefs())
    }

    @Test
    fun `garbage decodes to the defaults rather than throwing`() {
        assertThat(EditorPrefsCodec.decode("not json at all")).isEqualTo(EditorPrefs())
    }

    @Test
    fun `an unclosed brace decodes to the defaults`() {
        // The one a truncated write actually produces: valid prefix, missing tail.
        assertThat(EditorPrefsCodec.decode("{\"tabSize\": 4")).isEqualTo(EditorPrefs())
    }

    @Test
    fun `json that is not an object decodes to the defaults`() {
        // A bare array and a bare number are both valid JSON and neither is a prefs blob.
        assertThat(EditorPrefsCodec.decode("[1, 2, 3]")).isEqualTo(EditorPrefs())
        assertThat(EditorPrefsCodec.decode("42")).isEqualTo(EditorPrefs())
    }

    @Test
    fun `every field survives an encode decode round trip`() {
        // Every value is the non-default one, so nothing passes by being the default twice.
        val prefs = EditorPrefs(
            wordWrap = true,
            showLineNumbers = false,
            tabSize = 8,
            spacesInsteadOfTabs = true,
            autoSaveEnabled = false,
            autoSaveDelayMillis = 5_000L,
        )
        assertThat(EditorPrefsCodec.decode(EditorPrefsCodec.encode(prefs))).isEqualTo(prefs)
    }

    @Test
    fun `encode writes exactly the six fields`() {
        // A seventh field is a seventh thing to keep in sync with every future reader; the
        // set of keys is the format's identity, so it is pinned, not implied by the round trip.
        val keys = JSONObject(EditorPrefsCodec.encode(EditorPrefs())).keys().asSequence().toList()
        assertThat(keys).containsExactly(
            "wordWrap",
            "showLineNumbers",
            "tabSize",
            "spacesInsteadOfTabs",
            "autoSaveEnabled",
            "autoSaveDelayMillis",
        )
    }

    @Test
    fun `an unknown field is dropped and the known fields still decode`() {
        // Written by a newer app version. Dropping it — not erroring — is what lets the format
        // evolve without a migration step.
        val json = JSONObject()
            .put("wordWrap", true)
            .put("futureFeature", JSONObject().put("nested", 1))
            .put("tabSize", 2)
            .toString()
        val decoded = EditorPrefsCodec.decode(json)
        assertThat(decoded).isEqualTo(EditorPrefs(wordWrap = true, tabSize = 2))
    }

    @Test
    fun `a wrong typed boolean falls back to the default instead of crashing`() {
        // A string where a boolean belongs was not written by encode; the honest read is the
        // default, not org.json's "true"-string coercion. showLineNumbers as 1 exercises the
        // same rule for the other shape a hand-edit produces.
        val json = "{\"wordWrap\": \"yes\", \"showLineNumbers\": 1}"
        val decoded = EditorPrefsCodec.decode(json)
        assertThat(decoded.wordWrap).isEqualTo(false)
        assertThat(decoded.showLineNumbers).isEqualTo(true)
    }

    @Test
    fun `a wrong typed number falls back to the default`() {
        // A string and a fractional double are both "not a number this format writes".
        assertThat(EditorPrefsCodec.decode("{\"tabSize\": \"wide\"}").tabSize).isEqualTo(4)
        assertThat(EditorPrefsCodec.decode("{\"autoSaveDelayMillis\": 4.5}").autoSaveDelayMillis)
            .isEqualTo(2_000L)
    }

    @Test
    fun `a tab size below one clamps to one`() {
        // Zero or negative would break the column math outright; the nearest bound keeps the
        // editor usable while making it obvious the value was not honoured as written.
        assertThat(EditorPrefsCodec.decode("{\"tabSize\": 0}").tabSize).isEqualTo(1)
        assertThat(EditorPrefsCodec.decode("{\"tabSize\": -3}").tabSize).isEqualTo(1)
    }

    @Test
    fun `a tab size above sixteen clamps to sixteen`() {
        assertThat(EditorPrefsCodec.decode("{\"tabSize\": 99}").tabSize).isEqualTo(16)
    }

    @Test
    fun `tab sizes at the bounds are honoured, not clamped`() {
        assertThat(EditorPrefsCodec.decode("{\"tabSize\": 1}").tabSize).isEqualTo(1)
        assertThat(EditorPrefsCodec.decode("{\"tabSize\": 16}").tabSize).isEqualTo(16)
    }

    @Test
    fun `an auto save delay below a quarter second clamps to the floor`() {
        // Faster than that and autosave fires mid-burst: a save (on SFTP, a network round
        // trip) per sentence.
        assertThat(EditorPrefsCodec.decode("{\"autoSaveDelayMillis\": 100}").autoSaveDelayMillis)
            .isEqualTo(250L)
    }

    @Test
    fun `an auto save delay above a minute clamps to the ceiling`() {
        assertThat(EditorPrefsCodec.decode("{\"autoSaveDelayMillis\": 600000}").autoSaveDelayMillis)
            .isEqualTo(60_000L)
    }

    @Test
    fun `a delay written as a small int literal still decodes`() {
        // org.json hands small literals back as Int, not Long; the read must accept both or
        // every hand-written blob falls back to the default.
        assertThat(EditorPrefsCodec.decode("{\"autoSaveDelayMillis\": 300}").autoSaveDelayMillis)
            .isEqualTo(300L)
    }
}
