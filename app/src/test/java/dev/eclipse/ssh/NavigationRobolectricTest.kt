package dev.eclipse.ssh

import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToString
import androidx.lifecycle.ViewModelProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.eclipse.ssh.data.settings.SettingsRepository
import dev.eclipse.ssh.presentation.MainViewModel
import dev.eclipse.ssh.ui.settings.HostFormActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * The navigation surface, exercised on the JVM.
 *
 * [AppNavigationTest] covers the same ground as an instrumentation test, which needs a device or
 * emulator. This runs the identical flows under Robolectric so the UI is verified by an ordinary
 * `testDebugUnitTest` run — on a machine with no emulator, and at **sdk 35**, which is the app's
 * actual targetSdk rather than whatever image happens to be installed. Both are kept: this one runs
 * everywhere and catches regressions early, the instrumentation one is the real-device signal.
 *
 * `qualifiers` pins a normal phone width on purpose. The workspace shell branches at
 * `maxWidth >= 700.dp`, and Robolectric's default screen is narrow enough to be ambiguous;
 * 411dp puts this on the same side of the breakpoint as a phone.
 *
 * Assertions match a clean install, which is *seeded*, not empty: both repositories call
 * `seedIfEmpty()`, so there are two demo hosts and two demo transfers. Seeding lands through Room
 * and a Flow, so anything that depends on it is awaited with [waitForText] rather than assumed
 * present on the first frame.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EclipseApp::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class NavigationRobolectricTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    /** The navigation tab. Since the top bar went actions-only, it is also the only place a destination's name renders. */
    private fun tab(label: String) = compose.onNode(hasText(label) and hasClickAction())

    /** The activity's [MainViewModel], for raising a message the way the UI's own flows do. */
    private fun viewModel(): MainViewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]

    /**
     * A Settings section header.
     *
     * `SettingsSection` renders `title.uppercase()`, so the node's text is "SECURITY", not
     * "Security". Matching case-insensitively keeps the assertion readable and tied to the source
     * string rather than to a presentation detail.
     */
    private fun section(title: String) = hasText(title, ignoreCase = true)

    /** Waits for text that arrives asynchronously (Room seeding, Flow collection). */
    private fun waitForText(text: String) = compose.waitUntil(timeoutMillis = 10_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    /** As [waitForText], for a message whose full text includes something from the seed data. */
    private fun waitForTextContaining(fragment: String) = compose.waitUntil(timeoutMillis = 10_000) {
        compose.onAllNodes(hasText(fragment, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    /**
     * Waits for text that must also be *tappable* — a session chip, not the identically-worded
     * path-bar title.
     *
     * The explorer's path bar reads "This device" from its first frame, but the chips arrive one
     * coroutine hop later (the session list is refreshed by a LaunchedEffect reading Room), so
     * waiting on the bare text passes on the title and then races the chip — which is exactly how
     * this failed once on a loaded runner. Waiting on the clickable form waits for the chip itself.
     */
    private fun waitForClickable(text: String) = compose.waitUntil(timeoutMillis = 10_000) {
        compose.onAllNodes(hasText(text) and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
    }

    /**
     * Asserts the text is on screen, and says *why* if it is not.
     *
     * `assertIsDisplayed` fails with nothing but "The component is not displayed!", which does not
     * separate "composed but below the fold" from "never placed" — and on a phone-sized viewport
     * both are realistic. Reporting the geometry makes the difference obvious instead of guesswork.
     */
    private fun assertDisplayed(text: String) {
        val interaction = compose.onNodeWithText(text)
        try {
            interaction.assertIsDisplayed()
        } catch (expected: AssertionError) {
            // The diagnostic must not become the failure. `fetchSemanticsNode` throws its own
            // "Expected exactly '1' node but could not find any" when the text is absent rather than
            // merely off-screen, and that error replaced the real one and pointed at this line instead
            // of the assertion that failed — which is the opposite of what this helper is for. When the
            // node is genuinely missing the tree is the useful thing to print, because the usual cause
            // is that the screen under test never composed.
            val node = runCatching { interaction.fetchSemanticsNode() }.getOrNull()
                ?: throw AssertionError(
                    "\"$text\" is nowhere in the tree:\n${compose.onRoot().printToString(maxDepth = 12)}",
                    expected,
                )
            val root = compose.onRoot().fetchSemanticsNode()
            throw AssertionError(
                "\"$text\" is not displayed: bounds=${node.boundsInRoot} " +
                    "placed=${node.layoutInfo.isPlaced} viewport=${root.boundsInRoot}",
                expected,
            )
        }
    }

    /**
     * A Settings section, scrolled into view first.
     *
     * The shell hosts every destination in a `verticalScroll` Column, so every section composes but
     * only the first screenful is on screen — asserting display without scrolling would be asserting
     * the viewport height, not the app.
     */
    private fun assertSettingsSection(title: String) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(section(title)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(section(title)).performScrollTo().assertIsDisplayed()
    }

    /**
     * Runs pending main-looper work.
     *
     * Robolectric keeps the main looper paused, and opening or dismissing a dialog is a
     * `WindowManager.addView`/`removeView` posted to it, so nothing happens until it is drained.
     * `idle()` runs only what is already due, so a self-reposting frame callback cannot spin it.
     */
    private fun drainMainLooper() = shadowOf(Looper.getMainLooper()).idle()

    /**
     * Drives frames and main-looper work until [condition] produces a value, giving up after
     * [PUMP_TIMEOUT_MS] of real time. Returns null if it never does, so the caller can fail with its
     * own message.
     *
     * `sendApplyNotifications` on every turn is the part that matters. Clicking writes a
     * `mutableStateOf` from inside a snapshot, and the recomposer is only invalidated when that
     * write is *published*. `waitForIdle` normally publishes it, but it cannot be called with a
     * dialog open, and without publication the recomposer has nothing to recompose — so no number of
     * frames would ever bring the window up. Bounding by time rather than by a frame count for the
     * same reason the count was wrong before: Robolectric runs every class in one JVM, so how much
     * pumping a window needs depends on what ran before it, and any fixed number is a test that
     * passes alone and fails in a suite.
     */
    private fun <T> pumpUntil(condition: () -> T?): T? {
        val deadline = System.nanoTime() + PUMP_TIMEOUT_MS * 1_000_000
        while (System.nanoTime() < deadline) {
            condition()?.let { return it }
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
            drainMainLooper()
        }
        return condition()
    }

    private companion object {
        const val PUMP_TIMEOUT_MS = 20_000L
    }

    /**
     * A status message must not swallow taps on the navigation bar.
     *
     * The single [SnackbarHost] used to overlay the whole window at `BottomCenter`, directly on top of
     * the `NavigationBar`, so while a message was showing — four seconds, and every status or error
     * report raises one — the five tabs were untappable and a tap produced nothing at all. It is now
     * the narrow layout's `Scaffold` snackbar slot, which offsets it above the bottom bar.
     *
     * The message is raised through [MainViewModel.reportUiMessage] — the same public entry point the
     * UI's own failures call — rather than by driving a failing flow, because the old trigger
     * (browsing a disconnected host from Files) now reports *inline* in the explorer by design, and
     * the remaining snackbar raisers need pickers, prompts or live sockets a clean install cannot
     * offer. The property under test is the layout's, not the trigger's: a showing snackbar, however
     * it got there, must leave the bar tappable. The assertion is that the *next* tap still works.
     */
    @Test
    fun aStatusMessageDoesNotBlockTheNavigationBar() {
        compose.waitForIdle()

        tab("Files").performClick()
        compose.runOnUiThread { viewModel().reportUiMessage("Production edge is not connected") }
        waitForTextContaining("is not connected")

        tab("Transfers").performClick()
        assertDisplayed("Transfer queue")
    }

    @Test
    fun everyDestinationIsReachableAndRendersItsFirstRunContent() {
        compose.waitForIdle()

        // Hosts is the start destination. Its top bar carries the filter field in the title slot
        // and the two actions, so those are what identify the screen - there is no title text and
        // no separate search icon to look for.
        assertDisplayed("Search hosts, tags, or usernames")
        compose.onNodeWithContentDescription("Add host").assertIsDisplayed()
        compose.onNodeWithContentDescription("Import account").assertIsDisplayed()

        tab("Terminal").performClick()
        assertDisplayed("No active sessions")

        tab("Files").performClick()
        // The explorer's first run: the device's own session, first and always, with its front door
        // on screen because a SAF folder is the one thing this screen cannot grant itself — and an
        // honest "no folder chosen yet" rather than a claim about an empty folder. The seeded demo
        // hosts' chips are here too: every saved host stays reachable from Files, connected or not.
        // [FilesExplorerLayoutRobolectricTest] covers the chip order and the listing's layout.
        // Clickable rather than merely present: the path bar also reads "This device" before any
        // folder is granted, and only the chip is the tappable one.
        waitForClickable("This device")
        compose.onNode(hasText("This device") and hasClickAction()).assertExists()
        compose.onNode(hasText("Pick folder") and hasClickAction()).assertExists()
        waitForTextContaining("No folder chosen yet")
        waitForClickable("Production edge")
        compose.onNode(hasText("Production edge") and hasClickAction()).assertExists()

        tab("Transfers").performClick()
        // The header renders unconditionally; the queue is not empty on a clean install, so assert
        // the seeded rows actually arrived from Room rather than the "No transfers" branch.
        assertDisplayed("Transfer queue")
        waitForText("release-bundle.tar.gz")
        compose.onNodeWithText("release-bundle.tar.gz").assertExists()
        compose.onNodeWithText("deploy.sh").assertExists()

        tab("Settings").performClick()
        assertSettingsSection("Linux userspace")
        assertSettingsSection("Security")
        assertSettingsSection("Workspace")
        assertSettingsSection("Port forwarding")
        assertSettingsSection("Backup & restore")
        assertSettingsSection("Background processing")

        // And back, without the round trip having disturbed anything.
        tab("Hosts").performClick()
        assertDisplayed("Search hosts, tags, or usernames")
    }

    /**
     * The Linux userspace section is the one Settings opens on.
     *
     * Position, not reachability, and that is the whole point of the test: [assertSettingsSection]
     * scrolls a section into view wherever it sits, so every assertion above stays green with the
     * userspace moved anywhere on the list — the walk pins that the section still renders, never
     * that it renders first. What a user sees first is the section nearest the top, so that is what
     * is compared, by the same node bounds [assertDisplayed] reports a failure with.
     *
     * The two sections the comparison reads are both inside the first screenful of a phone — the
     * userspace section is one row — so neither is off the bottom of the viewport at the list's own
     * opening position, and no scroll is needed to make the bounds meaningful.
     */
    @Test
    fun theLinuxUserspaceSectionIsTheFirstOneOnSettings() {
        compose.waitForIdle()
        tab("Settings").performClick()

        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(section("Linux userspace")).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(section("Security")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(section("Linux userspace")).assertIsDisplayed()
        val userspace = compose.onNode(section("Linux userspace")).fetchSemanticsNode()
        val security = compose.onNode(section("Security")).fetchSemanticsNode()

        assertWithMessage(
            "the Linux userspace section is no longer the first one on Settings: its header's " +
                "bottom is at ${userspace.boundsInRoot.bottom} and Security's top is at " +
                "${security.boundsInRoot.top}, so Security now opens the list",
        ).that(userspace.boundsInRoot.bottom).isLessThan(security.boundsInRoot.top)
    }

    @Test
    fun theSelectedDestinationSurvivesActivityRecreation() {
        compose.waitForIdle()
        tab("Transfers").performClick()
        assertDisplayed("Transfer queue")

        // rememberSaveable has to carry the destination through the save/restore that a
        // configuration change or a process kill puts the activity through.
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()

        assertDisplayed("Transfer queue")
    }

    /**
     * The reconnect delay is reachable from Settings.
     *
     * It was not: the value was persisted, clamped, carried in vault backups and read by the
     * backoff, but no setter existed on the ViewModel and no row existed here, so the only way it
     * ever changed was importing a backup that happened to contain a different number. A setting the
     * user cannot reach is not a setting, and nothing in the suite noticed — every other test
     * asserted the value round-trips through storage, which it always did.
     *
     * Asserted read-only, against the default. Writing a setting here would land in the DataStore
     * that `preferencesDataStore` caches for the whole classloader and change what a sibling class
     * sees — see [MainActivitySecureWindowTest], which needs a restore guard for exactly that reason.
     * The store's own clamping is covered by `SettingsRepositoryTest`; what only this level can show
     * is that the row is on screen, shows the stored value rather than a hardcoded one, and offers a
     * control to change it.
     */
    @Test
    fun theReconnectDelayCanBeChangedFromSettings() {
        compose.waitForIdle()
        tab("Settings").performClick()
        assertSettingsSection("Background processing")

        // The stored value, not a fixed string: DEFAULT_RECONNECT_BASE_SECONDS is what a clean
        // install reads, so a row wired to anything else would not render this.
        val label = "First retry after ${SettingsRepository.DEFAULT_RECONNECT_BASE_SECONDS} s"
        compose.onNode(hasText(label, substring = true)).performScrollTo().assertIsDisplayed()

        // And a control that opens the picker. Window-level for the reason
        // `HostAndThemeUiRobolectricTest` documents: once a Compose dialog is up, waitForIdle never
        // returns under Robolectric, so the chips themselves are out of reach here.
        val changeButton = compose.onAllNodes(hasText("Change") and hasClickAction())
            .fetchSemanticsNodes()
        assertThat(changeButton).isNotEmpty()
    }

    @Test
    fun theHostsScreenActionsAreLabelledForScreenReaders() {
        compose.waitForIdle()

        // Icon-only buttons are unusable with TalkBack unless they carry a description.
        compose.onNodeWithContentDescription("Add host").assertIsDisplayed()
        compose.onNodeWithContentDescription("Import account").assertIsDisplayed()
    }

    /**
     * Add host opens the host form's own window.
     *
     * This used to be an `AlertDialog` over the Hosts list, and this test used to prove the dialog
     * window went up and that back took it down. Both halves of that are gone. The form is an
     * Activity now, and under Robolectric a `startActivity` is *recorded, not performed* — the target
     * never composes, so nothing about the form is reachable from this class at all. What is reachable
     * is the wiring, and that is what this asserts: the click names [HostFormActivity], nothing was
     * shown as a dialog to get there, and the workspace behind it is untouched.
     *
     * The two halves are asserted separately because they fail differently. The intent names the
     * activity; and `ShadowDialog.getShownDialogs()` must not have grown, because a regression that
     * went back to `AlertDialog` would keep the first assertion true-looking enough to be missed while
     * restoring the letterboxed, unscrollable body the promotion removed.
     *
     * The form's own contents — its field labels, the prefill on Edit, the Save button — are asserted
     * by `HostFormActivityRobolectricTest`, which launches the window directly so that it really does
     * compose. The instrumentation copy in [AppNavigationTest] covers the same ground on a device.
     */
    @Test
    fun theAddHostActionOpensTheHostFormWindow() {
        compose.waitForIdle()

        // Drain whatever startup queued, so the peek below only ever reports this click's doing.
        // Peeking does not consume, so a stale intent would mask the one under test.
        while (runCatching { shadowOf(compose.activity.application).nextStartedActivity }.getOrNull() != null) Unit
        val dialogsBefore = ShadowDialog.getShownDialogs().size

        compose.onNodeWithContentDescription("Add host").performClick()

        pumpUntil {
            runCatching { shadowOf(compose.activity.application).peekNextStartedActivity() }.getOrNull()
        }
        val intent = shadowOf(compose.activity.application).peekNextStartedActivity()
        assertWithMessage("the Add host button did not open ${HostFormActivity::class.simpleName}")
            .that(intent?.component?.className).isEqualTo(HostFormActivity::class.java.name)
        // Add carries no host to edit. A form opened for an id that was never passed and an Add form
        // are the same screen once the window is up, so the extra is the only place that difference
        // is visible from here.
        assertThat(intent?.getStringExtra(HostFormActivity.EXTRA_HOST_ID)).isNull()
        assertWithMessage("Add host still opened as a dialog over the workspace")
            .that(ShadowDialog.getShownDialogs().size).isEqualTo(dialogsBefore)

        // And the workspace underneath is intact.
        assertDisplayed("Search hosts, tags, or usernames")
    }
}
