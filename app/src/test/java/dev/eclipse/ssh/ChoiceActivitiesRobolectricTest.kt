package dev.eclipse.ssh

import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import com.google.common.truth.Truth.assertWithMessage
import dev.eclipse.ssh.data.settings.SettingsRepository
import dev.eclipse.ssh.ui.settings.ClipboardClearActivity
import dev.eclipse.ssh.ui.settings.KeepAliveActivity
import dev.eclipse.ssh.ui.settings.ReconnectDelayActivity
import dev.eclipse.ssh.ui.settings.TerminalFontSizeActivity
import dev.eclipse.ssh.ui.settings.TerminalHeightActivity
import dev.eclipse.ssh.ui.settings.TerminalWidthActivity
import dev.eclipse.ssh.ui.settings.VaultAutoLockActivity
import java.time.Duration
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/*
 * The six screens the promoted Settings rows open, each in its own window.
 *
 * Separate from [SettingsChoiceRowsRobolectricTest] because the subject is different: that class pins
 * the wiring from Settings, this file pins what the windows actually show. Holding both in one class
 * is impossible — a Compose rule's activity is fixed when the rule is constructed, so one class can
 * carry one window, and launching a window for real is the whole point: a `startActivity` recorded by
 * a MainActivity-driven test proves nothing about whether the screen behind it composes.
 *
 * Hence one class per window. They share this file's helpers rather than each redefining them, which
 * is the one thing the suite's house style does per *file* and not per class: `pump`, `pumpUntil` and
 * the assertions below are private to this file and used by all six.
 *
 * This is also where the assertions the dialogs made impossible now live. Under Robolectric an
 * `AlertDialog` never settles `waitForIdle`, and its body was capped at a fraction of the screen
 * height, so the old suite read whatever happened to compose above the fold and said so in a comment
 * rather than asserting the rest. A real activity window has neither limit: `assertIsDisplayed` idles
 * normally and every chip is asserted *displayed*, not merely present, which is the closest this level
 * can come to saying the body is no longer capped.
 *
 * Nothing here is found by a content description. The descriptions that disambiguate the buttons on
 * the Settings list belong to those rows, in `MainActivity`, and the row is behind us by the time a
 * window is on screen — no screen in this file repeats one. What a screen is asked about is what it
 * shows: its bar, its row, its chips, its readout.
 */

/**
 * A few frames of work, for the recompositions that follow the settings read.
 *
 * The same helper [SettingsChoiceRowsRobolectricTest] defines privately, and private here for the same
 * reason: it is the suite's shape for driving a Robolectric Compose window by hand.
 */
private fun AndroidComposeTestRule<*, *>.pump(frames: Int = 12) {
    repeat(frames) {
        Snapshot.sendApplyNotifications()
        mainClock.advanceTimeByFrame()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
    }
}

/**
 * Drives frames and main-looper work until [condition] holds, then fails with [describe].
 *
 * Not `compose.waitUntil`, which reports only "Condition still not satisfied"; `idleFor` rather than
 * `idle` so real `delay`s in the DataStore flow come due. The frame work is [pump]'s, one frame at a
 * time: these screens compose their first frame against `AppSettings()` defaults and only then read
 * the store, so a screen's *static* text is there when `waitForIdle` returns while anything derived
 * from a stored value is a hop behind it.
 */
private fun AndroidComposeTestRule<*, *>.pumpUntil(timeoutMs: Long = 20_000, describe: () -> String, condition: () -> Boolean) {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    while (System.nanoTime() < deadline && !condition()) pump(frames = 1)
    check(condition()) { "timed out after ${timeoutMs}ms: ${describe()}" }
}

/**
 * Asserts the window names itself: the top bar carries [title], and the body's row repeats it.
 *
 * Every one of these screens renders its title twice, so this asks for a collection rather than a
 * single node — `onNodeWithText` demands exactly one match and would fail on the pair. The count is
 * asserted at two and not merely "at least one" so that a screen which lost its bar, or its row, is a
 * failure here; and every match is asserted displayed, so neither copy is off screen.
 */
