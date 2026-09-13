package dev.eclipse.ssh.ui.terminal

import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.terminal.TerminalKey
import org.junit.Test

/**
 * The shortcut bar blob's contract, in the shape the editor blob's tests taught: decode never
 * throws, encode never loses, and every way a blob can be wrong costs only what it broke.
 */
class KeyBarPrefsCodecTest {

    @Test
    fun `a default configuration renders the bar the app has always shipped`() {
        val prefs = KeyBarPrefsCodec.decode("{}")

        val row = prefs.rowsForLayout().single()
        // The compatibility contract: an install that never opens the settings screen keeps the
        // exact bar it had before the bar became configurable.
        assertThat(row.map { it.id }).containsExactly(
            "CTRL", "ALT", "SHIFT",
            "ESCAPE", "TAB", "ARROW_LEFT", "ARROW_DOWN", "ARROW_UP", "ARROW_RIGHT",
            "HOME", "END", "PAGE_UP", "PAGE_DOWN", "DELETE", "BACKSPACE", "ENTER",
        ).inOrder()
        assertThat(row.all { it.visible }).isTrue()
    }

    @Test
    fun `encode then decode round trips every field`() {
        val prefs = KeyBarPrefs(
            mode = KeyBarLayoutMode.CUSTOM,
            size = KeyBarSize.LARGE,
            rows = 3,
            caps = listOf(
                KeyBarCap("CTRL", KeyBarCapKind.LATCH, row = 0, order = 0),
                KeyBarCap("F5", KeyBarCapKind.KEY, row = 2, order = 1, visible = false, label = "f5"),
                KeyBarCap("c1", KeyBarCapKind.TEXT, row = 2, order = 0, text = "../", label = "up"),
            ),
        )

        val decoded = KeyBarPrefsCodec.decode(KeyBarPrefsCodec.encode(prefs))

        assertThat(decoded.mode).isEqualTo(KeyBarLayoutMode.CUSTOM)
        assertThat(decoded.size).isEqualTo(KeyBarSize.LARGE)
        assertThat(decoded.rows).isEqualTo(3)
        assertThat(decoded.caps).containsExactlyElementsIn(prefs.caps).inOrder()
    }

    @Test
    fun `an unparseable blob yields the defaults wholesale`() {
        assertThat(KeyBarPrefsCodec.decode("not json")).isEqualTo(KeyBarPrefs())
        assertThat(KeyBarPrefsCodec.decode("[1,2,3]")).isEqualTo(KeyBarPrefs())
    }

    @Test
    fun `an unknown mode or size falls back rather than failing`() {
        val json = """{"mode":"SPIRAL","size":"TITANIC","caps":[
            {"id":"CTRL","kind":"LATCH"},
            {"id":"ESCAPE","kind":"KEY"}
        ]}"""

        val prefs = KeyBarPrefsCodec.decode(json)

        assertThat(prefs.mode).isEqualTo(KeyBarLayoutMode.SINGLE)
        assertThat(prefs.size).isEqualTo(KeyBarSize.NORMAL)
        assertThat(prefs.caps.map { it.id }).containsExactly("CTRL", "ESCAPE").inOrder()
    }

    @Test
    fun `every kind of broken cap is dropped without touching its neighbours`() {
        val json = """{"caps":[
            {"id":"CTRL","kind":"LATCH"},
            {"id":"NOT_A_LATCH","kind":"LATCH"},
            {"id":"NOT_A_KEY","kind":"KEY"},
            {"id":"c1","kind":"TEXT","text":"   "},
            {"id":"c2","kind":"TEXT","text":"ok"},
            {"id":"CTRL","kind":"LATCH"},
            {"id":"c3","kind":"MYSTERY"}
        ]}"""

        val prefs = KeyBarPrefsCodec.decode(json)

        // The duplicate CTRL (second occurrence), the unknown latch, the unknown key, the blank
        // text cap and the unknown kind are all gone; the real CTRL and the usable custom cap stay.
        assertThat(prefs.caps.map { it.id }).containsExactly("CTRL", "c2").inOrder()
        assertThat(prefs.caps.last().text).isEqualTo("ok")
    }

