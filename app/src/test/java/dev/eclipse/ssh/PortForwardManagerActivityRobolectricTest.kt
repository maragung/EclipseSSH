package dev.eclipse.ssh

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.data.HostRepository
import dev.eclipse.ssh.data.model.ForwardEntry
import dev.eclipse.ssh.data.model.ForwardRuntime
import dev.eclipse.ssh.data.model.ForwardStatus
import dev.eclipse.ssh.data.model.HostProfile
import dev.eclipse.ssh.data.model.MAX_SAVED_FORWARDS
import dev.eclipse.ssh.data.model.decodeForwardRules
import dev.eclipse.ssh.data.model.describe
import dev.eclipse.ssh.presentation.MainViewModel
import dev.eclipse.ssh.ui.actions.ActionAnswer
import dev.eclipse.ssh.ui.actions.ActionRequests
import dev.eclipse.ssh.ui.actions.ForwardRuleKind
import dev.eclipse.ssh.ui.forward.PortForwardManagerActivity
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The port-forwarding manager, as a window: the host's saved rules, what each one is doing, and what a
 * tap files back to the workspace.
 *
 * This suite is the counterpart of `HostAndThemeUiRobolectricTest`'s kebab test, which used to assert
 * the same screen where it was still a `ModalBottomSheet` over the Hosts list. The rows did not change
 * - the state dot, the rule's own description, the enabled switch, and Start/Stop/Edit/Delete - but the
 * surface did, and nothing about a sheet inside the workspace's composition proves anything about a
 * separate activity: the subject now travels through [ActionRequests], the rules are read from a
 * repository rather than handed over as a `HostProfile`, and a row that acts closes the window.
 *
 * What only this level can show is the split the window is built on. The saved rules are read live
 * from [HostRepository] by the host's id, so a rule written while the manager is open appears in it;
 * the runtime half - what each rule is doing, and which forwards are bound - is a snapshot the
 * workspace hands over, because no second window can reach the forwarding engine. The first is
 * asserted by writing through the window's own repository and watching the row appear, the second by
 * opening with a statuses map that disagrees with the rules themselves and reading the labels back.
 *
 * Start and Delete are deliberately the only rows this suite taps. Enable, Delete and the rule form
 * all produce the same `ActionAnswer.ForwardRules`, so one of them proves the shape; the three are one
 * claim about the saved column, and the engine's own handling of a whole new list is
 * `PortForwardingRobolectricTest`'s subject.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EclipseApp::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class PortForwardManagerActivityRobolectricTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    /**
     * The answer slot is process-wide, so a leftover from another test in this JVM would be read as
     * this test's answer. Emptied before each test rather than after, so a test that fails mid-flight
     * cannot poison the next one.
     */
    @Before
    fun drainAnswers() {
        ActionRequests.takeAnswer()
    }

    /**
     * The rules are the ones the host has saved, in the order the column holds them, and each row
     * carries the state the workspace recorded rather than a state the window guessed.
     *
     * The statuses map is keyed by rule id, and one of the two rules is deliberately missing from it: a
     * rule this process has never touched has no recorded state at all, and the window has to fall back
     * to what the rule itself says - Stopped when it is enabled and simply has not been started,
     * Disabled when it has been switched off. Both halves are asserted here, because that fallback is
     * the entire reason this window is worth opening on a host that is not connected.
     */
    @Test
    fun theRulesAreListedWithTheStateTheWorkspaceRecordedForThem() {
        val host = seedHost("L:8080:intranet.example:80\nD:1080")
        val rules = decodeForwardRules(host.savedForwards, host.id)
        val running = rules.first()

        launchWindow(host, statuses = mapOf(running.id to ForwardStatus(running, ForwardRuntime.RUNNING))).use {
            awaitRow("Stop")
            awaitRow("Start")
            // The recorded rule, and the untouched one behind it.
            awaitText(ForwardRuntime.RUNNING.label)
            awaitText(ForwardRuntime.STOPPED.label)
            // Both rows are described by the rule itself: the recorded one, and the one with no state
            // at all, which is the half that proves the fallback drew a row rather than nothing.
            awaitText(running.describe())
            awaitText(rules[1].describe())
        }
    }

    /**
     * The saved rules come from the repository, so a write that lands while the window is open shows
     * up in it.
     *
     * This is the property that decided the window reads the host live rather than taking a copy: the
     * add and edit forms in this window end in a save through the same repository, and a list held from
     * open time would keep showing the pre-save rules after its own Save button.
     */
    @Test
    fun theRulesAreReadLiveSoAWriteWhileTheWindowIsOpenShowsUp() {
        val host = seedHost("L:8080:intranet.example:80")
        val added = "R:9000:localhost:22"
        // What the new row will say, taken from the same decoder the window uses.
        val addedRow = decodeForwardRules("${host.savedForwards}\n$added", host.id).last().describe()

        launchWindow(host).use { scenario ->
            awaitText(decodeForwardRules(host.savedForwards, host.id).single().describe())
            scenario.onActivity { activity ->
                runBlocking { activity.hostRepository.save(host.copy(savedForwards = "${host.savedForwards}\n$added")) }
            }
            pumpUntil(describe = { "the rule saved while the window was open never appeared" }) {
                compose.onAllNodes(hasText(addedRow)).fetchSemanticsNodes().isNotEmpty()
            }
        }
    }

    /**
     * Start files the rule and the host it belongs to, and closes the window.
     *
     * Both halves of that are the reason the answer exists. The host id is what the workspace resolves
     * the tunnel against, and the close is not a courtesy: the runtime half of this window is a
     * snapshot, so a row left on screen after a tap would be showing the state from before it - a
     * "Start" button over a tunnel that is now coming up.
     */
    @Test
    fun startFilesTheRuleAndClosesTheWindow() {
        val host = seedHost("L:8080:intranet.example:80")
        val rule = decodeForwardRules(host.savedForwards, host.id).single()

        launchWindow(host).use { scenario ->
            awaitRow("Start")
            compose.onNode(hasText("Start") and hasClickAction()).performClick()

            awaitAnswer(
                ActionAnswer.ForwardRuleAction(host.id, rule.id, ForwardRuleKind.START),
                describe = { "the Start row never reached the workspace" },
            )
            awaitClosing(scenario, describe = { "the window stayed up after its Start row was tapped" })
        }
    }

    /**
     * A rule that is on its way back is offered Stop rather than Start.
     *
     * RECONNECTING is grouped with the running states on purpose: it is a recorded RUNNING whose
     * session died under it, so a handle may still exist and a Start here would race the reconnect's
     * own rebind. The state label is the workspace's word for it, not the row's guess from the rule.
     */
    @Test
    fun aReconnectingRuleIsOfferedStopRatherThanAStartThatWouldRaceItsRebind() {
        val host = seedHost("L:8080:intranet.example:80")
        val rule = decodeForwardRules(host.savedForwards, host.id).single()

        launchWindow(host, statuses = mapOf(rule.id to ForwardStatus(rule, ForwardRuntime.RECONNECTING))).use {
            awaitText(ForwardRuntime.RECONNECTING.label)
            awaitRow("Stop")
            assertThat(compose.onAllNodes(hasText("Start") and hasClickAction()).fetchSemanticsNodes()).isEmpty()
        }
    }

    /** Delete files the whole list without that rule, and closes: the saved column is one write. */
    @Test
    fun deleteFilesTheWholeSavedListWithoutThatRuleAndCloses() {
        val host = seedHost("L:8080:intranet.example:80\nD:1080")
        val rules = decodeForwardRules(host.savedForwards, host.id)

        launchWindow(host).use { scenario ->
            // The second row's own Delete: the list is the column's order, so the last Delete belongs
            // to the dynamic rule. Both rows are waited for, not one - the click below indexes into
            // them, and an await that settles for a single row would index a list still being drawn.
            awaitRows("Delete", count = 2)
            compose.onAllNodes(hasText("Delete") and hasClickAction())[1].performClick()

            awaitAnswer(
                ActionAnswer.ForwardRules(host.id, listOf(rules[0])),
                describe = { "the Delete row never reached the workspace" },
            )
            awaitClosing(scenario, describe = { "the window stayed up after its Delete row was tapped" })
        }
    }

    /**
     * The Add row stops at the same cap the column does.
     *
     * [MAX_SAVED_FORWARDS] is what the codec keeps, and a 33rd rule would be a button that saves a list
     * the codec silently truncates - so the row is disabled rather than refused after the fact.
     */
    @Test
    fun addRuleStopsAtTheCapTheCodecKeeps() {
        val full = (1..MAX_SAVED_FORWARDS).joinToString("\n") { "L:${8000 + it}:localhost:${80 + it}" }
        val host = seedHost(full)

        launchWindow(host).use {
            // The row is there and it is disabled. Waited for, and waited for as `Disabled` rather
            // than as the absence of a click action: `disabled()` is what a disabled control records,
            // and whether a disabled clickable also drops its OnClick is a detail of the Compose
            // version rather than something this window decides - the tree's other suites assert a
            // dead row the same way. The wait is also what makes the test honest about when it looks:
            // the row is drawn from the very first composition, while the 32 rules that fill the cap
            // arrive from the repository a frame or two later, so an immediate assert reads the
            // enabled empty-list frame and fails on a row the user never sees.
            awaitText("Add rule")
            pumpUntil(describe = { "the Add row never went disabled at the cap" }) {
                compose.onAllNodes(hasText("Add rule") and isNotEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
        }
    }

    /**
     * A host that leaves the list takes its window with it.
     *
     * Reachable without a race: another window can delete the host while this one is open, and a
     * manager for a profile that no longer exists has no rules left to manage.
     */
    @Test
    fun theWindowClosesWhenItsHostIsRemoved() {
        val host = seedHost("L:8080:intranet.example:80")

        launchWindow(host).use { scenario ->
            awaitText("Add rule")
            scenario.onActivity { activity -> runBlocking { activity.hostRepository.delete(host) } }
            awaitClosing(scenario, describe = { "the window stayed open on a host that no longer exists" })
        }
    }

    /**
     * A host with no rules explains what the three kinds are for rather than showing an empty list.
     *
     * The one sentence is all a rule-less host needs: each kind gets its own explanation the moment
     * there is a rule to show it on, so this line only has to say what the door is for - and without it
     * the window would be a host line, a button, and nothing else.
     */
    @Test
    fun aHostWithNoRulesIsToldWhatForwardingIsFor() {
        val host = seedHost("")

        launchWindow(host).use {
            awaitText("Add rule")
            assertThat(
                compose.onAllNodes(hasText("Port forwarding tunnels connections", substring = true))
                    .fetchSemanticsNodes(),
            ).isNotEmpty()
        }
    }

    /**
     * An intent carrying no token opens nothing, rather than a manager for an arbitrary host.
     *
     * The intent is the one thing about this window the system can re-deliver after the process is
     * gone, and there is no default host for it to fall back to: the first host in the list would be a
     * set of tunnels for something the user never opened.
     */
    @Test
    fun anIntentWithNoTokenOpensNothing() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ActivityScenario.launch<PortForwardManagerActivity>(
            Intent(context, PortForwardManagerActivity::class.java),
        ).use { scenario ->
            pumpUntil(describe = { "an intent with no subject token opened a window anyway" }) {
                scenario.state == Lifecycle.State.DESTROYED
            }
        }
    }

    // ---------------------------------------------------------------- driving the app

    /** The intent the app itself builds, so the handoff's two halves are the shipped ones. */
    private fun launchWindow(
        host: HostProfile,
        statuses: Map<String, ForwardStatus> = emptyMap(),
        runningForwards: List<ForwardEntry> = emptyList(),
    ): ActivityScenario<PortForwardManagerActivity> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return ActivityScenario.launch<PortForwardManagerActivity>(
            PortForwardManagerActivity.intent(context, host, statuses, runningForwards),
        )
    }

    /**
     * Saves a host through the app's own repository and returns it.
     *
     * The repository is taken off the workspace's view model - it is an `@Inject` field there, so an
     * instance of the app's own components is the only handle on the store the window under test will
     * be reading. A second repository would write somewhere else and the window would find nothing.
     *
     * The workspace is launched for exactly one write and closed again: this is a window suite, and the
     * surface under test is the one it opens. The tests that write *during* the window's life do it
     * through the window's own injected repository instead - see
     * [theRulesAreReadLiveSoAWriteWhileTheWindowIsOpenShowsUp].
     */
    private fun seedHost(savedForwards: String): HostProfile {
        val id = "b2-forwards-${nextId++}"
        val host = HostProfile(
            id = id,
            name = "Forwarder $id",
            host = "fwd.example.test",
            username = "forwarder",
            savedForwards = savedForwards,
        )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val viewModel = ViewModelProvider(activity)[MainViewModel::class.java]
                val repository = MainViewModel::class.java.getDeclaredField("hostRepository")
                    .apply { isAccessible = true }
                    .get(viewModel) as HostRepository
                runBlocking { repository.save(host) }
            }
        }
        return host
    }

    /** A row of the window that must be there, and on screen. */
    private fun awaitRow(label: String) {
        awaitText(label)
        compose.onNode(hasText(label) and hasClickAction()).assertIsDisplayed()
    }

    /**
     * Waits until the window is drawing exactly [count] rows that carry [label].
     *
     * [awaitRow] asserts a single node on purpose, which is the right claim for the rows that are one
     * per window. The rule list is one row per saved rule, so a two-rule host has two Deletes and the
     * single-node form finds two and fails on the ambiguity rather than on anything being wrong.
     */
    private fun awaitRows(label: String, count: Int) {
        awaitText(label)
        pumpUntil(describe = { "the window never drew $count rows named \"$label\"" }) {
            compose.onAllNodes(hasText(label) and hasClickAction()).fetchSemanticsNodes().size == count
        }
    }

    /** A string the window must be showing, wherever on the surface it is drawn. */
    private fun awaitText(text: String) {
        pumpUntil(describe = { "the window never showed \"$text\"" }) {
            compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Drives frames and main-looper work until [condition] holds, then fails with [describe].
     *
     * The shape the suites that drive live state all share - see
     * `FilesExplorerLayoutRobolectricTest.pumpUntil` for why it is this and not `compose.waitUntil`.
     */
    private fun pumpUntil(timeoutMs: Long = TIMEOUT_MS, describe: () -> String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline && !condition()) {
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        }
        check(condition()) { "timed out after ${timeoutMs}ms: ${describe()}" }
    }

    /**
     * Waits for the window's answer, and returns what the one-shot slot handed over.
     *
     * Cached rather than read straight into the condition, because the slot is read-once: a
     * `takeAnswer()` inside a condition is true on the evaluation that found the answer and null on
     * the next, and [pumpUntil] asks twice — once to leave the loop and once to decide whether it
     * timed out. Read straight, an answer that did arrive is reported as one that never did.
     */
    private fun awaitAnswer(expected: ActionAnswer, describe: () -> String): ActionAnswer {
        var answer: ActionAnswer? = null
        pumpUntil(describe = describe) {
            answer = answer ?: ActionRequests.takeAnswer()
            answer == expected
        }
        return checkNotNull(answer)
    }

    /**
     * Waits for the window to have been asked to close, and asserts it on the activity.
     *
     * `activity.isFinishing` rather than `scenario.state == DESTROYED`, which is the same fact one
     * looper-hop later: `finish()` sets the flag there and then, while the state the scenario reports
     * only falls once the destroy it posts has been run — and waiting for that is a test of
     * Robolectric's looper rather than of the window. See `ForwardFormActivityRobolectricTest` for the
     * account of why the failure it produces is the confusing one. It matters most for the host that
     * is deleted under the window: that close is driven by the repository's flow rather than by a tap,
     * which is one more hop again.
     *
     * A window that reached DESTROYED before this looked counts too, because a destroyed activity
     * cannot be showing a menu: `onActivity` throws once there is nothing live to run on. What cannot
     * pass is a window that is neither finishing nor gone. The one test that launches a window which
     * closes *before* it is ever resumed — no token — still asserts the state directly: that one
     * closes during the launch, and it is a different fact.
     */
    private fun awaitClosing(scenario: ActivityScenario<PortForwardManagerActivity>, describe: () -> String) {
        var closing = false
        pumpUntil(describe = describe) {
            runCatching { scenario.onActivity { activity -> closing = activity.isFinishing } }
            closing || scenario.state == Lifecycle.State.DESTROYED
        }
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
        var nextId = 0
    }
}