private fun AndroidComposeTestRule<*, *>.assertTitled(title: String) {
    val named = onAllNodesWithText(title)
    assertWithMessage("the window does not name itself: nothing reads \"$title\"")
        .that(named.fetchSemanticsNodes().size).isAtLeast(2)
    repeat(named.fetchSemanticsNodes().size) { named[it].assertIsDisplayed() }
}

/**
 * Asserts the body's section header is on screen.
 *
 * `SettingsSection` renders `title.uppercase()`, and here the header is the *same words* as the
 * window's title and its row's, so this match is case-sensitive on purpose. Asking for
 * `ignoreCase = true`, the way `SettingsAboutRobolectricTest` matches its section headers, would match
 * three nodes on these screens and fail with "expected exactly one".
 */
private fun AndroidComposeTestRule<*, *>.assertSectionHeader(title: String) {
    onNode(hasText(title.uppercase())).assertIsDisplayed()
}

/**
 * Asserts the screen offers a choice labelled [label], and that it is on screen.
 *
 * A collection rather than `onNodeWithText`, because the choice the screen is currently set to is
 * spelled twice: once on its chip, and once in the row's own control, which is the reading a dialog
 * had nowhere to put. `onNodeWithText` demands exactly one match, so the two-node case has to be
 * asked for deliberately.
 *
 * `assertIsDisplayed` and not `assertExists`, and deliberately without a `performScrollTo` first:
 * these screens exist to be the body a dialog could not give them, so a chip that is composed but
 * clipped out of the window is the regression this file is here to catch, and scrolling to it would
 * restore the very limit it is checking is gone.
 */
private fun AndroidComposeTestRule<*, *>.assertChoiceOffered(label: String) {
    val matches = onAllNodesWithText(label)
    assertWithMessage("the screen offers no choice labelled \"$label\"")
        .that(matches.fetchSemanticsNodes()).isNotEmpty()
    matches.onFirst().assertIsDisplayed()
}