    @Test
    fun `an empty caps array is the default bar, not an empty bar`() {
        val prefs = KeyBarPrefsCodec.decode("""{"caps":[]}""")

        assertThat(prefs.caps).isEqualTo(KeyBarCatalog.DEFAULT_CAPS)
    }

    @Test
    fun `rows and orders are clamped to what the layout can draw`() {
        val json = """{"mode":"CUSTOM","rows":99,"caps":[
            {"id":"c1","kind":"TEXT","text":"a","row":50,"order":-4}
        ]}"""

        val prefs = KeyBarPrefsCodec.decode(json)

        assertThat(prefs.rows).isEqualTo(KeyBarPrefsCodec.MAX_ROWS)
        val cap = prefs.caps.single()
        assertThat(cap.row).isEqualTo(KeyBarPrefsCodec.MAX_ROWS - 1)
        assertThat(cap.order).isEqualTo(0)
    }

    @Test
    fun `custom caps beyond the limit are dropped`() {
        val manyCaps = (1..(KeyBarPrefsCodec.MAX_CUSTOM_CAPS + 5)).joinToString(",") {
            """{"id":"c$it","kind":"TEXT","text":"x"}"""
        }

        val prefs = KeyBarPrefsCodec.decode("""{"caps":[$manyCaps]}""")

        assertThat(prefs.caps.count { it.kind == KeyBarCapKind.TEXT })
            .isEqualTo(KeyBarPrefsCodec.MAX_CUSTOM_CAPS)
    }

    @Test
    fun `long text and labels are truncated on the way in`() {
        val json = """{"caps":[
            {"id":"c1","kind":"TEXT","text":"aaaaaaaaaaaaaaaaaaaaaaaa","label":"bbbbbbbbbbbbbbbbbbbbbbbb"}
        ]}"""

        val cap = KeyBarPrefsCodec.decode(json).caps.single()

        assertThat(cap.text?.length).isEqualTo(KeyBarPrefsCodec.MAX_TEXT_LENGTH)
        assertThat(cap.label?.length).isEqualTo(KeyBarPrefsCodec.MAX_LABEL_LENGTH)
    }

    @Test
    fun `DOUBLE mode halves the visible caps in reading order`() {
        val prefs = KeyBarPrefs(
            mode = KeyBarLayoutMode.DOUBLE,
            caps = listOf(
                KeyBarCap("CTRL", KeyBarCapKind.LATCH, order = 0),
                KeyBarCap("ALT", KeyBarCapKind.LATCH, order = 1),
                KeyBarCap("F1", KeyBarCapKind.KEY, order = 2),
                KeyBarCap("F2", KeyBarCapKind.KEY, order = 3, visible = false),
                KeyBarCap("F3", KeyBarCapKind.KEY, order = 4),
            ),
        )

        val rows = prefs.rowsForLayout()

        // Four visible caps: two and two, left-to-right order preserved, the hidden one nowhere.
        assertThat(rows).hasSize(2)
        assertThat(rows[0].map { it.id }).containsExactly("CTRL", "ALT").inOrder()
        assertThat(rows[1].map { it.id }).containsExactly("F1", "F3").inOrder()
    }

    @Test
    fun `CUSTOM mode draws the stored rows literally`() {
        val prefs = KeyBarPrefs(
            mode = KeyBarLayoutMode.CUSTOM,
            rows = 3,
            caps = listOf(
                KeyBarCap("CTRL", KeyBarCapKind.LATCH, row = 0, order = 0),
                KeyBarCap("c1", KeyBarCapKind.TEXT, row = 2, order = 0, text = "/"),
                KeyBarCap("F4", KeyBarCapKind.KEY, row = 1, order = 0),
                KeyBarCap("F5", KeyBarCapKind.KEY, row = 1, order = 1),
            ),
        )

        val rows = prefs.rowsForLayout()

        assertThat(rows).hasSize(3)
        assertThat(rows[0].map { it.id }).containsExactly("CTRL")
        assertThat(rows[1].map { it.id }).containsExactly("F4", "F5").inOrder()
        assertThat(rows[2].map { it.id }).containsExactly("c1")
    }

