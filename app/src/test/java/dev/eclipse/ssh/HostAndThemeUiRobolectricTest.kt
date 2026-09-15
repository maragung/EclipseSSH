package dev.eclipse.ssh

import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.lifecycle.ViewModelProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.eclipse.ssh.data.model.HostProfile
import dev.eclipse.ssh.data.model.TerminalTheme
import dev.eclipse.ssh.data.settings.SettingsRepository
import dev.eclipse.ssh.presentation.MainViewModel
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * The two controls the Hosts and Settings screens were rebuilt around: the terminal-theme dropdown and
 * the per-host overflow menu.
 *
 * Both replaced something that grew with its content. The theme was a strip of chips, one per theme,
 * which meant adding a theme made the Settings screen worse and the ninth one was off the edge of a
 * phone; the host actions were a full-width Connect button, which pushed the card taller than the
 * information on it justified and left Edit and Remove reachable only from a detail screen. What
 * replaced them is fixed-size whatever the option list does — so the assertions here are about size
 * and reach as much as about behaviour, and the layout ones run at 320dp, the narrowest screen the
 * `minSdk` this app ships against can have.
 *
 * A note on what can be asserted where. Compose `DropdownMenu` popups compose into the same window
 * under Robolectric and are fully assertable, which is why both menus are driven end to end here. An
 * `AlertDialog` is not: it lands in a real dialog window and `waitForIdle` never settles with one open,
 * so the removal confirmation is asserted at window level through [ShadowDialog] and at view-model
 * level through the host list. Its text is asserted in the instrumentation copy of these tests, where
 * a real window exists.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EclipseApp::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class HostAndThemeUiRobolectricTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val repository get() = SettingsRepository(RuntimeEnvironment.getApplication())

    /**
     * Puts the theme back to the shipped default however a test ended.
     *
     * `preferencesDataStore` caches one store per delegate for the whole classloader, so a theme left
     * behind here is a failure in `SettingsRepositoryTest`'s pristine-defaults check two classes later.
     * Hosts are deleted for the same reason — the Room file is the app's real one.
     */
    @After
    fun restoreSharedState() {
        runCatching { runBlocking { repository.setTerminalTheme(TerminalTheme.DARK.name) } }
        runCatching {
            val viewModel = viewModel()
            compose.runOnUiThread {
                viewModel.uiState.value.hosts.filter { it.id.startsWith(ID_PREFIX) }.forEach(viewModel::deleteHost)
            }
        }
    }

    // ---------------------------------------------------------------- the theme dropdown

    /**
     * The dropdown shows the stored theme, offers every one the app has, and persists the pick.
     *
     * Three separate claims, and the middle one is why this is one test rather than three: a dropdown
     * that showed the right value and saved the right value while offering only the first few options
     * would pass either of the others alone.
     */
    @Test
    fun theThemeDropdownShowsEveryThemeAndPersistsTheChoice() {
        openThemeRow()

        // The trigger names the setting and its value, so a screen reader is not left with "Dark".
        compose.onNodeWithContentDescription("Terminal theme, Dark").assertIsDisplayed()

        compose.onNodeWithContentDescription("Terminal theme, Dark").performClick()
        pump()

        // Every theme, by its display label: nine today, and this fails if one is ever unreachable.
        TerminalTheme.entries.forEach { theme ->
            assertWithMessage("the menu is missing ${theme.label}")
                .that(compose.onAllNodesWithText(theme.label).fetchSemanticsNodes()).isNotEmpty()
        }

        compose.onAllNodesWithText(TerminalTheme.AMBER.label).onFirst().performClick()
        pump()

        val viewModel = viewModel()
        pumpUntil(describe = { "the theme never persisted: ${viewModel.uiState.value.settings.terminalTheme}" }) {
            viewModel.uiState.value.settings.terminalTheme == TerminalTheme.AMBER.name
        }
        // And the trigger now reads back what was chosen rather than the value it launched with.
        compose.onNodeWithContentDescription("Terminal theme, Amber").assertIsDisplayed()
    }

    /** Dismissing the menu without choosing leaves the stored theme alone. */
    @Test
    fun dismissingTheThemeMenuChangesNothing() {
        openThemeRow()
        compose.onNodeWithContentDescription("Terminal theme, Dark").performClick()
        pump()
        assertThat(compose.onAllNodesWithText(TerminalTheme.NORD.label).fetchSemanticsNodes()).isNotEmpty()

        // Back press is the gesture a user actually uses to abandon a menu.
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        pump()

        assertThat(viewModel().uiState.value.settings.terminalTheme).isEqualTo(TerminalTheme.DARK.name)
        compose.onNodeWithContentDescription("Terminal theme, Dark").assertIsDisplayed()
    }

    /**
     * The row holds its shape on a 320dp screen, with the longest theme name selected.
     *
     * This is the failure the chip strip had, measured rather than eyeballed: the control must stay
     * inside the window, stay inside the budget the row gives it, and stay one line tall. A label that
     * wrapped would grow the row's height, which is why the height is bounded too — an ellipsis is the
     * intended outcome here, not a second line.
     */
    @Test
    @Config(qualifiers = "w320dp-h480dp-mdpi")
    fun theThemeRowKeepsItsShapeOnANarrowScreenWithTheLongestName() {
        runBlocking { repository.setTerminalTheme(TerminalTheme.SOLARIZED_LIGHT.name) }
        openThemeRow()

        // The chip with the freshly-written label composes a frame or two after the row does —
        // under CI load a fixed pump can land before its layout pass, so wait for the display
        // assertion to hold instead of asserting it once.
        pumpUntil(describe = { "the theme chip never appeared on its row" }) {
            runCatching {
                compose.onNodeWithContentDescription("Terminal theme, Solarized light").assertIsDisplayed()
            }.isSuccess
        }
        val trigger = compose.onNodeWithContentDescription("Terminal theme, Solarized light")
        val bounds = trigger.getUnclippedBoundsInRoot()
        val root = compose.onNodeWithText("Terminal theme").getUnclippedBoundsInRoot()

        // Inside the window: nothing is hanging off the right edge where it cannot be tapped.
        assertWithMessage("the control runs past the screen")
            .that(bounds.right.value).isAtMost(320f)
        // Inside the budget the row gives it, so the title and subtitle keep the rest.
        assertWithMessage("the control took more than its share of the row")
            .that(bounds.width.value).isAtMost(TRAILING_MAX_WIDTH_DP)
        // One line: a wrapped label would make this roughly twice as tall.
        assertWithMessage("the label wrapped instead of ellipsizing")
            .that(bounds.height.value).isAtMost(SINGLE_LINE_MAX_HEIGHT_DP)
        // Still on the same row as its title rather than pushed below it.
        assertWithMessage("the control fell off its row")
            .that(bounds.top.value).isLessThan(root.bottom.value + SINGLE_LINE_MAX_HEIGHT_DP)
    }

    // ---------------------------------------------------------------- the host overflow menu

    /**
     * The card has no Connect button, and all three actions live behind the kebab.
     *
     * The negative half matters as much as the positive: the button being gone is the requested change,
     * and a card that grew one back would quietly undo it while every other assertion still passed.
     */
    @Test
    fun theHostCardOffersConnectEditAndRemoveBehindTheKebabAndNoConnectButton() {
        val host = addHost("Kebab edge")

        // Nothing on the card itself is a Connect control any more: the row itself connects (see the
        // test below), and it carries no "Connect" text of its own.
        assertWithMessage("the oversized Connect button is back on the card")
            .that(compose.onAllNodesWithText("Connect").fetchSemanticsNodes()).isEmpty()

        openKebabMenu(host.name, "Connect")
        pump()

        compose.onNodeWithText("Connect").assertIsDisplayed()
        compose.onNodeWithText("Edit").assertIsDisplayed()
        compose.onNodeWithText("Remove").assertIsDisplayed()
    }

    /**
     * One tap on the card opens the login - the same thing the kebab's Connect item does.
     *
     * The dialog is confirmed at window level, the pattern the Remove test below uses: a Compose
     * dialog never goes idle under Robolectric, so the honest signal that the login opened is the
     * window itself, not the nodes inside it.
     */
    @Test
    fun tappingTheCardItselfOpensTheLogin() {
        val host = addHost("Tappable")
        val before = ShadowDialog.getShownDialogs().size

        // The host's name sits on the card, so clicking it clicks the card.
        compose.onNodeWithText(host.name).performClick()
        pump()

        assertWithMessage("tapping the card did not open the login")
            .that(ShadowDialog.getShownDialogs().size).isGreaterThan(before)
        assertThat(ShadowDialog.getLatestDialog()?.isShowing).isTrue()
    }

    /**
     * Remove asks first, and the host is still there while it is asking.
     *
     * The dialog is confirmed at window level: `ShadowDialog` sees the real window the `AlertDialog`
     * opened, which is the one thing that distinguishes "asked for confirmation" from "did nothing".
     */
    @Test
    fun removingAHostAsksBeforeItDeletesAnything() {
        val host = addHost("Removable")
        val before = ShadowDialog.getShownDialogs().size

        openKebabMenu(host.name, "Remove")
        compose.onNodeWithText("Remove").performClick()
        pump()

        assertWithMessage("Remove deleted the host without asking")
            .that(ShadowDialog.getShownDialogs().size).isGreaterThan(before)
        assertThat(ShadowDialog.getLatestDialog()?.isShowing).isTrue()
        // Nothing has been deleted yet: the profile is still in the list behind the dialog.
        assertThat(viewModel().uiState.value.hosts.map(HostProfile::id)).contains(host.id)
    }

    /**
     * Editing opens the form on the host that was tapped, prefilled from it.
     *
     * The prefill is the assertion. An Edit that opened a blank Add form would look right — a form
     * appears — and then save a second host instead of changing the one the user meant.
     */
    @Test
    fun editingAHostOpensItsOwnFormPrefilled() {
        val host = addHost("Editable", hostname = "edit.example.test", username = "editor", port = 2244)

        openKebabMenu(host.name, "Edit")
        compose.onNodeWithText("Edit").performClick()
        pump()

        // The Add/Edit form is an AlertDialog, so its window is what can be seen from here.
        assertThat(ShadowDialog.getLatestDialog()?.isShowing).isTrue()
        // Prefilled with this host: the values are in the dialog's own window, reachable by text.
        listOf(host.name, "edit.example.test", "editor", "2244").forEach { value ->
            assertWithMessage("the form did not carry $value over")
                .that(compose.onAllNodesWithText(value, substring = true).fetchSemanticsNodes()).isNotEmpty()
        }
    }

    /**
     * Connect from the menu starts an attempt on the host that was tapped.
     *
     * There is no server here on purpose — this is about the wiring, not about SSH, which
     * `ConnectionMatrixRobolectricTest` drives against a real one. A profile with no saved credential
     * has to reach the authentication prompt, and reaching it proves the menu item carried the right
     * host to the right handler.
     */
    @Test
    fun connectFromTheMenuStartsAnAttemptOnThatHost() {
        val host = addHost("Connectable")
        val before = ShadowDialog.getShownDialogs().size

        openKebabMenu(host.name, "Connect")
        compose.onNodeWithText("Connect").performClick()
        pump()

        // Either the credential prompt opened, or an attempt is already under way with its own tab.
        val prompted = ShadowDialog.getShownDialogs().size > before
        val attempted = viewModel().uiState.value.tabs.any { it.hostId == host.id }
        assertWithMessage("Connect did nothing at all").that(prompted || attempted).isTrue()
    }

    /**
     * The kebab is a real touch target, and the card stays compact on a narrow screen.
     *
     * 48dp is the platform minimum for anything a finger has to hit; a card that squeezed its menu
     * button to fit a long host name would be the regression. The height bound is the other half of the
     * request — removing the full-width button was meant to make these cards shorter, so a card that
     * grew back past two rows of content plus its padding has lost the point of the change.
     */
    @Test
    @Config(qualifiers = "w320dp-h480dp-mdpi")
    fun theKebabStaysTappableAndTheCardStaysCompactOnANarrowScreen() {
        val host = addHost(
            "A deliberately long host name that cannot possibly fit",
            hostname = "a-very-long-hostname.internal.example.test",
            username = "a-long-username",
        )

        val kebab = compose.onNodeWithContentDescription("More actions for ${host.name}")
        // Scrolled to first: at 480dp tall this card is the third in a seeded list, so it composes
        // below the fold and "not displayed" would be a statement about the viewport, not the card.
        kebab.performScrollTo().assertIsDisplayed()
        val bounds = kebab.getUnclippedBoundsInRoot()

        // `touchBoundsInRoot`, not the layout bounds, and the difference is the whole point of the
        // assertion. A Material3 `IconButton` draws a 40dp state layer inside a 48dp target -
        // `minimumInteractiveComponentSize` reports the larger box to the parent and centres the
        // smaller one in it - so measuring the drawn size would fail a button that is in fact
        // perfectly tappable, and measuring nothing would miss one that had been squeezed.
        val touch = kebab.fetchSemanticsNode().touchBoundsInRoot
        val touchWidth = with(compose.density) { touch.width.toDp().value }
        val touchHeight = with(compose.density) { touch.height.toDp().value }
        assertWithMessage("the menu button is below the minimum touch target")
            .that(minOf(touchWidth, touchHeight)).isAtLeast(MIN_TOUCH_TARGET_DP)
        assertWithMessage("the menu button is off the right edge of a 320dp screen")
            .that(bounds.right.value).isAtMost(320f)
        // Compactness, stated structurally rather than as a magic height. The change that was asked
        // for was to take a full-width Connect button off the bottom of the card and put its actions
        // inline; a card that had grown one back would put the kebab on a row of its own, below the
        // name instead of beside it. Overlapping vertical spans is exactly "these share a row", and it
        // holds at any font scale or screen size, which a pixel bound would not.
        val nameBounds = compose.onNodeWithText(host.name, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertWithMessage("the actions are no longer on the same row as the host name")
            .that(bounds.top.value).isLessThan(nameBounds.bottom.value)
        assertWithMessage("the actions are no longer on the same row as the host name")
            .that(nameBounds.top.value).isLessThan(bounds.bottom.value)
        // One line, ellipsized: a name this long must not wrap and make every card in the list taller.
        assertWithMessage("the host name wrapped instead of ellipsizing")
            .that(nameBounds.height.value).isAtMost(SINGLE_LINE_MAX_HEIGHT_DP)
        assertWithMessage("the host name runs past the screen")
            .that(nameBounds.right.value).isAtMost(320f)

        // And it still opens there, at that size.
        kebab.performClick()
        pump()
        compose.onNodeWithText("Connect").assertIsDisplayed()
    }

    /**
     * The Hosts column reaches the edges of a narrow screen, measured on the search field.
     *
     * The screen margins were deliberately thinned to 8dp - a phone screen is the scarce resource,
     * and 20dp each side gave back 6% of a 320dp window for nothing. This pins the new margin from
     * below: the search field is the first full-width thing in the column, so where its text starts
     * is the margin plus the field's own inner padding (16dp in Material3), and where it ends is
     * the same on the other side. The bounds are taken on whatever node the placeholder text
     * resolves to - the merged field, or the placeholder inside it - which is why both bounds are
     * loose enough to hold for either reading and still fail the 20dp margin they replaced.
     */
    @Test
    @Config(qualifiers = "w320dp-h480dp-mdpi")
    fun theHostsColumnUsesAlmostTheWholeWidthOfANarrowScreen() {
        val search = compose.onNodeWithText("Search hosts, tags, or usernames")
        search.performScrollTo().assertIsDisplayed()
        val bounds = search.getUnclippedBoundsInRoot()

        assertWithMessage("the content column is inset like the 20dp margin never left")
            .that(bounds.left.value).isAtMost(28f)
        assertWithMessage("the content column does not reach the right edge of the screen")
            .that(bounds.right.value).isAtLeast(290f)
    }

    // ---------------------------------------------------------------- pasting a credential

    /**
     * The ViewModel's paste reads the clipboard through the audited boundary and hands a credential
     * field a value it can actually hold.
     *
     * The dialog fields themselves cannot be driven here - an open Compose dialog never idles under
     * Robolectric - so the wiring is asserted at the ViewModel boundary the dialogs call: what goes
     * onto the clipboard (a password manager's copy, newline included) and what the paste button
     * would receive. The empty case matters as much: a paste that returns a silent null is
     * indistinguishable from a broken one, which is why it reports instead.
     */
    @Test
    fun pastingASecretReadsTheClipboardAndStripsWhatASingleLineFieldCannotHold() {
        val clipboard = compose.activity.getSystemService(android.content.ClipboardManager::class.java)!!
        compose.runOnUiThread {
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("vault", "hunter2\r\n"))
        }

        var pasted: String? = null
        compose.runOnUiThread { pasted = viewModel().pasteSecret() }
        assertThat(pasted).isEqualTo("hunter2")

        // A clip that held nothing but the newline: normalized to nothing, so it is reported
        // rather than pasted as an empty password.
        compose.runOnUiThread { clipboard.setPrimaryClip(android.content.ClipData.newPlainText("vault", "\n")) }
        compose.runOnUiThread { assertThat(viewModel().pasteSecret()).isNull() }
        assertThat(viewModel().statusMessage.value)
            .isEqualTo("There is nothing on the clipboard to paste")
    }

    /**
     * The menu's Details item opens what only the sheet has: the saved configuration.
     *
     * The arrow this test used to drive sat beside the kebab as a second way into the same sheet, and
     * the sheet carried buttons for the favourite flag and the export, which the menu now owns too. So
     * the negative assertions are the ones that keep the duplication from creeping back, and the detail
     * lines are what must survive: everything the menu has nowhere else to put.
     */
    @Test
    fun detailsFromTheMenuOpensTheSheetAndLeavesTheMenuActionsToTheMenu() {
        val host = addHost("Detailed", hostname = "details.example.test", username = "reader", port = 2022)

        openKebabMenu(host.name, "Details")
        compose.onNodeWithText("Details").performClick()
        pumpUntil(describe = { "the details sheet never composed" }) {
            compose.onAllNodesWithText("Authentication").fetchSemanticsNodes().isNotEmpty()
        }

        // The detail half: what is saved about this host, and what the vault holds for it.
        listOf("Authentication", "Fingerprint", "Credentials", "Route").forEach { label ->
            assertWithMessage("the sheet no longer shows $label")
                .that(compose.onAllNodesWithText(label).fetchSemanticsNodes()).isNotEmpty()
        }
        // And none of what the kebab now offers. "Connect securely" rather than "Connect": the sheet's
        // old button carried that label, and matching the short form would also match the menu item
        // behind it.
        listOf("Connect securely", "Edit", "Delete", "Favorite", "Unfavorite", "Export account", "Duplicate").forEach { duplicate ->
            assertWithMessage("$duplicate is back in the sheet, where the kebab already offers it")
                .that(compose.onAllNodesWithText(duplicate).fetchSemanticsNodes()).isEmpty()
        }
    }

    /**
     * Favorite from the menu toggles the star without leaving the list.
     *
     * Asserted on the host's own row in the view model's state rather than on the star icon, because
     * the star's content description is not host-scoped and the seeded "Production edge" carries one
     * from the start - a toggle on another host that silently did nothing would otherwise look exactly
     * like a star that appeared. The state is also what the Favorites filter reads, so a toggle that
     * failed to persist is a star that filter then cannot find.
     */
    @Test
    fun favoriteFromTheMenuTogglesTheStarOnTheCard() {
        val host = addHost("Starred", hostname = "star.example.test", username = "starer")

        openKebabMenu(host.name, "Favorite")
        compose.onNodeWithText("Favorite").performClick()
        pumpUntil(describe = { "the star never appeared" }) {
            viewModel().uiState.value.hosts.firstOrNull { it.id == host.id }?.isFavorite == true
        }

        // And back off, through the item's other label.
        openKebabMenu(host.name, "Unfavorite")
        compose.onNodeWithText("Unfavorite").performClick()
        pumpUntil(describe = { "the star never left" }) {
            viewModel().uiState.value.hosts.firstOrNull { it.id == host.id }?.isFavorite == false
        }
    }

    /**
     * Export account from the menu opens the same passphrase dialog the sheet's button used to.
     *
     * The export never had a screen of its own, only this prompt, so reaching the prompt is the whole
     * wiring under test - the encryption and file writing behind it are covered by the vault tests.
     *
     * Asserted at window level, like [editingAHostOpensItsOwnFormPrefilled] and unlike every
     * non-dialog assertion in this class: `fetchSemanticsNodes` waits for the compose tree to go
     * idle before it reads, and an open Compose dialog under Robolectric never gets there - the
     * password field keeps the clock busy - so a semantics query after this click would hang for
     * sixty seconds and die as AppNotIdleException rather than answer the question.
     */
    @Test
    fun exportAccountFromTheMenuOpensTheExportDialog() {
        val host = addHost("Exportable", hostname = "export.example.test", username = "exporter")
        val before = ShadowDialog.getShownDialogs().size

        openKebabMenu(host.name, "Export account")
        compose.onNodeWithText("Export account").performClick()
        pump()

        // The only dialog this menu item can open is the export prompt, so the latest showing one
        // being new is the prompt. Nothing else was tapped that would raise a dialog of its own.
        assertWithMessage("the export dialog never opened")
            .that(ShadowDialog.getShownDialogs().size > before && ShadowDialog.getLatestDialog()?.isShowing == true)
            .isTrue()
    }

    /**
     * Duplicate saves a second host with the same settings and a name of its own.
     *
     * A copy that reused the id would overwrite the original - the assertion that both rows exist with
     * different ids is the one that catches it - and a copy named identically would be a card the user
     * cannot tell apart from the one they duplicated.
     */
    @Test
    fun duplicateFromTheMenuSavesAnIndependentCopy() {
        val host = addHost("Original", hostname = "dup.example.test", username = "duper", port = 2222)

        openKebabMenu(host.name, "Duplicate")
        compose.onNodeWithText("Duplicate").performClick()
        pumpUntil(describe = { "the copy never appeared" }) {
            compose.onAllNodesWithContentDescription("More actions for Original (copy)").fetchSemanticsNodes().isNotEmpty()
        }

        val copies = viewModel().uiState.value.hosts.filter { it.host == "dup.example.test" }
        assertThat(copies).hasSize(2)
        assertThat(copies.map { it.id }.toSet()).hasSize(2)
        assertThat(copies.map { it.name }).containsExactly("Original", "Original (copy)")
    }

    /**
     * Port forwarding lives on the host, and the kebab is the host's one way into everything besides
     * connect - so the manager opens from there, on the host it was opened for, and not from Settings.
     */
    @Test
    fun portForwardingInTheKebabOpensThePerHostManagerSheet() {
        val host = addHost("Forwarder", hostname = "fwd.example.test", username = "forwarder")

        openKebabMenu(host.name, "Port forwarding")
        compose.onNodeWithText("Port forwarding").performClick()
        // Anchored on "Add rule" rather than the sheet's own "Port forwarding" title: the kebab item
        // and the title are the same string, and this way the assertion is about the manager being
        // up, not about text that could survive from the menu that just closed. A bottom sheet
        // composes into the same window, so it can be waited on where a dialog cannot.
        pumpUntil(describe = { "the forwarding manager never opened" }) {
            compose.onAllNodesWithText("Add rule").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Add rule").assertIsDisplayed()
    }

    /**
     * Duplicating a host with forwarding rules is a question, not a command: "same box, different
     * purpose" usually wants the tunnels too, but the copy that does not want them must not inherit
     * them silently. A rules-free host duplicates straight away - that is the test above - so this
     * one gives the host rules and asserts the ask happens before any copy exists.
     *
     * Asserted at window level, like the removal ask: an AlertDialog never settles `waitForIdle`.
     */
    @Test
    fun duplicatingAHostWithForwardingRulesAsksBeforeCopyingThem() {
        val host = addHost("Tunnelled", hostname = "tun.example.test", username = "tunneller")
        val before = ShadowDialog.getShownDialogs().size
        compose.runOnUiThread {
            viewModel().saveHost(host.copy(savedForwards = "L:8080:intranet.example:80\nD:1080"))
        }
        pumpUntil(describe = { "the rules never reached the host" }) {
            viewModel().uiState.value.hosts.any { it.id == host.id && it.savedForwards.isNotBlank() }
        }

        openKebabMenu(host.name, "Duplicate")
        compose.onNodeWithText("Duplicate").performClick()
        pump()

        assertWithMessage("Duplicate copied the host's rules without asking")
            .that(ShadowDialog.getShownDialogs().size).isGreaterThan(before)
        assertThat(ShadowDialog.getLatestDialog()?.isShowing).isTrue()
        // No copy yet: the question is still open, and the profile list behind it is unchanged.
        assertThat(viewModel().uiState.value.hosts.filter { it.host == "tun.example.test" }).hasSize(1)
    }

    // ---------------------------------------------------------------- driving the app

    private fun viewModel(): MainViewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]

    /** Saves a host through the view model and waits for the card to appear on the Hosts screen. */
    private fun addHost(
        name: String,
        hostname: String = "kebab.example.test",
        username: String = "tester",
        port: Int = 22,
    ): HostProfile {
        compose.waitForIdle()
        val viewModel = viewModel()
        val profile = HostProfile(
            id = ID_PREFIX + nextId++,
            name = name,
            host = hostname,
            username = username,
            port = port,
        )
        compose.runOnUiThread { viewModel.saveHost(profile) }
        pumpUntil(describe = { "the host never reached the list" }) {
            viewModel.uiState.value.hosts.any { it.id == profile.id }
        }
        pumpUntil(describe = { "the card never appeared for $name" }) {
            compose.onAllNodesWithContentDescription("More actions for $name").fetchSemanticsNodes().isNotEmpty()
        }
        return profile
    }

    /**
     * Opens Settings and scrolls the theme row into view.
     *
     * The tab, not the identically-titled app bar — only the tab is clickable. The shell hosts every
     * destination in a `verticalScroll` Column, so the row composes wherever the viewport ends and
     * asserting display without scrolling first would be asserting the viewport height.
     */
    private fun openThemeRow() {
        compose.waitForIdle()
        compose.onNode(hasText("Settings") and hasClickAction()).performClick()
        pump()
        pumpUntil(describe = { "the Settings screen never composed a theme row" }) {
            compose.onAllNodesWithText("Terminal theme").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Terminal theme").performScrollTo().assertIsDisplayed()
        pump()
    }

    /** A few frames of work, for the popups and recompositions that follow a click. */
    private fun pump(frames: Int = 12) {
        repeat(frames) {
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        }
    }

    /**
     * Opens a host card's kebab menu and waits for the item the caller is about to tap.
     *
     * Two load-shaped failures share one mechanism, and the tap is retried for both: the menu's
     * items compose a frame or two after the kebab's click (the Favorite test's tap once found no
     * node at all), and an injected tap can also simply miss the icon while the host list settles
     * underneath it - a click that never landed is why one run waited 20 seconds for a menu that
     * was never going to open. Each attempt re-finds the kebab for fresh coordinates; a menu that
     * does open is still asserted to hold the item, so a missing item stays a failure.
     */
    private fun openKebabMenu(hostName: String, offering: String) {
        val kebab = "More actions for $hostName"
        repeat(KEBAB_OPEN_ATTEMPTS) {
            compose.onNodeWithContentDescription(kebab).performClick()
            if (waitFor(KEBAB_OPEN_WAIT_MS) {
                    compose.onAllNodesWithText(offering).fetchSemanticsNodes().isNotEmpty()
                }
            ) {
                return
            }
        }
        check(false) {
            "the kebab menu never offered $offering after $KEBAB_OPEN_ATTEMPTS taps on $kebab"
        }
    }

    /**
     * Drives frames until [condition] holds, then fails with [describe].
     *
     * Not `compose.waitUntil`, which reports only "Condition still not satisfied" and swallows an
     * exception thrown during composition. `idleFor` rather than `idle` so the real `delay`s in the
     * DataStore and Room flows come due.
     */
    private fun pumpUntil(timeoutMs: Long = 20_000, describe: () -> String, condition: () -> Boolean) {
        check(waitFor(timeoutMs, condition)) { "timed out after ${timeoutMs}ms: ${describe()}" }
    }

    /** [pumpUntil]'s loop without the failure, for callers that retry rather than give up. */
    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline && !condition()) {
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        }
        return condition()
    }

    private companion object {
        /** Prefix on every host this class creates, so the cleanup can find them and leave others. */
        const val ID_PREFIX = "ui-qa-"

        /** How many times [openKebabMenu] taps the kebab before giving up on the menu. */
        const val KEBAB_OPEN_ATTEMPTS = 3

        /** Per-tap wait for the menu's items; one tap's wait is ~180 frames at 16ms. */
        const val KEBAB_OPEN_WAIT_MS = 3_000L

        /** Mirrors `SETTING_TRAILING_MAX_WIDTH`, which is private to the UI. */
        const val TRAILING_MAX_WIDTH_DP = 156f

        /** A single line of body text plus the button's own padding, in dp. */
        const val SINGLE_LINE_MAX_HEIGHT_DP = 48f

        /** The platform minimum for a touch target. */
        const val MIN_TOUCH_TARGET_DP = 48f

        private var nextId = 0
    }
}