/**
 * Keep-alive interval: seconds between SSH keep-alive signals.
 *
 * The interval list is private to the activity, so the labels below are the only statement of it
 * anywhere but in that file — which is also why they are written out rather than derived: they are
 * what the user reads, and a test that computed them the same way the screen does could not notice the
 * screen changing its mind about the wording.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EclipseApp::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class KeepAliveScreenRobolectricTest {

    @get:Rule
    val compose = createAndroidComposeRule<KeepAliveActivity>()

    @Test
    fun theScreenOffersEveryIntervalItCanStore() {
        compose.waitForIdle()

        compose.assertTitled("Keep-alive interval")
        compose.onNodeWithText("Seconds between SSH keep-alive signals").assertIsDisplayed()
        compose.assertSectionHeader("Keep-alive interval")

        // The last entry is the one a capped body loses first, and nothing is scrolled before it.
        listOf("15 s", "30 s", "60 s", "120 s", "300 s").forEach { compose.assertChoiceOffered(it) }
    }
}

/**
 * Clipboard auto-clear: how long a copied secret is allowed to sit in the clipboard.
 *
 * "Off" is on the list and is the one choice whose label is a word rather than a number — 0 means
 * never clear, and the chip says so. The list stops at 120 s, so a store holding a longer delay is
 * shown as the nearest offered choice rather than as no choice at all; that fallback is the screen's
 * own business and not asserted here, which is why the labels are the offered set and not the store's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EclipseApp::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class ClipboardClearScreenRobolectricTest {

    @get:Rule
    val compose = createAndroidComposeRule<ClipboardClearActivity>()

    @Test
    fun theScreenOffersEveryClearDelayItCanStore() {
        compose.waitForIdle()

        compose.assertTitled("Clipboard auto-clear")
        compose.onNodeWithText("Clear copied secrets after (0 = never)").assertIsDisplayed()
        compose.assertSectionHeader("Clipboard auto-clear")

        listOf("Off", "15 s", "30 s", "60 s", "120 s").forEach { compose.assertChoiceOffered(it) }
    }
}

/**
 * Terminal font size: the one of the six that is a slider, not a list of choices.
 *
 * What it must show is the live readout — the dialog had it, and a slider with no number on it asks
 * the user to guess. The expected text is read from the same repository the screen reads (the same
 * store delegate, over the app's real DataStore) rather than hardcoded, so the assertion is that the
 * screen reports what is stored and spells it the way it always has, not that it matches a number this
 * test also decided on. The read is also why the wait is a `pumpUntil`: the readout is derived from a
 * stored value, and that value arrives a hop after the first frame.
 *
 * A pinch-to-zoom gesture writes the same setting, which is why the number matters more here than on
 * the other five screens: this is the only place the user can read what a pinch left behind.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EclipseApp::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class TerminalFontSizeScreenRobolectricTest {

    @get:Rule
    val compose = createAndroidComposeRule<TerminalFontSizeActivity>()

    private val repository get() = SettingsRepository(RuntimeEnvironment.getApplication())

    @Test
    fun theSliderReportsTheStoredSize() {
        compose.waitForIdle()

        compose.assertTitled("Terminal font size")
        compose.onNodeWithText("Pinch the terminal with two fingers to zoom without opening this screen")
            .assertIsDisplayed()
        compose.assertSectionHeader("Terminal font size")

        val stored = runBlocking { repository.settings.first().terminalFontSize }
        val readout = "${SettingsRepository.normalizeFontSize(stored)} sp"
        compose.pumpUntil(describe = { "the readout never showed the stored size, $readout" }) {
            compose.onAllNodesWithText(readout).fetchSemanticsNodes().isNotEmpty()
        }
        // One node, not a collection: a slider screen has no chip to spell the value a second time.
        compose.onNodeWithText(readout).assertIsDisplayed()
    }
}

/**
 * Terminal width: the narrowest terminal the server is told it has.
 *
 * "Fit screen" is the 0 of this setting and is last on no list — it is first here, because fitting the
 * screen is the default reading of "tell the server nothing". The labels carry the unit the setting is
 * counted in, and the list is the one [SettingsRepository.TERMINAL_MIN_COLUMN_CHOICES] holds: the
 * count is checked against that constant, because a label list spelled out by hand is exactly what
 * cannot notice a seventh choice being added to it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EclipseApp::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class TerminalWidthScreenRobolectricTest {

    @get:Rule
    val compose = createAndroidComposeRule<TerminalWidthActivity>()

    @Test
    fun theScreenOffersEveryMinimumWidthItCanStore() {
        compose.waitForIdle()

        compose.assertTitled("Terminal width")
        compose.onNodeWithText("The narrowest the server is told it has; drag the grid sideways for the rest")
            .assertIsDisplayed()
        compose.assertSectionHeader("Terminal width")

        val labels = listOf("Fit screen", "80 cols", "100 cols", "120 cols", "132 cols", "160 cols")
        assertWithMessage("the offered widths and the labels asserted here have drifted apart")
            .that(labels.size).isEqualTo(SettingsRepository.TERMINAL_MIN_COLUMN_CHOICES.size)
        labels.forEach { compose.assertChoiceOffered(it) }
    }
}

/**
 * Terminal height: how many rows the server is told it has, which the view scrolls through.
 *
 * The mirror of the width screen above, and worded as one: pick 132 and the server is told at least
 * 132 columns with the grid panning for the overflow; pick 1000 and it is told at least 1000 rows,
 * with the window following the cursor and the scroll gesture reaching the rest. The values above 60
 * are the ones only a floor can offer - no phone shows a hundred rows, let alone a thousand, and that
 * is the point: the shell prints that many lines before it pages, so the output stays in the app's
 * scrollback instead of going through `less` a screenful at a time. The subtitle asserted here is that
 * promise made visible, which is why it is pinned rather than left to the row's own text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EclipseApp::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class TerminalHeightScreenRobolectricTest {

    @get:Rule
    val compose = createAndroidComposeRule<TerminalHeightActivity>()

    @Test
    fun theScreenOffersEveryHeightItCanStore() {
        compose.waitForIdle()

        compose.assertTitled("Terminal height")
        compose.onNodeWithText("At least this many rows; the screen shows what fits and scrolls the rest")
            .assertIsDisplayed()
        compose.assertSectionHeader("Terminal height")

        val labels = listOf(
            "Fit screen", "24 rows", "30 rows", "40 rows", "50 rows", "60 rows",
            "100 rows", "200 rows", "500 rows", "1000 rows",
        )
        assertWithMessage("the offered heights and the labels asserted here have drifted apart")
            .that(labels.size).isEqualTo(SettingsRepository.TERMINAL_ROW_CHOICES.size)
        labels.forEach { compose.assertChoiceOffered(it) }
    }
}

/**
 * Reconnect delay: the wait before the first retry, which doubles on each further attempt.
 *
 * The row in Settings states what the delay works out to ("First retry after 5 s, then doubling"),
 * which is a sentence about the setting rather than the setting; the screen offers the base delays
 * themselves, in seconds, from [SettingsRepository.RECONNECT_BASE_CHOICES].
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EclipseApp::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class ReconnectDelayScreenRobolectricTest {

    @get:Rule
    val compose = createAndroidComposeRule<ReconnectDelayActivity>()

    @Test
    fun theScreenOffersEveryBaseDelayItCanStore() {
        compose.waitForIdle()

        compose.assertTitled("Reconnect delay")
        compose.onNodeWithText("Wait before the first reconnect attempt; each further attempt doubles it")
            .assertIsDisplayed()
        compose.assertSectionHeader("Reconnect delay")

        val labels = listOf("1 s", "2 s", "5 s", "10 s", "30 s", "60 s")
        assertWithMessage("the offered delays and the labels asserted here have drifted apart")
            .that(labels.size).isEqualTo(SettingsRepository.RECONNECT_BASE_CHOICES.size)
        labels.forEach { compose.assertChoiceOffered(it) }
    }
}

/**
 * Auto-lock vault: how long the app may sit in the background before the PIN is asked for again.
 *
 * "Never" is the 0 of this setting and is the *last* chip, not the first, because
 * [SettingsRepository.VAULT_AUTO_LOCK_CHOICES] orders the delays and puts never-re-locking at the end:
 * the list reads as a scale from a minute to not at all, and the labels follow it rather than sorting
 * the word to the front. The subtitle here is the screen's own description of the setting; what the
 * Settings row says about it ("No PIN is set, so there is no lock to re-arm") is a fact about a
 * different screen and is not this one's to repeat.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EclipseApp::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class VaultAutoLockScreenRobolectricTest {

    @get:Rule
    val compose = createAndroidComposeRule<VaultAutoLockActivity>()

    @Test
    fun theScreenOffersEveryAutoLockDelayItCanStore() {
        compose.waitForIdle()

        compose.assertTitled("Auto-lock vault")
        compose.onNodeWithText("Starts counting when you leave the app; the vault re-locks when you come back")
            .assertIsDisplayed()
        compose.assertSectionHeader("Auto-lock vault")

        val labels = listOf("1 min", "5 min", "15 min", "60 min", "Never")
        assertWithMessage("the offered delays and the labels asserted here have drifted apart")
            .that(labels.size).isEqualTo(SettingsRepository.VAULT_AUTO_LOCK_CHOICES.size)
        labels.forEach { compose.assertChoiceOffered(it) }
    }
}
