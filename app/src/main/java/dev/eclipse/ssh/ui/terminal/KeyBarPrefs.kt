package dev.eclipse.ssh.ui.terminal

import dev.eclipse.ssh.terminal.TerminalKey
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * How the enabled caps are cut into rows.
 *
 * [SINGLE] and [DOUBLE] are derived views: the caps keep one canonical (row, order) sequence and the
 * mode decides how it is presented — one scrolling row, or that sequence halved onto two — so
 * switching modes never loses an arrangement. [CUSTOM] is the only mode where the stored row is drawn
 * literally, because assigning caps to rows by hand is the point of it.
 */
enum class KeyBarLayoutMode { SINGLE, DOUBLE, CUSTOM }

/**
 * The cap height preset. The numbers are the cap's vertical padding and minimum content height; a
 * size is a pair of dp values rather than a scale factor because the two ends do different jobs —
 * the padding sets how much finger room a cap *looks* like it has, the minimum height is the
 * accessibility floor that keeps a one-character label tappable.
 */
enum class KeyBarSize(val verticalPaddingDp: Int, val minHeightDp: Int) {
    COMPACT(6, 20),
    NORMAL(10, 24),
    LARGE(14, 30),
}

/** What a cap does when tapped. */
enum class KeyBarCapKind { LATCH, KEY, TEXT }

/**
 * One cap on the shortcut bar, whatever it does.
 *
 * The bar is data, not code: every cap — the Ctrl latch, the Esc key, a user's own `../` button —
 * is a row in this one shape, and the bar, the settings screen and the presets all read the same
 * list. That is what makes a new key a one-line addition rather than a layout change.
 *
 * [id] is stable across saves: a latch's id is `CTRL`/`ALT`/`SHIFT`, a named key's is its
 * [TerminalKey] name, and a custom cap's is generated (`c1`, `c2`…). [label] and [text] are null
 * when they should fall back — the label to the cap's default, the text to "not a text cap".
 */
data class KeyBarCap(
    val id: String,
    val kind: KeyBarCapKind,
    val row: Int = 0,
    val order: Int = 0,
    val visible: Boolean = true,
    val label: String? = null,
    val text: String? = null,
)

/**
 * The whole shortcut bar as one value: which caps exist, which are showing, and how they are laid
 * out. The model behind the `terminal_key_bar_json` blob, persisted exactly the way the editor's
 * `editorPrefsJson` is — one DataStore key, whole-blob rewrite, tolerant decode.
 */
data class KeyBarPrefs(
    val mode: KeyBarLayoutMode = KeyBarLayoutMode.SINGLE,
    val size: KeyBarSize = KeyBarSize.NORMAL,
    /** Only [KeyBarLayoutMode.CUSTOM] reads this; the other modes derive their rows. */
    val rows: Int = 1,
    val caps: List<KeyBarCap> = KeyBarCatalog.DEFAULT_CAPS,
) {
    /**
     * The visible caps, cut into rows the way [mode] says.
     *
     * Sorting happens here rather than at the write sites because every writer (the settings
     * screen, a preset, a decoded blob) produces the same order guarantee — (row, order) — but a
     * decoded blob is untrusted, and this is the one place that turns whatever it holds into the
     * arrangement the terminal draws.
     */
    fun rowsForLayout(): List<List<KeyBarCap>> {
        val visible = caps.filter { it.visible }.sortedWith(compareBy({ it.row }, { it.order }))
        return when (mode) {
            KeyBarLayoutMode.SINGLE -> listOf(visible)
            // Halved, not interleaved: reading order stays left-to-right across the first row and
            // continues on the second, which is how every preset example in the settings screen
            // shows it and the least surprising split when the user has never touched the order.
            KeyBarLayoutMode.DOUBLE -> {
                val half = (visible.size + 1) / 2
                listOf(visible.take(half), visible.drop(half))
            }
            KeyBarLayoutMode.CUSTOM ->
                List(rows.coerceIn(1, KeyBarPrefsCodec.MAX_ROWS)) { row ->
                    visible.filter { it.row == row }
                }
        }
    }

    /**
     * The id for the next custom cap: `c` plus the smallest number not already taken, so adds and
     * deletes never collide with an id the configuration still mentions.
     */
    fun nextCustomId(): String {
        val taken = caps.mapTo(mutableSetOf()) { it.id }
        var number = 1
        while ("c$number" in taken) number++
        return "c$number"
    }

    /**
     * Adds the standard caps (latches plus every [TerminalKey]) that this configuration does not
     * mention yet, hidden.
     *
     * A configuration written before a key was added to the enum, or hand-trimmed, would otherwise
     * make that key unreachable: the settings screen only offers caps that exist in the list, so a
     * missing cap cannot be re-added from inside the app. Merging keeps the editor's cap list
     * complete while an untouched bar looks exactly as the user left it — the additions are hidden.
     */
    fun withMissingStandardCaps(): KeyBarPrefs {
        val present = caps.mapTo(mutableSetOf()) { it.id }
        val missing = KeyBarCatalog.standardCaps(visible = false).filter { it.id !in present }
        return if (missing.isEmpty()) this else copy(caps = caps + missing)
    }
}