    @Test
    fun `merging missing standard caps adds them hidden and touches nothing else`() {
        // A configuration from before F12 existed, say: no F-keys at all.
        val old = KeyBarPrefs(
            caps = listOf(KeyBarCap("CTRL", KeyBarCapKind.LATCH, order = 0)),
        )

        val merged = old.withMissingStandardCaps()

        val byId = merged.caps.associateBy { it.id }
        assertThat(byId.keys).containsAtLeast("CTRL", "SHIFT", "F12", "ENTER")
        // The cap the user configured keeps its state; the additions are opt-in.
        assertThat(byId.getValue("CTRL").visible).isTrue()
        assertThat(byId.getValue("F12").visible).isFalse()
        // Idempotent: a second merge changes nothing.
        assertThat(merged.withMissingStandardCaps()).isEqualTo(merged)
    }

    @Test
    fun `every preset decodes to caps that all exist and encode`() {
        val presets = mapOf(
            "basic" to KeyBarPresets.basic(),
            "terminal" to KeyBarPresets.terminal(),
            "developer" to KeyBarPresets.developer(),
            "functionKeys" to KeyBarPresets.functionKeys(),
            "full" to KeyBarPresets.full(),
        )

        presets.forEach { (name, preset) ->
            val decoded = KeyBarPrefsCodec.decode(KeyBarPrefsCodec.encode(preset))
            assertThat(decoded.caps).isEqualTo(preset.caps)
            // Every cap id a preset writes must be one the app can render.
            preset.caps.forEach { cap ->
                when (cap.kind) {
                    KeyBarCapKind.LATCH -> assertThat(cap.id).isIn(KeyBarCatalog.LATCHES)
                    KeyBarCapKind.KEY ->
                        assertThat(TerminalKey.entries.any { it.name == cap.id }).isTrue()
                    KeyBarCapKind.TEXT -> assertThat(cap.text).isNotEmpty()
                }
            }
            // And every preset must draw at least one row.
            assertThat(preset.rowsForLayout().first()).isNotEmpty()
        }
    }

    @Test
    fun `custom ids never collide across adds and deletes`() {
        val prefs = KeyBarPrefs(
            caps = KeyBarCatalog.DEFAULT_CAPS + KeyBarCap("c2", KeyBarCapKind.TEXT, text = "x"),
        )

        // c1 was never used here, so the next free id is c1, not c3.
        assertThat(prefs.nextCustomId()).isEqualTo("c1")

        val withC1 = prefs.copy(caps = prefs.caps + KeyBarCap("c1", KeyBarCapKind.TEXT, text = "y"))
        assertThat(withC1.nextCustomId()).isEqualTo("c3")
    }

    @Test
    fun `ordering swaps stay within a row and renumber densely`() {
        val prefs = KeyBarPrefs(
            mode = KeyBarLayoutMode.CUSTOM,
            rows = 2,
            caps = listOf(
                KeyBarCap("CTRL", KeyBarCapKind.LATCH, row = 0, order = 0),
                KeyBarCap("ALT", KeyBarCapKind.LATCH, row = 0, order = 1),
                KeyBarCap("F1", KeyBarCapKind.KEY, row = 1, order = 0),
            ),
        )
        val alt = prefs.caps.first { it.id == "ALT" }

        val moved = move(prefs, alt, +1)

        // ALT is at the end of its row, so a further right-move is a no-op.
        assertThat(moved.caps).isEqualTo(prefs.caps)
        // Left swaps it with CTRL and renumbers.
        val left = move(prefs, alt, -1)
        val rowZero = left.rowsForLayout()[0].map { it.id }
        assertThat(rowZero).containsExactly("ALT", "CTRL").inOrder()
        // Cross-row move lands at the end of row 1 (index 1).
        val down = moveRow(prefs, alt, +1)
        assertThat(down.rowsForLayout()[1].map { it.id }).containsExactly("F1", "ALT").inOrder()
    }
}