/**
 * The fixed vocabulary of the bar: which latches exist, what every named key is labelled by
 * default, and the arrangement a fresh install ships with.
 */
object KeyBarCatalog {
    /** The latch ids, in the order a fresh install shows them. */
    val LATCHES = listOf("CTRL", "ALT", "SHIFT")

    /**
     * The default label for every named key a cap can carry. The 13 keys the bar has always shown
     * keep the labels users already know from [NAMED_ROW]; the rest — Insert, F1–F12 — label
     * themselves, except Insert, which the terminal world has called INS for forty years.
     */
    private val KEY_LABELS: Map<TerminalKey, String> =
        NAMED_ROW.associate { (label, key) -> key to label } +
            mapOf(TerminalKey.INSERT to "INS")

    fun defaultLabel(key: TerminalKey): String = KEY_LABELS[key] ?: key.name

    fun defaultLabel(cap: KeyBarCap): String = when (cap.kind) {
        KeyBarCapKind.LATCH -> cap.id
        KeyBarCapKind.KEY -> cap.id.let { name ->
            TerminalKey.entries.firstOrNull { it.name == name }?.let(::defaultLabel) ?: name
        }
        KeyBarCapKind.TEXT -> cap.text.orEmpty()
    }

    /**
     * Every cap the app itself can offer — the three latches and every key in [TerminalKey] — with
     * no row or order, because those belong to the configuration that adopts it, not to the catalog.
     */
    fun standardCaps(visible: Boolean): List<KeyBarCap> =
        LATCHES.map { KeyBarCap(it, KeyBarCapKind.LATCH, visible = visible) } +
            TerminalKey.entries.map { KeyBarCap(it.name, KeyBarCapKind.KEY, visible = visible) }

    /**
     * The bar exactly as it was before it became configurable: the three latches, then the
     * [NAMED_ROW] caps in the order every existing user's thumb has learned, on one row. The
     * defaults are not just a preset — they are the compatibility contract, because an install that
     * never opens the settings screen must keep seeing the bar it has always seen.
     */
    val DEFAULT_CAPS: List<KeyBarCap> =
        LATCHES.mapIndexed { index, latch -> KeyBarCap(latch, KeyBarCapKind.LATCH, order = index) } +
            NAMED_ROW.mapIndexed { index, (_, key) ->
                KeyBarCap(key.name, KeyBarCapKind.KEY, order = LATCHES.size + index)
            }
}

/**
 * The built-in starting points, offered in the settings screen behind a confirmation.
 *
 * Each preset is a whole [KeyBarPrefs], not a patch: applying one replaces the arrangement, which
 * is why the settings screen asks before doing it. They are functions rather than vals so a preset
 * can never be handed out and mutated by a caller that still holds the reference.
 */
object KeyBarPresets {
    /** The minimum: quit, complete, run, erase. */
    fun basic(): KeyBarPrefs = KeyBarPrefs(
        mode = KeyBarLayoutMode.SINGLE,
        caps = caps(
            latches = listOf("CTRL", "ALT"),
            keys = listOf(TerminalKey.ESCAPE, TerminalKey.TAB, TerminalKey.ENTER, TerminalKey.BACKSPACE),
        ),
    )

    /** Modal programs: the navigation cluster. */
    fun terminal(): KeyBarPrefs = KeyBarPrefs(
        mode = KeyBarLayoutMode.SINGLE,
        caps = caps(
            latches = listOf("CTRL", "ALT"),
            keys = listOf(
                TerminalKey.ESCAPE, TerminalKey.TAB,
                TerminalKey.ARROW_LEFT, TerminalKey.ARROW_DOWN, TerminalKey.ARROW_UP, TerminalKey.ARROW_RIGHT,
                TerminalKey.HOME, TerminalKey.END,
            ),
        ),
    )

    /** The shell's punctuation, as custom text caps. */
    fun developer(): KeyBarPrefs = KeyBarPrefs(
        mode = KeyBarLayoutMode.SINGLE,
        caps = caps(
            latches = listOf("CTRL", "ALT"),
            keys = listOf(TerminalKey.ESCAPE, TerminalKey.TAB),
            texts = listOf("/", "\\", "|", "~", "_", "-", "=", "+"),
        ),
    )

    /** The function row, split onto two rows so F7 is one thumb-reach, not one scroll, away. */
    fun functionKeys(): KeyBarPrefs = KeyBarPrefs(
        mode = KeyBarLayoutMode.DOUBLE,
        caps = caps(keys = TerminalKey.entries.filter { it.name.startsWith("F") }),
    )

    /** Everything the app can name, nothing hidden. */
    fun full(): KeyBarPrefs = KeyBarPrefs(
        mode = KeyBarLayoutMode.DOUBLE,
        caps = caps(latches = KeyBarCatalog.LATCHES, keys = TerminalKey.entries.toList()),
    )

    private fun caps(
        latches: List<String> = emptyList(),
        keys: List<TerminalKey> = emptyList(),
        texts: List<String> = emptyList(),
    ): List<KeyBarCap> = buildList {
        var order = 0
        latches.forEach { add(KeyBarCap(it, KeyBarCapKind.LATCH, order = order++)) }
        keys.forEach { add(KeyBarCap(it.name, KeyBarCapKind.KEY, order = order++)) }
        texts.forEach { add(KeyBarCap("c${size + 1}", KeyBarCapKind.TEXT, order = order++, text = it)) }
    }
}

/**
 * The encode/decode engine behind `terminal_key_bar_json`.
 *
 * The contract is the editor blob's, word for word: [decode] never throws — a corrupt or hand-edited
 * blob still yields a working bar, because "cannot use your terminal because its key bar is broken"
 * is the same lockout the editor refuses — and [encode] writes every field, so a round-trip is
 * lossless. Unknown cap kinds and ids are dropped rather than guessed at, which is what makes the
 * format evolvable: a cap this version has never heard of has no correct rendering here.
 */
object KeyBarPrefsCodec {

    /**
     * The most rows CUSTOM mode will draw. The bar trades terminal height for keys, and past six
     * rows there is no terminal left on a phone — the cap also bounds what a corrupt blob can ask
     * the layout to build.
     */
    const val MAX_ROWS = 6

    /** The total cap count a decoded blob may carry; the rest is dropped. */
    const val MAX_CAPS = 64

    /**
     * The custom caps one configuration may hold. A preset or a patient user can fill a bar, but a
     * blob is untrusted input and an unbounded list is an unbounded settings screen.
     */
    const val MAX_CUSTOM_CAPS = 24

    /** What one custom cap may send. A cap is a key, not a snippet; the snippet sheet exists. */
    const val MAX_TEXT_LENGTH = 12

    /** What one cap may be labelled. `BACKSPACE` fits; a sentence does not. */
    const val MAX_LABEL_LENGTH = 8

    fun decode(json: String): KeyBarPrefs = try {
        val root = JSONObject(json)
        val mode = root.optString("mode")
            .let { name -> KeyBarLayoutMode.entries.firstOrNull { it.name == name } }
            ?: KeyBarLayoutMode.SINGLE
        val size = root.optString("size")
            .let { name -> KeyBarSize.entries.firstOrNull { it.name == name } }
            ?: KeyBarSize.NORMAL
        val rows = root.optInt("rows", 1).coerceIn(1, MAX_ROWS)
        val caps = decodeCaps(root.optJSONArray("caps"))
        // An empty caps list is not a bar somebody chose - it is a blob that lost its payload - so
        // it falls back to the defaults rather than rendering an empty strip above the keyboard.
        KeyBarPrefs(
            mode = mode,
            size = size,
            rows = if (mode == KeyBarLayoutMode.CUSTOM) rows else 1,
            caps = if (caps.isEmpty()) KeyBarCatalog.DEFAULT_CAPS else caps,
        )
    } catch (error: JSONException) {
        // Not an object, or not parseable at all: the defaults, wholesale.
        KeyBarPrefs()
    }

    fun encode(prefs: KeyBarPrefs): String = JSONObject()
        .put("mode", prefs.mode.name)
        .put("size", prefs.size.name)
        .put("rows", if (prefs.mode == KeyBarLayoutMode.CUSTOM) prefs.rows.coerceIn(1, MAX_ROWS) else 1)
        .put("caps", JSONArray().apply {
            prefs.caps.take(MAX_CAPS).forEach { cap ->
                put(JSONObject()
                    .put("id", cap.id)
                    .put("kind", cap.kind.name)
                    .put("row", cap.row.coerceIn(0, MAX_ROWS - 1))
                    .put("order", cap.order)
                    .put("visible", cap.visible)
                    // Only written when they carry information: a null label is "use the default",
                    // and a null text is "not a text cap", so their absence round-trips as null.
                    .apply { cap.label?.let { put("label", it) } }
                    .apply { if (cap.kind == KeyBarCapKind.TEXT) put("text", cap.text.orEmpty()) }
                )
            }
        })
        .toString()

    /**
     * Reads the caps array. Every rule here is a way a blob can be wrong: an entry that is not an
     * object, a kind this version does not know, a named key that no longer exists, a text cap with
     * nothing to send, a duplicate id (two caps answering to one identity is not a state the editor
     * can produce or the bar can render). Each costs only the cap that broke it.
     */
    private fun decodeCaps(array: JSONArray?): List<KeyBarCap> {
        if (array == null) return emptyList()
        val seen = mutableSetOf<String>()
        val out = ArrayList<KeyBarCap>(array.length())
        var customs = 0
        for (index in 0 until array.length()) {
            if (out.size >= MAX_CAPS) break
            val entry = array.optJSONObject(index) ?: continue
            val id = entry.optString("id").trim().take(64)
            if (id.isEmpty() || !seen.add(id)) continue
            val kind = entry.optString("kind")
                .let { name -> KeyBarCapKind.entries.firstOrNull { it.name == name } } ?: continue
            val cap = when (kind) {
                KeyBarCapKind.LATCH ->
                    if (id in KeyBarCatalog.LATCHES) KeyBarCap(id, kind) else null
                KeyBarCapKind.KEY ->
                    if (TerminalKey.entries.any { it.name == id }) KeyBarCap(id, kind) else null
                KeyBarCapKind.TEXT -> {
                    val text = entry.optString("text").trim().take(MAX_TEXT_LENGTH)
                    if (text.isEmpty() || customs >= MAX_CUSTOM_CAPS) null
                    else { customs++; KeyBarCap(id, kind, text = text) }
                }
            } ?: continue
            val label = entry.optString("label").trim().take(MAX_LABEL_LENGTH)
            out.add(
                cap.copy(
                    row = entry.optInt("row", 0).coerceIn(0, MAX_ROWS - 1),
                    order = entry.optInt("order", 0).coerceIn(0, 999),
                    visible = entry.optBoolean("visible", true),
                    label = label.ifEmpty { null },
                )
            )
        }
        return out
    }
}
